#!/usr/bin/env bash
# Build and run the no-boot-image perf/kprobe kernel address probe.
# The probe is read-only: kprobes attach, sample IPs are read, no memory writes.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-D:/D-code/tools/android/platform-tools/adb.exe}"
NDK="${ANDROID_NDK_ROOT:-${NDK:-D:/D-code/tools/android/android-ndk-r29}}"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android35-clang"
BIN="$HERE/tools/build/perf_kprobe"
REMOTE="/data/local/tmp/perf_kprobe"
LOG_DIR="$HERE/logs"
LOG="$LOG_DIR/perf-kprobe-$(date +%Y%m%d-%H%M%S).log"

[ -f "$CC" ] || CC="$CC.cmd"
[ -f "$CC" ] || { echo "missing compiler: $CC" >&2; exit 2; }
mkdir -p "$(dirname "$BIN")" "$LOG_DIR"

if [ ! -f "$BIN" ] || [ "$HERE/tools/perf_kprobe.c" -nt "$BIN" ]; then
    "$CC" -O2 -g0 -D_GNU_SOURCE -o "$BIN" "$HERE/tools/perf_kprobe.c" \
        -static -Wl,--strip-all || exit 2
fi
echo "built: $BIN"

adb_() { if [ -n "${1:-}" ] && [ "$1" = "--serial" ]; then shift 2; fi; "$ADB" "$@"; }
export MSYS2_ARG_CONV_EXCL='/data'
"$ADB" devices -l
"$ADB" push "$BIN" "$REMOTE" || exit 2
"$ADB" shell chmod 755 "$REMOTE" || exit 2
{
    echo "=== perf_kprobe $(date -Is) ==="
    "$ADB" shell "$REMOTE" 2>&1
    echo "=== direct attrs ==="
    "$ADB" shell 'cat /sys/bus/event_source/devices/kprobe/type; cat /sys/bus/event_source/devices/kprobe/format/func; cat /sys/bus/event_source/devices/kprobe/format/offset' 2>&1
} 2>&1 | tee "$LOG"

echo
echo "log: $LOG"
echo "No kernel writes. Compare PROBE_IP values with the certified r59 symbols:"
echo "  __traceiter_sys_exit 0xffffffc08008b6d0"
echo "  commit_creds         0xffffffc08036a1d4"
echo "  posix_cpu_timer_del  0xffffffc080519334"
