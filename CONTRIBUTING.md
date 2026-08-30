# Contributing to JVMAudit

Thanks for looking. The two most useful contributions are **a vendor JVMAudit does not recognise**
and **a licence rule that is wrong** — the second especially.

## Ground rules that are not negotiable

These are what the tool is for, so a change that breaks one will not be merged however good it is
otherwise.

1. **The scanner makes no network calls.** Not for rule updates, not for version checks, not for
   telemetry. People run this as root on production servers. If you need data, it goes in
   `rules/` and ships with the binary.
2. **No licensing claim without a citation.** Every rule in `rules/oracle-license-rules.yaml`
   carries a `citation` pointing at a primary source — Oracle's own FAQ, licence text, support
   roadmap or release notes. A rule that cannot be traced to one must be
   `confidence: unverified`, and the tool will display it as our inference rather than Oracle's
   word. `Classification` refuses to be constructed without a citation, so this is enforced by the
   compiler, not by good intentions.
3. **Never guess.** If the evidence does not settle what an installation is, the answer is
   `UNKNOWN` with a `remediation` saying what would settle it. A confidently wrong "you owe Oracle
   money" is the one unforgivable bug in this product; so is a confidently wrong "you're fine".
4. **Never tell a user they owe money.** JVMAudit reports what is installed and what licence
   applies. It does not know their contracts with Oracle. The wording is "most likely needs a paid
   licence", never "you owe".

## Adding a vendor

Most-wanted contribution, and it is a data-only change.

1. Add an entry to [`rules/vendors.yaml`](rules/vendors.yaml). Match on the `IMPLEMENTOR` field of
   the `release` file, and add a `runtimeName` alternative matching the `java -version` banner —
   many Java 8 builds ship no `IMPLEMENTOR` at all, so the banner is the only thing to go on.
2. Set `matchConfidence: unverified` unless you have confirmed the strings against a real
   downloaded build. If you have, set `verified` and record what you checked in `matchEvidence`.
3. Add a fixture to `JvmFixtures.catalogue()` in the core tests. The integration test will then
   cover it automatically.
4. In your pull request, paste the installation's `release` file and its `java -version` output.
   That is the evidence, and it is what a reviewer needs.

## Reporting a wrong licence verdict

Please open an issue with:

- the output of `jvmaudit scan --paths <the installation>`
- the contents of `<installation>/release`
- the output of `<installation>/bin/java -version`
- what you believe the correct answer is, **and the Oracle document that says so**

The last line matters most. A verdict changes only against a primary source.

## Building

```sh
mvn verify
```

Java 21 to build. Byte code targets 17, so the result runs on Java 17 and later — do not use APIs
newer than 17 in `core` or `cli`.

- `mvn spotless:apply` fixes formatting. CI fails on unformatted code.
- The build must stay warning-free.
- `mvn -pl core test -Dtest=ReportGoldenTest -Djvmaudit.golden.update=true` regenerates the golden
  output files. Read the diff before committing it — those files are what the tool says to
  customers.

## Testing conventions

- **No mocking of the filesystem.** Tests plant real directory trees in real temporary directories
  via `JvmFixtures`. The only things stubbed are the two process boundaries, `java -version` and the
  Windows `reg` tool, behind `ProcessRunner`.
- **No test may depend on what is installed on the machine.** Exactly two do, deliberately: one
  asserts that scanning the host does not crash, and one identifies the JDK running the tests.
- **A regression test must fail without its fix.** Check by reverting the fix and watching it fail.
  A regression test that passes either way documents nothing.

## Scope

JVMAudit inventories JVMs on a machine and classifies their licences. Things that are deliberately
out of scope for now: a fleet server, CVE and end-of-life enrichment, and anything that phones
home. Container image scanning is wanted and planned.
