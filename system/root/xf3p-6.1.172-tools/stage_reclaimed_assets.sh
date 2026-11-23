#!/system/bin/sh
# Stage assets for the reclaimed poc_tmp_root flow on PD2337 / 6.1.172.
# This script only copies and verifies files. It does not run the exploit.
D=/data/local/tmp
STAGE=/data/local/tmp/reclaimed
BASE_KO=$STAGE/ksu-final.ko
PATCH_TABLE=$STAGE/ksu_patch_6.1.172.txt
KSUD=$STAGE/ksud

say() { echo "$*"; }
fail() { say "!!!!! $*"; exit 1; }

[ -d "$STAGE" ] || fail "missing staging directory: $STAGE"
[ -s "$BASE_KO" ] || fail "missing $BASE_KO"
[ -s "$PATCH_TABLE" ] || fail "missing $PATCH_TABLE"
[ -s "$KSUD" ] || fail "missing $KSUD"
[ -x "$D/poc_tmp_root" ] || fail "missing $D/poc_tmp_root"
[ -x "$D/probe" ] || fail "missing $D/probe"
[ -f "$D/rk.sh" ] || fail "missing $D/rk.sh"

case "$(uname -r)" in
  *6.1.172-android14-11-maybe-dirty*) ;;
  *) fail "wrong kernel, refusing to stage" ;;
esac

grep -q '^kernelsu ' /proc/modules 2>/dev/null && fail "kernelsu already loaded"

POC_SHA=$(toybox sha256sum "$D/poc_tmp_root" 2>/dev/null | awk '{print $1}')
[ "$POC_SHA" = 369d2f78cf7d4692c165b0e888e3759d577de4ec831800e5705d997c727ea602 ] || fail "poc sha mismatch"

BASE_SHA=$(toybox sha256sum "$BASE_KO" 2>/dev/null | awk '{print $1}')
[ "$BASE_SHA" = 8cd5a11d26707a320660fc664a26c6301a11b5e6f0adca5369ca15e931ec53a1 ] || fail "base KO sha mismatch"

TABLE_SHA=$(toybox sha256sum "$PATCH_TABLE" 2>/dev/null | awk '{print $1}')
TABLE_LINES=$(wc -l < "$PATCH_TABLE" 2>/dev/null | tr -d ' ')
[ "$TABLE_LINES" = 226 ] || fail "patch table line count mismatch: $TABLE_LINES"

# poc_tmp_root hard-codes these paths for its internal ksu_try().
cp -f "$PATCH_TABLE" "$D/ksu_patch.txt" || fail "copy patch table failed"
cp -f "$BASE_KO" "$D/kernelsu.ko" || fail "copy base KO failed"
cp -f "$KSUD" "$D/ksud" || fail "copy ksud failed"
chmod 644 "$D/ksu_patch.txt" "$D/kernelsu.ko" 2>/dev/null
chmod 755 "$D/ksud" 2>/dev/null

say "STAGED poc_sha=$POC_SHA base_sha=$BASE_SHA table_sha=$TABLE_SHA table_lines=$TABLE_LINES"
say "files are ready; no exploit, kernel write, insmod, install, or kill was performed"
