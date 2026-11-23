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


def dex_names(apk):
    names = [name for name in apk.namelist() if DEX_NAME.fullmatch(name)]
    if "classes.dex" not in names or len(set(names)) != len(names):
        raise SystemExit("APK has missing or duplicate DEX entries")
    return set(names)


def verify(base_path, merged_path, patch_entry, dexdump):
    with ZipFile(base_path) as base, ZipFile(merged_path) as merged:
        expected = entries(base) + [patch_entry]
        actual = entries(merged)
        if actual != expected:
            raise SystemExit(f"Entry list mismatch: expected {expected}, got {actual}")

        base_dex = dex_names(base)
        merged_dex = dex_names(merged)
        if "classes2.dex" in base_dex or merged_dex != base_dex | {"classes2.dex"}:
            raise SystemExit("Expected exactly one new DEX: classes2.dex")
        for name in base_dex:
            if base.read(name) != merged.read(name):
                raise SystemExit(f"Base DEX was changed: {name}")

        remaining = {f"L{name.replace('.', '/')};".encode("utf-8") for name in actual}
        # Windows dexdump cannot map compressed ZIP members directly. Only
        # extract named DEX files, separately from the script and working dir.
        with tempfile.TemporaryDirectory(prefix="innocent-dex-check-") as directory:
            for name in sorted(merged_dex):
                dex = Path(directory) / name
                dex.write_bytes(merged.read(name))
                with subprocess.Popen([dexdump, "-l", "plain", str(dex)], stdout=subprocess.PIPE) as process:
                    for line in process.stdout:
                        match = CLASS_DESCRIPTOR.fullmatch(line)
                        if match:
                            remaining.discard(match[1])
                    if process.wait() != 0:
                        raise SystemExit(f"dexdump rejected {name}")
        if remaining:
            raise SystemExit("Entry classes undefined: " + ", ".join(sorted(name.decode() for name in remaining)))
        print("Verified Xposed entries and unchanged base DEX: " + ", ".join(actual))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base")
    parser.add_argument("merged")
    parser.add_argument("patch_entry")
    parser.add_argument("dexdump")
    args = parser.parse_args()
    verify(args.base, args.merged, args.patch_entry, args.dexdump)
