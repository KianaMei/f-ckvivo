#!/usr/bin/env python3
"""Repack kiroot.apk with the 6.12.81 payload + symbol table (entries replaced in place)."""
import zipfile, shutil, hashlib, sys
from pathlib import Path

ORIG = Path(r"cache\scratch\kiroot_orig.apk")
OUT = Path(r"cache\scratch\kiroot_61281\kiroot-81-unsigned.apk")
NEW = Path(r"cache\scratch\kiroot_61281")
REPL = {
    "assets/poc_tmp_root": NEW / "poc_tmp_root",
    "assets/ksu_patch.txt": NEW / "ksu_patch.txt",
}

zin = zipfile.ZipFile(ORIG)
names = zin.namelist()
print("entries:", len(names))
for k in REPL:
    if k not in names:
        raise SystemExit(f"missing entry {k}")

with zipfile.ZipFile(OUT, "w") as zout:
    for info in zin.infolist():
        data = zin.read(info.filename)
        if info.filename in REPL:
            new = REPL[info.filename].read_bytes()
            print(f"  replace {info.filename}: {len(data)} -> {len(new)} bytes")
            data = new
        zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        zi.compress_type = info.compress_type
        zi.external_attr = info.external_attr
        zi.internal_attr = info.internal_attr
        zi.create_system = info.create_system
        zout.writestr(zi, data)
zin.close()
print("wrote", OUT, OUT.stat().st_size, "bytes")
print("sha256", hashlib.sha256(OUT.read_bytes()).hexdigest()[:16], "…")
