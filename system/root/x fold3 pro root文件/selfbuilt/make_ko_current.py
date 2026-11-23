#!/usr/bin/env python3
"""Patch ksu-final.ko for a known 6.1.172 KASLR slide.

The module has 226 unresolved symbols.  ksym_20261121_1310.txt is a runtime
snapshot from the 2026-11-21 boot with slide 0x1c7d000000; subtracting that
gives the link-time symbol table.  Add the current slide and mark every
undefined symbol SHN_ABS (0xfff1), which is what poc_tmp_root would do itself.
"""
from __future__ import annotations
import struct, sys
from pathlib import Path
from elftools.elf.elffile import ELFFile
from elftools.elf.sections import SymbolTableSection

OLD_SLIDE = 0x1C7D000000
OLD = b"scmversion=g0c0fdb960178\x00"
NEW = b"vivo_module_filter=auto\x00\x00"

def main() -> int:
    if len(sys.argv) != 4:
        print('usage: make_ko_current.py <ksu-final.ko> <ksym> <slide>', file=sys.stderr)
        return 2
    ko, ksym, slide_s = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
    slide = int(slide_s, 0)
    link = {}
    for line in ksym.read_text(errors='replace').splitlines():
        f = line.split()
        if len(f) >= 3 and f[2] not in link:
            try:
                link[f[2]] = int(f[0], 16) - OLD_SLIDE
            except ValueError:
                pass
    data = bytearray(ko.read_bytes())
    shoff = None
    with ko.open('rb') as f:
        elf = ELFFile(f)
        sec = elf.get_section_by_name('.symtab')
        if not isinstance(sec, SymbolTableSection):
            raise SystemExit('no .symtab')
        shoff = sec['sh_offset']
        entsize = sec['sh_entsize']
        count = sec['sh_size'] // entsize
        strtab = elf.get_section(sec['sh_link'])
        patched = 0
        missing = []
        for i in range(count):
            off = shoff + i * entsize
            st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from('<IBBHQQ', data, off)
            if st_shndx != 0:
                continue
            name = strtab.get_string(st_name)
            if not name:
                continue
            if name not in link:
                missing.append(name)
                continue
            runtime = (link[name] + slide) & ((1 << 64) - 1)
            struct.pack_into('<IBBHQQ', data, off, st_name, st_info, st_other, 0xFFF1, runtime, st_size)
            patched += 1
    i = data.find(OLD)
    if i < 0:
        raise SystemExit('scmversion slot not found')
    data[i:i + len(OLD)] = NEW
    if missing:
        print(f'missing {len(missing)} symbols: {missing[:8]}', file=sys.stderr)
        return 1
    outdir = ko.parent / 'selfbuilt'
    outdir.mkdir(exist_ok=True)
    out = outdir / f'{ko.stem}_slide{slide:x}.ko'
    out.write_bytes(data)
    print(f'wrote {out} patched={patched} filter={NEW.decode().strip()} slide={slide:#x}')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
