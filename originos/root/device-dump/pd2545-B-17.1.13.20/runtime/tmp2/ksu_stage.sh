#!/system/bin/sh
echo "[ksu] start uid=$(id -u) selinux=$(getenforce)"
if [ -x /data/local/tmp/stage2.sh ]; then exec /system/bin/sh /data/local/tmp/stage2.sh; fi
exec >/data/local/tmp/ksu.log 2>&1
setenforce 0 2>/dev/null
echo "[ksu] after setenforce: $(getenforce)"
grep -E '^(Cap|Seccomp)' /proc/self/status
PKG=me.weishu.kernelsu
APK=$(pm path "$PKG" 2>/dev/null | head -1 | cut -d: -f2)
KSUD=''
if [ -n "$APK" ]; then KSUD="$(dirname "$APK")/lib/arm64/libksud.so"; fi
[ -x "$KSUD" ] || KSUD=$(find /data/app -path '*/me.weishu.kernelsu*/lib/arm64/libksud.so' 2>/dev/null | head -1)
[ -x "$KSUD" ] || KSUD=/data/local/tmp/ksud
[ -x "$KSUD" ] || KSUD=/data/adb/ksu/bin/ksud
if [ ! -x "$KSUD" ]; then echo '[ksu] ksud missing'; exit 1; fi
echo "[ksu] ksud=$KSUD version=$("$KSUD" --version 2>&1 | head -1)"
"$KSUD" late-load --kmi android16-6.12 --allow-shell --package-name me.weishu.kernelsu
echo "[ksu] late-load rc=$?"
i=0
while [ $i -lt 50 ]; do
  grep -q '^kernelsu ' /proc/modules && break
  i=$((i+1)); sleep 0.1
done
if grep -q '^kernelsu ' /proc/modules; then echo KSU_LOADED; else echo KSU_MISSING; fi
echo "[ksu] selinux=$(getenforce) (leave as-is)"
