#!/usr/bin/env python3
"""Static analysis of vivo vr.ko for anti-root tags and hooks."""
from __future__ import annotations

import re
import struct
import argparse
from collections import Counter
from pathlib import Path


def load_elf_file():
    try:
        from elftools.elf.elffile import ELFFile
    except ImportError as exc:
        raise SystemExit("missing dependency: install with 'python -m pip install pyelftools'") from exc
    return ELFFile


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="path to vr.ko or equivalent ELF")
    parser.add_argument("--output", type=Path, help="optional report path; defaults to stdout only")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    vr_path = args.input
    if not vr_path.is_file():
        raise SystemExit(f"input ELF not found: {vr_path}")
    data = vr_path.read_bytes()
    lines: list[str] = []

    def log(s: str = "") -> None:
        lines.append(s)
        print(s)

    ELFFile = load_elf_file()
    with vr_path.open("rb") as f:
        elf = ELFFile(f)
        log(f"file={vr_path} size={len(data)}")
        log(f"arch={elf.get_machine_arch()} type={elf.header['e_type']}")
        log("\n== sections ==")
        for s in elf.iter_sections():
            log(f"  {s.name:28s} type={s['sh_type']!s:12s} "
                f"addr=0x{s['sh_addr']:x} size=0x{s['sh_size']:x}")

        symtab = elf.get_section_by_name(".symtab") or elf.get_section_by_name(".dynsym")
        syms = []
        if symtab:
            for s in symtab.iter_symbols():
                if s.name:
                    syms.append(
                        (
                            s["st_value"],
                            s["st_info"]["type"],
                            s["st_info"]["bind"],
                            s["st_size"],
                            s.name,
                        )
                    )
        log(f"\n== symbols ({len(syms)}) ==")
        for v, t, b, sz, n in sorted(syms, key=lambda x: (x[0], x[4])):
            log(f"  0x{v:08x} {t:8s} {b:7s} sz={sz:6d} {n}")

        # Interesting strings
        log("\n== interesting strings ==")
        printable = re.findall(rb"[\x20-\x7e]{4,}", data)
        keys = (
            b"commit_creds",
            b"sys_exit",
            b"tracepoint",
            b"android_rvh",
            b"kill",
            b"force_sig",
            b"send_sig",
            b"euid",
            b"fsuid",
            b"setuid",
            b"cred",
            b"task",
            b"vr",
            b"vklp",
            b"root",
            b"su",
            b"magisk",
            b"shell",
        )
        seen = set()
        for s in printable:
            low = s.lower()
            if any(k in low for k in keys):
                try:
                    txt = s.decode("ascii")
                except Exception:
                    continue
                if txt not in seen:
                    seen.add(txt)
                    log(f"  {txt}")

        # Relocations: which kernel symbols does vr import?
        log("\n== relocations / undefined symbols ==")
        for sec in elf.iter_sections():
            if not sec.name.startswith(".rela") and not sec.name.startswith(".rel."):
                continue
            log(f"  [{sec.name}]")
            try:
                for rel in sec.iter_relocations():
                    sym = symtab.get_symbol(rel["r_info_sym"])
                    if sym and sym.name:
                        log(
                            f"    off=0x{rel['r_offset']:x} "
                            f"type={rel['r_info_type']} "
                            f"addend={getattr(rel, 'r_addend', 0)} "
                            f"sym={sym.name}"
                        )
            except Exception as e:
                log(f"    parse error: {e}")

        # Scan .text for ldrb/strb with small immediates (tag offsets)
        text = elf.get_section_by_name(".text")
        if text:
            tdata = text.data()
            log(f"\n== .text size 0x{len(tdata):x} ==")
            # AArch64 LDRB Wt, [Xn, #imm] : 0x39400000 | (imm12<<10) | (Rn<<5) | Rt
            # STRB Wt, [Xn, #imm] : 0x39000000 | ...
            ldrb_imm = Counter()
            strb_imm = Counter()
            for i in range(0, len(tdata) - 3, 4):
                w = struct.unpack_from("<I", tdata, i)[0]
                if (w & 0xFFC00000) == 0x39400000:  # LDRB unsigned offset
                    imm = (w >> 10) & 0xFFF
                    ldrb_imm[imm] += 1
                if (w & 0xFFC00000) == 0x39000000:  # STRB unsigned offset
                    imm = (w >> 10) & 0xFFF
                    strb_imm[imm] += 1

            log("top LDRB imm offsets:")
            for imm, c in ldrb_imm.most_common(30):
                log(f"  +0x{imm:02x} ({imm}) count={c}")
            log("top STRB imm offsets:")
            for imm, c in strb_imm.most_common(30):
                log(f"  +0x{imm:02x} ({imm}) count={c}")

            # Also look for AND/ORR with 0x400 (bit 10)
            log("\n== instructions mentioning 0x400 / bit10 ==")
            hits = 0
            for i in range(0, len(tdata) - 3, 4):
                w = struct.unpack_from("<I", tdata, i)[0]
                # MOVZ Xd, #0x400  => 0xD2808000 | Rd  (imm16=0x400 at bits 20:5)
                # ORR Xd, Xn, #0x400 etc — just dump nearby immediates of 0x400
                if ((w >> 5) & 0xFFFF) == 0x400 and (w & 0xFF800000) in (
                    0xD2800000,
                    0x52800000,
                ):
                    log(f"  text+0x{i:x}: word=0x{w:08x} (movz-like imm=0x400)")
                    hits += 1
                # AND/ORR with imm encoding is complex; scan literal 0x400 as mov
                if (w & 0xFFE0FFE0) == 0xD2808000 or (w & 0xFFE0FFE0) == 0x52808000:
                    log(f"  text+0x{i:x}: word=0x{w:08x}")
                    hits += 1
            if hits == 0:
                # brute: any instruction with 0x400 in low 16 of imm field
                for i in range(0, len(tdata) - 3, 4):
                    w = struct.unpack_from("<I", tdata, i)[0]
                    if ((w >> 5) & 0xFFFF) == 0x400:
                        log(f"  text+0x{i:x}: word=0x{w:08x} imm16@bit5=0x400")
                        hits += 1
                        if hits >= 40:
                            break

            # Look for force_sig / send_sig / do_send_sig_info call patterns via relocs
            log("\n== undefined/imported names matching kill/cred/trace ==")
            for v, t, b, sz, n in sorted(syms, key=lambda x: x[4]):
                low = n.lower()
                if any(
                    k in low
                    for k in (
                        "kill",
                        "sig",
                        "cred",
                        "uid",
                        "trace",
                        "commit",
                        "exit",
                        "task",
                        "hook",
                        "register",
                        "vendor",
                        "rvh",
                    )
                ):
                    log(f"  0x{v:08x} {t} {b} {n}")

    report = "\n".join(lines)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
        print(f"written {args.output}")


if __name__ == "__main__":
    main()
