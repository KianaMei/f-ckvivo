#!/usr/bin/env python3
"""Build a 6.1.172 link-time symbol table from the 2026-11-21 kallsyms dump."""
import argparse
from pathlib import Path

DEFAULT_SLIDE = 0x1C7D000000
MASK = (1 << 64) - 1


def build(ksyms_path: Path, old_slide: int) -> dict[str, int]:
    linkmap: dict[str, int] = {}
    for line in ksyms_path.read_text(encoding="utf-8", errors="replace").splitlines():
        fields = line.split()
        if len(fields) < 3 or fields[2] in linkmap:
            continue
        try:
            runtime = int(fields[0], 16)
        except ValueError:
            continue
        if runtime >= old_slide:
            linkmap[fields[2]] = (runtime - old_slide) & MASK
    return linkmap


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ksyms")
    ap.add_argument("output")
    ap.add_argument("--old-slide", default=hex(DEFAULT_SLIDE))
    args = ap.parse_args()

    old_slide = int(args.old_slide, 0)
    linkmap = build(Path(args.ksyms), old_slide)
    if linkmap.get("_text") != 0xFFFFFFC008000000:
        raise SystemExit(f"unexpected _text: {linkmap.get('_text'):#x}")

    with Path(args.output).open("w", encoding="utf-8", newline="\n") as out:
        for name, link in sorted(linkmap.items()):
            out.write(f"{name} {link:#x}\n")
    print(f"wrote {len(linkmap)} link symbols to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
