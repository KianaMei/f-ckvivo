#!/usr/bin/env python3
"""GhostLock profile.bin (wire v2) parser + release-string swapper.

Format (from NativeProfile.kt):
  Header (16B): magic u32, version u16, frontend u16, backend u16, route u16, rlen u16, reserved u16
  release[rlen]
  section_count u16
  per section: name_len u8, name, entry_count u32
    per entry: key_len u8, key, value u64 LE
"""
import struct
import sys


def parse(path):
    d = open(path, 'rb').read()
    magic, ver, fe, be, route, rlen, rsv = struct.unpack_from('<IHHHHHH', d, 0)
    assert magic == 0x0D000721, f"bad magic 0x{magic:x}"
    assert ver == 2, f"bad version {ver}"
    off = 16
    release = d[off:off + rlen].decode()
    off += rlen
    nsec = struct.unpack_from('<H', d, off)[0]
    off += 2
    sections = []
    for _ in range(nsec):
        nl = d[off]; off += 1
        name = d[off:off + nl].decode(); off += nl
        nent = struct.unpack_from('<I', d, off)[0]; off += 4
        entries = []
        for _ in range(nent):
            kl = d[off]; off += 1
            key = d[off:off + kl].decode(); off += kl
            val = struct.unpack_from('<q', d, off)[0]; off += 8
            entries.append((key, val))
        sections.append((name, entries))
    assert off == len(d), f"trailing bytes: {len(d) - off}"
    return dict(magic=magic, ver=ver, fe=fe, be=be, route=route, release=release, sections=sections)


def emit(p, path):
    out = bytearray()
    rb = p['release'].encode()
    out += struct.pack('<IHHHHHH', p['magic'], p['ver'], p['fe'], p['be'], p['route'], len(rb), 0)
    out += rb
    out += struct.pack('<H', len(p['sections']))
    for name, entries in p['sections']:
        nb = name.encode()
        out += bytes([len(nb)]) + nb
        out += struct.pack('<I', len(entries))
        for key, val in entries:
            kb = key.encode()
            out += bytes([len(kb)]) + kb
            out += struct.pack('<q', val)
    open(path, 'wb').write(out)
    return len(out)


if __name__ == '__main__':
    src = sys.argv[1]
    dst = sys.argv[2]
    new_release = sys.argv[3] if len(sys.argv) > 3 else None

    p = parse(src)
    print(f"parsed: release={p['release']} route={p['route']} sections={len(p['sections'])}")

    # roundtrip check first (emit unmodified, compare)
    tmp = dst + '.rt'
    emit(p, tmp)
    orig = open(src, 'rb').read()
    rt = open(tmp, 'rb').read()
    if orig == rt:
        print("ROUNDTRIP OK (byte-exact)")
    else:
        print(f"ROUNDTRIP MISMATCH ({len(orig)} vs {len(rt)})")
        sys.exit(2)

    if new_release:
        p['release'] = new_release
        n = emit(p, dst)
        print(f"emitted {dst}: {n} bytes, release={new_release}")
        # verify by re-parsing
        p2 = parse(dst)
        print(f"verify: release={p2['release']} route={p2['route']} sections={len(p2['sections'])}")
        assert p2['release'] == new_release
        print("VERIFY OK")
