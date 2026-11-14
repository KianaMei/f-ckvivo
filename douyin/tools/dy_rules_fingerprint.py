#!/usr/bin/env python3
"""Compute DouAi HostRuleSet canonical fingerprint for a rules TSV (read-only)."""
import hashlib
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().splitlines()


def parse_values(value):
    out, cur, esc = [], [], False
    for ch in value:
        if esc:
            cur.append(ch)
            esc = False
        elif ch == "\\":
            esc = True
        elif ch == "|":
            if cur:
                out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if esc:
        cur.append("\\")
    if cur:
        out.append("".join(cur))
    return out


def join(values):
    return "|".join(v.replace("\\", "\\\\").replace("|", "\\|") for v in values)


meta = None
header = None
rules = []
for line in lines:
    if meta is None:
        if line.startswith("#douai-host-rules\t"):
            meta = line[1:]
        elif line.strip() and not line.startswith("#"):
            meta = line
        continue
    if header is None:
        if line.strip() and not line.startswith("#"):
            header = line
        continue
    if line.strip() and not line.startswith("#"):
        rules.append(line.split("\t"))

version, target = meta.split("\t")[1], meta.split("\t")[2]
digest = hashlib.sha256()
digest.update((version + "\n" + target + "\n").encode())
for f in rules:
    assert len(f) == 15, (len(f), f[0])
    kind = f[2].upper()
    emc = -1 if f[11] == "" else int(f[11])
    efc = -1 if f[12] == "" else int(f[12])
    canonical = "\t".join([
        f[0], f[1], kind, f[3], join(parse_values(f[4])), f[5], f[6],
        join(parse_values(f[7])), join(parse_values(f[8])), join(parse_values(f[9])),
        join(parse_values(f[10])), str(emc), str(efc),
        join(parse_values(f[13])), join(parse_values(f[14])),
    ])
    digest.update((canonical + "\n").encode())
print(f"{path}\trules={len(rules)}\tfingerprint={digest.hexdigest()}")
