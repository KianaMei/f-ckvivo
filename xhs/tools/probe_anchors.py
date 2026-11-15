#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate QuietBox's exact JVM anchors against an installed XHS APK.

Usage:
  python -I xhs/tools/probe_anchors.py base.apk anchors.json
  python -I xhs/tools/probe_anchors.py base.apk anchors.json --json report.json

Only classes referenced by anchors.json are expanded, keeping the 23-dex XHS
inventory bounded. The comparison mirrors r2.a: declared methods and the full
parameter/return signature must match exactly.
"""
import argparse
import collections
import importlib.util
import json
import struct
import sys
from pathlib import Path


PRIMITIVES = set("VZBSCIFDJ")


def load_dexscan():
    repo = Path(__file__).resolve().parents[2]
    path = repo / "modules" / "fq-fold-pad" / "tools" / "dexscan.py"
    spec = importlib.util.spec_from_file_location("quietbox_dexscan", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load dex scanner: %s" % path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_one(desc, pos):
    start = pos
    while pos < len(desc) and desc[pos] == "[":
        pos += 1
    if pos >= len(desc):
        raise ValueError("truncated descriptor: %s" % desc)
    array_prefix = desc[start:pos]
    kind = desc[pos]
    if kind in PRIMITIVES:
        return array_prefix + kind, pos + 1
    if kind != "L":
        raise ValueError("bad descriptor %r at %d in %s" % (kind, pos, desc))
    end = desc.find(";", pos)
    if end < 0:
        raise ValueError("unterminated object descriptor: %s" % desc)
    class_name = desc[pos + 1:end].replace("/", ".")
    return array_prefix + class_name, end + 1


def split_signature(signature):
    if not signature or not signature.startswith("("):
        raise ValueError("bad signature: %s" % signature)
    end = signature.find(")")
    if end < 0:
        raise ValueError("bad signature: %s" % signature)
    params = []
    pos = 1
    while pos < end:
        value, pos = parse_one(signature, pos)
        params.append(value)
    result, pos = parse_one(signature, end + 1)
    if pos != len(signature):
        raise ValueError("trailing signature data: %s" % signature)
    return params, result


def display_method(name, params, result):
    return "%s(%s)%s" % (name, ",".join(params), result)


class TargetIndex:
    def __init__(self, apk, dexscan, wanted):
        self.classes = {}
        self.dex_counts = collections.Counter()
        remaining = set(wanted)
        for dex_name, dex in dexscan.load(str(apk)):
            self.dex_counts[dex_name] = dex.class_defs_size
            for off in dex.class_def_offsets():
                class_idx = struct.unpack_from("<I", dex.d, off)[0]
                name = dex.type_name(class_idx)
                if name not in remaining:
                    continue
                info = dex.class_detail(off)
                info["_dex"] = dex_name
                self.classes[name] = info
                remaining.discard(name)

    def check_anchor(self, key, anchor):
        owner = anchor.get("c", "")
        method = anchor.get("m", "")
        signature = anchor.get("s", "")
        base = {
            "id": key,
            "feature": anchor.get("feature", ""),
            "critical": bool(anchor.get("critical")),
            "class": owner,
            "method": method,
            "signature": signature,
        }
        info = self.classes.get(owner)
        if info is None:
            base.update(status="CLASS_MISSING", dex=None, candidates=[])
            return base
        base["dex"] = info["_dex"]
        try:
            params, result = split_signature(signature)
            expected = display_method(method, params, result)
        except ValueError as exc:
            base.update(status="BAD_SIGNATURE", error=str(exc), candidates=[])
            return base
        declared = info.get("methods", [])
        if expected in declared:
            base.update(status="OK", resolved=expected, candidates=[])
            return base
        prefix = method + "("
        candidates = [item for item in declared if item.startswith(prefix)]
        base.update(
            status="SIGNATURE_DRIFT" if candidates else "METHOD_MISSING",
            expected=expected,
            candidates=candidates,
        )
        return base


def feature_summary(rows):
    grouped = collections.defaultdict(collections.Counter)
    for row in rows:
        grouped[row["feature"]][row["status"]] += 1
    return {feature: dict(sorted(counts.items())) for feature, counts in sorted(grouped.items())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("anchors", type=Path)
    parser.add_argument("--json", dest="json_path", type=Path)
    args = parser.parse_args()

    document = json.loads(args.anchors.read_text(encoding="utf-8"))
    if document.get("schemaVersion") != 1:
        raise SystemExit("unsupported anchors schemaVersion")
    anchors = document.get("anchors", {})
    auxiliary = document.get("classes", {})
    wanted = {item.get("c") for item in anchors.values()} | set(auxiliary.values())
    wanted.discard(None)

    print("Indexing %d referenced classes in %s ..." % (len(wanted), args.apk), file=sys.stderr)
    index = TargetIndex(args.apk, load_dexscan(), wanted)
    rows = [index.check_anchor(key, anchor) for key, anchor in anchors.items()]
    aux_rows = []
    for key, owner in auxiliary.items():
        info = index.classes.get(owner)
        aux_rows.append({
            "id": key,
            "class": owner,
            "status": "OK" if info else "CLASS_MISSING",
            "dex": info["_dex"] if info else None,
        })

    summary = collections.Counter(row["status"] for row in rows)
    report = {
        "apk": str(args.apk.resolve()),
        "anchorsPath": str(args.anchors.resolve()),
        "declaredAppVersion": document.get("appVersion"),
        "declaredVersionCode": document.get("versionCode"),
        "dexFiles": dict(index.dex_counts),
        "summary": dict(sorted(summary.items())),
        "features": feature_summary(rows),
        "anchors": rows,
        "classes": aux_rows,
    }

    print("Anchors: %d; %s" % (len(rows), ", ".join(
        "%s=%d" % item for item in sorted(summary.items()))))
    for feature, counts in feature_summary(rows).items():
        print("  %-6s %s" % (feature, " ".join("%s=%d" % item for item in counts.items())))
    print("\nFailures and drift candidates:")
    for row in rows:
        if row["status"] == "OK":
            continue
        print("\n[%s] %s %s (%s)" % (row["feature"], row["id"], row["status"], row["class"]))
        if row["status"] == "BAD_SIGNATURE":
            print("  " + row["error"])
            continue
        if row["status"] == "CLASS_MISSING":
            continue
        print("  expected: " + row.get("expected", row["signature"]))
        for candidate in row["candidates"]:
            print("  actual:   " + candidate)
        if not row["candidates"]:
            print("  no declared overload with this method name")
    print("\nAuxiliary classes:")
    for row in aux_rows:
        print("  %-24s %-14s %s" % (row["id"], row["status"], row["class"]))

    if args.json_path:
        args.json_path.parent.mkdir(parents=True, exist_ok=True)
        args.json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                  encoding="utf-8")
        print("\nJSON report -> %s" % args.json_path, file=sys.stderr)
    return 0 if not summary["BAD_SIGNATURE"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
