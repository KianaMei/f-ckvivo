#!/usr/bin/env bash
# 只读内存/后台采样：不写设备、不改设置、不发广播。
# 用法: ./memdump.sh [输出目录]        （默认当前目录下 memdump-<时间>）
# 依赖: adb（或 ADB=/path/to/adb）、设备已授权；root 节点用 su 读，失败也能继续。
set -u
ADB="${ADB:-adb}"
OUT="${1:-./memdump-$(date +%Y%m%d-%H%M)}"
mkdir -p "$OUT"

sh_() { $ADB shell "$1" 2>&1; }
su_() { $ADB shell "su -c '$1'" 2>&1; }

{
  echo "## device"
  sh_ 'getprop ro.product.model; getprop ro.build.display.id; getprop ro.board.platform; cat /proc/uptime'
  echo; echo "## meminfo"
  sh_ 'dumpsys meminfo | tail -9'
  echo; echo "## /proc/meminfo"
  sh_ 'grep -E "^(MemTotal|MemFree|MemAvailable|Buffers|Cached|AnonPages|Mapped|Shmem|Slab|SReclaimable|SUnreclaim|KernelStack|PageTables|VmallocUsed|CmaTotal)" /proc/meminfo'
} > "$OUT/memory.txt"

{
  echo "## zram"
  su_ 'cat /sys/block/zram0/disksize; cat /sys/block/zram0/mm_stat; cat /sys/block/zram0/backing_dev; cat /sys/block/zram0/writeback_limit_enable; cat /sys/block/zram0/writeback_limit'
  echo; echo "## zram_wb/stat_readable"
  su_ 'cat /sys/block/zram0/zram_wb/stat_readable'
  echo; echo "## /proc/swaps"
  su_ 'cat /proc/swaps'
  echo; echo "## vm"
  su_ 'for f in swappiness page-cluster watermark_scale_factor min_free_kbytes; do echo $f=$(cat /proc/sys/vm/$f); done'
  echo; echo "## ufs life (JEDEC 0x01 最好，>0x05 时 vivo 会关 writeback)"
  su_ 'cat /sys/ufs/life_time_a; cat /sys/ufs/life_time_b'
  echo; echo "## props"
  su_ 'getprop persist.vendor.vivo.zramwb.size; getprop persist.vendor.vivo.zramwb.enable; getprop ro.vivo.zramwb.default'
} > "$OUT/zram.txt"

sh_ 'dumpsys meminfo | sed -n "/Total PSS by process/,/Total PSS by OOM/p" | head -31' > "$OUT/top-pss.txt"

{
  echo "## doze / freezer / phantom / standby"
  sh_ 'dumpsys deviceidle whitelist | grep "^user," | wc -l'
  sh_ 'settings get global cached_apps_freezer'
  sh_ 'settings get global settings_enable_monitor_phantom_procs'
  for p in "$@"; do :; done
} > "$OUT/policy.txt" 2>/dev/null

{
  echo "## single-cleaner timeline（改包名自行替换）"
  for p in com.twitter.android com.xingin.xhs tv.danmaku.bili com.ss.android.ugc.aweme; do
    echo "--- $p"
    sh_ "dumpsys activity exit-info $p | grep -E 'timestamp=|description=' | paste - - | grep -E 'single-cleaner|low memory|pruner' | head -6"
  done
} > "$OUT/exit-info.txt"

echo "written: $OUT"
wc -c "$OUT"/*.txt
