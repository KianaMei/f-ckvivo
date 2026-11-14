#!/usr/bin/env python3
"""Minimal DEX string table dumper (read-only). Usage: python -I dy_dex_strings.py <classes.dex> [regex]"""
import re
import struct
import sys

PATH = sys.argv[1]
pattern = re.compile(sys.argv[2], re.I) if len(sys.argv) > 2 else None

with open(PATH, "rb") as f:
    data = f.read()

if data[:4] != b"dex\n":
    sys.exit("not a dex file")
string_ids_size, string_ids_off = struct.unpack_from("<II", data, 0x38)


def uleb(buf, off):
    result = shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def mutf8(raw):
    # decode modified UTF-8 (surrogate pairs encoded separately)
    out = []
    i = 0
    while i < len(raw):
        b = raw[i]
        if b < 0x80:
            out.append(chr(b))
            i += 1
        elif b & 0xE0 == 0xC0:
            out.append(chr(((b & 0x1F) << 6) | (raw[i + 1] & 0x3F)))
            i += 2
        else:
            cp = ((b & 0x0F) << 12) | ((raw[i + 1] & 0x3F) << 6) | (raw[i + 2] & 0x3F)
            out.append(chr(cp))
            i += 3
    return "".join(out)


for idx in range(string_ids_size):
    (off,) = struct.unpack_from("<I", data, string_ids_off + 4 * idx)
    _, p = uleb(data, off)
    end = data.index(b"\x00", p)
    s = mutf8(data[p:end])
    if pattern is None or pattern.search(s):
        print(f"{idx:6d}\t{s}")
