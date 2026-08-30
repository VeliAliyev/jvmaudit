#!/usr/bin/env python3
"""Turns captured ANSI terminal output into an SVG, for the README.

The point is that the image in the README is a rendering of output the tool really produced, not a
mock-up someone drew. Pipe a real run through this and commit the result:

    jvmaudit scan --color | python3 .github/scripts/render-terminal-svg.py docs/scan.svg "jvmaudit scan"

Only the escape sequences JVMAudit actually emits are handled - reset, bold, dim, and the eight
basic foreground colours. Anything else is dropped rather than guessed at.
"""

from __future__ import annotations

import html
import re
import sys

ANSI = re.compile(r"\x1b\[([0-9;]*)m")

# A dark terminal palette with enough contrast for the status words to stay legible.
COLOURS = {
    30: "#5c6370", 31: "#e06c75", 32: "#98c379", 33: "#e5c07b",
    34: "#61afef", 35: "#c678dd", 36: "#56b6c2", 37: "#abb2bf",
}
FOREGROUND = "#d7dae0"
BACKGROUND = "#1c1f26"
CHROME = "#282c34"

CHAR_WIDTH = 8.05
LINE_HEIGHT = 19.0
PADDING_X = 18.0
PADDING_TOP = 46.0
PADDING_BOTTOM = 16.0


def parse(text: str) -> list[list[tuple[str, str, bool, float]]]:
    """Splits ANSI text into lines of (text, colour, bold, opacity) runs."""
    lines: list[list[tuple[str, str, bool, float]]] = []
    colour, bold, opacity = FOREGROUND, False, 1.0

    for raw_line in text.replace("\r\n", "\n").split("\n"):
        runs: list[tuple[str, str, bool, float]] = []
        position = 0
        for match in ANSI.finditer(raw_line):
            chunk = raw_line[position : match.start()]
            if chunk:
                runs.append((chunk, colour, bold, opacity))
            position = match.end()
            for code in (match.group(1) or "0").split(";"):
                value = int(code) if code.isdigit() else 0
                if value == 0:
                    colour, bold, opacity = FOREGROUND, False, 1.0
                elif value == 1:
                    bold = True
                elif value == 2:
                    opacity = 0.72
                elif value in COLOURS:
                    colour = COLOURS[value]
        tail = raw_line[position:]
        if tail:
            runs.append((tail, colour, bold, opacity))
        lines.append(runs)

    while lines and not any(run[0].strip() for run in lines[-1]):
        lines.pop()
    return lines


def render(lines, title: str) -> str:
    columns = max((sum(len(run[0]) for run in line) for line in lines), default = 80)
    width = PADDING_X * 2 + columns * CHAR_WIDTH
    height = PADDING_TOP + len(lines) * LINE_HEIGHT + PADDING_BOTTOM

    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width:.0f}" height="{height:.0f}" '
        f'viewBox="0 0 {width:.0f} {height:.0f}" font-family="ui-monospace, SFMono-Regular, '
        f'Menlo, Consolas, monospace" font-size="13">',
        f'<rect width="{width:.0f}" height="{height:.0f}" rx="8" fill="{BACKGROUND}"/>',
        f'<rect width="{width:.0f}" height="30" rx="8" fill="{CHROME}"/>',
        f'<rect y="22" width="{width:.0f}" height="8" fill="{CHROME}"/>',
        '<circle cx="18" cy="15" r="5.5" fill="#e06c75"/>',
        '<circle cx="36" cy="15" r="5.5" fill="#e5c07b"/>',
        '<circle cx="54" cy="15" r="5.5" fill="#98c379"/>',
        f'<text x="72" y="19.5" fill="#8b92a0" font-size="11.5">{html.escape(title)}</text>',
    ]

    for index, runs in enumerate(lines):
        y = PADDING_TOP + index * LINE_HEIGHT
        column = 0
        spans = []
        for text, colour, bold, opacity in runs:
            if text.strip():
                x = PADDING_X + column * CHAR_WIDTH
                weight = ' font-weight="600"' if bold else ""
                fade = f' opacity="{opacity}"' if opacity < 1.0 else ""
                spans.append(
                    f'<tspan x="{x:.1f}" y="{y:.1f}" fill="{colour}"{weight}{fade}'
                    f' xml:space="preserve">{html.escape(text)}</tspan>'
                )
            column += len(text)
        if spans:
            out.append("<text>" + "".join(spans) + "</text>")

    out.append("</svg>")
    return "\n".join(out) + "\n"


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: render-terminal-svg.py <output.svg> [title]", file=sys.stderr)
        return 2
    destination = sys.argv[1]
    title = sys.argv[2] if len(sys.argv) > 2 else "jvmaudit"
    captured = sys.stdin.read()
    if not captured.strip():
        print("render-terminal-svg: nothing on stdin", file=sys.stderr)
        return 1
    with open(destination, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(render(parse(captured), title))
    print(f"render-terminal-svg: wrote {destination}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
