#!/bin/bash
# 等待设备授权 → 自动执行全套设备侧采集（只读）。
# 产物：tools/race-logs/ 下的 vr.ko、内核身份、SELinux 状态、CPU 拓扑 + preflight 完整输出。
set -u
export PATH="/d/D-code/tools/android/platform-tools:$PATH"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DUMP="$REPO/tools/race-logs"
LOG="$DUMP/collect.log"
mkdir -p "$DUMP"

echo "[watcher] 等待设备授权（最多 60 分钟）…" | tee -a "$LOG"
for i in $(seq 1 360); do
    st=$(adb devices 2>/dev/null | awk 'NR==2{print $2}' | tr -d '\r')
    if [ "$st" = "device" ]; then
        echo "[watcher] $(date '+%H:%M:%S') 已授权，开始采集" | tee -a "$LOG"
        break
    fi
    sleep 10
done
[ "$st" = "device" ] || { echo "[watcher] 超时未授权，退出" | tee -a "$LOG"; exit 1; }

# 1) 完整预检（含 --collect 拉 vr.ko）
bash "$REPO/tools/phone_preflight.sh" --collect 2>&1 | tee -a "$LOG"

# 2) vr.ko 静态分析（若拉到了）
if [ -f "$DUMP/vr.ko" ]; then
    echo "[watcher] 运行 analyze_vr.py" | tee -a "$LOG"
    python "$REPO/tools/analyze_vr.py" --input "$DUMP/vr.ko" 2>&1 | tee -a "$LOG"
else
    echo "[watcher] 未取到 vr.ko，跳过分析" | tee -a "$LOG"
fi

# 3) 补充采集：模块列表 / init.rc 中的服务定义（dumpstatez 相关，只读）
{
    echo; echo "==== 补充: vendor modules ===="
    adb shell ls -la /vendor/lib/modules/ 2>/dev/null | tr -d '\r'
    echo; echo "==== 补充: dumpstate 相关服务 ===="
    adb shell grep -rn 'dumpstate\|bugreportd' /system/etc/init/ /vendor/etc/init/ 2>/dev/null | tr -d '\r' | head -30
    echo; echo "==== 补充: 当前进程里的 dumpstate/bugreport ===="
    adb shell ps -A 2>/dev/null | tr -d '\r' | grep -iE 'dumpstate|bugreport' || echo "(无)"
    echo; echo "==== 补充: uname -a / build ===="
    adb shell uname -a | tr -d '\r'
    adb shell getprop ro.build.fingerprint | tr -d '\r'
} 2>&1 | tee -a "$LOG"

echo "[watcher] 采集完成。全部输出在 $LOG" | tee -a "$LOG"
