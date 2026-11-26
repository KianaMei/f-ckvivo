"""Verify the appended module did not lose the APK's own Xposed entry points."""

import argparse
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZipFile


ENTRY_LIST = "META-INF/xposed/java_init.list"
DEX_NAME = re.compile(r"classes(?:[0-9]+)?\.dex")
CLASS_DESCRIPTOR = re.compile(rb"\s*Class descriptor\s*:\s*'(L[^']+;)'\s*")


def entries(apk):
    if apk.namelist().count(ENTRY_LIST) != 1:
        raise SystemExit(f"APK must contain exactly one {ENTRY_LIST}")
    names = [line.strip() for line in apk.read(ENTRY_LIST).decode("utf-8").splitlines()
             if line.strip() and not line.lstrip().startswith("#")]
    if not names or len(set(names)) != len(names):
        raise SystemExit("APK entry list is empty or has duplicate entries")
    return names


def dex_index(name):
    match = DEX_NAME.fullmatch(name)
    if not match:
        raise SystemExit(f"Unexpected DEX entry name: {name}")
    if name == "classes.dex":
        return 1
    suffix = name[len("classes"):-len(".dex")]
    if suffix.startswith("0") or int(suffix) < 2:
        raise SystemExit(f"Invalid multidex suffix in DEX entry: {name}")
    return int(suffix)


def dex_names(apk):
    names = [name for name in apk.namelist() if DEX_NAME.fullmatch(name)]
    if "classes.dex" not in names or len(set(names)) != len(names):
        raise SystemExit("APK has missing or duplicate DEX entries")
    indices = sorted(dex_index(name) for name in names)
    if indices != list(range(1, len(names) + 1)):
        raise SystemExit(f"APK has non-contiguous DEX entries: {sorted(names)}")
    return set(names)


def verify(base_path, merged_path, patch_entry, dexdump):
    with ZipFile(base_path) as base, ZipFile(merged_path) as merged:
        base_entries = entries(base)
        expected = base_entries + [patch_entry]
        actual = entries(merged)
        if actual != expected:
            raise SystemExit(f"Entry list mismatch: expected {expected}, got {actual}")

        base_dex = dex_names(base)
        merged_dex = dex_names(merged)
        expected_new_dex = f"classes{len(base_dex) + 1}.dex"
        if expected_new_dex in base_dex or merged_dex != base_dex | {expected_new_dex}:
            raise SystemExit(
                f"Expected exactly one new DEX: {expected_new_dex} (base={sorted(base_dex, key=dex_index)}, merged={sorted(merged_dex, key=dex_index)})"
            )
        for name in sorted(base_dex, key=dex_index):
            if base.read(name) != merged.read(name):
                raise SystemExit(f"Base DEX was changed: {name}")

        base_descriptors = {f"L{name.replace('.', '/')};".encode("utf-8") for name in base_entries}
        patch_descriptor = f"L{patch_entry.replace('.', '/')};".encode("utf-8")
        defined_in_base = set()
        defined_in_patch = set()
        # Windows dexdump cannot map compressed ZIP members directly. Only
        # extract named DEX files, separately from the script and working dir.
        with tempfile.TemporaryDirectory(prefix="innocent-dex-check-") as directory:
            for name in sorted(merged_dex, key=dex_index):
                dex = Path(directory) / name
                dex.write_bytes(merged.read(name))
                target_bucket = defined_in_patch if name == expected_new_dex else defined_in_base
                with subprocess.Popen([dexdump, "-l", "plain", str(dex)], stdout=subprocess.PIPE) as process:
                    for line in process.stdout:
                        match = CLASS_DESCRIPTOR.fullmatch(line)
                        if match:
                            descriptor = match[1]
                            if descriptor in base_descriptors or descriptor == patch_descriptor:
                                target_bucket.add(descriptor)
                    if process.wait() != 0:
                        raise SystemExit(f"dexdump rejected {name}")
        missing_base = base_descriptors - defined_in_base
        missing_patch = ({patch_descriptor} - defined_in_patch)
        if missing_base or missing_patch:
            missing = sorted(name.decode() for name in (missing_base | missing_patch))
            raise SystemExit("Entry classes undefined in expected DEX: " + ", ".join(missing))
        print(f"Verified Xposed entries, {len(base_dex)} unchanged base DEX(es), and {expected_new_dex}: " + ", ".join(actual))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base")
    parser.add_argument("merged")
    parser.add_argument("patch_entry")
    parser.add_argument("dexdump")
    args = parser.parse_args()
    verify(args.base, args.merged, args.patch_entry, args.dexdump)
