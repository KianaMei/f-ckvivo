#!/usr/bin/env python3
"""Offline kallsyms extractor for a raw ARM64 Linux kernel Image.

Works on the decompressed Image (no ELF, no symbols). Locates the compressed
kallsyms blobs the way the kernel lays them out:

    [kallsyms_offsets] [kallsyms_relative_base] [kallsyms_num_syms]
    [kallsyms_names] [kallsyms_markers] [kallsyms_seqs_of_names]
    [kallsyms_token_table] [kallsyms_token_index]

Discovery order:
  1. token_table: 256 NUL-terminated strings followed by 256 monotonic u16
     offsets (token_index) - a distinctive signature, prefiltered with numpy.
  2. relative_base: a kernel-pointer u64 before token_table whose +8 u32 looks
     like a symbol count.
  3. names: decoded forward until the count is reached; the end position must
     line up with markers/seqs, which validates the whole parse.

Usage:
  python tools/kallsyms_dump.py <kernel-image> [--json out.json] [--grep PAT]
"""
import json
import re
import struct
import sys

NUL = b"\x00"


def find_token_table(data):
    """Yield (token_table_off, index_off) candidates.

    Prefilter with numpy on the u16 view (token_index is 256 monotonic u16
    starting at 0), then verify by walking the 256 strings and requiring
    starts[i] - tt_off == index[i].
    """
    import numpy as np
    u16 = np.frombuffer(data, dtype="<u2")
    n = len(u16) - 256
    ar = np.arange(n)
    ok = (u16[ar] == 0) & (u16[ar + 1] > 0) & (u16[ar + 1] < 0x40)
    for i in np.nonzero(ok)[0]:
        vals = u16[i:i + 256]
        idx_off = int(i) * 2
        if not np.all(np.diff(vals.astype(np.int32)) > 0):
            continue
        last = int(vals[-1])
        if last < 0x100 or last > 0x2000:
            continue
        for tt in range(idx_off - last - 40, idx_off - last + 1):
            if tt < 0:
                continue
            starts = []
            q = tt
            good = True
            for _ in range(256):
                e = data.find(NUL, q, min(idx_off, q + 64))
                if e < 0:
                    good = False
                    break
                if any(c < 0x20 or c > 0x7E for c in data[q:e]):
                    good = False
                    break
                starts.append(q)
                q = e + 1
            if not good or q > idx_off:
                continue
            if any(starts[k] - tt != int(vals[k]) for k in range(256)):
                continue
            if any(data[q:idx_off]):
                continue
            yield tt, idx_off
            break


def build_tokens(data, tt_off):
    tokens = []
    p = tt_off
    for _ in range(256):
        e = data.find(NUL, p)
        tokens.append(data[p:e])
        p = e + 1
    return tokens


def decode_names(data, names_off, count, tokens):
    out = []
    p = names_off
    for _ in range(count):
        if p >= len(data):
            return None, p
        ln = data[p]
        p += 1
        if ln & 0x80:
            if p >= len(data):
                return None, p
            ln = (ln & 0x7F) | (data[p] << 7)
            p += 1
        if ln == 0 or p + ln > len(data):
            return None, p
        out.append(b"".join(tokens[b] for b in data[p:p + ln]))
        p += ln
    return out, p


def find_relative_base(data, end, back=1 << 22):
    out = []
    lo = max(0, end - back)
    for off in range(lo, end - 12, 4):
        v = struct.unpack_from("<Q", data, off)[0]
        if v < 0xFFFFFF8000000000:
            continue
        if v & 0xFFFF:
            continue
        n = struct.unpack_from("<I", data, off + 8)[0]
        if 10000 <= n <= 1000000:
            out.append((off, v, n))
    return out


def extract(data, verbose=True):
    for tt_off, idx_off in find_token_table(data):
        tokens = build_tokens(data, tt_off)
        for rb_off, base, n in reversed(find_relative_base(data, tt_off)):
            names_off = rb_off + 12
            if names_off >= tt_off:
                continue
            names, end = decode_names(data, names_off, n, tokens)
            if names is None:
                continue
            markers = ((n + 255) // 256) * 4
            matched = None
            for seqw, seqname in ((3, "3-byte"), (4, "4-byte")):
                for pad1 in range(0, 8):
                    for pad2 in range(0, 8):
                        if end + markers + pad1 + n * seqw + pad2 == tt_off:
                            matched = (seqw, seqname)
                            break
                    if matched:
                        break
                if matched:
                    break
            if not matched:
                continue
            seqw, seqname = matched
            if True:
                if any((not s) or any(c < 0x20 or c > 0x7E for c in s) for s in names):
                    continue
                offsets_off = rb_off - 4 * n
                if offsets_off < 0:
                    continue
                addrs = []
                for i in range(n):
                    rel = struct.unpack_from("<i", data, offsets_off + 4 * i)[0]
                    addrs.append((base + rel) & 0xFFFFFFFFFFFFFFFF)
                if any(addrs[i] > addrs[i + 1] for i in range(n - 1)):
                    continue
                if verbose:
                    print("[+] token_table @%#x  token_index @%#x" % (tt_off, idx_off))
                    print("[+] relative_base @%#x = %#x   num_syms = %d   seqs=%s"
                          % (rb_off, base, n, seqname))
                    print("[+] names @%#x .. %#x (%d B)" % (names_off, end, end - names_off))
                    print("[+] offsets @%#x   addr range %#x .. %#x"
                          % (offsets_off, addrs[0], addrs[-1]))
                return {"base": base, "count": n, "token_table": tt_off,
                        "token_index": idx_off, "names": names_off,
                        "offsets": offsets_off, "seqs_encoding": seqname,
                        "symbols": [(s.decode("ascii", "replace"), a)
                                    for s, a in zip(names, addrs)]}
    return None


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    img = sys.argv[1]
    out = sys.argv[sys.argv.index("--json") + 1] if "--json" in sys.argv else None
    pat = sys.argv[sys.argv.index("--grep") + 1] if "--grep" in sys.argv else None
    data = open(img, "rb").read()
    print("[*] image %s (%d B)" % (img, len(data)))
    res = extract(data)
    if not res:
        print("[-] kallsyms not located")
        return 1
    if out:
        with open(out, "w") as f:
            json.dump({k: v for k, v in res.items() if k != "symbols"}, f, indent=1)
            f.write("\n")
        txt = re.sub(r"\.json$", "", out) + ".txt"
        with open(txt, "w") as f:
            for s, a in res["symbols"]:
                f.write("%016x %s\n" % (a, s))
        print("[+] wrote %s (+ %s)" % (out, txt))
    if pat:
        rx = re.compile(pat)
        for s, a in res["symbols"]:
            if rx.search(s):
                print("%016x %s" % (a, s))
    return 0


if __name__ == "__main__":
    sys.exit(main())
