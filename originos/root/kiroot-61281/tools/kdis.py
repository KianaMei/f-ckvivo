#!/usr/bin/env python3
"""Disassemble a kernel function with a validated symbol table.

usage: dis.py <elf> <anchors|struct> <func> [start_off] [end_off] [--calls]
"""
import sys, struct, numpy as np
from pathlib import Path
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from capstone.arm64 import ARM64_OP_IMM

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kallsyms2 import Kallsyms

BASE = 0xFFFFFFC080000000


class Image:
    def __init__(self, path, anchors=None):
        self.ks = Kallsyms(path)
        self.d = self.ks.data
        self._elf()
        self._offsets(anchors)
        self.tab = self.ks.table()

    def _elf(self):
        """The image wrappers are ET_REL with one .data section holding the raw kernel."""
        d = self.d
        shoff = struct.unpack_from("<Q", d, 0x28)[0]
        shnum = struct.unpack_from("<H", d, 0x3c)[0]
        shentsize = struct.unpack_from("<H", d, 0x3a)[0]
        shstrndx = struct.unpack_from("<H", d, 0x3e)[0]
        secs = []
        for i in range(shnum):
            o = shoff + i * shentsize
            name, typ, flags, addr, off, size = struct.unpack_from("<IIQQQQ", d, o)
            secs.append((name, typ, flags, addr, off, size))
        stroff = secs[shstrndx][4]

        def nm(x):
            s = d[stroff + x:]
            return s[:s.index(b"\x00")].decode("latin1")

        self.segs = []
        for name, typ, flags, addr, off, size in secs:
            if nm(name) == ".data":
                self.segs.append((BASE, off, size))
        if not self.segs:
            raise RuntimeError("no .data section")
        print(f"[elf] .data @file 0x{self.segs[0][1]:x} size 0x{self.segs[0][2]:x}")

    def v2o(self, va):
        for v, o, sz in self.segs:
            if v <= va < v + sz:
                return o + (va - v)
        raise KeyError(hex(va))

    def _offsets(self, anchors):
        ks, d = self.ks, self.d
        if anchors:
            hits, total = ks.fit_offsets(anchors)
            print(f"[anchors] fit {hits}/{total} @0x{ks.offs_at:x}")
            return
        # structural: array ends exactly (mod padding) at the relative_base u64
        pat = struct.pack("<Q", BASE)
        pos = d.find(pat)
        p = pos - 4 * ks.N
        pad = 0
        while p % 4 and pad < 16:
            p += 1
        arr = np.frombuffer(d[p:p + 4 * ks.N], dtype="<i4")
        assert int(arr[0]) == 0, "struct fit: arr[0] != 0"
        assert (np.diff(arr.astype(np.int64)) >= 0).all(), "struct fit: not sorted"
        ks.offs_at = p
        ks.off_arr = arr
        ks.addrs = [BASE + int(v) for v in arr]
        print(f"[struct] offsets@0x{p:x} base@0x{pos:x} gap={pos - (p + 4 * ks.N)} "
              f"last=0x{max(ks.addrs):x}")

    def addr(self, name):
        return self.tab.get(name)

    def span(self, name):
        a = self.addr(name)
        if a is None:
            return None
        order = self.ks.addrs
        i = order.index(a)
        nxt = order[i + 1] if i + 1 < len(order) else a + 0x400
        return a, nxt

    def disasm(self, va, size):
        md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        md.detail = True
        off = self.v2o(va)
        return list(md.disasm(self.d[off:off + size], va))

    def near(self, va):
        best = None
        for a, n in zip(self.ks.addrs, self.ks.names):
            if a <= va:
                best = (a, n[1:])
            else:
                break
        return best


def main():
    path, mode, func = sys.argv[1], sys.argv[2], sys.argv[3]
    anchors = None
    if mode == "anchors":
        anchors = {}
        for line in open(r"system\root\.upstream\kiroot-apk\extracted\assets\ksu_patch.txt", encoding="utf-8"):
            q = line.split()
            if len(q) >= 2:
                anchors[q[0]] = int(q[1], 16)
    img = Image(path, anchors)
    b = img.span(func)
    if not b:
        raise SystemExit(f"{func} not found")
    start, end = b
    print(f"{func}: 0x{start:x}..0x{end:x} span=0x{end - start:x}")
    sites = []
    for arg in sys.argv[4:]:
        if arg.startswith("+0x"):
            sites.append(int(arg, 16))
    # call map
    calls = []
    for ins in img.disasm(start, end - start):
        if ins.mnemonic == "bl":
            for op in ins.operands:
                if op.type == ARM64_OP_IMM:
                    t = img.near((op.imm & 0xFFFFFFFFFFFFFFFF))
                    calls.append((ins.address - start, t[1] if t else hex((op.imm & 0xFFFFFFFFFFFFFFFF))))
    print("calls:")
    for off, nm in calls:
        mark = ""
        if sites:
            dmin = min(abs(off - s) for s in sites)
            if dmin <= 0x80:
                mark = "  <-- near a site"
        print(f"  +0x{off:05x} bl {nm}{mark}")
    if sites:
        print("site windows:")
        for s in sites:
            print(f"-- site +0x{s:x} (0x{start + s:x}) --")
            for ins in img.disasm(start + s - 0x30, 0x60):
                tgt = ""
                if ins.mnemonic in ("bl", "b", "b.eq", "b.ne", "cbz", "cbnz", "tbz", "tbnz", "b.ls", "b.hi"):
                    for op in ins.operands:
                        if op.type == ARM64_OP_IMM:
                            t = img.near((op.imm & 0xFFFFFFFFFFFFFFFF))
                            tgt = f"   ; {t[1] if t else hex((op.imm & 0xFFFFFFFFFFFFFFFF))}"
                mark = " <<<" if ins.address == start + s else ""
                print(f"  +0x{ins.address - start:05x}  {ins.mnemonic:8s} {ins.op_str}{tgt}{mark}")


if __name__ == "__main__":
    main()



