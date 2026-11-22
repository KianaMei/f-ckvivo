#!/usr/bin/env bash
# Build the PD2545D CVE-2026-64560 payload with the Android NDK.
# The output is a static AArch64 ELF with no PT_INTERP.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NDK="${ANDROID_NDK_ROOT:-${NDK:-D:/D-code/tools/android/android-ndk-r29}}"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android35-clang"
OUT="${1:-$HERE/build/cve-2026-64560-pd2545}"

[ -f "$CC" ] || CC="$CC.cmd"
[ -f "$CC" ] || { echo "missing compiler: $CC" >&2; exit 2; }
[ -f "$HERE/cve-2026-64560-pd2545.c" ] || { echo "missing source" >&2; exit 2; }

mkdir -p "$(dirname "$OUT")"
"$CC" -O2 -g0 -D_GNU_SOURCE -I"$HERE" \
    -o "$OUT.new" "$HERE/cve-2026-64560-pd2545.c" \
    -static -pthread -Wl,--strip-all
mv -f "$OUT.new" "$OUT"
sha256sum "$OUT"
