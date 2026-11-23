#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dex 引用反查：字符串 / 方法 / 字段 的被引用点。

python tools/xref.py apk.apk string "双列"
python tools/xref.py apk.apk method "IAndroidPadDoublePage#getConfig"
python tools/xref.py apk.apk field "AndroidPadDoublePage#a"
"""
import struct
import sys

import dexscan
from xref_string import SIZE_TABLE


def iter_refs(dex, code_off):
    d = dex.d
    (_regs, _ins, _outs, _tries, _dbg, insns_size) = struct.unpack_from("<HHHHII", d, code_off)
    insns_off = code_off + 16
    pos = 0
    strings, methods, fields = set(), set(), set()
    while pos < insns_size:
        unit0 = struct.unpack_from("<H", d, insns_off + pos * 2)[0]
        op = unit0 & 0xFF
        if op == 0x00 and unit0 != 0:
            if unit0 == 0x0100:
                size = struct.unpack_from("<H", d, insns_off + (pos + 1) * 2)[0]
                pos += size * 2 + 4
            elif unit0 == 0x0200:
                size = struct.unpack_from("<H", d, insns_off + (pos + 1) * 2)[0]
                pos += size * 4 + 2
            elif unit0 == 0x0300:
                width = struct.unpack_from("<H", d, insns_off + (pos + 1) * 2)[0]
                size = struct.unpack_from("<I", d, insns_off + (pos + 2) * 2)[0]
                pos += (size * width + 1) // 2 + 4
            else:
                pos += 1
            continue
        if op in (0x1a, 0x1b):
            strings.add(struct.unpack_from("<H", d, insns_off + (pos + 1) * 2)[0])
        elif 0x52 <= op <= 0x6d:
            fields.add(struct.unpack_from("<H", d, insns_off + (pos + 1) * 2)[0])
        elif 0x6e <= op <= 0x72 or 0x74 <= op <= 0x78:
            methods.add(struct.unpack_from("<H", d, insns_off + (pos + 1) * 2)[0])
        pos += SIZE_TABLE[op]
    return strings, methods, fields


def main():
    path, mode, needle = sys.argv[1], sys.argv[2], sys.argv[3]
    cls_part, _, meth_part = needle.partition("#")

    for dex_name, dex in dexscan.load(path):
        total = dex.string_ids_size if mode == "string" else (
            dex.method_ids_size if mode == "method" else dex.field_ids_size)
        targets = set()
        for i in range(total):
            if mode == "string":
                label = dex.string(i)
            elif mode == "method":
                name, cls, ret, params = dex.method(i)
                label = cls + "#" + name
            else:
                fname, ftype = dex.field(i)
                fclass_idx = struct.unpack_from("<H", dex.d, dex.field_ids_off + i * 8)[0]
                label = dex.type_name(fclass_idx) + "#" + fname
            if cls_part in label and (not meth_part or meth_part in label):
                targets.add((i, label))
        if not targets:
            continue
        wanted = set(t[0] for t in targets)
        labels = dict(targets)

        found = {}
        for class_off in dex.class_def_offsets():
            try:
                info = dex.class_detail(class_off)
            except Exception:
                continue
            for method_idx, code_off in info.get("method_refs", []):
                if not code_off:
                    continue
                try:
                    strings, methods, fields = iter_refs(dex, code_off)
                except Exception:
                    continue
                pool = strings if mode == "string" else (methods if mode == "method" else fields)
                hit = pool & wanted
                if not hit:
                    continue
                try:
                    name, _cls, ret, params = dex.method(method_idx)
                except Exception:
                    continue
                for h in hit:
                    found.setdefault(labels[h], set()).add(
                        "%s#%s(%s)%s [%s]" % (info["name"], name,
                                              ",".join(params), ret, dex_name))
        for key in sorted(found):
            print("=== " + key)
            for item in sorted(found[key]):
                print("    " + item)


if __name__ == "__main__":
    main()
