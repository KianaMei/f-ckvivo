import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def class_descriptors(data):
    require(data[:4] == b"dex\n", "Expected a standard DEX file")
    require(struct.unpack_from("<I", data, 40)[0] == 0x12345678, "Unexpected DEX byte order")
    strings_count, strings_offset = struct.unpack_from("<II", data, 56)
    types_count, types_offset = struct.unpack_from("<II", data, 64)
    classes_count, classes_offset = struct.unpack_from("<II", data, 96)
    require(classes_offset + classes_count * 32 <= len(data), "Truncated class table")
    for class_number in range(classes_count):
        type_index = struct.unpack_from("<I", data, classes_offset + class_number * 32)[0]
        require(type_index < types_count, "Invalid class type index")
        string_index = struct.unpack_from("<I", data, types_offset + type_index * 4)[0]
        require(string_index < strings_count, "Invalid descriptor index")
        offset = struct.unpack_from("<I", data, strings_offset + string_index * 4)[0]
        for byte_number in range(5):
            byte_value = data[offset]
            offset += 1
            if not byte_value & 0x80:
                break
        else:
            raise ValueError("Invalid string length")
        end = data.index(b"\0", offset)
        yield data[offset:end].decode("utf-8")


def inspect(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP entries")
        require("assets/xposed_init" not in names, "Unexpected legacy entry")
        scope = archive.read("META-INF/xposed/scope.list").decode().splitlines()
        require(scope == ["com.vivo.globaldragdrop", "com.vivo.accessibilityenhance", "org.BroTech.gege.pilibro", "tv.danmaku.bili", "com.twitter.android"],
                "Unexpected drag/source scope")
        entry = archive.read("META-INF/xposed/java_init.list").decode().splitlines()
        require(entry == ["dev.ganvivo.dragall.DragAllModule"], "Unexpected module entry")
        properties = dict(line.split("=", 1) for line in
                          archive.read("META-INF/xposed/module.prop").decode().splitlines()
                          if line.strip() and not line.startswith("#"))
        require(properties.get("minApiVersion") == "102", "Wrong minimum API")
        require(properties.get("targetApiVersion") == "102", "Wrong target API")
        require(properties.get("staticScope") == "true", "Static scope is required")
        descriptors = set()
        dex_names = [name for name in names if name.startswith("classes") and name.endswith(".dex")]
        require(dex_names, "No DEX files")
        for name in dex_names:
            descriptors.update(class_descriptors(archive.read(name)))
        required = {
            "Ldev/ganvivo/dragall/DragAllModule;",
            "Ldev/ganvivo/dragall/SettingsActivity;",
            "Ldev/ganvivo/dragall/DragFeature;",
            "Ldev/ganvivo/dragall/CatcherFeature;",
            "Ldev/ganvivo/dragall/XComposeDragFeature;",
            "Ldev/ganvivo/dragall/NativeSelectionFeature;",
            "Ldev/ganvivo/dragall/ShareFeature;",
            "Lio/github/libxposed/service/XposedProvider;",
        }
        require(required <= descriptors, f"Missing classes: {required - descriptors}")
        require("Ldev/ganvivo/dragall/TelegramSelectionFeature;" not in descriptors,
                "Crashing Telegram injection must remain excluded")
        native_rules = json.loads(archive.read("assets/telegram-native.json"))
        require(isinstance(native_rules, list) and len(native_rules) == 2,
                "Missing native extraction rules")
        require(not any(name.startswith("Lio/github/libxposed/api/") for name in descriptors),
                "Hook API must remain compileOnly")
        require(not any(name.startswith("Lde/robv/android/xposed/") for name in descriptors),
                "Classic Xposed classes must not be packaged")
        require(not any("MultiTaskHooks" in name or "RecentsHooks" in name or "SplitHooks" in name
                        for name in descriptors), "Unrelated system hooks found")
    return {
        "apk": str(apk.resolve()),
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "scope": scope,
        "entry": entry[0],
        "api": properties["targetApiVersion"],
        "defined_classes": len(descriptors),
        "result": "PASS",
    }


def main():
    parser = argparse.ArgumentParser(description="Check drag-all APK packaging without a device")
    parser.add_argument("apk", type=Path)
    arguments = parser.parse_args()
    print(json.dumps(inspect(arguments.apk), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
