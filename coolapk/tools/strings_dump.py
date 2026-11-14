#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从易盾加固壳里静态扒出明文的字符串表 / 类型表 / 方法签名表。

背景（酷安 16.7.3，实测见 NOTES.md）：
  壳 dex 只声明 30 个 com.netease.nis.wrapper.* 类（表格到 ~63 KB 就结束），
  紧随其后的 ~98 MB 是原始 app 的多个 dex 首尾相接，packer 的做法是：
    * 抹掉每个 dex 的 112 字节 header（换成随机字节）；
    * 打乱 class_defs / class_data（顺序被重排，不可直接按规范解析）。
  但 string_ids / type_ids / proto_ids / field_ids / method_ids 五张表
  和 string_data 全是明文 —— 足够还原「类名 + 成员名 + 方法签名」这层 API 面。

用法：
  python tools/strings_dump.py work/classes.dex work/ids
产出：work/ids/unit-XX-types.txt / unit-XX-strings.txt / unit-XX-protos.txt
"""
import os
import struct
import sys

HDR = 0x70
DATA_MIN = 8 << 20


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
        # 跳过 uleb128 的 utf16 长度
        while buf[p] & 0x80:
            p += 1
        p += 1
        end = buf.index(b'\x00', p)
        out.append(buf[p:end].decode('utf-8', 'replace'))
    return out


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
        strings = read_strings(buf, unit_start, str_sz)
        typ_base = unit_start + HDR + str_sz * 4
        types = [strings[u32(buf, typ_base + i * 4)] for i in range(typ_sz)]

        # proto_ids：ret 非递减，parameters_off 为 0 或落在 data 区
        pos = unit_start + HDR + (str_sz + typ_sz) * 4
        protos, prev, proto_sz = [], -1, 0
        while pos + 12 <= unit_end:
            _shorty, ret, params = struct.unpack_from('<3I', buf, pos)
            if ret < prev or ret >= typ_sz:
                break
            if params != 0 and params < DATA_MIN:
                break
            plist = []
            if params and params < unit_end - unit_start - 4:
                p = unit_start + params
                n = u32(buf, p)
                if n < 64:
                    for k in range(n):
                        t = u16(buf, p + 4 + k * 2)
                        plist.append(types[t] if t < typ_sz else '?')
            protos.append('%s (%s)' % (types[ret] if ret < typ_sz else '?', ', '.join(plist)))
            prev, proto_sz = ret, proto_sz + 1
            pos += 12

        def write(name, lines):
            with open(os.path.join(outdir, 'unit-%02d-%s.txt' % (idx, name)), 'w',
                      encoding='utf-8') as f:
                f.write('\n'.join(lines))

        write('strings', strings)
        write('types', types)
        write('protos', protos)
        print('unit %d @%d  strings=%d types=%d protos=%d (%.1fMB)'
              % (idx, unit_start, str_sz, typ_sz, proto_sz, (unit_end - unit_start) / 1048576))


if __name__ == '__main__':
    main()
