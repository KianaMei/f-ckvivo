#!/usr/bin/env python3
"""Generate the ksu_patch.txt format consumed by poc_tmp_root's ksu_try()."""
import argparse
import struct
from pathlib import Path


def undefined_symbols(ko: bytearray) -> list[str]:
    shoff, = struct.unpack_from("<Q", ko, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", ko, 0x3A)
    sections = [struct.unpack_from("<IIQQQQIIQQ", ko, shoff + i * shentsize) for i in range(shnum)]
    shstr = sections[shstrndx][4]

    def cstr(offset: int) -> str:
        end = ko.index(b"\0", offset)
        return ko[offset:end].decode("utf-8", "replace")

    symtab = strtab = None
    for section in sections:
        name = cstr(shstr + section[0])
        if name == ".symtab":
            symtab = section
        elif name == ".strtab":
            strtab = section
    if symtab is None or strtab is None:
        raise SystemExit("missing .symtab or .strtab")

    names: list[str] = []
    for index in range(symtab[5] // 24):
        name_index, _info, _other, shndx, _value, _size = struct.unpack_from(
            "<IBBHQQ", ko, symtab[4] + index * 24
        )
        if shndx != 0:
            continue
        offset = strtab[4] + name_index
        end = ko.index(b"\0", offset)
        name = ko[offset:end].decode("utf-8", "replace")
        if name:
            names.append(name)
    return names


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ko")
    ap.add_argument("linkmap")
    ap.add_argument("output")
    args = ap.parse_args()

    linkmap: dict[str, int] = {}
    for line in Path(args.linkmap).read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) >= 2:
            linkmap[fields[0]] = int(fields[1], 0)

    names = undefined_symbols(bytearray(Path(args.ko).read_bytes()))
    missing = [name for name in names if name not in linkmap]
    if missing:
        raise SystemExit(f"missing {len(missing)} symbols: {missing[:8]}")

    with Path(args.output).open("w", encoding="utf-8", newline="\n") as out:
        for name in names:
            out.write(f"{name} {linkmap[name]:#x}\n")
    print(f"wrote {len(names)} rows to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
