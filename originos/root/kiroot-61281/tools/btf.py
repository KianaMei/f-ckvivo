#!/usr/bin/env python3
"""Minimal BTF reader: dump struct/union layouts for selected type names."""
import struct, sys, numpy as np

KIND_STRUCT, KIND_UNION = 4, 5


class BTF:
    def __init__(self, path, data_off):
        d = open(path, "rb").read()
        blob = d[data_off:]
        pos = -1
        hdr = None
        while True:
            pos = blob.find(b"\x9f\xeb", pos + 1)
            if pos < 0 or pos % 4:
                continue
            ver, flags = blob[pos + 2], blob[pos + 3]
            hdr_len, type_off, type_len, str_off, str_len = struct.unpack_from("<IIIII", blob, pos + 4)
            if ver == 1 and 0 < hdr_len <= 64 and type_len and str_len and pos + hdr_len + type_off + type_len <= len(blob):
                hdr = (pos, hdr_len, type_off, type_len, str_off, str_len)
                break
        if not hdr:
            raise RuntimeError("BTF header not found")
        pos, hdr_len, type_off, type_len, str_off, str_len = hdr
        self.type_start = pos + hdr_len + type_off
        self.type_end = self.type_start + type_len
        self.str_start = pos + hdr_len + str_off
        self.blob = blob
        self.types = self._parse()

    def s(self, off):
        p = self.str_start + off
        e = self.blob.index(b"\x00", p)
        return self.blob[p:e].decode("latin1")

    def _parse(self):
        b, p, end = self.blob, self.type_start, self.type_end
        types = [None]      # type ids start at 1
        while p < end:
            name_off, info, size = struct.unpack_from("<III", b, p)
            p += 12
            vlen, kind = info & 0xFFFF, (info >> 24) & 0x1F
            kflag = (info >> 31) & 1
            members = []
            if kind in (KIND_STRUCT, KIND_UNION):
                for _ in range(vlen):
                    m_name, m_type, m_off = struct.unpack_from("<III", b, p)
                    p += 12
                    members.append((m_name, m_type, m_off))
            elif kind == 1:      # INT
                p += 4
            elif kind == 3:      # ARRAY
                p += 12
            elif kind in (6, 13):  # ENUM, FUNC_PROTO
                p += 8 * vlen if kind == 6 else 8 * vlen
            elif kind == 14:     # VAR
                p += 4
            elif kind == 15:     # DATASEC
                p += 12 * vlen
            elif kind == 17:     # DECL_TAG
                p += 4
            elif kind == 19:     # ENUM64
                p += 12 * vlen
            types.append({"name": self.s(name_off), "kind": kind, "size": size,
                          "vlen": vlen, "kflag": kflag, "members": members})
        return types

    def find(self, name, kinds=(KIND_STRUCT, KIND_UNION)):
        return [(i, t) for i, t in enumerate(self.types)
                if t and t["name"] == name and t["kind"] in kinds]


def dump(btf, name):
    hits = btf.find(name)
    if not hits:
        print(f"  {name}: NOT FOUND")
        return
    for tid, t in hits[:2]:
        print(f"  {name} (id={tid}) size=0x{t['size']:x} kind={t['kind']} members={t['vlen']}")
        for m_name, m_type, m_off in t["members"]:
            off = m_off if not t["kflag"] else m_off & 0xFFFFFF
            bits = off % 8
            print(f"     +0x{off // 8:03x}" + (f".{bits}" if bits else "   ") + f"  {btf.s(m_name) or '<anon>'}")


if __name__ == "__main__":
    path, off = sys.argv[1], int(sys.argv[2], 16)
    btf = BTF(path, off)
    print(f"{path}: {len(btf.types)-1} types")
    for nm in sys.argv[3:]:
        dump(btf, nm)
