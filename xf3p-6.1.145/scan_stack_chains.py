"""Find syscall-entry call chains whose kernel stack frames intersect the stale-waiter band.

Waiter (from FUTEX_WAIT_REQUEUE_PI on this build): SP_top-0x2d8 .. SP_top-0x2a0
(fields: task@-0x2a8, lock@-0x2a0; base -0x2d8).

Method: disassemble every __arm64_sys_* wrapper, BFS static `bl` edges, accumulate
frame depth (sub sp / stp [sp,#-N]!), and record any function whose frame window
intersects the band. Then flag the ones that call a copy_from_user-family helper.
"""
import bisect
import capstone
import re
import struct
from collections import deque

IMG = open('xf3p_artifacts/kernel_image', 'rb').read()
BASE = 0xffffffc008000000
md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_LITTLE_ENDIAN)

syms = []
byname = {}
for line in open('ksubuilder/out/kallsyms.txt'):
    p = line.split()
    if len(p) >= 3:
        try:
            a = int(p[0], 16)
        except ValueError:
            continue
        n = ' '.join(p[2:])
        syms.append((a, n))
        byname.setdefault(n, a)
syms.sort()
addrs = [a for a, _ in syms]


def symbol_of(a):
    i = bisect.bisect_right(addrs, a) - 1
    if i < 0:
        return None, None
    return syms[i][1], a - syms[i][0]


def size_of(i):
    return (syms[i + 1][0] - syms[i][0]) if i + 1 < len(syms) else 0x40


CACHE = {}


def disasm_func(a, size):
    key = (a, size)
    if key in CACHE:
        return CACHE[key]
    code = IMG[a - BASE:a - BASE + min(size, 0x2000)]
    out = list(md.disasm(code, a))
    CACHE[key] = out
    return out


def frame_size(insns):
    for ins in insns[:10]:
        if ins.mnemonic == 'sub' and ins.op_str.startswith('sp, sp, #'):
            return int(ins.op_str.split('#')[1], 16)
        if ins.mnemonic == 'stp' and '[sp' in ins.op_str:
            m = re.search(r'#-(0x[0-9a-f]+)', ins.op_str)
            if m:
                return int(m.group(1), 16)
    return 0


COPY_FUNCS = {'copy_from_user', '__copy_from_user', 'copy_msghdr_from_user',
              'get_compat_msghdr', '_copy_from_iter', 'copy_from_user_tmp'}

BAND_LO = 0x2a0          # shallowest waiter byte of interest (lock slot)
BAND_HI = 0x2d8 + 8      # deepest (waiter base .. +8)

entries = [(a, n) for a, n in syms if n.startswith('__arm64_sys_')]
results = []
for ea, ename in entries:
    i = bisect.bisect_right(addrs, ea) - 1
    insns = disasm_func(ea, size_of(i))
    # BFS
    seen = {}
    q = deque()
    q.append((ename, 0, 0))   # (fname, depth, hopped)
    seen[ename] = 0
    while q:
        fname, depth, hops = q.popleft()
        if hops >= 5:
            continue
        a = byname.get(fname)
        if a is None:
            continue
        j = bisect.bisect_right(addrs, a) - 1
        fins = disasm_func(a, size_of(j))
        f = frame_size(fins)
        lo, hi = depth, depth + f  # frame window below SP_top
        def _bl_target(op):
            m = re.search(r'#(0x[0-9a-f]+)', op)
            return int(m.group(1), 16) if m else None
        hits_copy = any(symbol_of(t)[0] in COPY_FUNCS for t in (_bl_target(ins.op_str) for ins in fins if ins.mnemonic == 'bl') if t)
        if lo < BAND_HI and hi > BAND_LO and f > 0x40:
            results.append((ename, fname, depth, f, hits_copy, hops))
        for ins in fins:
            if ins.mnemonic == 'bl':
                m = re.search(r'#(0x[0-9a-f]+)', ins.op_str)
                if not m:
                    continue
                tgt = int(m.group(1), 16)
                tn, off = symbol_of(tgt)
                if not tn or off:
                    continue
                nd = depth + f
                if tn not in seen and nd < BAND_HI + 0x100:
                    seen[tn] = nd
                    q.append((tn, nd, hops + 1))

results.sort(key=lambda r: r[4], reverse=True)
print(f'entries scanned: {len(entries)}, functions in band: {len(results)}')
for en, fn, d, f, hc, h in results[:40]:
    print(f'{en:38s} -> {fn:32s} depth={d:#5x} frame={f:#5x} window=[{d:#x},{d + f:#x}] copy_user={hc} hops={h}')
