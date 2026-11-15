#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Read version metadata from a QuietBox anchor profile."""
import argparse
import json
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--app-version", action="store_true")
    group.add_argument("--version-code", action="store_true")
    group.add_argument("--source-version", action="store_true")
    group.add_argument("--source-code", action="store_true")
    group.add_argument("--json", action="store_true")
    args = parser.parse_args()

    try:
        profile = json.loads(args.profile.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise SystemExit("cannot read profile %s: %s" % (args.profile, exc))
    app_version = profile.get("appVersion")
    version_code = profile.get("versionCode")
    if not isinstance(app_version, str) or not app_version:
        raise SystemExit("profile appVersion is missing")
    if not isinstance(version_code, int) or version_code <= 0:
        raise SystemExit("profile versionCode is invalid")
    ported_from = profile.get("portedFrom")
    if not isinstance(ported_from, dict):
        ported_from = {}
    source_version = ported_from.get("appVersion") or ""
    source_code = ported_from.get("versionCode") or ""

    if args.json:
        print(json.dumps({
            "appVersion": app_version,
            "versionCode": version_code,
            "portedFrom": ported_from,
        }, ensure_ascii=False, sort_keys=True))
    elif args.app_version:
        print(app_version)
    elif args.version_code:
        print(version_code)
    elif args.source_version:
        print(source_version)
    else:
        print(source_code)
    return 0


if __name__ == "__main__":
    sys.exit(main())
