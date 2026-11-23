#!/system/bin/sh
# cleanup.sh —— KSU 起来后的收尾：杀残留 exploit 进程、停 rootd、清临时文件
# 用法：sh cleanup.sh          （需要已能 su，或仍处于临时 root）
D=/data/local/tmp

echo "[1/4] 杀残留 exploit 进程（避免 for(;;) pause 空转烧 CPU）"
pkill -9 -f 'libroot.so exploit' 2>/dev/null
pkill -9 -f 'libroot.so calibrate' 2>/dev/null

echo "[2/4] 确认 su 可用后停掉 rootd（KSU 的 su 会接管）"
SUOK=0
if su -c 'id -u' 2>/dev/null | grep -qx 0; then SUOK=1; fi
if [ "$SUOK" = "1" ]; then
  su -c 'pkill -9 -f "libroot.so rootd" 2>/dev/null; rm -f /data/local/tmp/pc98260-root.sock'
  echo "  rootd 已停止"
else
  echo "  su 不可用，保留 rootd"
fi

echo "[3/4] 清理临时文件"
rm -f $D/att_*.txt $D/rc_logic.log $D/rc_cred.log $D/rootout2.txt $D/rootprobe.txt \
      $D/rootd.log $D/cal.txt $D/slide_cache.txt $D/sel_done.txt $D/att_seq.txt \
      $D/zs*.log $D/zs*.sh $D/p1.log $D/zb.log $D/ksym*.txt $D/sym_addr.txt \
      $D/ksu_v*.ko $D/ksu_live*.ko $D/ksu-ready.ko $D/ksym_real.txt $D/ksud_new \
      $D/ksud_new.* $D/zzk $D/zzk2 2>/dev/null
rm -rf $D/cve98260 2>/dev/null
echo "[4/4] 完成"
echo "--- 剩余 ---"
ls $D 2>/dev/null | head -20
echo "--- 当前 su ---"
su -c 'id' 2>/dev/null | head -1
