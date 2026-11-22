#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Rank structural candidates for QuietBox anchors missing in a new XHS DEX.

This is a recovery aid, not an auto-updater. Every emitted candidate requires
xref or runtime validation before it is promoted into anchors.json.
"""
import argparse
import collections
import importlib.util
import json
import re
import sys
from pathlib import Path


OBFUSCATED_PART = re.compile(r"^[a-z][a-z0-9]{0,3}$")


def load_local(name, relative):
    path = Path(__file__).resolve().parent / relative
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


probe = load_local("quietbox_probe", "probe_anchors.py")
dexscan = probe.load_dexscan()


def obfuscated(name):
    if not name or name.startswith("[") or len(name) == 1:
        return False
    parts = name.split(".")
    return all(OBFUSCATED_PART.fullmatch(part) for part in parts)


def informative(name):
    return name not in ("java.lang.Object", "java.lang.String") and not obfuscated(name)


def method_pattern(anchor):
    params, result = probe.split_signature(anchor["s"])
    return anchor["m"], params, result


def method_matches(method_name, actual, params, result, any_name=False):
    expected = probe.display_method(method_name, params, result)
    if actual == expected:
        return 20
    if not any_name and not actual.startswith(method_name + "("):
        return 0
    try:
        actual_name, actual_params, actual_result = parse_actual(actual)
    except ValueError:
        return 0
    if (not any_name and actual_name != method_name) or len(actual_params) != len(params):
        return 0
    if result != actual_result and not obfuscated(result):
        return 0
    score = 2 if any_name else 4
    for want, got in zip(params, actual_params):
        if want == got:
            score += 4 if informative(want) else 2
        elif obfuscated(want):
            score += 0
        else:
            return 0
    return score


def parse_actual(actual):
    open_pos = actual.find("(")
    close_pos = actual.rfind(")")
    if open_pos <= 0 or close_pos < open_pos:
        raise ValueError(actual)
    name = actual[:open_pos]
    raw_params = actual[open_pos + 1:close_pos]
    # dexscan's display format has no commas inside descriptors, including arrays.
    params = raw_params.split(",") if raw_params else []
    return name, params, actual[close_pos + 1:]


def simple_name(name):
    return name.rsplit(".", 1)[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("audit", type=Path, help="JSON emitted by probe_anchors.py")
    parser.add_argument("--json", dest="json_path", type=Path)
    args = parser.parse_args()

    audit = json.loads(args.audit.read_text(encoding="utf-8"))
    source = json.loads(Path(audit["anchorsPath"]).read_text(encoding="utf-8"))
    failed = [row for row in audit["anchors"] if row["status"] in
              ("CLASS_MISSING", "METHOD_MISSING", "SIGNATURE_DRIFT")]
    groups = collections.defaultdict(list)
    for row in failed:
        groups[row["class"]].append(source["anchors"][row["id"]] | {
            "id": row["id"], "_status": row["status"]})
    patterns = {
        owner: [(item["id"], item["_status"]) + method_pattern(item) for item in items]
        for owner, items in groups.items()
    }
    candidates = collections.defaultdict(list)

    for dex_name, dex in dexscan.load(str(args.apk)):
        for off in dex.class_def_offsets():
            try:
                info = dex.class_detail(off)
            except Exception:
                continue
            class_name = info["name"]
            methods = info.get("methods", [])
            for owner, owner_patterns in patterns.items():
                same_simple = simple_name(class_name) == simple_name(owner)
                matches = []
                total_score = 0
                for anchor_id, status, method_name, params, result in owner_patterns:
                    scored = []
                    for actual in methods:
                        score = method_matches(method_name, actual, params, result)
                        if score:
                            scored.append((score, actual))
                    if (not scored and status in ("CLASS_MISSING", "METHOD_MISSING")
                            and (same_simple or any(informative(p) for p in params))):
                        for actual in methods:
                            score = method_matches(method_name, actual, params, result, any_name=True)
                            if score:
                                scored.append((score, actual))
                    if not scored:
                        continue
                    # Generic zero/primitive/Object shapes are only useful in the
                    # original simple-name class; otherwise they flood the report.
                    has_informative_type = any(informative(p) for p in params) or informative(result)
                    if not has_informative_type and not same_simple and class_name != owner:
                        continue
                    scored.sort(reverse=True)
                    matches.append({"id": anchor_id, "methods": [m for _, m in scored[:4]]})
                    total_score += max(score for score, _ in scored)
                if not matches:
                    continue
                if class_name == owner:
                    total_score += 20
                if same_simple:
                    total_score += 12
                if len(matches) == len(owner_patterns):
                    total_score += 20 * len(matches)
                candidates[owner].append({
                    "class": class_name,
                    "dex": dex_name,
                    "score": total_score,
                    "sameSimpleName": same_simple,
                    "matchedAnchors": len(matches),
                    "totalAnchors": len(owner_patterns),
                    "matches": matches,
                })

    report = {}
    for owner, items in candidates.items():
        items.sort(key=lambda item: (-item["score"], item["class"]))
        report[owner] = items[:12]
    for owner in patterns:
        print("\n=== %s (%d anchor(s))" % (owner, len(patterns[owner])))
        items = report.get(owner, [])
        if not items:
            print("  no structural candidates")
        for item in items:
            print("  score=%d %s [%s] matched=%d/%d sameSimple=%s" % (
                item["score"], item["class"], item["dex"], item["matchedAnchors"],
                item["totalAnchors"], item["sameSimpleName"]))
            for match in item["matches"]:
                print("    " + match["id"] + ": " + " | ".join(match["methods"]))
    if args.json_path:
        args.json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                  encoding="utf-8")
        print("\nJSON candidates -> %s" % args.json_path, file=sys.stderr)


if __name__ == "__main__":
    main()
