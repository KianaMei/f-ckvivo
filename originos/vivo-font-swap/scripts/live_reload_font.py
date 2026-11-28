#!/usr/bin/env python3
"""vivo OriginOS 7 zero-reboot live font reload & offline TTF validator.

Reads the live global Configuration Parcel via `service call activity 54`
(IActivityManager.getConfiguration), bumps ONLY the `typefaceChanged` float32
field (word[-2]) by +step, and sends it back via `service call activity 131`
(IActivityManager.updatePersistentConfiguration).

Leaves zero temporary files on the device and requires no reboot/soft-reboot.
Default mode is read-only (--dry-run); pass --apply only after user approval.
"""

from __future__ import annotations

import argparse
import re
import struct
import subprocess
import sys
from pathlib import Path

TX_GET_CONFIGURATION = 54
TX_UPDATE_PERSISTENT_CONFIGURATION = 131

SAMPLE_CHARS = (
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    "!@#$%^&*()-_=+[]{}|;:,.<>?/~"
    "！@#￥%……&*（）—+【】、；：‘’“”，。《》？"
    "设置显示与亮度字体大小网络蓝牙电池存储安全隐私关于手机系统更新"
)


def adb_su(cmd: str, serial: str | None) -> str:
    base = ["adb"]
    if serial:
        base += ["-s", serial]
    base += ["shell", f'su -c "{cmd}"']
    proc = subprocess.run(base, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(
            f"adb command failed (rc={proc.returncode}): {cmd}\n"
            f"stdout: {proc.stdout}\nstderr: {proc.stderr}"
        )
    return proc.stdout


def parse_parcel_words(raw: str) -> list[int]:
    words: list[int] = []
    for line in raw.splitlines():
        m = re.match(r"\s*0x[0-9a-fA-F]+:\s+((?:[0-9a-fA-F]{8}\s+)+)", line)
        if m:
            for tok in m.group(1).split():
                words.append(int(tok, 16))
    return words


def u32_to_f32(u: int) -> float:
    return struct.unpack("<f", struct.pack("<I", u))[0]


def f32_to_u32(f: float) -> int:
    return struct.unpack("<I", struct.pack("<f", f))[0]


def u32_to_s32(u: int) -> int:
    return struct.unpack("<i", struct.pack("<I", u))[0]


def validate_ttf(ttf_path: Path) -> int:
    if not ttf_path.is_file():
        print(f"ERROR: file not found: {ttf_path}", file=sys.stderr)
        return 2
    try:
        from fontTools.ttLib import TTFont
    except ImportError:
        print(
            "ERROR: fontTools not installed (pip install fonttools)",
            file=sys.stderr,
        )
        return 2

    ft = TTFont(str(ttf_path))
    tables = [k for k in ft.keys() if k != "GlyphOrder"]
    for tag in tables:
        _ = ft[tag]

    cmap = ft.getBestCmap() or {}
    missing = [ch for ch in SAMPLE_CHARS if ord(ch) not in cmap]

    names: dict[int, str] = {}
    for rec in ft["name"].names:
        if rec.nameID in (1, 2, 4, 5, 6) and rec.nameID not in names:
            try:
                names[rec.nameID] = rec.toUnicode()
            except Exception:
                pass

    print(f"File       : {ttf_path} ({ttf_path.stat().st_size:,} bytes)")
    print(f"Name       : family={names.get(1)!r} style={names.get(2)!r} ps={names.get(6)!r}")
    print(f"Version    : {names.get(5)!r} (head.rev={ft['head'].fontRevision:.3f})")
    print(f"Glyphs     : maxp={ft['maxp'].numGlyphs}, best_cmap={len(cmap)}, fvar={'fvar' in ft}")
    print(f"Tables OK  : {', '.join(tables)}")
    if missing:
        print(f"WARNING    : missing {len(missing)} sample chars: {''.join(missing)}")
        return 1
    print("Coverage   : ALL required ASCII / punctuation / CN settings chars present")
    return 0


def get_core_pids(serial: str | None) -> str:
    return adb_su("pidof zygote64 lspd system_server", serial).strip()


def reload_font(serial: str | None, step: float, apply: bool) -> int:
    pids_before = get_core_pids(serial)
    print(f"Core PIDs (zygote64 / lspd / system_server): {pids_before}")

    vfonts_ls = adb_su("ls -laZ /data/vfonts/", serial).strip()
    print("=== /data/vfonts/ ===")
    print(vfonts_ls)

    raw = adb_su(f"service call activity {TX_GET_CONFIGURATION}", serial)
    words = parse_parcel_words(raw)
    if len(words) < 60:
        raise RuntimeError(f"Unexpected getConfiguration parcel length: {len(words)} words\n{raw}")
    if words[0] != 0:
        raise RuntimeError(f"getConfiguration returned non-zero status: {hex(words[0])}\n{raw}")
    if words[1] != 1:
        raise RuntimeError(f"getConfiguration returned null Configuration marker: {hex(words[1])}")

    font_scale = u32_to_f32(words[2])
    mcc = words[3]
    mnc = words[4]
    night_lvl = u32_to_s32(words[-4])
    ss_mode = u32_to_s32(words[-3])
    old_tf_u32 = words[-2]
    old_tf = u32_to_f32(old_tf_u32)
    ea_mode = u32_to_s32(words[-1])

    print("=== Decoded Live Configuration (sanity anchors) ===")
    print(
        f"words={len(words)} (payload={len(words)-1}) | "
        f"fontScale={font_scale:.2f} mcc={mcc} mnc={mnc} | "
        f"nightModeLevel={night_lvl} ssMode={ss_mode} "
        f"typefaceChanged={old_tf:.4f} ({hex(old_tf_u32)}) eaMode={ea_mode}"
    )

    if not (0.2 <= font_scale <= 3.0):
        raise RuntimeError(f"Sanity check failed: fontScale={font_scale}")
    if not (0.0 <= old_tf < 100.0):
        raise RuntimeError(f"Sanity check failed: typefaceChanged={old_tf} ({hex(old_tf_u32)})")
    if ea_mode not in (0, 1, 2):
        raise RuntimeError(f"Sanity check failed: eaMode={ea_mode}")

    new_tf = round(old_tf + step, 4)
    if new_tf == old_tf:
        raise RuntimeError("New typefaceChanged equals old value; pick a larger --step")
    new_tf_u32 = f32_to_u32(new_tf)
    print(f"Planned update: typefaceChanged {old_tf:.4f} ({hex(old_tf_u32)}) -> {new_tf:.4f} ({hex(new_tf_u32)})")

    if not apply:
        print("[DRY-RUN] No write performed. Pass --apply after user confirmation to trigger live reload.")
        return 0

    payload = words[1:]  # drop reply status word[0]
    payload[-2] = new_tf_u32
    args = " ".join(f"i32 {u32_to_s32(w)}" for w in payload)
    res = adb_su(f"service call activity {TX_UPDATE_PERSISTENT_CONFIGURATION} {args}", serial).strip()
    print("=== updatePersistentConfiguration (tx 131) result ===")
    print(res)

    reply_words = parse_parcel_words(res)
    single_line_ok = bool(re.search(r"Parcel\(\s*00000000\b", res))
    if not ((reply_words and reply_words[0] == 0) or single_line_ok):
        raise RuntimeError(f"tx 131 returned exception: {res}")

    pids_after = get_core_pids(serial)
    print(f"Core PIDs after reload: {pids_after}")
    if pids_before != pids_after:
        print("WARNING: core PIDs changed! Inspect logcat immediately.", file=sys.stderr)
        return 1

    print("OK: system_server live font reload triggered cleanly with zero reboot.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate TTF and trigger zero-reboot vivo OriginOS 7 font hot-reload."
    )
    parser.add_argument("-s", "--serial", default=None, help="ADB device serial (optional if single device)")
    parser.add_argument("--validate-ttf", type=Path, help="Offline validate a local .ttf/.otf file with fontTools")
    parser.add_argument("--dry-run", action="store_true", help="Read-only check of live Configuration parcel (default)")
    parser.add_argument("--apply", action="store_true", help="Send tx 131 updatePersistentConfiguration (requires user approval)")
    parser.add_argument("--step", type=float, default=0.01, help="Increment for typefaceChanged (default: 0.01)")
    args = parser.parse_args()

    if args.validate_ttf:
        rc = validate_ttf(args.validate_ttf)
        if not args.dry_run and not args.apply:
            return rc
        if rc > 1:
            return rc

    return reload_font(serial=args.serial, step=args.step, apply=args.apply)


if __name__ == "__main__":
    raise SystemExit(main())
