"""Package the verified local build, exact source snapshot and validation records.

The original Douyin APK, private signing material and ignored working files are
never copied into the deliverable. Run only after the final Gradle test/build.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
from xml.etree import ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
repo = root / "repos/DouAi"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--test-log", required=True, type=Path)
parser.add_argument("--build-log", required=True, type=Path)
args = parser.parse_args()
for log in (args.test_log, args.build_log):
    if "BUILD SUCCESSFUL" not in log.read_text(encoding="utf-8", errors="replace"):
        raise SystemExit(f"No successful final run recorded in {log}")

suites = []
for file in (repo / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
    suite = ET.parse(file).getroot()
    suites.append({
        "suite": suite.attrib.get("name"),
        **{key: int(suite.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")},
        "skipped_tests": [case.attrib.get("name") for case in suite.findall("testcase") if case.find("skipped") is not None],
    })
if not suites or any(suite["failures"] or suite["errors"] for suite in suites):
    raise SystemExit("Current test XML contains failures or no tests")
apk = repo / "app/build/outputs/apk/debug/app-debug.apk"
if not apk.is_file():
    raise SystemExit("Debug APK is missing")
java = Path(r"C:\jdk-21.0.9+10\bin\java.exe")
sdk = Path(r"C:\Users\jaqenze\AppData\Local\Android\Sdk\build-tools\36.0.0")
signature = subprocess.run([str(java), "-jar", str(sdk / "lib/apksigner.jar"), "verify", "--verbose", "--print-certs", str(apk)], capture_output=True, check=True)
badging = subprocess.run([str(sdk / "aapt.exe"), "dump", "badging", str(apk)], capture_output=True, check=True).stdout.decode("utf-8", "replace")
if "name='io.github.yudigaga.douai.adapt412'" not in badging or "41.2-unified" not in badging:
    raise SystemExit("APK identity is not the dedicated unified build")
with zipfile.ZipFile(apk) as archive:
    if "assets/douyin-host-rules-41.2.0.tsv" not in archive.namelist():
        raise SystemExit("New host rules are missing from the packaged APK")
    source_rules = repo / "app/src/main/assets/douyin-host-rules-41.2.0.tsv"
    if archive.read("assets/douyin-host-rules-41.2.0.tsv") != source_rules.read_bytes():
        raise SystemExit("Packaged host rules differ from the reviewed source")

dest = root / "dist/unified-41.2"
dest.mkdir(parents=True, exist_ok=True)
output_apk = dest / "Douyin-Unified-41.2.apk"
shutil.copy2(apk, output_apk)
(dest / "apk-signature.txt").write_bytes(signature.stdout + signature.stderr)
(dest / "apk-badging.txt").write_text(badging, encoding="utf-8")
shutil.copy2(args.test_log, dest / "tests.log")
shutil.copy2(args.build_log, dest / "build.log")
original_lint_crash = repo / "work/adapt-41.2.0/unified-build.log"
if original_lint_crash.is_file() and "PrivateApiDetector" in original_lint_crash.read_text(encoding="utf-8", errors="replace"):
    shutil.copy2(original_lint_crash, dest / "lint-parser-crash.log")
shutil.copy2(repo / "docs/unified-41.2.zh-CN.md", dest / "使用说明.md")
shutil.copytree(repo / "app/build/test-results/testDebugUnitTest", dest / "test-results", dirs_exist_ok=True)
lint_path = repo / "app/build/reports/lint-results-debug.xml"
lint = {"completed": False}
if lint_path.is_file():
    issues = ET.parse(lint_path).getroot().findall("issue")
    lint = {"completed": True,
            "errors": sum(issue.attrib.get("severity") in {"Error", "Fatal"} for issue in issues),
            "warnings": sum(issue.attrib.get("severity") == "Warning" for issue in issues),
            "excluded_detectors_due_to_dex_identifier_parser_crash": ["BlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi"]}
    if lint["errors"]:
        raise SystemExit("Current lint report still contains errors")
    for extension in ("xml", "txt", "html"):
        report = repo / f"app/build/reports/lint-results-debug.{extension}"
        if report.is_file():
            shutil.copy2(report, dest / report.name)
files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=repo).split(b"\0")
names = sorted(set(raw.decode("utf-8") for raw in files if raw))
snapshot = dest / "Douyin-Unified-41.2-source.zip"
with zipfile.ZipFile(snapshot, "w", zipfile.ZIP_DEFLATED, compresslevel=7) as archive:
    for name in names:
        path = repo / name
        if not path.is_file():
            continue
        if path.suffix.lower() in {".apk", ".jks", ".keystore", ".key", ".p12", ".pem"}:
            raise SystemExit(f"Unexpected binary or signing material in source list: {name}")
        archive.write(path, "DouAi/" + name)
def digest(file):
    with file.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()
manifest = {
    "date": "2026-11-13", "product": "抖音融合 41.2", "package": "io.github.yudigaga.douai.adapt412",
    "upstream": "https://github.com/yudigaga/DouAi", "upstream_commit": "6e51a2a436d6a5aad6f831f6ca1cc1c46a95e5b6",
    "target": {"package": "com.ss.android.ugc.aweme", "version": "41.2.0", "versionCode": 410200,
               "sha256": "4d161a8eb4da14ee9931b87476e9f0de3847797006e3485e51c4cf497a9d5a99"},
    "tests": {key: sum(suite[key] for suite in suites) for key in ("tests", "failures", "errors", "skipped")},
    "test_suites": suites,
    "lint": lint,
    "host_rules": {"count": sum(bool(line) and not line.startswith("#") and not line.startswith("id\t")
                                    for line in source_rules.read_text(encoding="utf-8").splitlines()),
                   "canonical_fingerprint": re.search(r'PROFILE_412 = new Profile\(.*?"([0-9a-f]{64})"',
                       (repo / "app/src/main/java/com/douai/module/scan/HostAdaptation.java").read_text(encoding="utf-8"), re.S).group(1)},
    "apk": {"file": output_apk.name, "bytes": output_apk.stat().st_size, "sha256": digest(output_apk), "signature_verified": True},
    "source": {"file": snapshot.name, "sha256": digest(snapshot), "files": len(names)},
    "device_tested": False, "adb_connected_devices": 0,
}
(dest / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
(dest / "SHA256SUMS.txt").write_text(f"{digest(output_apk)}  {output_apk.name}\n{digest(snapshot)}  {snapshot.name}\n", encoding="utf-8")
print(json.dumps({"directory": str(dest), "apk": manifest["apk"], "tests": manifest["tests"]}, ensure_ascii=False, indent=2))
