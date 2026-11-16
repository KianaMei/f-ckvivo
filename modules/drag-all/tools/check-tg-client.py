import argparse
import json
import re
import struct
import zipfile
from pathlib import Path


class Dex:
    def __init__(self, data):
        if data[:4] != b"dex\n":
            raise ValueError("Not a standard DEX")
        self.data = data
        strings_count, strings_offset = self.unpack("II", 56)
        self.strings = []
        for index in range(strings_count):
            offset = self.unpack("I", strings_offset + index * 4)[0]
            _, offset = self.uleb(offset)
            self.strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace"))
        types_count, types_offset = self.unpack("II", 64)
        self.types = [self.strings[self.unpack("I", types_offset + index * 4)[0]]
                      for index in range(types_count)]
        self.proto_offset = self.unpack("I", 76)[0]
        self.field_offset = self.unpack("I", 84)[0]
        self.method_offset = self.unpack("I", 92)[0]

    def unpack(self, pattern, offset):
        return struct.unpack_from("<" + pattern, self.data, offset)

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.data[offset]
            offset += 1
            value |= (byte & 0x7f) << shift
            if byte < 0x80:
                return value, offset
        raise ValueError("Invalid ULEB128")

    def method(self, index):
        _, proto, name = self.unpack("HHI", self.method_offset + index * 8)
        short_name, result, parameters = self.unpack("III", self.proto_offset + proto * 12)
        arguments = ""
        if parameters:
            count = self.unpack("I", parameters)[0]
            arguments = "".join(self.types[self.unpack("H", parameters + 4 + index * 2)[0]]
                                for index in range(count))
        return f"{self.strings[name]}({arguments}){self.types[result]}"

    def field(self, index):
        _, field_type, name = self.unpack("HHI", self.field_offset + index * 8)
        return f"{self.strings[name]}:{self.types[field_type]}"

    def definitions(self):
        count, offset = self.unpack("II", 96)
        for index in range(count):
            definition = offset + index * 32
            owner = self.types[self.unpack("I", definition)[0]]
            cursor = self.unpack("I", definition + 24)[0]
            fields, methods = set(), set()
            if cursor:
                sizes = []
                for _ in range(4):
                    size, cursor = self.uleb(cursor)
                    sizes.append(size)
                for group, size in enumerate(sizes):
                    member_index = 0
                    for _ in range(size):
                        delta, cursor = self.uleb(cursor)
                        _, cursor = self.uleb(cursor)
                        member_index += delta
                        if group >= 2:
                            _, cursor = self.uleb(cursor)
                            methods.add(self.method(member_index))
                        else:
                            fields.add(self.field(member_index))
            yield owner, fields, methods


CELL = "Lorg/telegram/ui/Cells/ChatMessageCell;"
MESSAGE = "Lorg/telegram/messenger/MessageObject;"
RECEIVER = "Lorg/telegram/messenger/ImageReceiver;"
DRAWER = re.compile(r"^L[\w/$]*PhotoViewer\$FrameLayoutDrawer;$")

CHECKS = [
    ("ChatMessageCell.getMessageObject", "Lorg/telegram/messenger/MessageObject;",
     "method"),
    ("ChatMessageCell.getPhotoImage", "Lorg/telegram/messenger/ImageReceiver;",
     "method"),
    ("MessageObject.messageText", None, "field"),
    ("MessageObject.caption", None, "field"),
    ("MessageObject.canForwardMessage", None, "method"),
    ("ImageReceiver.isInsideImage", None, "method"),
    ("ImageReceiver.getBitmap", None, "method"),
    ("drawer.this$0", None, "field"),
    ("PhotoViewer.currentMessageObject", None, "field"),
    ("PhotoViewer.centerImage", None, "field"),
]


def inspect(apk):
    owners = {CELL: None, MESSAGE: None, RECEIVER: None}
    drawers = {}
    relocated = None
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not (name.startswith("classes") and name.endswith(".dex")):
                continue
            for owner, fields, methods in Dex(archive.read(name)).definitions():
                if owner in owners:
                    owners[owner] = (fields, methods)
                elif DRAWER.match(owner):
                    drawers[owner] = (fields, methods)
                names = {item.split(":")[0] for item in fields}
                if relocated is None and "messageText" in names and "caption" in names:
                    relocated = (owner, fields, methods)
    results = {}
    cell = owners[CELL]
    results["class ChatMessageCell"] = cell is not None
    message = owners[MESSAGE]
    if message is None and relocated is not None and any(
            m.startswith("canForwardMessage(") for m in relocated[2]):
        message = (relocated[1], relocated[2])
        results["MessageObject relocated to"] = relocated[0]
    results["class MessageObject"] = message is not None
    receiver = owners[RECEIVER]
    results["class ImageReceiver"] = receiver is not None
    drawer_name = next(iter(drawers), None)
    results["class PhotoViewer$FrameLayoutDrawer"] = drawer_name is not None
    drawer = drawers.get(drawer_name)
    outer = None
    if drawer_name:
        outer_name = drawer_name[: drawer_name.index("$")] + ";"
    else:
        outer_name = None

    def member(source, kind, needle, owner_name):
        if source is None:
            return False
        pool = source[1] if kind == "method" else source[0]
        found = any(m == needle or m.split("(")[0] == needle for m in pool)
        return found

    results["ChatMessageCell.getMessageObject"] = cell is not None and any(
        m.startswith("getMessageObject(") for m in cell[1])
    results["ChatMessageCell.getPhotoImage"] = cell is not None and any(
        m.startswith("getPhotoImage(") for m in cell[1])
    if message is not None:
        results["MessageObject.messageText"] = any(f.startswith("messageText:")
                                                   for f in message[0])
        results["MessageObject.caption"] = any(f.startswith("caption:")
                                               for f in message[0])
        results["MessageObject.canForwardMessage"] = any(
            m.startswith("canForwardMessage(") for m in message[1])
    else:
        results["MessageObject.messageText"] = False
        results["MessageObject.caption"] = False
        results["MessageObject.canForwardMessage"] = False
    if receiver is not None:
        results["ImageReceiver.isInsideImage"] = any(
            m.startswith("isInsideImage(") for m in receiver[1])
        results["ImageReceiver.getBitmap"] = any(
            m.startswith("getBitmap(") for m in receiver[1])
    else:
        results["ImageReceiver.isInsideImage"] = False
        results["ImageReceiver.getBitmap"] = False
    results["drawer.this$0"] = drawer is not None and any(
        f.startswith("this$0:") for f in drawer[0])
    outer_members = None
    if outer_name is not None:
        with zipfile.ZipFile(apk) as archive:
            for name in archive.namelist():
                if not (name.startswith("classes") and name.endswith(".dex")):
                    continue
                for owner, fields, methods in Dex(archive.read(name)).definitions():
                    if owner == outer_name:
                        outer_members = (fields, methods)
        results["PhotoViewer.currentMessageObject"] = outer_members is not None and any(
            f.startswith("currentMessageObject:") for f in outer_members[0])
        results["PhotoViewer.centerImage"] = outer_members is not None and any(
            f.startswith("centerImage:") for f in outer_members[0])
    else:
        results["PhotoViewer.currentMessageObject"] = False
        results["PhotoViewer.centerImage"] = False
    return {"drawer_class": drawer_name, "outer_class": outer_name,
            "checks": results, "compatible": all(results.values())}


def main():
    parser = argparse.ArgumentParser(description="Check TG client DEX members for drag-all native rules")
    parser.add_argument("apk", nargs="+", type=Path)
    args = parser.parse_args()
    report = {}
    for apk in args.apk:
        report[apk.stem] = inspect(apk)
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return 0 if all(item["compatible"] for item in report.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
