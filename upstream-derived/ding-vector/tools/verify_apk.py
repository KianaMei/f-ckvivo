#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify that a signed Ding Vector build differs only at the reviewed gate."""
import argparse
import hashlib
import json
import struct
import zipfile
import zlib
from pathlib import Path

from dex_framework_patch import DexFile, patch_dex, signature_entry


def entries(apk: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(apk) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError("corrupt ZIP member %s in %s" % (bad, apk))
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate ZIP member in %s" % apk)
        return {info.filename: archive.read(info.filename)
                for info in archive.infolist() if not info.is_dir()}


def check_integrity(dex: bytes) -> None:
    if not dex.startswith(b"dex\n") or struct.unpack_from("<I", dex, 32)[0] != len(dex):
        raise ValueError("invalid DEX header/file_size")
    if dex[12:32] != hashlib.sha1(dex[32:]).digest():
        raise ValueError("DEX SHA-1 signature mismatch")
    if struct.unpack_from("<I", dex, 8)[0] != (zlib.adler32(dex[12:]) & 0xFFFFFFFF):
        raise ValueError("DEX Adler-32 checksum mismatch")


def verify(original_apk: Path, candidate_apk: Path) -> dict:
    original = entries(original_apk)
    candidate = entries(candidate_apk)
    original_names = {name for name in original if not signature_entry(name)}
    candidate_names = {name for name in candidate if not signature_entry(name)}
    if original_names != candidate_names:
        raise ValueError("unexpected APK entry delta: missing=%s added=%s" % (
            sorted(original_names - candidate_names),
            sorted(candidate_names - original_names)))

    patched_dex = []
    reports = []
    for name in sorted(original_names):
        if original[name] == candidate[name]:
            continue
        if not name.startswith("classes") or not name.endswith(".dex"):
            raise ValueError("unexpected non-DEX payload change: %s" % name)
        expected, report = patch_dex(original[name])
        if report is None:
            raise ValueError("changed DEX has no framework gate: %s" % name)
        if candidate[name] != expected:
            raise ValueError("DEX contains changes beyond the gate-entry code units and "
                             "standard integrity fields: %s" % name)
        check_integrity(candidate[name])
        report["dex_entry"] = name
        report["candidate_dex_sha256"] = hashlib.sha256(candidate[name]).hexdigest()
        patched_dex.append(name)
        reports.append(report)

    if len(patched_dex) != 1:
        raise ValueError("expected exactly one changed DEX, got %s" % patched_dex)

    # Reparse the emitted method, rather than trusting only a byte diff.
    dex = DexFile(candidate[patched_dex[0]])
    methods = list(dex.methods())
    if len(methods) != 1 or methods[0].identity != reports[0]["method"]:
        raise ValueError("patched method identity changed during verification")
    entry_units = struct.unpack_from("<HH", candidate[patched_dex[0]],
                                     methods[0].insns_offset)
    if entry_units != (0x1012, 0x000F):
        raise ValueError("framework gate is not const/4 v0, 1; return v0")

    return {
        "result": "OK",
        "changed_payload_entries": patched_dex,
        "unchanged_non_signature_entries": len(original_names) - 1,
        "patches": reports,
        "candidate_sha256": hashlib.sha256(candidate_apk.read_bytes()).hexdigest(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.original, args.candidate)
    except (KeyError, ValueError, zipfile.BadZipFile) as error:
        raise SystemExit("verification failed: %s" % error)
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
