"""One-off maintenance script: raise tree-definition variant `count`.

Rule (agreed): new = max(4, old + 2), applied ONLY to definition-level `count`
keys (siblings of name/trunk/leaves). Branch / sub_branch `count` keys live
inside a "canopy" subtree and are left untouched, because branch density is
probabilistic, not counted.

It edits files in place, preserving formatting, and validates JSON afterwards.
Run:  python tools/bump_counts.py
"""
import json
import re
import sys
from pathlib import Path

SPECIES_DIR = Path("common/src/main/resources/species")
COUNT_RE = re.compile(r'^(\s*)"count"\s*:\s*(\d+)(\s*,?\s*)$')


def bump_file(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)

    depth = 0
    canopy_depth = None  # brace depth of the active "canopy" object body
    pending_canopy = False
    changed = 0
    out = []

    for line in lines:
        stripped = line.lstrip()
        # Decide bump eligibility using the brace state at the START of the line.
        m = COUNT_RE.match(line.rstrip("\n").rstrip("\r"))
        if m and canopy_depth is None:
            old = int(m.group(2))
            new = max(4, old + 2)
            if new != old:
                line = f'{m.group(1)}"count": {new}{m.group(3)}'
                if not line.endswith("\n"):
                    line += "\n"
                changed += 1

        # Mark that the next "{" opens a canopy body.
        if '"canopy"' in line:
            pending_canopy = True

        # Walk braces on this line to maintain depth / canopy tracking.
        for ch in line:
            if ch == "{":
                depth += 1
                if pending_canopy:
                    canopy_depth = depth
                    pending_canopy = False
            elif ch == "}":
                if canopy_depth is not None and depth == canopy_depth:
                    canopy_depth = None
                depth -= 1

        out.append(line)

    new_text = "".join(out)
    # Validate JSON integrity before writing.
    json.loads(new_text)
    if new_text != text:
        path.write_text(new_text, encoding="utf-8", newline="")
    return changed


def main() -> int:
    if not SPECIES_DIR.is_dir():
        print(f"Species dir not found: {SPECIES_DIR.resolve()}", file=sys.stderr)
        return 1
    total = 0
    for path in sorted(SPECIES_DIR.glob("*.json")):
        c = bump_file(path)
        total += c
        print(f"{path.name}: bumped {c} definition count(s)")
    print(f"TOTAL bumped: {total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
