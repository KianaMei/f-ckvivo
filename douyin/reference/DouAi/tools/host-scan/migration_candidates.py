"""Generate static relocation candidates from the reviewed rule signatures and a DEX index.

This is a triage tool, not an authorization to hook. Candidates still need string,
instruction/call-flow and consumer-field contracts against the original APK.
"""
import argparse
import csv
import json
from pathlib import Path
import re
import sqlite3


def generalized_descriptor(value):
    return re.sub(r"LX/[^;]+;|LY/[^;]+;|Lkotlin/jvm/internal/ALambda[^;]+;", "L[^;]+;", re.escape(value).replace(r"\;", ";"))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("index", type=Path)
    p.add_argument("rules", type=Path)
    p.add_argument("scan", type=Path)
    p.add_argument("output", type=Path)
    a = p.parse_args()
    read = lambda path: list(csv.DictReader((line for line in path.read_text(encoding="utf8").splitlines()
                                            if not line.startswith("#")), delimiter="\t"))
    missing = {r["id"] for r in read(a.scan) if r["status"] != "UNIQUE"}
    connection = sqlite3.connect(a.index)
    connection.row_factory = sqlite3.Row
    output = []
    for rule in read(a.rules):
        if rule["id"] not in missing:
            continue
        owner = rule["class_descriptor"]
        stable_owner = owner if owner.startswith(("Lcom/", "Landroid/", "Landroidx/")) else ""
        candidates = []
        truncated = False
        if rule["kind"] == "method":
            proto = re.compile("^" + generalized_descriptor(rule["method_descriptor"]) + "$") if rule["method_descriptor"] else None
            named = rule["method_name"]
            stable_name = named if named in ("onGetMessage", "onNotify", "setPlaybackParams", "invoke", "onPageSelected", "onBind", "onBindViewHolder") else ""
            clauses, parameters = [], []
            if stable_owner:
                clauses.append("owner=?"); parameters.append(stable_owner)
            if stable_name:
                clauses.append("name=?"); parameters.append(stable_name)
            if rule["method_descriptor"]:
                like = re.sub(r"LX/[^;]+;|LY/[^;]+;|Lkotlin/jvm/internal/ALambda[^;]+;", "%", rule["method_descriptor"])
                clauses.append("proto LIKE ?"); parameters.append(like)
            query = "select * from methods" + (" where " + " AND ".join(clauses) if clauses else "")
            for method in connection.execute(query, parameters):
                if stable_owner and stable_owner != method["owner"]:
                    continue
                if stable_name and stable_name != method["name"]:
                    continue
                if proto and not proto.fullmatch(method["proto"]):
                    continue
                candidates.append(dict(method))
                if len(candidates) == 250:
                    truncated = True
                    break
        else:
            names = set(rule["field_names"].split("|")) - {""}
            types = set(rule["field_types"].split("|")) - {""}
            if stable_owner:
                possible = connection.execute("select * from classes where name=?", (stable_owner,))
            elif names:
                placeholders = ",".join("?" for _ in names)
                possible = connection.execute("select * from classes where name IN (select owner from fields where name IN ("
                                              + placeholders + ") group by owner having count(distinct name)=?)", (*names, len(names)))
            elif types:
                placeholders = ",".join("?" for _ in types)
                possible = connection.execute("select * from classes where name IN (select owner from fields where type IN ("
                                              + placeholders + ") group by owner having count(distinct type)=?)", (*types, len(types)))
            else:
                # String-only classes require a streaming DEX evidence scan, not all 565k classes in RAM.
                possible = []
            for cls in possible:
                name = cls["name"]
                if stable_owner and stable_owner != name:
                    continue
                actual = [dict(f) for f in connection.execute("select * from fields where owner=?", (name,))]
                if names and not names.issubset({f["name"] for f in actual}):
                    continue
                if types and not types.issubset({f["type"] for f in actual}):
                    continue
                if rule["exact_field_count"] and len(actual) != int(rule["exact_field_count"]):
                    continue
                candidates.append({**dict(cls), "fields": actual})
                if len(candidates) == 250:
                    truncated = True
                    break
        output.append({"id": rule["id"], "feature": rule["feature"], "old": rule,
                       "candidate_count": len(candidates), "truncated_at_250": truncated, "candidates": candidates})
        print(rule["feature"], rule["id"], len(candidates), "truncated" if truncated else "", flush=True)
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(output, indent=2, ensure_ascii=False), encoding="utf8")


if __name__ == "__main__":
    main()
