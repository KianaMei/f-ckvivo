"""Read APK DEX declarations into SQLite without running application code.

Usage: python scripts/index_host_dex.py analysis/host412/sample.apk
Query the resulting read-only data with Python sqlite3. Descriptors use DEX
notation, e.g. Lcom/ss/android/ugc/aweme/feed/model/Aweme; and (I)V.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import struct
import time
import zipfile


class Dex:
    def __init__(self, data):
        if data[:4] != b"dex\n" or data[40:44] != b"\x78\x56\x34\x12":
            raise ValueError("Expected standard little-endian DEX")
        self.data = data
        self.strings = []
        size, offset = self.table(56)
        for index in range(size):
            cursor = self.u32(offset + index * 4)
            _, cursor = self.uleb(cursor)
            end = data.index(b"\0", cursor)
            self.strings.append(data[cursor:end].replace(b"\xc0\x80", b"\0").decode("utf-8", "replace"))
        size, offset = self.table(64)
        self.types = [self.strings[self.u32(offset + i * 4)] for i in range(size)]
        size, offset = self.table(72)
        self.protos = []
        for index in range(size):
            _, return_index, args_offset = struct.unpack_from("<III", data, offset + index * 12)
            args = self.type_list(args_offset)
            self.protos.append("(" + "".join(args) + ")" + self.types[return_index])
        size, offset = self.table(80)
        self.fields = []
        for index in range(size):
            owner, type_index, name = struct.unpack_from("<HHI", data, offset + index * 8)
            self.fields.append((self.types[owner], self.strings[name], self.types[type_index]))
        size, offset = self.table(88)
        self.methods = []
        for index in range(size):
            owner, proto, name = struct.unpack_from("<HHI", data, offset + index * 8)
            self.methods.append((self.types[owner], self.strings[name], self.protos[proto]))

    def u32(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def table(self, offset):
        return struct.unpack_from("<II", self.data, offset)

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.data[offset]
            offset += 1
            value |= (byte & 0x7f) << shift
            if byte < 128:
                return value, offset
        raise ValueError("Invalid uleb128")

    def type_list(self, offset):
        if offset == 0:
            return []
        count = self.u32(offset)
        return [self.types[struct.unpack_from("<H", self.data, offset + 4 + i * 2)[0]] for i in range(count)]

    def declarations(self, dex_name):
        classes, fields, methods = [], [], []
        count, offset = self.table(96)
        for i in range(count):
            owner_idx, access, super_idx, interfaces_off, _, _, class_data_off, _ = struct.unpack_from("<8I", self.data, offset + i * 32)
            owner = self.types[owner_idx]
            superclass = self.types[super_idx] if super_idx != 0xffffffff else None
            classes.append((owner, dex_name, access, superclass, json.dumps(self.type_list(interfaces_off))))
            if class_data_off == 0:
                continue
            cursor = class_data_off
            counts = []
            for _ in range(4):
                value, cursor = self.uleb(cursor)
                counts.append(value)
            for group_count in counts[:2]:
                field_idx = 0
                for _ in range(group_count):
                    difference, cursor = self.uleb(cursor)
                    field_access, cursor = self.uleb(cursor)
                    field_idx += difference
                    field_owner, name, field_type = self.fields[field_idx]
                    if field_owner != owner:
                        raise ValueError("Field owner mismatch")
                    fields.append((owner, name, field_type, field_access, dex_name))
            for group_count in counts[2:]:
                method_idx = 0
                for _ in range(group_count):
                    difference, cursor = self.uleb(cursor)
                    method_access, cursor = self.uleb(cursor)
                    code_off, cursor = self.uleb(cursor)
                    method_idx += difference
                    method_owner, name, proto = self.methods[method_idx]
                    if method_owner != owner:
                        raise ValueError("Method owner mismatch")
                    methods.append((owner, name, proto, method_access, code_off, dex_name))
        return classes, fields, methods


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    output = args.out or args.apk.with_name("dex-index.sqlite")
    if output.exists():
        raise SystemExit(f"Refusing to replace existing index: {output}")
    started = time.time()
    db = sqlite3.connect(output)
    db.executescript("""
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        CREATE TABLE classes(name TEXT PRIMARY KEY, dex TEXT, access INTEGER, superclass TEXT, interfaces TEXT);
        CREATE TABLE fields(owner TEXT, name TEXT, type TEXT, access INTEGER, dex TEXT);
        CREATE TABLE methods(owner TEXT, name TEXT, proto TEXT, access INTEGER, code_off INTEGER, dex TEXT);
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
    """)
    stats = []
    with zipfile.ZipFile(args.apk) as archive:
        dex_names = sorted((n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)),
                           key=lambda n: int(re.search(r"\d+", n).group()) if re.search(r"\d+", n) else 1)
        for name in dex_names:
            dex = Dex(archive.read(name))
            classes, fields, methods = dex.declarations(name)
            db.executemany("INSERT INTO classes VALUES (?,?,?,?,?)", classes)
            db.executemany("INSERT INTO fields VALUES (?,?,?,?,?)", fields)
            db.executemany("INSERT INTO methods VALUES (?,?,?,?,?,?)", methods)
            db.commit()
            row = {"dex": name, "classes": len(classes), "fields": len(fields), "methods": len(methods)}
            stats.append(row)
            print(json.dumps(row), flush=True)
            del dex, classes, fields, methods
    print("Creating declaration indexes", flush=True)
    db.executescript("CREATE INDEX methods_owner ON methods(owner); CREATE INDEX methods_name ON methods(name); CREATE INDEX fields_owner ON fields(owner);")
    with args.apk.open("rb") as stream:
        fingerprint = hashlib.file_digest(stream, "sha256").hexdigest()
    totals = {key: sum(row[key] for row in stats) for key in ("classes", "fields", "methods")}
    summary = {"apk": str(args.apk.resolve()), "sha256": fingerprint, "dex_count": len(stats), **totals,
               "seconds": round(time.time() - started, 2), "dex": stats}
    db.executemany("INSERT INTO meta VALUES (?,?)", [("summary", json.dumps(summary))])
    db.commit()
    db.close()
    output.with_suffix(".summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in summary.items() if k != "dex"}), flush=True)


if __name__ == "__main__":
    main()
