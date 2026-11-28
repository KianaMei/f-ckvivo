#!/usr/bin/env python3
"""Repack kiroot.apk with a retargeted payload + symbol table.

Replaces assets/poc_tmp_root and assets/ksu_patch.txt in place and rewrites the zip,
dropping the old v1 signature entries (they cannot survive the edit; sign afterwards
with apksigner -- see PORT-61281.md §5).

usage: repack_apk.py [--orig APK] [--new DIR] [--out APK] [--skip-hash]
"""
import argparse
import hashlib
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
ORIG = REPO / "originos" / "root" / ".upstream" / "kiroot-apk" / "kiroot.apk"
ORIG_SHA256 = "ca47e660d0011ed6344d7c65b6101e80ce18170807d93d74d2768ae58b11a303"
NEW = REPO / "cache" / "scratch" / "kiroot_61281"
OUT = NEW / "kiroot-81-unsigned.apk"
REPL = ("assets/poc_tmp_root", "assets/ksu_patch.txt")
STALE_SIG = ("META-INF/ANDROIDD.RSA", "META-INF/ANDROIDD.SF", "META-INF/MANIFEST.MF")


def sha(b):
    return hashlib.sha256(b).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--orig", default=str(ORIG))
    ap.add_argument("--new", default=str(NEW))
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--skip-hash", action="store_true", help="accept any source APK")
    args = ap.parse_args()

    orig, new, out = Path(args.orig), Path(args.new), Path(args.out)
    blob = orig.read_bytes()
    got = sha(blob)
    if not args.skip_hash and got != ORIG_SHA256:
        raise SystemExit(f"{orig} is not the archived kiroot.apk\n  want {ORIG_SHA256}\n  got  {got}")
    for name in REPL:
        if not (new / name.split("/")[-1]).exists():
            raise SystemExit(f"missing retargeted {new / name.split('/')[-1]}")

    zin = zipfile.ZipFile(orig)
    names = zin.namelist()
    for name in REPL:
        if name not in names:
            raise SystemExit(f"missing entry {name} in {orig}")

    out.parent.mkdir(parents=True, exist_ok=True)
    dropped = []
    with zipfile.ZipFile(out, "w") as zout:
        for info in zin.infolist():
            if info.filename in STALE_SIG:
                dropped.append(info.filename)
                continue
            data = zin.read(info.filename)
            if info.filename in REPL:
                repl = (new / info.filename.split("/")[-1]).read_bytes()
                print(f"  replace {info.filename}: {len(data)} -> {len(repl)} bytes "
                      f"sha256 {sha(repl)[:16]}…")
                data = repl
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system
            zout.writestr(zi, data)
    zin.close()
    print(f"  dropped stale signature entries: {dropped}")
    print(f"wrote {out} {out.stat().st_size} bytes  sha256 {sha(out.read_bytes())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
