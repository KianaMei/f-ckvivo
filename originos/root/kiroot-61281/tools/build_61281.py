#!/usr/bin/env python3
"""Retarget kiroot's payload from a 6.12.58 kernel to another build of the same tree.

Inputs
  --image     kernel image of the TARGET build. Either the raw arm64 Image or the
              thin ELF wrapper made by tools/wrap_image.py (ET_REL, one .data section).
  --assets    directory holding the reference poc_tmp_root + ksu_patch.txt
  --outdir    where the retargeted payload / table are written

What is build-specific (and therefore what this script rewrites)
  1. ksu_patch.txt   : 225 name -> link-time address entries, rebuilt from the image's kallsyms.
  2. poc_tmp_root    : four kprobe site literals `load_module+0xNNNN`. All four sit on the
                       instruction right after a `bl` inside load_module, so they are located
                       by disassembling load_module and taking (bl offset + 4).
Everything else in the payload is version independent:
  * `cred+0x40` / `cred+0x42`  - struct cred layout is identical (cap_effective at +0x40)
  * `p:m6 do_init_module`      - resolved by symbol name, no offset
  * `p:m7 complete_formation`  - that static function is inlined out in both builds, the
                                 probe registration simply fails and is non-fatal
  * kernelsu.ko vermagic is normalised to `6.12.76-4k`, the loader patches the checks
"""
import argparse, hashlib, struct, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kallsyms2 import Kallsyms            # noqa: E402
from capstone.arm64 import ARM64_OP_IMM  # noqa: E402
from kdis import Image                   # noqa: E402

BASE = 0xFFFFFFC080000000

# bl targets whose return point kiroot hooks inside load_module, in payload order m2..m5
SITE_TARGETS = [
    ("blocking_notifier_call_chain_robust", b"load_module+0x28dc"),  # 6.12.58 value
    ("parse_args", b"load_module+0x2924"),
    ("mod_sysfs_setup", b"load_module+0x2948"),
    ("codetag_load_module", b"load_module+0x2964"),
]


def sha(b):
    return hashlib.sha256(b).hexdigest()


def build_table(image_path, names):
    img = open(image_path, "rb").read()
    ks = Kallsyms(image_path)
    pat = struct.pack("<Q", BASE)
    pos = img.find(pat)
    if pos < 0:
        raise SystemExit("relative_base 0xffffffc080000000 not found")
    p = pos - 4 * ks.N
    if p % 4:
        raise SystemExit(f"offsets array not 4-aligned (0x{p:x})")
    offs = struct.unpack_from(f"<{ks.N}i", img, p)
    addrs = [BASE + v for v in offs]
    if addrs[0] != BASE:
        raise SystemExit("offsets[0] != relative_base")
    if any(b < a for a, b in zip(addrs, addrs[1:])):
        raise SystemExit("offsets array is not monotonic")
    syms = {}
    for nm, a in zip(ks.names, addrs):
        syms.setdefault(nm[1:], a)
    missing = [n for n in names if n not in syms]
    if missing:
        raise SystemExit(f"missing symbols in target build: {missing}")
    print(f"[table] N={ks.N} offsets@0x{p:x} relative_base@0x{pos:x} "
          f"gap={pos - (p + 4 * ks.N)} resolved {len(names)}/{len(names)}")
    return syms


def build_sites(image_path, sites):
    img = Image(image_path)
    start, end = img.span("load_module")
    print(f"[sites] load_module 0x{start:x}..0x{end:x} span=0x{end - start:x}")
    calls = {}
    for ins in img.disasm(start, end - start):
        if ins.mnemonic != "bl":
            continue
        for op in ins.operands:
            if op.type != ARM64_OP_IMM:
                continue
            tgt = img.near(op.imm & 0xFFFFFFFFFFFFFFFF)
            if tgt:
                calls.setdefault(tgt[1], []).append(ins.address - start)
    out = {}
    for target, old_lit in sites:
        offs = calls.get(target, [])
        if len(offs) != 1:
            raise SystemExit(f"expected exactly one call to {target} in load_module, got {offs}")
        new_off = offs[0] + 4
        hexpart = old_lit.split(b"+")[1]
        new_lit = b"load_module+0x%x" % new_off
        if len(new_lit) != len(old_lit):
            raise SystemExit(f"literal length changed: {old_lit!r} -> {new_lit!r}")
        print(f"[sites] {target:38s} bl+0x{offs[0]:05x} -> site +0x{new_off:05x} "
              f"({old_lit.decode()} -> {new_lit.decode()})")
        out[old_lit] = new_lit
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", required=True)
    ap.add_argument("--assets", default=r"system\root\.upstream\kiroot-apk\extracted\assets")
    ap.add_argument("--outdir", default=r"cache\scratch\kiroot_retarget")
    args = ap.parse_args()

    out = Path(args.outdir)
    out.mkdir(parents=True, exist_ok=True)
    src = Path(args.assets)

    ref = [l.split() for l in (src / "ksu_patch.txt").read_text(encoding="utf-8").splitlines() if l.split()]
    names = [q[0] for q in ref]
    syms = build_table(args.image, names)
    table = "".join(f"{n} 0x{syms[n]:x}\n" for n in names)
    (out / "ksu_patch.txt").write_bytes(table.encode())

    sites = build_sites(args.image, SITE_TARGETS)
    payload = (src / "poc_tmp_root").read_bytes()
    patched = payload
    for old, new in sites.items():
        if patched.count(old) != 1:
            raise SystemExit(f"{old!r} appears {patched.count(old)} times in payload")
        patched = patched.replace(old, new)
    if len(patched) != len(payload):
        raise SystemExit("payload size changed")
    (out / "poc_tmp_root").write_bytes(patched)

    print(f"[payload] {sha(payload)[:16]}… -> {sha(patched)[:16]}… "
          f"({sum(1 for a, b in zip(payload, patched) if a != b)} bytes changed)")
    print(f"[done] {out}")


if __name__ == "__main__":
    main()

