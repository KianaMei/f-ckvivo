#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从易盾加固壳里静态导出「类 → 成员名」映射（field_ids + method_ids 合表）。

壳把每个 dex 的 header 抹掉，并打乱 class_defs / class_data 的顺序，
但 field_ids / method_ids 两张 8 字节表是明文：
    u2 class_idx | u2 type_or_proto_idx | u4 name_idx
所以即使顺序被打乱，也能把「类名 ↔ 成员名」重新绑起来 —— 这正是给
酷安净化那种 target manifest 补 profile 所需要的东西。

用法：
  python tools/members_dump.py work/classes.dex work/members
产出：work/members/unit-XX.tsv  (class \t member \t x_idx)
"""
import os
import struct
import sys

HDR = 0x70


def u32(b, o):
    return struct.unpack_from('<I', b, o)[0]


def u16(b, o):
    return struct.unpack_from('<H', b, o)[0]


def shell_end(buf):
    map_off = u32(buf, 52)
    return map_off + 4 + u32(buf, map_off) * 12


def monotonic_runs(buf, base, min_count=2000, cap=0x2000000):
    runs = {}
    i = base
    while i < len(buf) - 16:
        a, b, c = struct.unpack_from('<3I', buf, i)
        if a < b < c and c < cap:
            prev, k = c, 3
            while i + (k + 1) * 4 <= len(buf):
                v = u32(buf, i + k * 4)
                if v <= prev or v >= cap:
                    break
                prev = v
                k += 1
            if k >= min_count:
                runs[i] = k
                i += k * 4
                continue
        i += 4
    return runs


def find_units(buf):
    runs = monotonic_runs(buf, shell_end(buf))
    return sorted((off - HDR, count, runs[off + count * 4])
                  for off, count in runs.items() if off + count * 4 in runs)


def read_strings(buf, unit_start, count):
    out = []
    for i in range(count):
        p = unit_start + u32(buf, unit_start + HDR + i * 4)
        while buf[p] & 0x80:
            p += 1
        p += 1
        end = buf.index(b'\x00', p)
        out.append(buf[p:end].decode('utf-8', 'replace'))
    return out


def find_ids_table(buf, start, unit_end, typ_sz, str_sz, need=500):
    """扫出 field_ids/method_ids 合表的起点：连续 8 字节记录满足 class<typ & name<str。"""
    p = start
    while p + need * 8 < unit_end:
        ok = True
        for k in range(need):
            o = p + k * 8
            if u16(buf, o) >= typ_sz or u32(buf, o + 4) >= str_sz:
                ok = False
                break
        if ok:
            return p
        p += 4
    return None


def find_class_defs(buf, start, unit_end, typ_sz, need=40):
    p = start
    while p + need * 32 < unit_end:
        prev, ok = -1, True
        for k in range(need):
            o = p + k * 32
            cls = u32(buf, o)
            sup = u32(buf, o + 8)
            acc = u32(buf, o + 4)
            if cls >= typ_sz or cls <= prev or (sup != 0xFFFFFFFF and sup >= typ_sz) or (acc & 0xFFFF0000):
                ok = False
                break
            prev = cls
        if ok:
            return p
        p += 4
    return None


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src, outdir = sys.argv[1], sys.argv[2]
    buf = open(src, 'rb').read()
    units = find_units(buf)
    os.makedirs(outdir, exist_ok=True)
    print('dex units: %d' % len(units))

    for idx, (unit_start, str_sz, typ_sz) in enumerate(units):
        unit_end = units[idx + 1][0] if idx + 1 < len(units) else len(buf)
        try:
            strings = read_strings(buf, unit_start, str_sz)
        except Exception as e:
            print('unit %d: strings 失败 %s' % (idx, e))
            continue
        typ_base = unit_start + HDR + str_sz * 4
        types = [strings[u32(buf, typ_base + i * 4)] for i in range(typ_sz)]

        after_types = unit_start + HDR + (str_sz + typ_sz) * 4
        ids_start = find_ids_table(buf, after_types, unit_end, typ_sz, str_sz)
        if ids_start is None:
            print('unit %d: ids 表未找到' % idx)
            continue
        class_start = find_class_defs(buf, ids_start, unit_end, typ_sz)
        ids_end = class_start if class_start else ids_start + 8 * 1000

        total = (ids_end - ids_start) // 8
        rows = []
        for i in range(total):
            o = ids_start + i * 8
            cls = u16(buf, o)
            x = u16(buf, o + 2)
            name_idx = u32(buf, o + 4)
            if cls >= typ_sz or name_idx >= str_sz:
                continue
            rows.append('%s\t%s\t%d' % (types[cls], strings[name_idx], x))

        out = os.path.join(outdir, 'unit-%02d.tsv' % idx)
        with open(out, 'w', encoding='utf-8') as f:
            f.write('\n'.join(rows))
        print('unit %d @%d  types=%d members=%d -> %s'
              % (idx, unit_start, typ_sz, len(rows), os.path.basename(out)))


if __name__ == '__main__':
    main()
