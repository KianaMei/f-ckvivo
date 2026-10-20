"""Extract partitions from a full-OTA zip's payload.bin (update_engine).

Supports REPLACE(0), REPLACE_BZ(1), REPLACE_XZ(8), REPLACE_ZSTD(14), ZERO(6), DISCARD(7).
The payload.bin in the vivo full OTA zip is stored (not deflated), so we read it
in place at its absolute file offset — no 9 GB extraction needed.
"""
import argparse
import bz2
import lzma
import struct
import sys
import zipfile
from pathlib import Path

try:
    from compression import zstd
except ImportError:  # python < 3.14
    import zstandard as zstd


MAGIC = b"CrAU"
PAGE = 4096

OP_NAMES = {
    0: "REPLACE", 1: "REPLACE_BZ", 2: "MOVE", 3: "BSDIFF", 4: "SOURCE_COPY",
    5: "SOURCE_BSDIFF", 6: "ZERO", 7: "DISCARD", 8: "REPLACE_XZ", 9: "PUFFDIFF",
    10: "BROTLI_BSDIFF", 11: "ZUCCHINI", 12: "LZ4DIFF_BSDIFF", 13: "LZ4DIFF_PUFFDIFF",
    14: "REPLACE_ZSTD",
}


def varint(buf, pos):
    out = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise ValueError("varint out of bounds")
        b = buf[pos]
        pos += 1
        out |= (b & 0x7F) << shift
        if not b & 0x80:
            return out, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def parse_msg(buf):
    fields = {}
    pos = 0
    while pos < len(buf):
        key, pos = varint(buf, pos)
        num, wire = key >> 3, key & 7
        if wire == 0:
            val, pos = varint(buf, pos)
        elif wire == 1:
            val = struct.unpack_from("<Q", buf, pos)[0]
            pos += 8
        elif wire == 2:
            ln, pos = varint(buf, pos)
            val = buf[pos:pos + ln]
            pos += ln
        elif wire == 5:
            val = struct.unpack_from("<I", buf, pos)[0]
            pos += 4
        else:
            raise ValueError(f"wire type {wire}")
        fields.setdefault(num, []).append((wire, val))
    return fields


def ints(f, n):
    return [v for w, v in f.get(n, []) if w == 0]


def blobs(f, n):
    return [v for w, v in f.get(n, []) if w == 2]


def msgs(f, n):
    return [parse_msg(v) for v in blobs(f, n)]


def one_int(f, n, default=0):
    v = ints(f, n)
    return v[0] if v else default


def payload_offset_in_zip(zip_path):
    with zipfile.ZipFile(zip_path) as z:
        info = z.getinfo("payload.bin")
        if info.compress_type != zipfile.ZIP_STORED:
            raise SystemExit("payload.bin is deflated inside the zip; extract it first")
        with open(zip_path, "rb") as fh:
            fh.seek(info.header_offset)
            local = fh.read(30)
            if local[:4] != b"PK\x03\x04":
                raise SystemExit("bad local file header")
            name_len = struct.unpack_from("<H", local, 26)[0]
            extra_len = struct.unpack_from("<H", local, 28)[0]
            return info.header_offset + 30 + name_len + extra_len, info.file_size


class Payload:
    def __init__(self, zip_path):
        self.base, self.size = payload_offset_in_zip(zip_path)
        self.fh = open(zip_path, "rb")
        self.fh.seek(self.base)
        head = self.fh.read(24)
        self.base += 24
        magic, version, manifest_size, sig_size = struct.unpack(">4sQQI", head)
        if magic != MAGIC:
            raise SystemExit("not a CrAU payload")
        self.manifest_size = manifest_size
        manifest = self.fh.read(manifest_size)
        self.data_start = self.base + manifest_size + sig_size
        self.fields = parse_msg(manifest)

    def partitions(self):
        out = {}
        for part in msgs(self.fields, 13):
            name = blobs(part, 1)[0].decode()
            out[name] = part
        return out

    def extract(self, name, out_path):
        part = self.partitions().get(name)
        if part is None:
            raise SystemExit(f"no partition {name!r}")
        ops = msgs(part, 8)
        tally = {}
        out = bytearray()
        for op in ops:
            otype = one_int(op, 1)
            label = OP_NAMES.get(otype, str(otype))
            tally[label] = tally.get(label, 0) + 1
            extents = msgs(op, 6)
            blocks = sum(one_int(e, 2) for e in extents)
            if otype in (6, 7):
                size = blocks * PAGE if blocks else one_int(op, 7)
                out += b"\x00" * size
                continue
            if otype in (0, 1, 8, 14):
                off = one_int(op, 2)
                ln = one_int(op, 3)
                self.fh.seek(self.data_start + off)
                blob = self.fh.read(ln)
                if len(blob) != ln:
                    raise SystemExit(f"truncated op data {len(blob)}/{ln}")
                if otype == 1:
                    blob = bz2.decompress(blob)
                elif otype == 8:
                    blob = lzma.decompress(blob)
                elif otype == 14:
                    try:
                        blob = zstd.decompress(blob)
                    except Exception as e:
                        print(f"  zstd decompress failed ({e}); trying raw frame streaming", file=sys.stderr)
                        dctx = getattr(zstd, "ZstdDecompressor", None)
                        if dctx:
                            d = dctx().decompressobj()
                            blob = d.decompress(blob)
                        else:
                            raise
                out += blob
                continue
            raise SystemExit(f"partition {name} has unsupported op {label}")
        Path(out_path).write_bytes(out)
        return len(out), tally


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zip")
    ap.add_argument("partition", nargs="?")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()
    p = Payload(args.zip)
    if args.list:
        for name, part in p.partitions().items():
            ops = msgs(part, 8)
            types = {}
            for op in ops:
                t = OP_NAMES.get(one_int(op, 1), str(one_int(op, 1)))
                types[t] = types.get(t, 0) + 1
            size = sum(one_int(e, 2) for op in ops for e in msgs(op, 6)) * PAGE
            print(f"{name:24s} ops={len(ops):4d} ~{size/1048576:.1f}MiB {types}")
        return
    n, tally = p.extract(args.partition, args.out)
    print(f"wrote {args.out}: {n} bytes  ops={tally}")


if __name__ == "__main__":
    main()
