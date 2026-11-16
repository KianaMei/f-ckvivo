"""Verify both bootstrap identities and package the startup-fixed unified module.

Run after tools/verify-unified.ps1 -RunLint succeeds. Never executes an APK, copies
the host APK, modifies the real Git index, or includes private signing material.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
from xml.etree import ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT / "repos/DouAi"
BASELINE = "6e51a2a436d6a5aad6f831f6ca1cc1c46a95e5b6"
JAVA = Path(r"C:\jdk-21.0.9+10\bin\java.exe")
BUILD_TOOLS = Path(r"C:\Users\jaqenze\AppData\Local\Android\Sdk\build-tools\36.0.0")
RELEASE_TAG = "startup2"
VERSION_NAME = "0.2.67"
VERSION_CODE = 168


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def execute(arguments, **kwargs):
    return subprocess.run(arguments, check=True, capture_output=True, **kwargs).stdout


def dex_classes(data):
    """Read defined descriptors, rather than mistaking an API reference for embedding."""
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]
    if not data.startswith(b"dex\n"):
        raise ValueError("Unexpected DEX container")
    strings, types = u32(60), u32(68)
    for index in range(u32(96)):
        type_index = u32(u32(100) + 32 * index)
        string_index = u32(types + 4 * type_index)
        offset = u32(strings + 4 * string_index)
        while data[offset] & 128:
            offset += 1
        offset += 1  # Skip the final ULEB128 UTF-16 length byte.
        yield data[offset:data.index(0, offset)].decode("utf-8", "replace")


def inspect_apk(path, kind, destination):
    package = "io.github.yudigaga.douai." + ("legacy.adapt412" if kind == "legacy" else "adapt412")
    expected_version = VERSION_NAME + "-41.2-" + ("legacy" if kind == "legacy" else "unified") + "-" + RELEASE_TAG
    signature = execute([str(JAVA), "-jar", str(BUILD_TOOLS / "lib/apksigner.jar"),
                         "verify", "--verbose", "--print-certs", str(path)]).decode("utf-8", "replace")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in signature:
        raise ValueError("APK v2 signature did not verify")
    certificate = re.search(r"certificate SHA-256 digest:\s*([0-9a-f]+)", signature).group(1)
    if certificate != "115ed98e05242efdbf2d3fa2f0e2d6c254a379063964d5d1f087898648408fa3":
        raise ValueError("Signing certificate differs from the original local unified APK")
    badging = execute([str(BUILD_TOOLS / "aapt.exe"), "dump", "badging", str(path)]).decode("utf-8", "replace")
    for expected in (f"name='{package}'", f"versionCode='{VERSION_CODE}'", f"versionName='{expected_version}'"):
        if expected not in badging:
            raise ValueError(f"APK identity mismatch: {expected}")
    tree = execute([str(BUILD_TOOLS / "aapt.exe"), "dump", "xmltree", str(path), "AndroidManifest.xml"]).decode("utf-8", "replace")
    metadata = {}
    for block in re.split(r"E: meta-data", tree)[1:]:
        block = block.split("E:", 1)[0]
        name = re.search(r'android:name[^=]*="([^"]+)"', block)
        value = re.search(r'android:value[^=]*=(.*)', block)
        if name and value:
            metadata[name.group(1)] = value.group(1).strip()
    minimum = metadata.get("xposedminversion", "")
    api_number = "82" if kind == "legacy" else "102"
    if f'"{api_number}"' not in minimum and f"0x{int(api_number):x}" not in minimum:
        raise ValueError(f"Unexpected xposedminversion: {minimum}")
    for component in ("com.douai.module.ui.DesktopSettingsActivity", "com.douai.module.config.ModuleBridgeProvider", package + ".bridge"):
        if component not in tree:
            raise ValueError(f"Required Android component missing: {component}")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entries")
        modern_entries = [name for name in names if name.startswith("META-INF/xposed/")]
        classic_entry = "assets/xposed_init" in names
        expected_rules = (REPO / "app/src/main/assets/douyin-host-rules-41.2.0.tsv").read_bytes()
        if archive.read("assets/douyin-host-rules-41.2.0.tsv") != expected_rules:
            raise ValueError("Packaged rules differ from source")
        if kind == "legacy":
            if modern_entries or not classic_entry or archive.read("assets/xposed_init").strip() != b"com.douai.module.legacy.LegacyModuleEntry":
                raise ValueError("Legacy bootstrap is not isolated")
        else:
            if classic_entry or archive.read("META-INF/xposed/java_init.list").strip() != b"com.douai.module.DouAiModule":
                raise ValueError("Modern bootstrap is not isolated")
            if b"minApiVersion=102" not in archive.read("META-INF/xposed/module.prop"):
                raise ValueError("Wrong modern minimum API")
            if archive.read("META-INF/xposed/scope.list").strip() != b"com.ss.android.ugc.aweme":
                raise ValueError("Wrong modern scope")
        classes = set()
        for name in names:
            if re.fullmatch(r"classes\d*\.dex", name):
                for descriptor in dex_classes(archive.read(name)):
                    if descriptor in classes:
                        raise ValueError(f"Duplicate class definition: {descriptor}")
                    classes.add(descriptor)
        api_classes = [name for name in classes if name.startswith("Lio/github/libxposed/api/")]
        legacy_classes = [name for name in classes if name.startswith("Lcom/douai/module/legacy/")]
        has_api = bool(api_classes)
        has_legacy = bool(legacy_classes)
        if has_api != (kind == "legacy") or has_legacy != (kind == "legacy"):
            raise ValueError("API classes or classic bootstrap leaked into the wrong variant")
        if kind == "legacy" and not {"Lio/github/libxposed/api/XposedInterface;", "Lcom/douai/module/legacy/LegacyModuleEntry;"}.issubset(classes):
            raise ValueError("Required compatibility adapter definitions missing")
        if any(name.startswith("Lde/robv/android/xposed/") for name in classes):
            raise ValueError("Classic framework stub/native API must remain compileOnly")
        if "Lcom/ss/android/ugc/aweme/sharer/ui/SharePackage;" in classes:
            raise ValueError("A test host fixture must never be packaged in the module")
        if "Lcom/douai/module/ui/DesktopSettingsActivity;" not in classes or "Lcom/douai/module/config/ModuleBridgeProvider;" not in classes:
            raise ValueError("Startup settings/bridge implementation missing")
        features = sorted(name for name in classes if name.startswith("Lcom/douai/module/feature/") and "$" not in name)
    output = destination / f"Douyin-Unified-41.2-{RELEASE_TAG}-{kind}.apk"
    shutil.copy2(path, output)
    (destination / f"{kind}-signature.txt").write_text(signature, encoding="utf-8")
    (destination / f"{kind}-badging.txt").write_text(badging, encoding="utf-8")
    (destination / f"{kind}-manifest.txt").write_text(tree, encoding="utf-8")
    return {"file": output.name, "bytes": output.stat().st_size, "sha256": sha256(output),
            "package": package, "versionName": expected_version, "versionCode": VERSION_CODE,
            "signature_verified": True, "certificate_sha256": certificate,
            "bootstrap_verified": True, "framework_api": int(api_number), "embedded_api102": has_api,
            "classic_api_embedded": False, "class_definitions": len(classes), "api102_classes": len(api_classes),
            "legacy_implementation_classes": len(legacy_classes), "feature_classes": len(features),
            "feature_class_inventory_sha256": hashlib.sha256("\n".join(features).encode()).hexdigest()}


def main():
    global RELEASE_TAG, VERSION_NAME, VERSION_CODE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verification-log", required=True, type=Path)
    parser.add_argument("--profile", choices=("startup2", "download3"), default="startup2")
    parser.add_argument("--test-results-dir", type=Path,
                        default=REPO / "app/build/test-results/testDebugUnitTest")
    args = parser.parse_args()
    if args.profile == "download3":
        RELEASE_TAG, VERSION_NAME, VERSION_CODE = "download3", "0.2.68", 169
    log = args.verification_log.read_text(encoding="utf-8", errors="replace")
    if log.count("BUILD SUCCESSFUL") < 3 or "BUILD FAILED" in log:
        raise SystemExit("Need final successful test, dual build and dual lint runs")
    suites = []
    for file in sorted(args.test_results_dir.glob("TEST-*.xml")):
        suite = ET.parse(file).getroot()
        suites.append({"suite": suite.get("name"),
                       **{key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")},
                       "skipped_tests": [case.get("name") for case in suite.findall("testcase") if case.find("skipped") is not None]})
    if not suites or any(suite["failures"] or suite["errors"] for suite in suites):
        raise SystemExit("Current test results contain failures or are empty")
    destination = ROOT / ("dist/download-fixed-41.2" if RELEASE_TAG == "download3" else "dist/startup-fixed-41.2")
    destination.mkdir(parents=True, exist_ok=True)
    variants = {kind: inspect_apk(REPO / f"app/build/outputs/apk/{build_type}/app-{build_type}.apk", kind, destination)
                for kind, build_type in (("modern", "debug"), ("legacy", "legacy"))}
    if variants["modern"]["feature_class_inventory_sha256"] != variants["legacy"]["feature_class_inventory_sha256"]:
        raise ValueError("Modern and classic builds must contain the same enhancement classes")
    lint = {}
    for build_type in ("debug", "legacy"):
        report = REPO / f"app/build/reports/lint-results-{build_type}.xml"
        issues = ET.parse(report).getroot().findall("issue")
        errors = sum(issue.get("severity") in {"Error", "Fatal"} for issue in issues)
        if errors:
            raise SystemExit(f"{build_type} lint contains errors")
        lint[build_type] = {"completed": True, "errors": errors,
                            "warnings": sum(issue.get("severity") == "Warning" for issue in issues),
                            "excluded_detectors": ["BlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi"],
                            "localized_suppressions": ([{"id": "XposedInternalApi", "method": "LegacyModuleEntry.bindCompatibilityFramework",
                                "reason": "This legacy-only embedded framework adapter owns official API102 binding; no global suppression"}] if build_type == "legacy" else [])}
        for extension in ("xml", "txt", "html"):
            file = report.with_suffix("." + extension)
            if file.is_file():
                shutil.copy2(file, destination / file.name)
    files = execute(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=REPO).split(b"\0")
    names = sorted({raw.decode("utf-8") for raw in files if raw})
    source = destination / f"Douyin-Unified-41.2-{RELEASE_TAG}-source.zip"
    with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED, compresslevel=7) as archive:
        for name in names:
            path = REPO / name
            if not path.is_file():
                continue
            if path.suffix.lower() in {".apk", ".jks", ".keystore", ".key", ".p12", ".pem", ".pfx"}:
                raise ValueError(f"Unexpected APK/signing material in source list: {name}")
            archive.write(path, "DouAi/" + name)
        archive.write(Path(__file__), "delivery-tools/package_startup_fixed.py")
    # An isolated temporary index includes new files in the review patch while preserving staging.
    index = REPO / "work/startup-fix/delivery-temporary.index"
    if index.exists():
        raise ValueError("Temporary delivery index already exists; inspect before proceeding")
    environment = {**os.environ, "GIT_INDEX_FILE": str(index.resolve())}
    try:
        execute(["git", "read-tree", BASELINE], cwd=REPO, env=environment)
        execute(["git", "-c", "core.autocrlf=input", "add", "--", "."], cwd=REPO, env=environment)
        patch = execute(["git", "diff", "--cached", "--binary", BASELINE], cwd=REPO, env=environment)
        (destination / f"Douyin-Unified-41.2-{RELEASE_TAG}.patch").write_bytes(patch)
    finally:
        if index.is_file():
            index.unlink()
    shutil.copy2(args.verification_log, destination / "verification.log")
    shutil.copy2(REPO / "docs/unified-41.2.zh-CN.md", destination / "使用说明.md")
    shutil.copytree(args.test_results_dir, destination / "test-results", dirs_exist_ok=True)
    evidence = ROOT / "analysis/startup-fix"
    for name in ("boot-audit.md", "ui-diagnostics.md", "payload-verification.md", "payload-baseline.json", "payload-baseline.py"):
        if (evidence / name).is_file():
            shutil.copy2(evidence / name, destination / name)
    original_lint = REPO / "work/adapt-41.2.0/unified-build.log"
    if original_lint.is_file() and "PrivateApiDetector" in original_lint.read_text(encoding="utf-8", errors="replace"):
        shutil.copy2(original_lint, destination / "lint-parser-crash.log")
    if RELEASE_TAG == "download3":
        shutil.copy2(REPO / "docs/download-41.2-fix.zh-CN.md", destination / "下载修复说明.md")
        for name in ("source-model-fields.txt", "source-urlmodel-getters.txt", "shared-execute-rule-scan.tsv", "shared-execute-rule-scan.log", "shared-download-evidence.md",
                     "VerifyDownloadArtifact.java", "modern-artifact-verification.txt", "legacy-artifact-verification.txt"):
            file = ROOT / "analysis/download-fix" / name
            if file.is_file(): shutil.copy2(file, destination / name)
        for name in ("red-reproduction.log", "shared-route.log", "transfer-integration.log", "final-download-session-tests.log"):
            file = REPO / "work/download-fix" / name
            if file.is_file(): shutil.copy2(file, destination / name)
    adaptation_source = (REPO / "app/src/main/java/com/douai/module/scan/HostAdaptation.java").read_text(encoding="utf-8")
    manifest = {"date": "2026-11-13", "product": "抖音融合 41.2 · " + ("下载修复版3" if RELEASE_TAG == "download3" else "启动与设置修复版2"),
                "upstream": "https://github.com/yudigaga/DouAi", "upstream_commit": BASELINE,
                "target": {"package": "com.ss.android.ugc.aweme", "versionName": "41.2.0", "versionCode": 410200,
                           "sha256": "4d161a8eb4da14ee9931b87476e9f0de3847797006e3485e51c4cf497a9d5a99",
                           "manifest_sha256": "86d442f7f45098a2d7656bfdc4801481c6770ffb5ca535007c876700b4aa6d8d",
                           "payload_sha256": "b376fa7c53b08ea668b3138ca97503d7c9b34c3ecd6e41c3c2c57f71a26ccc76"},
                "variants": variants, "tests": {key: sum(suite[key] for suite in suites) for key in ("tests", "failures", "errors", "skipped")},
                "test_suites": suites, "lint": lint,
                "host_rules": {"count": sum(bool(line.strip()) and not line.startswith(("#", "id\t")) for line in
                    (REPO / "app/src/main/assets/douyin-host-rules-41.2.0.tsv").read_text(encoding="utf-8").splitlines()),
                    "canonical_fingerprint": re.search(r'PROFILE_412 = new Profile\(.*?"([0-9a-f]{64})"', adaptation_source, re.S).group(1)},
                "source": {"file": source.name, "sha256": sha256(source), "repository_files": len(names)},
                "device_tested": False, "adb_connected_devices": 0,
                "limitations": ["Only one variant/module should be active for the host scope", "Legacy bridge has no deoptimization or native API102 service",
                                "Changed Manifest/DEX payload is rejected; arbitrary LSPatch repackaging is not validated", "No connected phone runtime verification"]}
    provenance = args.test_results_dir / "provenance.json"
    if provenance.is_file():
        manifest["test_result_provenance"] = json.loads(provenance.read_text(encoding="utf-8"))
    (destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    outputs = [destination / value["file"] for value in variants.values()] + [source, destination / f"Douyin-Unified-41.2-{RELEASE_TAG}.patch"]
    (destination / "SHA256SUMS.txt").write_text("".join(f"{sha256(file)}  {file.name}\n" for file in outputs), encoding="utf-8")
    print(json.dumps({"directory": str(destination), "tests": manifest["tests"], "variants": variants, "lint": lint}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
