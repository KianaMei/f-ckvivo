#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从易盾（com.netease.nis.wrapper）加固的 classes.dex 里重建真实 dex。

结论（酷安 16.7.3，实测）：
  APK 的 classes.dex 只有 30 个壳类（表格到 map_list 约 63 KB 结束），
  其后 ~98 MB 是原始 app 的**多个完整 dex 首尾相接**；packer 只把每个 dex 的
  112 字节 header 抹成随机字节，其余（string_ids / type_ids / proto_ids /
  field_ids / method_ids / class_defs / string_data / code_item）全是明文。

所以不用破它的解密算法，只要：
  1. 用「string_ids 紧跟 type_ids 的单调 u32 表」定位每个 dex 单元；
  2. 用各表自身的排序性质把 proto_ids / field_ids / method_ids / class_defs 切出来；
  3. 拼一个正确的 header（含 adler32 + sha1）写回去。

用法：
  python tools/rebuild_dex2.py <shell classes.dex> <out_dir> [unit_index]
"""
import hashlib
import os
import struct
import sys
import zlib

HDR = 0x70
DATA_MIN = 8 << 20      # data 区偏移的下限（本 sample 中 data 从 1.6 MB 起，用 8MB 只作 sanity）


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
    units = []
    for off, count in runs.items():
        if off + count * 4 in runs:          # 紧跟 type_ids 才算一个 dex 单元
            units.append((off - HDR, count, runs[off + count * 4]))
    return sorted(units)


def parse_tables(buf, unit_start, unit_end, str_sz, typ_sz):
    pos = unit_start + HDR + (str_sz + typ_sz) * 4

    # proto_ids：12 字节，(shorty_idx, return_type_idx, parameters_off)
    # 排序键是 return_type；parameters_off 要么 0，要么落在 data 区（很大的偏移）
    proto_start, proto_count, prev_ret = pos, 0, -1
    while pos + 12 <= unit_end:
        _shorty, ret, params = struct.unpack_from('<3I', buf, pos)
        if ret < prev_ret or ret >= typ_sz:
            break
        if params != 0 and params < DATA_MIN:
            break
        prev_ret, proto_count = ret, proto_count + 1
        pos += 12
    if proto_count == 0:
        return None
    ids_start = pos

    # class_defs：32 字节记录，前 40 条 class_idx 严格递增（后面会被 packer 打乱重排）
    def class_def_ok(o):
        if o + 32 > unit_end:
            return False
        cls, acc, sup, ifaces, src, anno, cdata, sval = struct.unpack_from('<8I', buf, o)
        if cls >= typ_sz or (sup != 0xFFFFFFFF and sup >= typ_sz):
            return False
        if src != 0xFFFFFFFF and src >= str_sz:
            return False
        if acc & 0xFFFF0000:
            return False
        for v in (ifaces, anno, cdata, sval):
            if v and not (DATA_MIN <= v < unit_end - unit_start):
                return False
        return True

    class_start = None
    q = ids_start
    while q + 32 * 40 <= unit_end:
        prev = -1
        shape = True
        for k in range(40):
            o = q + k * 32
            if not class_def_ok(o) or u32(buf, o) <= prev:
                shape = False
                break
            prev = u32(buf, o)
        if shape:
            class_start = q
            break
        q += 4
    if class_start is None:
        return None

    ids_bytes = class_start - ids_start
    if ids_bytes <= 0 or ids_bytes % 8:
        return None
    ids_total = ids_bytes // 8

    # field_ids → method_ids 的分界：class_idx 最大回落点（field 表末尾 class_idx 接近最大）
    classes = [u16(buf, ids_start + i * 8) for i in range(ids_total)]
    split, best = None, 0
    for i in range(1, ids_total):
        drop = classes[i - 1] - classes[i]
        if drop > best:
            best, split = drop, i
    if split is None:
        return None

    class_count = 0
    p = class_start
    while p + 32 <= unit_end and class_def_ok(p):
        class_count += 1
        p += 32

    return {
        'proto': (proto_count, proto_start - unit_start),
        'field': (split, ids_start - unit_start),
        'method': (ids_total - split, ids_start + split * 8 - unit_start),
        'class_defs': (class_count, class_start - unit_start),
        'data_off': ((p - unit_start) + 3) // 4 * 4,
    }


def rebuild(buf, unit_start, unit_end, str_sz, typ_sz, t, out_path):
    file_size = unit_end - unit_start
    body = bytearray(buf[unit_start:unit_end])

    struct.pack_into('<8sI20s', body, 0, b'dex\n035\x00', 0, b'\x00' * 20)
    struct.pack_into('<III', body, 32, file_size, HDR, 0x12345678)
    struct.pack_into('<II', body, 44, 0, 0)
    struct.pack_into('<I', body, 52, 0)                        # map_off（不给，能解析就行）
    struct.pack_into('<II', body, 56, str_sz, HDR)
    struct.pack_into('<II', body, 64, typ_sz, HDR + str_sz * 4)
    struct.pack_into('<II', body, 72, t['proto'][0], t['proto'][1])
    struct.pack_into('<II', body, 80, t['field'][0], t['field'][1])
    struct.pack_into('<II', body, 88, t['method'][0], t['method'][1])
    struct.pack_into('<II', body, 96, t['class_defs'][0], t['class_defs'][1])
    struct.pack_into('<II', body, 104, file_size - t['data_off'], t['data_off'])

    body[12:32] = hashlib.sha1(bytes(body[32:])).digest()
    struct.pack_into('<I', body, 8, zlib.adler32(bytes(body[12:])) & 0xFFFFFFFF)

    with open(out_path, 'wb') as f:
        f.write(body)
    return file_size


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src, outdir = sys.argv[1], sys.argv[2]
    only = int(sys.argv[3]) if len(sys.argv) > 3 else None
    buf = open(src, 'rb').read()
    units = find_units(buf)
    print('dex units: %d' % len(units))
    os.makedirs(outdir, exist_ok=True)
    for idx, (unit_start, str_sz, typ_sz) in enumerate(units):
        if only is not None and idx != only:
            continue
        unit_end = units[idx + 1][0] if idx + 1 < len(units) else len(buf)
        t = parse_tables(buf, unit_start, unit_end, str_sz, typ_sz)
        if not t:
            print('unit %d @%d: 切分失败' % (idx, unit_start))
            continue
        out = os.path.join(outdir, 'unit-%02d.dex' % idx)
        size = rebuild(buf, unit_start, unit_end, str_sz, typ_sz, t, out)
        print('unit %d @%d  %.1fMB  strings=%d types=%d proto=%d field=%d method=%d classes=%d -> %s'
              % (idx, unit_start, size / 1048576, str_sz, typ_sz, t['proto'][0],
                 t['field'][0], t['method'][0], t['class_defs'][0], os.path.basename(out)))


if __name__ == '__main__':
    main()
