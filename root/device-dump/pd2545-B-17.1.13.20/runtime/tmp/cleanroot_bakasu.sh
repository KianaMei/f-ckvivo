#!/system/bin/sh
# ============================================================
#  cleanroot_bakasu.sh
#  用 kiroot 同款「干净收尾」参数，加载 BakaSU(ResukiSU) 内核模块
#
#  背景（2026-11-12 本地实证）：
#   - poc_tmp_root 与 kiroot/Rift 完全同源（sha256 369d2f78…，9130848 B）
#   - Rift run98260.sh 的 orange7 段传了 POC_* 全套，唯独缺
#     POC_RMCLEAN=1 / POC_DEEP_MB=1300（kiroot dex 里明确传了这两个）
#   - poc 从磁盘读 /data/local/tmp/kernelsu.ko 并自行 patch+insmod
#     （不在二进制内嵌模块；二进制内可见 INITBUF/INIT_MODULE/KSU patched）
#   - poc 会用到 /data/local/tmp/ksud 作为 userland 源
#     （二进制内可见 COPY %s -> %s 与 /data/adb/ksu/bin 痕迹）
#     故本脚本把 BakaSU 的 libksud.so 同时放到 ksud 路径，
#     保证整条链都是 BakaSU，不混入 weishu。
#
#  前置条件（缺一不可）：
#   1) 设备刚重启过：/proc/modules 无 kernelsu、ps 无 t28k
#      （同名模块不能热换；park 的 t28k 不能 kill，只能重启）
#   2) BakaSU(com.resukisu.resukisu) 已安装
#   3) staging 目录含：poc_tmp_root（必须）、ksu_patch.txt（必须）、
#      kiro-hello.ko（可选）
#
#  用法（设备上，shell 用户，无需 root）：
#   sh /data/local/tmp/cleanroot_bakasu.sh [staging_dir]
#   sh /data/local/tmp/cleanroot_bakasu.sh --check [staging_dir]   # 只检查不执行
#   默认 staging=/data/local/tmp/cleanroot
#
#  推送示例（PC 侧 Git Bash，注意 MSYS 路径转换）：
#   export MSYS2_ARG_CONV_EXCL='/data'
#   adb push poc_tmp_root ksu_patch.txt kiro-hello.ko /data/local/tmp/cleanroot/
#   adb push cleanroot_bakasu.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/cleanroot_bakasu.sh --check
#
#  风险（务必读）：
#   - 本机 poc 路在 Rift 原 env 下曾 3/3 kernel panic；kiroot 同二进制
#     +RMCLEAN 成功过，但「RMCLEAN 就是成功原因」未被证明
#   - BakaSU 515952 B 模块 + poc 内置 patch 的组合本机未验证
#   - 若 panic：用户数据不受影响，但 /data/local/tmp 近期写入会被清零，
#     复跑前必须重推并校验 sha256
#   - 本脚本不做：rmmod / kill / 写分区 / 装 APK / 改系统属性
# ============================================================

TMP=/data/local/tmp
LOG=$TMP/cleanroot.log
PKG=com.resukisu.resukisu
KMI=android16-6.12

POC_SIZE=9130848
POC_SHA=369d2f78cf7d4692c165b0e888e3759d577de4ec831800e5705d997c727ea602
MIN_AVAIL_KB=2097152   # 2GB：POC_DEEP_MB=1300 + PRESSURE_MB=700 的余量

CHECK=0
[ "${1:-}" = "--check" ] && { CHECK=1; shift; }
STAGE=${1:-$TMP/cleanroot}

say() {
    echo "$*"
    echo "$*" >> "$LOG" 2>/dev/null
}
die() {
    say "!!!!! $*"
    exit 1
}

say "===== cleanroot_bakasu $(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null) ====="
say "staging=$STAGE  check=$CHECK"

# ---------- 0. 前置闸门 ----------
if grep -q '^kernelsu ' /proc/modules 2>/dev/null; then
    die "已有 kernelsu 模块在运行（同名模块不能热换），请重启后再跑"
fi
if ps -A 2>/dev/null | grep -w t28k >/dev/null; then
    die "检测到残留 t28k（park 状态），不要 kill，请重启后再跑"
fi

AVAIL=$(grep '^MemAvailable:' /proc/meminfo 2>/dev/null | awk '{print $2}')
[ -n "$AVAIL" ] || AVAIL=0
if [ "$AVAIL" -lt "$MIN_AVAIL_KB" ]; then
    die "MemAvailable 不足 2GB（当前 ${AVAIL}KB），POC_DEEP_MB=1300 可能失败"
fi

# ---------- 1. staging 资产 ----------
[ -d "$STAGE" ] || die "staging 目录不存在: $STAGE"
[ -f "$STAGE/poc_tmp_root" ] || die "缺少 $STAGE/poc_tmp_root"
[ -f "$STAGE/ksu_patch.txt" ] || die "缺少 $STAGE/ksu_patch.txt"
[ -s "$STAGE/ksu_patch.txt" ] || die "$STAGE/ksu_patch.txt 为空"

# ---------- 2. BakaSU 管理器与 libksud ----------
APK=$(pm path "$PKG" 2>/dev/null | head -1 | cut -d: -f2)
[ -n "$APK" ] || die "BakaSU($PKG) 未安装"
LIBDIR=$(dirname "$APK")/lib/arm64
KSUD=$LIBDIR/libksud.so
[ -f "$KSUD" ] || die "找不到 $KSUD"
"$KSUD" --version 2>/dev/null | head -1 | grep -q ksud \
    || die "$KSUD 不是可用的 ksud（--version 失败）"

say "管理器: $PKG"
say "ksud: $KSUD -> $("$KSUD" --version 2>/dev/null | head -1)"
say "MemAvailable: ${AVAIL}KB"

if [ "$CHECK" = "1" ]; then
    say "--check 通过：前置条件满足，未执行任何写操作"
    exit 0
fi

# ---------- 3. 部署资产 ----------
cp -f "$STAGE/poc_tmp_root" "$TMP/poc_tmp_root" || die "复制 poc_tmp_root 失败"
cp -f "$STAGE/ksu_patch.txt" "$TMP/ksu_patch.txt" || die "复制 ksu_patch.txt 失败"
if [ -f "$STAGE/kiro-hello.ko" ]; then
    cp -f "$STAGE/kiro-hello.ko" "$TMP/kiro-hello.ko" 2>/dev/null
fi
chmod 755 "$TMP/poc_tmp_root"
chmod 644 "$TMP/ksu_patch.txt" 2>/dev/null

# BakaSU 的 libksud 同时作为 poc 的 userland 源
cp -f "$KSUD" "$TMP/ksud" || die "复制 ksud 失败"
chmod 755 "$TMP/ksud"

# ---------- 4. 校验 poc（panic 清零坑） ----------
SZ=$(wc -c < "$TMP/poc_tmp_root" 2>/dev/null | tr -d ' ')
[ "$SZ" = "$POC_SIZE" ] || die "poc_tmp_root 尺寸不符（$SZ != $POC_SIZE），可能被 panic 清零，请重推"
if command -v sha256sum >/dev/null 2>&1; then
    H=$(sha256sum "$TMP/poc_tmp_root" 2>/dev/null | awk '{print $1}')
    [ "$H" = "$POC_SHA" ] || die "poc_tmp_root sha256 不符（$H），请重新推送原包资产"
    say "poc 校验: size=$SZ sha256=OK"
else
    say "警告: 无 sha256sum，仅校验了尺寸"
fi

# ---------- 5. 从 BakaSU 导出模块 ----------
rm -f "$TMP/kernelsu.ko"
"$KSUD" debug extract-binary "${KMI}_kernelsu.ko" "$TMP/kernelsu.ko" >/dev/null 2>&1
[ -s "$TMP/kernelsu.ko" ] || die "从 BakaSU 导出 ${KMI}_kernelsu.ko 失败"
MODSZ=$(wc -c < "$TMP/kernelsu.ko" 2>/dev/null | tr -d ' ')
say "模块: $MODSZ 字节（BakaSU）"
chmod 644 "$TMP/kernelsu.ko" 2>/dev/null

# ---------- 6. 跑 poc（kiroot 同款 env + RMCLEAN） ----------
say "=== BEFORE ==="
grep -E '^(MemAvailable|SUnreclaim|Slab):' /proc/meminfo 2>/dev/null | while read -r l; do say "  $l"; done

rm -f "$TMP/root.log"
say "启动 poc（RMCLEAN=1, DEEP_MB=1300）..."
( cd "$TMP" && env PATH=/sbin:/system/sbin:/system/bin:/system/xbin \
    POC_LOG="$TMP/root.log" \
    POC_RMCLEAN=1 POC_DEEP_MB=1300 \
    POC_PRESSURE_MB=700 POC_GATE_MS=8000 POC_TP=1 \
    POC_KILLHOOK=1 POC_INSTALL_KSU=1 POC_SU_TEST=1 \
    setsid ./poc_tmp_root --seed "$TMP" ) >/dev/null 2>&1 &

i=0
while [ $i -lt 900 ]; do
    grep -q '^kernelsu ' /proc/modules 2>/dev/null && break
    i=$((i + 1))
    if [ $((i % 25)) -eq 0 ]; then
        say "  运行中… $((i / 5))s  $(tail -n 1 "$TMP/root.log" 2>/dev/null)"
    fi
    sleep 0.2 2>/dev/null || sleep 1
done

if ! grep -q '^kernelsu ' /proc/modules 2>/dev/null; then
    say "❌ 超时未出现 kernelsu，root.log 末尾:"
    tail -n 10 "$TMP/root.log" 2>/dev/null | while read -r l; do say "  $l"; done
    if ps -A 2>/dev/null | grep -w t28k >/dev/null; then
        say "!! 检测到 t28k park 残留：不要 kill，只能重启。"
    fi
    exit 1
fi
say "✅ 模块已加载: $(grep '^kernelsu ' /proc/modules)"

if grep -q 'pressure released' "$TMP/root.log" 2>/dev/null; then
    say "RMCLEAN 生效：pressure released（喷出物已释放）"
else
    say "警告：root.log 未见 'pressure released'，可能未走 clean 收尾"
fi

# ---------- 7. 安装 BakaSU 用户态 ----------
# 说明：shell 身份跑 install 可能因权限失败（正常）；POC_INSTALL_KSU=1 时
# poc 自己会以提权身份完成 userland 安装（二进制内有 /data/adb/ksu/bin/su
# 与 COPY ... 痕迹）。这里只做尽力尝试，rc!=0 不视为失败。
say "尝试 BakaSU 用户态安装（libksud install，权限不足属正常）..."
"$KSUD" install >> "$LOG" 2>&1
RC=$?
say "libksud install rc=$RC"
if [ -x /data/adb/ksu/bin/su ]; then
    say "su 存在: $(ls -l /data/adb/ksu/bin/su 2>/dev/null)"
else
    say "警告: /data/adb/ksu/bin/su 不存在；请打开 BakaSU 管理器确认/安装"
fi

# ---------- 8. AFTER 报告（不清理、不 kill） ----------
say "=== AFTER ==="
grep -E '^(MemAvailable|SUnreclaim|Slab):' /proc/meminfo 2>/dev/null | while read -r l; do say "  $l"; done

if ps -A 2>/dev/null | grep -w t28k >/dev/null; then
    P=$(ps -A 2>/dev/null | grep -w t28k | awk '{print $1}' | head -1)
    FD=$(ls "/proc/$P/fd" 2>/dev/null | wc -l | tr -d ' ')
    RSS=$(grep '^VmRSS:' "/proc/$P/status" 2>/dev/null | awk '{print $2}')
    say "残留进程: t28k pid=$P fd=$FD rss=${RSS}kB"
    say "  fd 很大(数千) = 走了 park 分支；只能重启清，绝不 kill"
else
    say "无 t28k 残留进程（clean 分支）"
fi

say "完成。打开 BakaSU 管理器确认 root。"
say "本次日志: $LOG"
