#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Patch QuietBox's shared target-version string and repair DEX integrity fields."""
import argparse
import hashlib
import struct
import zlib
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--old", default="9.53.0")
    parser.add_argument("--new", default="9.54.0")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.output.exists() and not args.force:
        raise SystemExit("output exists; pass --force to replace it: %s" % args.output)
    old = args.old.encode("ascii") + b"\0"
    new = args.new.encode("ascii") + b"\0"
    if len(old) != len(new):
        raise SystemExit("version strings must have equal encoded lengths")

    data = bytearray(args.source.read_bytes())
    if not data.startswith(b"dex\n"):
        raise SystemExit("not a DEX file: %s" % args.source)
    count = data.count(old)
    if count != 1:
        raise SystemExit("expected exactly one %r string, found %d" % (old, count))
    offset = data.index(old)
    data[offset:offset + len(old)] = new

    # DEX: checksum@8 (adler32 of bytes 12+), signature@12 (SHA-1 of bytes 32+).
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
    if data.count(old) != 0 or data.count(new) != 1:
        raise SystemExit("version replacement verification failed")
    if struct.unpack_from("<I", data, 32)[0] != len(data):
        raise SystemExit("DEX file_size field was damaged")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data)
    print("patched string at file offset 0x%x: %s -> %s" % (
        offset, args.old, args.new))
    print("DEX signature: %s" % data[12:32].hex())
    print("DEX checksum:  %s" % data[8:12].hex())
    print("SHA-256:       %s" % hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()
