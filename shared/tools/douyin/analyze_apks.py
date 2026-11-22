"""Reproduce the offline inventory and JADX decompilation of the six samples.

This script reads APKs as archives. It does not install or execute their code.
JADX and Android build-tools are external analysis tools, not sample code.
"""

import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ORIGINALS = Path(r"C:\Users\jaqenze\Downloads\vivo临时root")
SAMPLES = {
    "Dyoo": "Dyoo.apk",
    "DouAi": "DouAi.apk",
    "base3": "base(3).apk.1",
    "base4": "base(4).apk.1",
    "base2": "base(2).apk.1",
    "base1": "base(1).apk.1",
}
JAVA = Path(r"C:\jdk-21.0.9+10\bin\java.exe")
BUILD_TOOLS = Path(r"C:\Users\jaqenze\AppData\Local\Android\Sdk\build-tools\36.0.0")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inventory():
    records = []
    for key, filename in SAMPLES.items():
        folder = ROOT / "analysis" / key
        apk = folder / "sample.apk"
        original = ORIGINALS / filename
        copy_hash = sha256(apk)
        original_hash = sha256(original)
        if copy_hash != original_hash:
            raise ValueError(f"Sample copy differs from original: {key}")
        badging = subprocess.run(
            [str(BUILD_TOOLS / "aapt.exe"), "dump", "badging", str(apk)],
            capture_output=True, check=True,
        ).stdout.decode("utf-8", errors="replace")
        signatures = subprocess.run(
            [str(JAVA), "-jar", str(BUILD_TOOLS / "lib" / "apksigner.jar"),
             "verify", "--verbose", "--print-certs", str(apk)],
            capture_output=True,
        )
        signature_output = (signatures.stdout + signatures.stderr).decode("utf-8", errors="replace")
        (folder / "signature-verification.txt").write_text(signature_output, encoding="utf-8")
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            entry_files = {}
            for name in names:
                if name.startswith("META-INF/xposed/") or name in ("assets/xposed_init", "assets/xposed_scope"):
                    entry_files[name] = archive.read(name).decode("utf-8", errors="replace")
            native = [
                {"path": item.filename, "size": item.file_size}
                for item in archive.infolist() if item.filename.endswith(".so")
            ]
        package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
        label = re.search(r"^application-label:'(.*)'", badging, flags=re.M)
        sdk = re.search(r"^sdkVersion:'([^']+)'", badging, flags=re.M)
        target = re.search(r"^targetSdkVersion:'([^']+)'", badging, flags=re.M)
        records.append({
            "key": key, "original_file": str(original), "sample_file": str(apk),
            "size_bytes": apk.stat().st_size, "sha256": copy_hash,
            "copy_matches_original": True,
            "package": package.group(1) if package else None,
            "version_code": package.group(2) if package else None,
            "version_name": package.group(3) if package else None,
            "label": label.group(1) if label else None,
            "min_sdk": sdk.group(1) if sdk else None,
            "target_sdk": target.group(1) if target else None,
            "permissions": re.findall(r"^uses-permission(?:-sdk-\d+)?: name='([^']+)'", badging, flags=re.M),
            "dex_files": [name for name in names if re.fullmatch(r"classes\d*\.dex", name)],
            "native_libraries": native,
            "module_entry_files": entry_files,
            "signature_verified": signatures.returncode == 0,
            "signature_details": str(folder / "signature-verification.txt"),
        })
    output = ROOT / "analysis" / "inventory.json"
    output.write_text(json.dumps(records, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Inventoried {len(records)} samples: {output}", flush=True)


def decompile_one(key):
    folder = ROOT / "analysis" / key
    jars = list((ROOT / "tools" / "jadx" / "lib").glob("jadx-*-all.jar"))
    if len(jars) != 1:
        raise ValueError("Expected exactly one installed JADX jar")
    command = [
        str(JAVA), "-Xms128m", "-Xmx1536m", "-cp", str(jars[0]), "jadx.cli.JadxCLI",
        "--threads-count", "2", "--show-bad-code", "-d", str(folder / "jadx"),
        str(folder / "sample.apk"),
    ]
    print(f"Decompiling {key}", flush=True)
    with (folder / "jadx.log").open("w", encoding="utf-8") as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    print(f"Finished {key}, JADX exit={result.returncode}; log={folder / 'jadx.log'}", flush=True)
    return key, result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", action="store_true")
    parser.add_argument("--decompile", nargs="*", choices=list(SAMPLES))
    args = parser.parse_args()
    if args.inventory:
        inventory()
    if args.decompile is not None:
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
            list(executor.map(decompile_one, args.decompile or SAMPLES))


if __name__ == "__main__":
    main()
