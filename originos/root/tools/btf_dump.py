#!/usr/bin/env python3
"""Minimal BTF parser for a raw ARM64 kernel Image.

Locates the embedded .BTF blob (magic 0xeb9f), parses its type/string sections and
prints member offsets for the structs needed by the CVE-20264560 port. No external
deps; works directly on boot-6.12.img (the kernel Image extracted from a GKI boot
image).

Usage:
  python tools/btf_dump.py <kernel-image> [struct ...]
"""
import struct
import sys

KIND = {
    0: "UNKN", 1: "INT", 2: "PTR", 3: "ARRAY", 4: "STRUCT", 5: "UNION",
    6: "ENUM", 7: "FWD", 8: "TYPEDEF", 9: "VOLATILE", 10: "CONST",
    11: "RESTRICT", 12: "FUNC", 13: "FUNC_PROTO", 14: "VAR", 15: "DATASEC",
    16: "FLOAT", 17: "DECL_TAG", 18: "TYPE_TAG", 19: "ENUM64",
}


def find_btf(data):
    """Return (btf_start, header_fields) for the first plausible BTF blob."""
    start = 0
    while True:
        i = data.find(b"\x9f\xeb\x01\x00", start)
        if i < 0:
            return None
        hdr_len, type_off, type_len, str_off, str_len = struct.unpack_from(
            "<IIIII", data, i + 4)
        if hdr_len in (24, 32) and type_len > 1000 and str_len > 1000:
            if i + hdr_len + max(type_off + type_len, str_off + str_len) <= len(data):
                return i, (hdr_len, type_off, type_len, str_off, str_len)
        start = i + 1


def parse_btf(data, base):
    hdr_len, type_off, type_len, str_off, str_len = struct.unpack_from(
        "<IIIII", data, base + 4)
    types_blob = data[base + hdr_len + type_off: base + hdr_len + type_off + type_len]
    str_blob = data[base + hdr_len + str_off: base + hdr_len + str_off + str_len]
    strings = {}
    off = 0
    while off < len(str_blob):
        end = str_blob.find(b"\x00", off)
        if end < 0:
            end = len(str_blob)
        strings[off] = str_blob[off:end].decode("utf-8", "replace")
        off = end + 1

    def name(off):
        return strings.get(off, "<bad:%d>" % off)

    types = [None]  # id 0 is void
    pos = 0
    while pos < len(types_blob):
        if pos + 12 > len(types_blob):
            break
        name_off, info, size_or_type = struct.unpack_from("<III", types_blob, pos)
        pos += 12
        vlen = info & 0xFFFF
        kind = (info >> 24) & 0x1F
        kflag = (info >> 31) & 1
        entry = {"name": name(name_off), "kind": kind, "kflag": kflag,
                 "size": size_or_type, "members": [], "type": size_or_type}
        if kind == 1:      # INT
            pos += 4
        elif kind == 3:    # ARRAY
            entry["array"] = struct.unpack_from("<III", types_blob, pos)
            pos += 12
        elif kind in (4, 5):  # STRUCT / UNION
            for _ in range(vlen):
                m_name, m_type, m_off = struct.unpack_from("<III", types_blob, pos)
                pos += 12
                if kflag:
                    bit_off = m_off & 0xFFFFFF
                    bit_sz = m_off >> 24
                else:
                    bit_off = m_off
                    bit_sz = 0
                entry["members"].append({
                    "name": name(m_name), "type": m_type,
                    "byte_off": bit_off // 8, "bit_off": bit_off, "bit_sz": bit_sz
                })
        elif kind == 6:    # ENUM
            pos += 8 * vlen
        elif kind == 13:   # FUNC_PROTO
            pos += 8 * vlen
        elif kind == 14:   # VAR
            pos += 4
        elif kind == 15:   # DATASEC
            pos += 12 * vlen
        elif kind == 17:   # DECL_TAG
            pos += 4
        elif kind == 19:   # ENUM64
            pos += 12 * vlen
        types.append(entry)
    return types


def resolve(types, type_id, depth=0):
    if depth > 32 or type_id <= 0 or type_id >= len(types):
        return "<bad:%d>" % type_id
    t = types[type_id]
    k = t["kind"]
    if k == 2:
        return resolve(types, t["type"], depth + 1) + " *"
    if k in (8, 9, 10, 11, 18):
        return resolve(types, t["type"], depth + 1)
    if k == 3:
        elem = resolve(types, t["array"][0], depth + 1)
        return "%s[%d]" % (elem, t["array"][2])
    if k in (4, 5):
        return ("struct " if k == 4 else "union ") + t["name"]
    if k == 1:
        return t["name"] or "int"
    if k == 6:
        return "enum " + t["name"]
    if k == 12:
        return t["name"] + "()"
    return "%s %s" % (KIND.get(k, k), t["name"])


def dump_struct(types, name, fields=None):
    ids = [i for i, t in enumerate(types)
           if t and t["kind"] in (4, 5) and t["name"] == name]
    if not ids:
        print("MISSING struct %s" % name)
        return
    tid = ids[0]
    t = types[tid]
    print("\n== %s %s (id=%d size=0x%x)" % (KIND[t["kind"]], name, tid, t["size"]))
    for m in t["members"]:
        if fields and m["name"] not in fields:
            continue
        print("  +0x%04x  %-32s %s (bit_sz=%d)" %
              (m["byte_off"], m["name"], resolve(types, m["type"]), m["bit_sz"]))
    return t


def dump_var(types, name):
    ids = [i for i, t in enumerate(types)
           if t and t["kind"] == 14 and t["name"] == name]
    if not ids:
        print("MISSING var %s" % name)
        return
    tid = ids[0]
    t = types[tid]
    print("\n== VAR %s (id=%d) type=%s" % (name, tid, resolve(types, t["type"])))


def main():
    path = sys.argv[1]
    wanted = sys.argv[2:] or None
    data = open(path, "rb").read()
    found = find_btf(data)
    if not found:
        print("no BTF found in %s" % path)
        return 1
    base, hdr = found
    print("BTF at file offset 0x%x hdr=%s" % (base, hdr))
    types = parse_btf(data, base)
    print("types parsed: %d" % (len(types) - 1))

    task_fields = ["tasks", "pid", "tgid", "group_leader", "real_cred", "cred",
                   "comm", "thread_group", "thread_info", "signal", "sighand",
                   "thread_node", "stack", "usage", "flags"]
    cred_fields = ["usage", "security", "uid", "gid", "suid", "sgid", "euid",
                   "egid", "fsuid", "fsgid", "securebits", "cap_inheritable",
                   "cap_permitted", "cap_effective", "cap_bset", "cap_ambient",
                   "user", "user_ns", "ucounts", "group_info"]
    sig_fields = ["thread_head", "posix_timers", "cpu_timers", "nr_threads",
                  "thread_group", "leader"]
    pipe_fields = ["page", "offset", "len", "ops", "flags", "private"]
    fops_fields = ["read_iter", "write_iter", "unlocked_ioctl", "open",
                   "release", "read", "write", "poll", "llseek", "mmap"]

    dump_struct(types, "task_struct", None if not wanted else task_fields)
    dump_struct(types, "cred", None if not wanted else cred_fields)
    dump_struct(types, "signal_struct", None if not wanted else sig_fields)
    dump_struct(types, "pipe_buffer", None if not wanted else pipe_fields)
    dump_struct(types, "file_operations", None if not wanted else fops_fields)
    dump_struct(types, "miscdevice", None if not wanted else None)
    dump_struct(types, "k_itimer", None if not wanted else None)
    dump_struct(types, "cpu_timer", None if not wanted else None)
    dump_struct(types, "timerqueue_node", None if not wanted else None)
    dump_struct(types, "configfs_buffer", None if not wanted else None)
    dump_var(types, "kmalloc_caches")
    dump_var(types, "init_task")
    dump_var(types, "init_signals")
    return 0


if __name__ == "__main__":
    sys.exit(main())
