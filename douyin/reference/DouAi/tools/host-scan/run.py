"""Compile and run the production HostScanner as a read-only desktop tool.

All APKs are parsed as ZIP/DEX data. Host classes are never loaded or executed.
Supply dependency JARs explicitly; no dependency is downloaded by this script.
"""
import argparse
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("rules", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--dexlib", type=Path, required=True)
    parser.add_argument("--guava", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--require-unique", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    classes = root / "work" / "host-scan-cli" / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    dependencies = [args.dexlib.resolve(), args.guava.resolve(), args.android_jar.resolve()]
    for item in [args.apk, args.rules, *dependencies]:
        if not item.is_file():
            parser.error(f"Required input is missing: {item}")
    import os
    classpath = os.pathsep.join(str(item) for item in [classes, *dependencies])
    sources = root / "app/src/main/java/com/douai/module/scan"
    names = ["HostAdaptation", "HostRule", "HostRuleSet", "HostCandidate", "RuleScanResult",
             "HostScanReport", "HostInstallation", "HostScanCache", "StartupHostCache", "HostScanner"]
    suffix = ".exe" if os.name == "nt" else ""
    subprocess.run([str(args.java_home / "bin" / f"javac{suffix}"), "-encoding", "UTF-8", "-cp", classpath,
                    "-d", str(classes), *[str(sources / f"{name}.java") for name in names],
                    str(Path(__file__).with_name("HostScanCli.java"))], check=True)
    command = [str(args.java_home / "bin" / f"java{suffix}"), "-Xmx1200m", "-cp", classpath,
               "com.douai.module.scan.HostScanCli", str(args.apk.resolve()), str(args.rules.resolve()), str(output)]
    if args.require_unique:
        command.append("--require-unique")
    return subprocess.run(command).returncode


if __name__ == "__main__":
    raise SystemExit(main())
