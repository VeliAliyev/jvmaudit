#!/usr/bin/env python3
"""Compares the surveyed JDK installations and reports on the two candidate discriminators.

Reads the files written by survey-jdk.sh and answers three questions:

1. Which release-file fields differ between distributions at all? (the diff the build plan asks for)
2. Does the SOURCE field separate Oracle JDK from Oracle OpenJDK, across at least three versions
   of each? (candidate discriminator 1)
3. Does the licence text shipped in the installation root separate them, and does it agree with
   SOURCE on every sample? (candidate discriminator 2)

Neither discriminator may be encoded in rules/vendors.yaml unless questions 2 and 3 both come back
VALIDATED. Until then JVMAudit runs `java -version`, and reports UNKNOWN when it may not.

Exit code is always 0: this is a survey, and a discriminator that does not hold is a finding, not a
build failure.
"""

from __future__ import annotations

import pathlib
import sys
from collections import defaultdict

MIN_SAMPLES_PER_FAMILY = 3


def read_release(path: pathlib.Path) -> dict[str, str]:
    fields: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        fields[key.strip()] = value.strip().strip('"')
    return fields


def is_java_tm(version_output: str) -> bool | None:
    if "Java(TM) SE Runtime Environment" in version_output:
        return True
    if "OpenJDK Runtime Environment" in version_output:
        return False
    return None


def classify_licence(text: str) -> str:
    lowered = text.lower()
    if "no-fee terms and conditions" in lowered:
        return "NFTC"
    if "oracle technology network" in lowered or "otn license" in lowered:
        return "OTN"
    if "graalvm free terms" in lowered:
        return "GFTC"
    if "gnu general public license" in lowered:
        return "GPLv2"
    return "unrecognised"


def licence_sections(licence_text: str) -> list[tuple[str, str]]:
    """Splits a .licence survey file into (relative path, first lines) sections."""
    sections: list[tuple[str, str]] = []
    current_path: str | None = None
    body: list[str] = []
    for line in licence_text.splitlines():
        if line.startswith("## "):
            if current_path is not None:
                sections.append((current_path, "\n".join(body)))
            current_path = line[3:].split(" (")[0].strip()
            body = []
        elif current_path is not None:
            body.append(line)
    if current_path is not None:
        sections.append((current_path, "\n".join(body)))
    return sections


def root_licence_kind(licence_text: str) -> str:
    """The licence in the installation ROOT, if it ships one there.

    Oracle's Windows installer puts a LICENSE in the installation root; the tar.gz distributions
    of both families put nothing there at all. So "absent" here is a property of the packaging,
    not of the product, and it cannot be used to tell the two families apart on its own.
    """
    for path, body in licence_sections(licence_text):
        normalised = path.replace("\\", "/")
        if "/" not in normalised and normalised.upper().startswith("LICENSE"):
            return classify_licence(body)
    return "absent"


def nested_licence_kind(licence_text: str) -> str:
    """The licence under legal/<module>/LICENSE, which every distribution ships regardless of how
    it was packaged. This is where the two Oracle families actually differ."""
    for path, body in licence_sections(licence_text):
        normalised = path.replace("\\", "/")
        if "/" in normalised and normalised.upper().endswith("LICENSE"):
            return classify_licence(body)
    return "absent"


def effective_licence_kind(licence_text: str) -> str:
    """The licence this installation actually ships, wherever it keeps it.

    Prefers the installation root, because that is the copy a human opens first, and falls back to
    legal/<module>/LICENSE, which is present in every distribution. Judging on the root alone was
    the first attempt and it was wrong: the tar.gz builds of Oracle JDK ship no root LICENSE, so
    the check reported "absent" for both families and separated nothing.
    """
    root = root_licence_kind(licence_text)
    if root != "absent" and root != "unrecognised":
        return root
    return nested_licence_kind(licence_text)


def main(directory: str) -> int:
    root = pathlib.Path(directory)
    ids = sorted(p.stem for p in root.glob("*.release"))
    if not ids:
        print(f"No survey files under {root}", file=sys.stderr)
        return 0

    installations = {}
    for identifier in ids:
        release = read_release(root / f"{identifier}.release")
        version_path = root / f"{identifier}.version"
        licence_path = root / f"{identifier}.licence"
        version_output = (
            version_path.read_text(encoding="utf-8", errors="replace")
            if version_path.exists()
            else ""
        )
        licence_text = (
            licence_path.read_text(encoding="utf-8", errors="replace")
            if licence_path.exists()
            else ""
        )
        installations[identifier] = {
            "release": release,
            "version": version_output,
            "licence": licence_text,
            "java_tm": is_java_tm(version_output),
            "licence_kind": effective_licence_kind(licence_text),
            "root_licence_kind": root_licence_kind(licence_text),
            "nested_licence_kind": nested_licence_kind(licence_text),
        }

    lines: list[str] = ["# JDK artifact survey", "", f"{len(installations)} installations surveyed.", ""]

    # ---- 1. the release-file diff
    lines += ["## Release file fields", ""]
    all_keys = sorted({key for data in installations.values() for key in data["release"]})
    lines.append("| id | " + " | ".join(all_keys) + " |")
    lines.append("|---" * (len(all_keys) + 1) + "|")
    for identifier, data in installations.items():
        cells = [data["release"].get(key, "") for key in all_keys]
        lines.append(f"| `{identifier}` | " + " | ".join(f"`{c}`" if c else "-" for c in cells) + " |")
    lines.append("")

    lines += ["## IMPLEMENTOR strings actually observed", ""]
    by_implementor: dict[str, list[str]] = defaultdict(list)
    for identifier, data in installations.items():
        by_implementor[data["release"].get("IMPLEMENTOR", "(none)")].append(identifier)
    for implementor, members in sorted(by_implementor.items()):
        lines.append(f"- `{implementor}` - {', '.join(members)}")
    lines.append("")
    lines.append(
        "Compare these against `rules/vendors.yaml`. Any string that differs is a detection bug."
    )
    lines.append("")

    # ---- the two Oracle families, judged by what java -version says
    oracle_jdk = {
        i: d
        for i, d in installations.items()
        if d["release"].get("IMPLEMENTOR") == "Oracle Corporation" and d["java_tm"] is True
    }
    oracle_openjdk = {
        i: d
        for i, d in installations.items()
        if d["release"].get("IMPLEMENTOR") == "Oracle Corporation" and d["java_tm"] is False
    }

    lines += [
        "## Candidate discriminator 1: the SOURCE field",
        "",
        f"Oracle JDK samples: {len(oracle_jdk)} ({', '.join(oracle_jdk) or 'none'})",
        f"Oracle OpenJDK samples: {len(oracle_openjdk)} ({', '.join(oracle_openjdk) or 'none'})",
        "",
    ]
    jdk_has_open = {i: "open:git:" in d["release"].get("SOURCE", "") for i, d in oracle_jdk.items()}
    openjdk_has_open = {
        i: "open:git:" in d["release"].get("SOURCE", "") for i, d in oracle_openjdk.items()
    }
    for identifier, data in {**oracle_jdk, **oracle_openjdk}.items():
        family = "Oracle JDK" if data["java_tm"] else "Oracle OpenJDK"
        source = data["release"].get("SOURCE", "(absent)")
        lines.append(f"- `{identifier}` ({family}): `SOURCE={source}`")
    lines.append("")

    enough = (
        len(oracle_jdk) >= MIN_SAMPLES_PER_FAMILY
        and len(oracle_openjdk) >= MIN_SAMPLES_PER_FAMILY
    )
    source_holds = all(jdk_has_open.values()) and not any(openjdk_has_open.values())
    if not enough:
        source_verdict = (
            f"INSUFFICIENT DATA - need at least {MIN_SAMPLES_PER_FAMILY} samples per family"
        )
    elif source_holds:
        source_verdict = "VALIDATED - every Oracle JDK has open:git: and no Oracle OpenJDK does"
    else:
        source_verdict = "REJECTED - the field does not separate the two families"
    lines += [f"**Verdict: {source_verdict}**", ""]

    # ---- 3. the licence text
    lines += [
        "## Candidate discriminator 2: the licence text in the installation root",
        "",
        "Oracle JDK ships the NFTC or OTN text; Oracle's OpenJDK builds ship GPLv2 with the"
        " Classpath Exception. Where that text lives depends on the packaging: the Windows"
        " installer puts a LICENSE in the installation root, the tar.gz builds put nothing there"
        " and keep it under legal/<module>/LICENSE. The verdict below is judged on whichever copy"
        " is present, which is the `effective` column.",
        "",
        "| id | family | root LICENSE | legal/*/LICENSE | effective |",
        "|---|---|---|---|---|",
    ]
    for identifier, data in {**oracle_jdk, **oracle_openjdk}.items():
        family = "Oracle JDK" if data["java_tm"] else "Oracle OpenJDK"
        lines.append(
            f"| `{identifier}` | {family} | `{data['root_licence_kind']}` |"
            f" `{data['nested_licence_kind']}` | `{data['licence_kind']}` |"
        )
    lines.append("")
    licence_holds = (
        bool(oracle_jdk)
        and bool(oracle_openjdk)
        and all(d["licence_kind"] in ("NFTC", "OTN") for d in oracle_jdk.values())
        and all(d["licence_kind"] == "GPLv2" for d in oracle_openjdk.values())
    )
    if not enough:
        licence_verdict = (
            f"INSUFFICIENT DATA - need at least {MIN_SAMPLES_PER_FAMILY} samples per family"
        )
    elif licence_holds:
        licence_verdict = (
            "VALIDATED - every Oracle JDK ships the NFTC or OTN text and every Oracle OpenJDK"
            " build ships GPLv2"
        )
    else:
        licence_verdict = "REJECTED - the licence text does not separate the two families"
    lines += [f"**Verdict: {licence_verdict}**", ""]

    # ---- do the two agree?
    lines += ["## Do the two discriminators agree?", ""]
    disagreements = []
    for identifier, data in {**oracle_jdk, **oracle_openjdk}.items():
        by_source = "open:git:" in data["release"].get("SOURCE", "")
        by_licence = data["licence_kind"] in ("NFTC", "OTN")
        if by_source != by_licence:
            disagreements.append(
                f"- `{identifier}`: SOURCE says {'Oracle JDK' if by_source else 'Oracle OpenJDK'}, "
                f"licence says {'Oracle JDK' if by_licence else 'Oracle OpenJDK'}"
            )
    if disagreements:
        lines += disagreements + ["", "**They disagree. Encode neither.**", ""]
        agree = False
    else:
        lines += ["They agree on every sample.", ""]
        agree = True

    ready = enough and source_holds and licence_holds and agree
    lines += [
        "## What to do next",
        "",
        (
            "Both discriminators hold and agree across the sample. Encode them together in"
            " `rules/vendors.yaml` as an additional match alternative for `oracle-jdk` and"
            " `oracle-openjdk`, with `confidence: verified`, and keep `java -version` as the"
            " tie-breaker. Record the run URL in docs/DECISIONS.md."
            if ready
            else "Do NOT encode either discriminator. JVMAudit keeps running `java -version`, and"
            " keeps reporting UNKNOWN when it may not. Re-run this survey with more samples, or"
            " record the rejection in docs/DECISIONS.md."
        ),
        "",
    ]

    report = "\n".join(lines)
    pathlib.Path("discriminator-report.md").write_text(report, encoding="utf-8")
    print(report)

    summary = pathlib.Path(__import__("os").environ.get("GITHUB_STEP_SUMMARY", ""))
    if str(summary):
        try:
            with summary.open("a", encoding="utf-8") as handle:
                handle.write(report)
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "surveys"))
