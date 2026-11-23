#!/system/bin/sh
# phaseA.sh —— 只负责拿到 root 并输出 slide（主机据此算地址）
D=/data/local/tmp
cd $D || exit 1
ok() { [ "$(./libroot.so su -c 'id -u' 2>/dev/null | tail -1)" = "0" ]; }
if ! ok; then
  nohup sh $D/attempt_fix.sh >> $D/phaseA.log 2>&1 &
  i=0; while [ $i -lt 240 ]; do ok && break; sleep 2; i=$((i+1)); done
fi
if ok; then
  echo "SLIDE=$(sed -n 2p $D/slide_cache.txt)"
  echo "BOOT=$(cat /proc/sys/kernel/random/boot_id)"
else
  echo FAIL
fi
