#!/system/bin/sh
PROG=/data/local/tmp/ksu.log
exec >>"$PROG" 2>&1
PKG=me.weishu.kernelsu
export RUST_LOG=info
export PATH=/system/bin:/system/xbin:/vendor/bin
echo "[fix] start pkg=$PKG uid=$(id -u) selinux=$(getenforce) kernel=$(uname -r)"
setenforce 0 2>/dev/null
echo "[fix] modules_disabled=$(cat /proc/sys/kernel/modules_disabled 2>/dev/null)"
KSUD=
for f in $(find /data/app -type f -name 'libksud*.so' 2>/dev/null | grep -F "$PKG" | head -n 1) $(find /data/app -type f -name 'libksud*.so' 2>/dev/null | head -n 1) /data/adb/ksu/bin/ksud /data/local/tmp/ksud; do
  [ -f "$f" ] || continue
  [ -x "$f" ] || chmod 755 "$f" 2>/dev/null
  if [ -x "$f" ]; then KSUD="$f"; break; fi
done
echo "[fix] ksud=$KSUD"
if [ -z "$KSUD" ]; then echo KSUD_NOT_FOUND; echo KSU_MISSING; exit 2; fi
_ksu_install() {
  "$KSUD" install < /dev/null 2>&1
}
"$KSUD" --version 2>&1 | head -n 1
if grep -q '^kernelsu ' /proc/modules 2>/dev/null; then echo KSU_LOADED; exit 0; fi
echo '[fix] --- ksud install ---'
out=$(_ksu_install 2>&1); rc=$?
[ -n "$out" ] && echo "$out" | tail -n 15
echo "[fix] install rc=$rc"
ls -l /data/adb/ksu 2>&1 | head -n 8
echo "[fix] ko: $(find /data/adb/ksu -name '*.ko' 2>/dev/null | head -n 3 | tr '\n' ' ')"
for KMI in '' android15-6.6 android14-6.1 android13-5.15 android12-5.10; do
  echo "[fix] --- late-load kmi=${KMI:-auto} ---"
  if [ -n "$KMI" ]; then
    out=$("$KSUD" late-load --kmi "$KMI" --allow-shell --package-name "$PKG" 2>&1); rc=$?
  else
    out=$("$KSUD" late-load --allow-shell --package-name "$PKG" 2>&1); rc=$?
  fi
  [ -n "$out" ] && echo "$out" | tail -n 12
  echo "[fix] rc=$rc"
  sleep 3
  if grep -q '^kernelsu ' /proc/modules 2>/dev/null; then echo KSU_LOADED; exit 0; fi
done
echo KSU_MISSING
echo '[fix] --- modules ---'; grep kernelsu /proc/modules 2>&1
echo '[fix] --- ksu dir ---'; ls -l /data/adb/ksu 2>&1 | head -n 5
echo '[fix] --- dmesg ---'; dmesg | tail -n 20 2>&1
exit 3
