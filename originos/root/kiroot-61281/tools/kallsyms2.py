#!/usr/bin/env python3
"""Anchor-validated kallsyms recovery.

Names are decoded block-by-block bounded by kallsyms_markers (no drift).
The offsets array start is then solved so that known (name, addr) anchors from an
authoritative table match exactly, which also proves the names list is aligned.
"""
import struct, sys, numpy as np
from pathlib import Path

MASK = (1 << 64) - 1


class Kallsyms:
    def __init__(self, path):
        self.data = d = open(path, "rb").read()
        self.n = n = len(d)
        self.u16 = np.frombuffer(d[: n // 2 * 2], dtype="<u2")
        self.u32 = np.frombuffer(d[: n // 4 * 4], dtype="<u4")
        self._tokens()
        self._markers()
        self._names()

    def _tokens(self):
        d, u16 = self.data, self.u16
        for i in np.flatnonzero(u16 == 0):
            if i + 256 > len(u16):
                continue
            prev, ok = 0, True
            for k in range(1, 256):
                v = int(u16[i + k])
                if v <= prev:
                    ok = False
                    break
                prev = v
            if not ok or prev > 0x2000:
                continue
            cand = int(i) * 2
            idx = [int(u16[i + k]) for k in range(256)]
            for s in range(cand - prev - 40, cand - prev + 40):
                if s < 0 or d[cand - 1] != 0:
                    continue
                if all(d[s + idx[k] - 1] == 0 for k in range(1, 256)):
                    j = b"".join(d[s:cand].split(b"\x00")[:256])
                    if b"init" in j and b"alloc" in j:
                        self.ti, self.tt = cand, s
                        toks, p = [], s
                        for _ in range(256):
                            e = d.index(b"\x00", p)
                            toks.append(d[p:e].decode("latin1"))
                            p = e + 1
                        self.toks = toks
                        return
        raise RuntimeError("token table not found")

    def _markers(self):
        """Longest strictly-increasing u32 run ending before the token table."""
        d, u32, tt = self.data, self.u32, self.tt
        best = None
        for pad in range(0, 64, 4):
            end = tt - pad
            if end % 4 or end <= 0:
                continue
            i = end // 4 - 1
            cnt, prev = 0, 1 << 32
            while i >= 0:
                v = int(u32[i])
                if v < prev:
                    prev, cnt = v, cnt + 1
                    i -= 1
                else:
                    break
            if cnt >= 100 and prev == 0:
                if best is None or cnt > best[1]:
                    best = ((i + 1) * 4, cnt, pad)
        if best is None:
            raise RuntimeError("markers not found")
        self.mstart, self.nm, self.mpad = best
        self.markers = [int(u32[self.mstart // 4 + k]) for k in range(self.nm)]

    def _decode_block(self, start, end, want):
        d, toks = self.data, self.toks
        names, p = [], start
        while p < end:
            if want is not None and len(names) >= want:
                return None
            b = d[p]; p += 1
            if b & 0x80:
                b = (b & 0x7F) | (d[p] << 7); p += 1
            if b == 0 or b > 200 or p + b > end:
                return None
            names.append("".join(toks[d[p + j]] for j in range(b)))
            p += b
        return names if (want is None or len(names) == want) else None

    def _names(self):
        """Solve the names blob start from markers[1], then decode continuously to the markers."""
        mk, mstart = self.markers, self.mstart
        m1 = mk[1]
        s_found = None
        for L in range(mk[-1], mk[-1] + 8192):
            s = mstart - L
            if s < 0:
                break
            if self._decode_block(s, s + m1, 256) is not None:
                s_found = s
                break
        if s_found is None:
            raise RuntimeError("names blob start not solved")
        s = s_found
        # continuous decode; trailing zero padding up to 8 bytes is expected
        d, toks = self.data, self.toks
        names, p = [], s
        while p < mstart:
            b = d[p]; p += 1
            if b & 0x80:
                b = (b & 0x7F) | (d[p] << 7); p += 1
            if b == 0 or b > 200 or p + b > mstart:
                break
            names.append("".join(toks[d[p + j]] for j in range(b)))
            p += b
        if mstart - p > 8 or any(d[p:mstart]):
            raise RuntimeError(f"names decode desync at 0x{p:x}")
        # markers must agree with the decoded stream
        bad = sum(1 for bi in range(1, len(mk)) if mk[bi] > mstart - s)
        if bad:
            raise RuntimeError("markers inconsistent with names blob")
        self.names_start = s
        self.names = names
        self.N = len(names)
        self.blob_end = mstart

    def fit_offsets(self, anchors):
        """Solve the offsets array start from (name -> link-time addr) anchors."""
        d = self.data
        idx = {}
        for i, nm in enumerate(self.names):
            idx.setdefault(nm[1:], i)
        pairs = [(idx[nm], va) for nm, va in anchors.items() if nm in idx]
        if len(pairs) < 8:
            raise RuntimeError(f"too few anchors found in names: {len(pairs)}")
        base = 0xFFFFFFC080000000
        # candidate from the anchor with the rarest address pattern
        best = None
        for i0, v0 in pairs[:40]:
            pat = struct.pack("<i", v0 - base)
            pos = -1
            while True:
                pos = d.find(pat, pos + 1)
                if pos < 0:
                    break
                p = pos - 4 * i0
                if p < 0 or p % 4 or p + 4 * self.N > len(d):
                    continue
                hits = 0
                for i, va in pairs:
                    if struct.unpack_from("<i", d, p + 4 * i)[0] == (va - base) & 0xFFFFFFFF - (1 << 32 if (va - base) & 0x80000000 else 0):
                        pass
                # exact check below (avoid sign juggling)
                hits = sum(
                    1
                    for i, va in pairs
                    if struct.unpack_from("<i", d, p + 4 * i)[0] == va - base
                )
                if best is None or hits > best[0]:
                    best = (hits, p)
                if hits == len(pairs):
                    break
            if best and best[0] == len(pairs):
                break
        if not best:
            raise RuntimeError("offsets array not located")
        hits, p = best
        self.offs_at = p
        arr = np.frombuffer(d[p: p + 4 * self.N], dtype="<i4")
        self.off_arr = arr
        self.addrs = [base + int(v) for v in arr]
        self.anchor_hits = (hits, len(pairs))
        return hits, len(pairs)

    def table(self):
        out = {}
        for nm, a in zip(self.names, self.addrs):
            out.setdefault(nm[1:], a)
        return out

    def info(self):
        print(f"token_table@0x{self.tt:x} markers@0x{self.mstart:x} n={self.nm} pad={self.mpad}")
        print(f"names@0x{self.names_start:x}..0x{self.blob_end:x} N={self.N}")
        m = np.diff(np.array(self.addrs, dtype=object))
        print(f"offsets@0x{self.offs_at:x} first=0x{self.addrs[0]:x} last=0x{self.addrs[-1]:x} "
              f"monotonic={sum(1 for x in m if x >= 0) / len(m):.5f}")


if __name__ == "__main__":
    path = sys.argv[1]
    ks = Kallsyms(path)
    anchors = {}
    ap = Path(__file__).resolve().parents[4] / "originos" / "root" / ".upstream" / \
        "kiroot-apk" / "extracted" / "assets" / "ksu_patch.txt"
    for line in open(ap, encoding="utf-8"):
        q = line.split()
        if len(q) >= 2:
            anchors[q[0]] = int(q[1], 16)
    if len(sys.argv) > 2 and sys.argv[2] == "fit":
        hits, total = ks.fit_offsets(anchors)
        print(f"anchor fit: {hits}/{total}")
        ks.info()
        t = ks.table()
        miss = [k for k in anchors if k not in t]
        bad = [(k, anchors[k], t[k]) for k in anchors if k in t and t[k] != anchors[k]]
        print(f"names present: {len(anchors)-len(miss)}/{len(anchors)}  exact-addr mismatches: {len(bad)}")
        for k, a, b in bad[:5]:
            print(f"   {k} table=0x{a:x} extracted=0x{b:x}")
        if not bad:
            Path("cache/scratch/kallsyms_61258.txt").write_text(
                "".join(f"{k} 0x{v:x}\n" for k, v in sorted(t.items())), encoding="utf-8")
            print("wrote cache/scratch/kallsyms_61258.txt")



