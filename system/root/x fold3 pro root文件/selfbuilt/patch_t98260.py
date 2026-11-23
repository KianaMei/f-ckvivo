#!/usr/bin/env python3
import struct, sys
from pathlib import Path
from elftools.elf.elffile import ELFFile

def off(elf, va):
    for s in elf.iter_segments():
        if s['p_type']=='PT_LOAD' and s['p_vaddr']<=va<s['p_vaddr']+s['p_filesz']:
            return s['p_offset']+va-s['p_vaddr']
    raise SystemExit(hex(va))

def main():
    src,dst=Path(sys.argv[1]),Path(sys.argv[2]);d=bytearray(src.read_bytes())
    f=src.open('rb');e=ELFFile(f)
    def w(va,val):
        o=off(e,va);d[o:o+4]=struct.pack('<I',val)
    # discovery tail: page_free is the second record id; force 271.
    w(0x21F30C, 0xB9400901)  # ldr w1,[x8,#8]
    w(0x21F310, 0x528021E2)  # mov w2,#271
    w(0x21F320, 0xB9004501)  # str w1,[x8,#0x44]
    w(0x21F324, 0xB9004902)  # str w2,[x8,#0x48]
    f.close();dst.write_bytes(d);print('patched',dst)
if __name__=='__main__': main()
