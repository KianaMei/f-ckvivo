#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Patch Ding's framework gate without depending on obfuscated member names."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import struct
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path

ANCHORS = frozenset({"lsposed", "npatch", "lspatch", "fpa"})
STRING_TYPE = "Ljava/lang/String;"
ACCESS_STATIC = 0x0008


def uleb128(data: bytes | bytearray, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    for _ in range(5):
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7
    raise ValueError("overlong ULEB128 at 0x%x" % offset)


def instruction_width(insns: list[int], offset: int) -> int:
    unit = insns[offset]
    opcode = unit & 0xFF
    if opcode == 0 and unit:
        payload = unit >> 8
        if payload == 1:  # packed-switch-payload
            return 4 + insns[offset + 1] * 2
        if payload == 2:  # sparse-switch-payload
            return 2 + insns[offset + 1] * 4
        if payload == 3:  # array-data-payload
            element_width = insns[offset + 1]
            size = insns[offset + 2] | (insns[offset + 3] << 16)
            return 4 + (element_width * size + 1) // 2
        raise ValueError("unknown NOP payload 0x%04x" % unit)

    if opcode in (0x03, 0x06, 0x09, 0x14, 0x17, 0x26, 0x2A, 0x2B, 0x2C) or \
            0x6E <= opcode <= 0x78:
        return 3
    if opcode == 0x18:
        return 5
    if opcode in (0x1B,):
        return 3
    if opcode in (0xFA, 0xFB):
        return 4
    if opcode in (0xFC, 0xFD):
        return 3
    if opcode in (0xFE, 0xFF):
        return 2
    if opcode in (0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A,
                  0x1C, 0x1F, 0x20, 0x22, 0x23, 0x29) or \
            0x2D <= opcode <= 0x3D or 0x44 <= opcode <= 0x6D or \
            0x90 <= opcode <= 0xAF or 0xD0 <= opcode <= 0xE2:
        return 2
    if opcode in (0x24, 0x25):
        return 3
    # All remaining defined DEX opcodes are one code unit. The parser later
    # rejects an instruction stream if these widths run beyond insns_size.
    return 1


@dataclass(frozen=True)
class Method:
    class_name: str
    name: str
    signature: str
    access_flags: int
    code_offset: int
    insns_offset: int
    insns_size: int
    string_refs: frozenset[str]

    @property
    def identity(self) -> str:
        return "%s->%s%s" % (self.class_name, self.name, self.signature)


class DexFile:
    def __init__(self, data: bytes | bytearray):
        self.data = data
        if len(data) < 112 or not data.startswith(b"dex\n"):
            raise ValueError("not a supported DEX file")
        if struct.unpack_from("<I", data, 32)[0] != len(data):
            raise ValueError("DEX file_size does not match its container")
        self.strings = self._read_strings()
        self.types = self._read_types()

    def _table(self, size_offset: int, offset_offset: int) -> tuple[int, int]:
        return (struct.unpack_from("<I", self.data, size_offset)[0],
                struct.unpack_from("<I", self.data, offset_offset)[0])

    def _read_strings(self) -> list[str]:
        count, table = self._table(0x38, 0x3C)
        result = []
        for index in range(count):
            offset = struct.unpack_from("<I", self.data, table + index * 4)[0]
            _, cursor = uleb128(self.data, offset)  # UTF-16 length; not byte length.
            end = self.data.index(0, cursor)
            raw = bytes(self.data[cursor:end])
            # The four anchors are ASCII. surrogatepass handles any MUTF-8
            # surrogate encodings elsewhere without making them fatal.
            result.append(raw.decode("utf-8", "surrogatepass"))
        return result

    def _read_types(self) -> list[str]:
        count, table = self._table(0x40, 0x44)
        return [self.strings[struct.unpack_from("<I", self.data, table + i * 4)[0]]
                for i in range(count)]

    def _proto_signature(self, proto_index: int) -> tuple[str, list[str]]:
        _, table = self._table(0x48, 0x4C)
        _, return_type, parameters = struct.unpack_from("<III", self.data,
                                                        table + proto_index * 12)
        params = []
        if parameters:
            size = struct.unpack_from("<I", self.data, parameters)[0]
            params = [self.types[struct.unpack_from("<H", self.data,
                       parameters + 4 + i * 2)[0]] for i in range(size)]
        return self.types[return_type], params

    def methods(self):
        string_count, string_table = self._table(0x38, 0x3C)
        del string_count, string_table
        method_count, method_table = self._table(0x58, 0x5C)
        class_count, class_table = self._table(0x60, 0x64)
        methods = {}
        for index in range(method_count):
            class_index, proto_index, name_index = struct.unpack_from(
                "<HHI", self.data, method_table + index * 8)
            return_type, params = self._proto_signature(proto_index)
            signature = "(%s)%s" % ("".join(params), return_type)
            methods[index] = (self.types[class_index], self.strings[name_index],
                              signature, return_type, params)

        for class_index in range(class_count):
            base = class_table + class_index * 32
            class_data = struct.unpack_from("<I", self.data, base + 24)[0]
            if not class_data:
                continue
            cursor = class_data
            sizes = []
            for _ in range(4):
                value, cursor = uleb128(self.data, cursor)
                sizes.append(value)
            static_fields, instance_fields, direct_count, virtual_count = sizes
            for count in (static_fields, instance_fields):
                for _ in range(count):
                    _, cursor = uleb128(self.data, cursor)
                    _, cursor = uleb128(self.data, cursor)
            for count in (direct_count, virtual_count):
                method_index = 0
                for _ in range(count):
                    difference, cursor = uleb128(self.data, cursor)
                    flags, cursor = uleb128(self.data, cursor)
                    code_offset, cursor = uleb128(self.data, cursor)
                    method_index += difference
                    if not code_offset:
                        continue
                    class_name, name, signature, return_type, params = methods[method_index]
                    if return_type != "Z" or params != [STRING_TYPE] or not flags & ACCESS_STATIC:
                        continue
                    registers, ins_size, outs_size, tries_size = struct.unpack_from(
                        "<HHHH", self.data, code_offset)
                    del registers, ins_size, outs_size, tries_size
                    insns_size = struct.unpack_from("<I", self.data, code_offset + 12)[0]
                    if insns_size < 2:
                        continue
                    insns_offset = code_offset + 16
                    insns = list(struct.unpack_from("<%dH" % insns_size, self.data,
                                                    insns_offset))
                    refs = self._string_refs(insns)
                    if ANCHORS <= refs:
                        yield Method(class_name, name, signature, flags, code_offset,
                                     insns_offset, insns_size, frozenset(refs))

    def _string_refs(self, insns: list[int]) -> frozenset[str]:
        refs = set()
        offset = 0
        while offset < len(insns):
            width = instruction_width(insns, offset)
            if width <= 0 or offset + width > len(insns):
                raise ValueError("invalid instruction stream near code unit %d" % offset)
            opcode = insns[offset] & 0xFF
            if opcode == 0x1A:
                index = insns[offset + 1]
            elif opcode == 0x1B:
                index = insns[offset + 1] | (insns[offset + 2] << 16)
            else:
                index = None
            if index is not None and index < len(self.strings):
                value = self.strings[index]
                if value in ANCHORS:
                    refs.add(value)
            offset += width
        return frozenset(refs)


def repair_dex_integrity(data: bytearray) -> None:
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)


def patch_dex(source: bytes) -> tuple[bytes, dict | None]:
    dex = DexFile(source)
    candidates = list(dex.methods())
    if not candidates:
        return source, None
    if len(candidates) != 1:
        raise ValueError("ambiguous framework gates: %s" %
                         ", ".join(method.identity for method in candidates))
    method = candidates[0]
    patched = bytearray(source)
    insns = list(struct.unpack_from("<%dH" % method.insns_size, source,
                                    method.insns_offset))
    if tuple(insns[:2]) == (0x1012, 0x000F):
        raise ValueError("framework gate is already patched: %s" % method.identity)
    # Cover complete original instructions up to the two replacement units.
    # In 1.10/1.16 the second instruction is if-eqz (two units), so its second
    # half must become NOP rather than being decoded as a stray opcode.
    patch_size = 0
    while patch_size < 2:
        patch_size += instruction_width(insns, patch_size)
    if patch_size > method.insns_size:
        raise ValueError("invalid entry instruction boundary in %s" % method.identity)
    original_units = insns[:patch_size]
    replacement = [0x1012, 0x000F] + [0x0000] * (patch_size - 2)
    # const/4 v0, #1 ; return v0 ; optional NOP. Keep the unreachable remainder
    # intact so debug tables, exception tables and later instruction boundaries
    # remain exactly as upstream emitted them.
    struct.pack_into("<%dH" % patch_size, patched, method.insns_offset, *replacement)
    repair_dex_integrity(patched)
    report = {
        "method": method.identity,
        "access_flags": "0x%x" % method.access_flags,
        "code_offset": "0x%x" % method.code_offset,
        "insns_offset": "0x%x" % method.insns_offset,
        "insns_size": method.insns_size,
        "original_entry_code_units": ["0x%04x" % unit for unit in original_units],
        "patched_entry_code_units": ["0x%04x" % unit for unit in replacement],
        "anchors": sorted(method.string_refs & ANCHORS),
    }
    return bytes(patched), report


def signature_entry(name: str) -> bool:
    upper = name.upper()
    if upper == "META-INF/MANIFEST.MF":
        return True
    return upper.startswith("META-INF/") and "/" not in upper[9:] and \
        upper.endswith((".SF", ".RSA", ".DSA", ".EC"))


def patch_apk(source: Path, output: Path) -> dict:
    patched_entries = {}
    reports = []
    with zipfile.ZipFile(source) as archive:
        if archive.testzip():
            raise ValueError("source APK has a corrupt ZIP member")
        for info in archive.infolist():
            if info.is_dir() or not info.filename.startswith("classes") or \
                    not info.filename.endswith(".dex"):
                continue
            original = archive.read(info.filename)
            patched, report = patch_dex(original)
            if report:
                report["dex_entry"] = info.filename
                report["original_dex_sha256"] = hashlib.sha256(original).hexdigest()
                report["patched_dex_sha256"] = hashlib.sha256(patched).hexdigest()
                patched_entries[info.filename] = patched
                reports.append(report)
        if len(reports) != 1:
            raise ValueError("expected one framework gate across all APK DEX files; found %d" %
                             len(reports))
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", allowZip64=True) as dest:
            dest.comment = archive.comment
            for info in archive.infolist():
                if signature_entry(info.filename):
                    continue
                cloned = copy.copy(info)
                dest.writestr(cloned, patched_entries.get(info.filename,
                                                          archive.read(info.filename)))
    return {
        "source_apk": str(source),
        "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "unsigned_apk": str(output),
        "patches": reports,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="unmodified upstream APK")
    parser.add_argument("output", type=Path, help="unsigned patched APK")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.output.exists() and not args.force:
        raise SystemExit("output exists; pass --force to replace it: %s" % args.output)
    report = patch_apk(args.source, args.output)
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
