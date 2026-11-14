#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""在 dex 里按字符串常量反查类/方法：找出"双列"判断到底在哪个类。

用法：python tools/xref_string.py apk.apk "双列"
输出：包含该字符串常量的 类名#方法签名
"""
import io
import struct
import sys

import dexscan

SIZE_TABLE = [1] * 256
for op, size in {
    0x02: 2, 0x03: 3, 0x05: 2, 0x06: 3, 0x08: 2, 0x09: 3,
    0x13: 2, 0x14: 3, 0x15: 2, 0x16: 2, 0x17: 3, 0x18: 5, 0x19: 2,
    0x1a: 2, 0x1b: 3, 0x1c: 2, 0x1f: 2, 0x20: 2, 0x22: 2, 0x23: 2,
    0x24: 3, 0x25: 3, 0x26: 3, 0x29: 2, 0x2a: 3, 0x2b: 3, 0x2c: 3,
}.items():
    SIZE_TABLE[op] = size
for op in list(range(0x2d, 0x32)) + list(range(0x32, 0x3e)):
    SIZE_TABLE[op] = 2
for op in list(range(0x44, 0x6e)):
    SIZE_TABLE[op] = 2
for op in list(range(0x6e, 0x73)) + list(range(0x74, 0x79)):
    SIZE_TABLE[op] = 3
for op in list(range(0x90, 0xb0)):
    SIZE_TABLE[op] = 2
for op in list(range(0xd0, 0xd8)) + list(range(0xd8, 0xe3)):
    SIZE_TABLE[op] = 2
SIZE_TABLE[0xfa] = 4
SIZE_TABLE[0xfb] = 4
SIZE_TABLE[0xfc] = 3
SIZE_TABLE[0xfd] = 3
SIZE_TABLE[0xfe] = 2
SIZE_TABLE[0xff] = 2


def iter_code_strings(d, code_off):
    """返回该方法引用到的字符串下标集合；同时尽量对齐指令流。"""
    (registers_size, ins_size, outs_size, tries_size, debug_info_off,
     insns_size) = struct.unpack_from("<HHHHII", d, code_off)
    insns_off = code_off + 16
    pos = 0
    found = set()
    while pos < insns_size:
        unit0 = struct.unpack_from("<H", d, insns_off + pos * 2)[0]
        op = unit0 & 0xFF
        if op == 0x00 and unit0 != 0:
            # payload
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
            idx = struct.unpack_from("<H", d, insns_off + (pos + 1) * 2)[0]
            found.add(idx)
        pos += SIZE_TABLE[op]
    return found


def main():
    path = sys.argv[1]
    needle = sys.argv[2]

    for dex_name, dex in dexscan.load(path):
        # 目标字符串下标
        targets = {}
        for i in range(dex.string_ids_size):
            s = dex.string(i)
            if needle in s:
                targets[i] = s
        if not targets:
            continue

        hits = {}
        for class_off in dex.class_def_offsets():
            try:
                info = dex.class_detail(class_off)
            except Exception:
                continue
            class_name = info["name"]
            for method_idx, code_off in info.get("method_refs", []):
                if not code_off:
                    continue
                try:
                    name, _cls, ret, params = dex.method(method_idx)
                    used = iter_code_strings(dex.d, code_off)
                except Exception:
                    continue
                common = used & set(targets)
                if common:
                    for c in common:
                        hits.setdefault(targets[c], []).append(
                            "%s#%s(%s)%s" % (class_name, name,
                                             ",".join(params), ret))
        for key in sorted(hits):
            print("=== [%s] %s" % (key, dex_name))
            for item in sorted(set(hits[key])):
                print("    " + item)


if __name__ == "__main__":
    main()
