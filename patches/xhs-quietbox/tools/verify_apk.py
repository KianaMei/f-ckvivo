#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify a QuietBox build against its base APK and reviewed anchor profile."""
import argparse
import hashlib
import json
import struct
import zipfile
import zlib
from pathlib import Path


def signature_entry(name):
    upper = name.upper()
    if not upper.startswith("META-INF/"):
        return False
    base = upper.rsplit("/", 1)[-1]
    return base == "MANIFEST.MF" or base.endswith((".SF", ".RSA", ".DSA", ".EC"))


def entries(apk):
    with zipfile.ZipFile(apk) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError("corrupt ZIP member %s in %s" % (bad, apk))
        return {name: archive.read(name) for name in archive.namelist() if not name.endswith("/")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("profile", type=Path)
    parser.add_argument("--old-version", default=None,
                        help="module DEX target string before patching")
    parser.add_argument("--new-version", default=None,
                        help="target XHS version string; defaults to profile appVersion")
    parser.add_argument("--skip-version-patch", action="store_true",
                        help="accept a candidate whose classes.dex was not version-patched")
    args = parser.parse_args()

    profile_bytes = args.profile.read_bytes()
    profile = json.loads(profile_bytes.decode("utf-8"))
    target_version = profile.get("appVersion")
    target_code = profile.get("versionCode")
    if not isinstance(target_version, str) or not target_version:
        raise SystemExit("profile appVersion is missing")
    if not isinstance(target_code, int) or target_code <= 0:
        raise SystemExit("profile versionCode is invalid")

    original = entries(args.original)
    candidate = entries(args.candidate)
    original_names = {name for name in original if not signature_entry(name)}
    candidate_names = {name for name in candidate if not signature_entry(name)}
    allowed_removed = {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}
    missing = original_names - candidate_names
    added = candidate_names - original_names
    if not missing <= allowed_removed or added:
        raise SystemExit("unexpected APK entry delta: missing=%s added=%s" % (
            sorted(missing), sorted(added)))

    changed = [name for name in sorted(candidate_names)
               if original[name] != candidate[name]]
    allowed_changed = {"classes.dex", "anchors.json"}
    if "anchors.json" not in changed or not set(changed) <= allowed_changed:
        raise SystemExit("unexpected payload changes: %s" % changed)
    if candidate["anchors.json"] != profile_bytes:
        raise SystemExit("packaged anchors.json differs from the reviewed profile")

    dex = candidate["classes.dex"]
    if not dex.startswith(b"dex\n"):
        raise SystemExit("invalid DEX magic")
    if not args.skip_version_patch:
        old_version = args.old_version
        new_version = args.new_version or target_version
        if old_version and old_version != new_version:
            old_token = old_version.encode("ascii") + b"\0"
            new_token = new_version.encode("ascii") + b"\0"
            if dex.count(old_token) != 0:
                raise SystemExit("old target-version string remains in DEX: %s" % old_version)
            if dex.count(new_token) != 1:
                raise SystemExit("target-version string is missing or ambiguous: %s" % new_version)
    if struct.unpack_from("<I", dex, 32)[0] != len(dex):
        raise SystemExit("DEX file_size mismatch")
    if dex[12:32] != hashlib.sha1(dex[32:]).digest():
        raise SystemExit("DEX SHA-1 signature mismatch")
    checksum = struct.unpack_from("<I", dex, 8)[0]
    if checksum != (zlib.adler32(dex[12:]) & 0xFFFFFFFF):
        raise SystemExit("DEX Adler-32 checksum mismatch")

    print("Payload changes: " + ", ".join(changed))
    if missing:
        print("Removed stale DEX profiles: " + ", ".join(sorted(missing)))
    else:
        print("No stale DEX profiles present")
    print("All other non-signature entries are byte-identical")
    print("Profile: %s / %s; DEX integrity: OK" % (target_version, target_code))
    print("Candidate SHA-256: " + hashlib.sha256(args.candidate.read_bytes()).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
