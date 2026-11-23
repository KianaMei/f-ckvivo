#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Apply a reviewed version-transition file to a QuietBox anchor profile.

Usage:
  python -I xhs/tools/port_anchors.py SOURCE_PROFILE TRANSITION OUTPUT [--force]

The transition is explicit data, not a recovered candidate set. It records the
source and target XHS versions and the exact class/method/signature replacements
that were reviewed against DEX, xrefs or runtime evidence.
"""
import argparse
import copy
import json
from pathlib import Path


SELECTOR_FIELDS = ("c", "m", "s")


def version_tuple(value, label):
    if not isinstance(value, dict):
        raise SystemExit("%s must be an object" % label)
    app_version = value.get("appVersion")
    version_code = value.get("versionCode")
    if not isinstance(app_version, str) or not app_version:
        raise SystemExit("%s.appVersion must be a non-empty string" % label)
    if not isinstance(version_code, int) or version_code <= 0:
        raise SystemExit("%s.versionCode must be a positive integer" % label)
    return app_version, version_code


def load_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise SystemExit("cannot read JSON %s: %s" % (path, exc))


def apply_transition(source, transition):
    if transition.get("schemaVersion") != 1:
        raise SystemExit("unsupported transition schemaVersion")
    source_version = version_tuple(source, "source profile")
    expected_source = version_tuple(transition.get("from"), "transition.from")
    if source_version != expected_source:
        raise SystemExit("source profile is %s/%s, transition expects %s/%s" % (
            source_version + expected_source))
    target_version = version_tuple(transition.get("to"), "transition.to")

    changes = transition.get("changes")
    if not isinstance(changes, dict) or not changes:
        raise SystemExit("transition.changes must be a non-empty object")
    source_anchors = source.get("anchors")
    if not isinstance(source_anchors, dict):
        raise SystemExit("source profile has no anchors object")

    result = copy.deepcopy(source)
    anchors = result["anchors"]
    result.update({
        "appVersion": target_version[0],
        "versionCode": target_version[1],
        "generatedBy": "xhs/tools/port_anchors.py",
        "portedFrom": {
            "appVersion": source_version[0],
            "versionCode": source_version[1],
        },
    })
    for key, change in changes.items():
        if key not in anchors:
            raise SystemExit("transition references unknown anchor: %s" % key)
        if not isinstance(change, dict):
            raise SystemExit("transition change must be an object: %s" % key)
        for field in SELECTOR_FIELDS:
            value = change.get(field)
            if not isinstance(value, str) or not value:
                raise SystemExit("transition change %s.%s must be a non-empty string" % (
                    key, field))
        anchors[key].update({field: change[field] for field in SELECTOR_FIELDS})
    return result, source_version, target_version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="existing anchor profile")
    parser.add_argument("transition", type=Path, help="reviewed transition JSON")
    parser.add_argument("output", type=Path, help="generated target profile")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.output.exists() and not args.force:
        raise SystemExit("output exists; pass --force to replace it: %s" % args.output)

    transition = load_json(args.transition)
    result, source_version, target_version = apply_transition(
        load_json(args.source), transition)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    print("ported %s/%s -> %s/%s with %d reviewed replacements across %d anchors" % (
        source_version[0], source_version[1], target_version[0], target_version[1],
        len(transition["changes"]), len(result["anchors"])))


if __name__ == "__main__":
    main()
