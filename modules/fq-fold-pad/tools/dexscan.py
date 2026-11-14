#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""极简 dex 解析：列类名 / 方法签名 / 字段 / 字符串，用于逆向番茄小说 ssconfig 开关。

用法：
  python tools/dexscan.py apk.apk classes <子串>
  python tools/dexscan.py apk.apk detail <子串>
  python tools/dexscan.py apk.apk strings <子串>
"""
import io
import struct
import sys
import zipfile


def read_uleb128(buf, pos):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


def read_mutf8(buf, pos):
    _, pos = read_uleb128(buf, pos)
    end = buf.index(b"\x00", pos)
    return buf[pos:end].decode("utf-8", "replace"), end + 1


class Dex(object):
    def __init__(self, data):
        self.d = data
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<12I", data, 56)
        self._strings = {}
        self._types = {}

    def string(self, idx):
        if idx not in self._strings:
            off = struct.unpack_from("<I", self.d, self.string_ids_off + idx * 4)[0]
            self._strings[idx] = read_mutf8(self.d, off)[0]
        return self._strings[idx]

    def type_name(self, idx):
        if idx not in self._types:
            descriptor_idx = struct.unpack_from("<I", self.d, self.type_ids_off + idx * 4)[0]
            raw = self.string(descriptor_idx)
            if raw.startswith("L") and raw.endswith(";"):
                raw = raw[1:-1].replace("/", ".")
            self._types[idx] = raw
        return self._types[idx]

    def proto(self, idx):
        _shorty, ret_idx, params_off = struct.unpack_from(
            "<III", self.d, self.proto_ids_off + idx * 12)
        params = []
        if params_off:
            size = struct.unpack_from("<I", self.d, params_off)[0]
            for i in range(size):
                t = struct.unpack_from("<H", self.d, params_off + 4 + i * 2)[0]
                params.append(self.type_name(t))
        return self.type_name(ret_idx), params

    def method(self, idx):
        class_idx, proto_idx, name_idx = struct.unpack_from(
            "<HHI", self.d, self.method_ids_off + idx * 8)
        ret, params = self.proto(proto_idx)
        return self.string(name_idx), self.type_name(class_idx), ret, params

    def field(self, idx):
        _class_idx, type_idx, name_idx = struct.unpack_from(
            "<HHI", self.d, self.field_ids_off + idx * 8)
        return self.string(name_idx), self.type_name(type_idx)

    def class_def_offsets(self):
        for i in range(self.class_defs_size):
            yield self.class_defs_off + i * 32

    def class_names(self):
        for off in self.class_def_offsets():
            class_idx = struct.unpack_from("<I", self.d, off)[0]
            yield self.type_name(class_idx)

    def class_detail(self, class_def_off):
        class_idx, flags, super_idx, ifaces_off, _src, _anno, class_data_off, _static = \
            struct.unpack_from("<8I", self.d, class_def_off)
        info = {
            "name": self.type_name(class_idx),
            "super": self.type_name(super_idx) if super_idx != 0xFFFFFFFF else None,
            "interfaces": [],
            "fields": [],
            "methods": [],
        }
        if ifaces_off:
            size = struct.unpack_from("<I", self.d, ifaces_off)[0]
            for i in range(size):
                info["interfaces"].append(self.type_name(
                    struct.unpack_from("<H", self.d, ifaces_off + 4 + i * 2)[0]))

        if not class_data_off:
            return info
        pos = class_data_off
        sf, pos = read_uleb128(self.d, pos)
        inf, pos = read_uleb128(self.d, pos)
        dm, pos = read_uleb128(self.d, pos)
        vm, pos = read_uleb128(self.d, pos)

        field_diffs = []
        for _ in range(sf + inf):
            diff, pos = read_uleb128(self.d, pos)
            _flags, pos = read_uleb128(self.d, pos)
            field_diffs.append(diff)
        direct = []
        for _ in range(dm):
            diff, pos = read_uleb128(self.d, pos)
            _flags, pos = read_uleb128(self.d, pos)
            code_off, pos = read_uleb128(self.d, pos)
            direct.append((diff, code_off))
        virtual = []
        for _ in range(vm):
            diff, pos = read_uleb128(self.d, pos)
            _flags, pos = read_uleb128(self.d, pos)
            code_off, pos = read_uleb128(self.d, pos)
            virtual.append((diff, code_off))

        acc = 0
        for diff in field_diffs:
            acc += diff
            info["fields"].append(self.field(acc))
        # direct / virtual 两个列表各自从 0 累加（dex 实际写法，与 dexdump 一致）
        info["method_refs"] = []
        for group in (direct, virtual):
            acc = 0
            for diff, code_off in group:
                acc += diff
                name, _cls, ret, params = self.method(acc)
                info["methods"].append("%s(%s)%s" % (name, ",".join(params), ret))
                info["method_refs"].append((acc, code_off))
        return info

    def strings(self):
        for i in range(self.string_ids_size):
            yield self.string(i)


def load(path):
    dexes = []
    if path.lower().endswith(".apk"):
        with zipfile.ZipFile(path) as z:
            for name in sorted(z.namelist()):
                if name.endswith(".dex"):
                    dexes.append((name, z.read(name)))
    else:
        dexes.append((path, io.open(path, "rb").read()))
    return [(name, Dex(data)) for name, data in dexes]


def main():
    path = sys.argv[1]
    mode = sys.argv[2]
    pattern = sys.argv[3].lower() if len(sys.argv) > 3 else ""

    for dex_name, dex in load(path):
        if mode == "classes":
            for name in dex.class_names():
                if pattern in name.lower():
                    print(name)
        elif mode == "detail":
            for off in dex.class_def_offsets():
                try:
                    info = dex.class_detail(off)
                except Exception:
                    continue
                if pattern not in info["name"].lower():
                    continue
                print("== " + info["name"])
                print("   super=" + str(info["super"]) + " ifaces=" + str(info["interfaces"]))
                for fname, ftype in info["fields"]:
                    print("   field %s : %s" % (fname, ftype))
                for m in info["methods"]:
                    print("   method %s" % m)
        elif mode == "strings":
            for s in dex.strings():
                if pattern in s.lower():
                    print(s)
        else:
            raise SystemExit("mode: classes|detail|strings")


if __name__ == "__main__":
    main()
