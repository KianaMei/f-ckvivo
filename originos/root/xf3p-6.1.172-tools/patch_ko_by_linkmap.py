#!/usr/bin/env python3
"""Patch a KernelSU KO for a PD2337 6.1.172 boot using a link-time symbol table."""
import argparse
import struct
from pathlib import Path

MASK = (1 << 64) - 1
SHN_ABS = 0xFFF1
FILTER_OLDS = (
    b"scmversion=g0c0fdb960178\x00",
    b"scmversion=g013b044886bb\x00",
)
FILTER_NEWS = (
    b"vivo_module_filter=auto\x00\x00",
    b"vivo_module_filter=auto\x00\x00\x00\x00\x00\x00\x00",
)


def section_by_name(data: bytearray, name: str) -> dict:
    shoff, = struct.unpack_from("<Q", data, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]
    shstr = sections[shstrndx][4]

    def cstr(offset: int) -> str:
        end = data.index(b"\0", offset)
        return data[offset:end].decode("utf-8", "replace")

    found = None
    for section in sections:
        if cstr(shstr + section[0]) == name:
            found = {
                "offset": section[4],
                "size": section[5],
                "link": section[6],
                "entsize": section[9],
            }
            break
    if found is None:
        raise SystemExit(f"missing section: {name}")
    return found


def symbol_name(data: bytearray, strtab_offset: int, name_index: int) -> str:
    offset = strtab_offset + name_index
    end = data.index(b"\0", offset)
    return data[offset:end].decode("utf-8", "replace")


def patch_filter(data: bytearray) -> bool:
    if b"vivo_module_filter=auto" in data:
        return True
    for old, new in zip(FILTER_OLDS, FILTER_NEWS):
        offset = data.find(old)
        if offset < 0:
            continue
        if len(old) != len(new):
            raise SystemExit("filter replacement length mismatch")
        data[offset:offset + len(old)] = new
        return True
    return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("base_ko")
    ap.add_argument("linkmap")
    ap.add_argument("slide")
    ap.add_argument("output_ko")
    ap.add_argument("--no-filter", action="store_true")
    args = ap.parse_args()

    slide = int(args.slide, 0)
    linkmap: dict[str, int] = {}
    for line in Path(args.linkmap).read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) >= 2:
            linkmap[fields[0]] = int(fields[1], 0)

    data = bytearray(Path(args.base_ko).read_bytes())
    symtab = section_by_name(data, ".symtab")
    strtab = section_by_name(data, ".strtab")
    if symtab["entsize"] != 24:
        raise SystemExit(f"unexpected .symtab entsize: {symtab['entsize']}")

    patched = 0
    missing: list[str] = []
    for index in range(symtab["size"] // 24):
        offset = symtab["offset"] + index * 24
        name_index, info, other, shndx, value, size = struct.unpack_from("<IBBHQQ", data, offset)
        name = symbol_name(data, strtab["offset"], name_index)
        if shndx != 0 or not name:
            continue
        if name in linkmap:
            runtime = (linkmap[name] + slide) & MASK
            struct.pack_into("<IBBHQQ", data, offset, name_index, info, other, SHN_ABS, runtime, size)
            patched += 1
        else:
            missing.append(name)

    if missing:
        raise SystemExit(f"missing {len(missing)} undefined symbols: {missing[:8]}")
    if patched != 226:
        raise SystemExit(f"expected 226 patched symbols, got {patched}")
    if not args.no_filter and not patch_filter(data):
        raise SystemExit("could not patch vivo_module_filter")

    Path(args.output_ko).write_bytes(data)
    print(f"patched={patched} slide={slide:#x} output={args.output_ko}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
