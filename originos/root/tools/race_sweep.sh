#!/bin/bash
# race 核对扫描器 —— 43499 multicast 路线的「CPU 核心对」校准工具。
#
# 背景：race 需要两个线程同 L2 簇。MT6993 实测拓扑（2026-10-17）：
#   cluster0 {0,1,2,3} @2.7GHz   cluster1 {4,5,6} @3.5GHz   cluster2 {7} 单核不可配对
# 默认 (1,0) 是上游 PD2502 的校准值。本脚本用 --race-cpu 在不重编译的前提下
# 扫描同簇候选对，统计 chain_hits / bit4_hits / marker。
#
# 用法：
#   ./tools/race_sweep.sh                    # 默认 6 个候选点
#   ./tools/race_sweep.sh "1,0 5,4 6,5"      # 自定义对列表
#
# 输出：tools/device-dump/race_<main>_<trigger>.log 每对一个；
#       汇总表打印到 stdout 并追加到 tools/device-dump/race_sweep_results.txt

set -u
export PATH="/d/D-code/tools/android/platform-tools:$PATH"
export MSYS_NO_PATHCONV=1
BIN=/data/local/tmp/exploit_xf6_v4
DUMP="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/device-dump"
mkdir -p "$DUMP"
RESULTS="$DUMP/race_sweep_results.txt"

PAIRS="${1:-1,0 0,1 2,3 5,4 4,5 6,4}"

# 安全闸：无论正常结束、被打断还是 adb 掉线，都要清掉设备上的残留进程。
# 教训（2026-10-17）：首次扫描因 adb 会话中断留下 803 个 fork 出来的孤儿进程，
# 把 load 顶到 3000，导致后续 kernelsnitch 时序测量失效、运行被 SIGKILL。
cleanup() {
    adb shell 'for p in $(ps -A -o PID,NAME 2>/dev/null | grep exploit_xf6_v4 | awk "{print \$1}"); do kill -9 $p 2>/dev/null; done' >/dev/null 2>&1
}
trap cleanup EXIT INT TERM

# 噪声闸：kernelsnitch 是时序测量，系统繁忙时必然失败（这不是 race 的结论，
# 只是"这次没测到"）。负载不过关就等，等不到就跳过该点，绝不硬跑。
wait_quiet() {
    for _ in $(seq 1 40); do
        l=$(adb shell "cat /proc/loadavg" 2>/dev/null | cut -d' ' -f1 | cut -d. -f1 | tr -d '\r')
        [ -z "$l" ] && { echo "  adb 掉线，跳过"; return 1; }
        [ "$l" -le 8 ] && return 0
        sleep 15
    done
    echo "  负载始终 >8，跳过该点（噪声下数据无意义）"
    return 1
}

printf '%-8s %-7s %-7s %-7s %-8s %-9s %s\n' "pair" "tries" "reclaim" "chain" "bit4" "elapsed" "result" | tee -a "$RESULTS"

for p in $PAIRS; do
  m=${p%,*}; t=${p#*,}
  wait_quiet || continue
  log="$DUMP/race_${m}_${t}.log"
  start=$(date +%s)
  adb shell "$BIN --probe-epoll --race-cpu $m,$t" > "$log" 2>&1
  rc=$?
  elapsed=$(( $(date +%s) - start ))
  # 汇总 RACE_SUMMARY 各 delay 桶的计数（无 bc，用 awk）
  sum_field() { grep -oE "$1=[0-9]+" "$log" | cut -d= -f2 | awk '{s+=$1} END{print s+0}'; }
  tries=$(sum_field tries); reclaim=$(sum_field reclaim_hits)
  chain=$(sum_field chain_hits); bit4=$(sum_field bit4_hits)
  verdict=$(grep -m1 -oE 'RESULT (PASS|FAIL)[^ ]*' "$log" | head -1)
  printf '%-8s %-7s %-7s %-7s %-8s %-9s %s rc=%d\n' \
    "$p" "${tries:-0}" "${reclaim:-0}" "${chain:-0}" "${bit4:-0}" "${elapsed}s" "${verdict:-NO_RESULT}" "$rc" | tee -a "$RESULTS"
done
echo "完整日志: $DUMP/race_*.log"
