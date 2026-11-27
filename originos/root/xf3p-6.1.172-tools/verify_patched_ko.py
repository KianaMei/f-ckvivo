#!/usr/bin/env python3
"""Verify a patched KO against a 6.1.172 linkmap and a chosen KASLR slide."""
import argparse
import struct
from pathlib import Path

SHN_ABS = 0xFFF1
MASK = (1 << 64) - 1


def section_by_name(data: bytes, name: str) -> tuple[int, int, int] | None:
    shoff, = struct.unpack_from("<Q", data, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]
    shstr = sections[shstrndx][4]

    def cstr(offset: int) -> str:
        end = data.index(b"\0", offset)
        return data[offset:end].decode("utf-8", "replace")

    for section in sections:
        if cstr(shstr + section[0]) == name:
            return section[4], section[5], section[9]
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ko")
    ap.add_argument("linkmap")
    ap.add_argument("slide")
    args = ap.parse_args()

    slide = int(args.slide, 0)
    linkmap: dict[str, int] = {}
    for line in Path(args.linkmap).read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) >= 2:
            linkmap[fields[0]] = int(fields[1], 0)

    data = Path(args.ko).read_bytes()
    symtab = section_by_name(data, ".symtab")
    strtab = section_by_name(data, ".strtab")
    if symtab is None or strtab is None:
        raise SystemExit("missing .symtab or .strtab")
    symoff, symsize, entsize = symtab
    stroff = strtab[0]
    if entsize != 24:
        raise SystemExit(f"unexpected symbol size: {entsize}")

    patched = 0
    mismatches: list[str] = []
    undef: list[str] = []
    for index in range(symsize // 24):
        off = symoff + index * 24
        name_index, _info, _other, shndx, value, _size = struct.unpack_from("<IBBHQQ", data, off)
        end = data.index(b"\0", stroff + name_index)
        name = data[stroff + name_index:end].decode("utf-8", "replace")
        if not name:
            continue
        if shndx == 0:
            undef.append(name)
        elif shndx == SHN_ABS and name in linkmap:
            expected = (linkmap[name] + slide) & MASK
            if value != expected:
                mismatches.append(f"{name}: {value:#x} != {expected:#x}")
            patched += 1

    has_filter = b"vivo_module_filter=auto" in data
    print(f"patched={patched} undef={len(undef)} mismatches={len(mismatches)} filter={has_filter}")
    if mismatches:
        print(*mismatches[:8], sep="\n")
    if patched != 226 or undef or mismatches or not has_filter:
        raise SystemExit("verification failed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
