#!/bin/bash
# X Fold6 (PD2545D / MT6993) 设备侧事实采集 —— 只读，不需要 root。
#
# 设计原则：**期望值全部从 xf6-exploit/target.h 读取**，脚本内不硬编码任何
# release / 偏移 / sha256。target.h 是唯一权威源；此前的版本把期望串写死在脚本里，
# 结果停留在别的机型（PD2502）的值上，属于典型的「自洽但永远为真」的假闸门。
#
# 用途：
#   1. 跑 exploit 前的身份闸门（错了就不该往下走）
#   2. 采集 vr.ko 反汇编所需素材（vr.ko 本体 + 身份）
#   3. 采集 64560 / 43499 校准所需的只读事实（SELinux、CPU 拓扑、slabinfo 可见性）
#
# 用法：
#   ./tools/phone_preflight.sh              # 采集并打印
#   ./tools/phone_preflight.sh --collect    # 额外把 vr.ko 等素材拉到 tools/device-dump/

set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_H="$REPO/xf6-exploit/target.h"
DUMP="$REPO/tools/device-dump"
COLLECT=0
[ "${1:-}" = "--collect" ] && COLLECT=1

# ---------- adb 定位（PATH → 已知安装点） ----------
find_adb() {
    command -v adb 2>/dev/null && return
    for p in "D:/D-code/tools/android/platform-tools/adb.exe" \
             "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; do
        [ -x "$p" ] && { echo "$p"; return; }
    done
}
ADB="$(find_adb)"
if [ -z "$ADB" ]; then
    echo "FATAL: 找不到 adb。设置 PATH 或放到 D:\\D-code\\tools\\android\\platform-tools\\" >&2
    exit 2
fi

# ---------- 从 target.h 读期望值（唯一权威源） ----------
# 注意两个坑，都会导致「静默取到错值」，必须处理：
#   1) target.h 里存在**续行**的 #define（值在下一行）——先合并续行再匹配
#   2) 文件是 **CRLF** ——所有取值都要去掉 \r，否则 "0x9220\r" 转不了数字
[ -f "$TARGET_H" ] || { echo "FATAL: 找不到 $TARGET_H" >&2; exit 2; }

# 合并续行后的完整宏定义行（反斜杠与 CR 用 tr 去除，避开 awk 正则转义坑）
_tl_defs() {
    awk '
        /^[[:space:]]*#define[[:space:]]/ {
            line = $0
            while (line ~ /\\[[:space:]]*$/) {
                if ((getline nxt) > 0) line = line " " nxt; else break
            }
            print line
        }
    ' "$TARGET_H" | tr -d '\\\r'
}
# 取宏的字符串字面量
th()  { _tl_defs | grep -m1 -E "^\s*#define\s+$1([^A-Za-z0-9_]|$)" | grep -oE '"[^"]*"' | head -1 | tr -d '"'; }
# 取宏的第一个十六进制字面量
thx() { _tl_defs | grep -m1 -E "^\s*#define\s+$1([^A-Za-z0-9_]|$)" | grep -oE '0x[0-9a-fA-F]+' | head -1; }

EXP_RELEASE="$(th TARGET_RELEASE)"
EXP_VERSION="$(th TARGET_VERSION)"
EXP_TOKEN="$(th TARGET_REQUIRED_CMDLINE_TOKEN)"
EXP_CARRIER_PATH="$(th TARGET_CARRIER_PATH)"
EXP_CARRIER_SHA="$(th TARGET_CARRIER_SHA256)"
EXP_CARRIER_SIZE="$(thx TARGET_CARRIER_SIZE)"
EXP_PATCH_OFF="$(thx TARGET_CARRIER_PATCH_OFFSET)"
EXP_VR_SHA="$(th TARGET_VR_MODULE_SHA256)"
EXP_VR_VERMAGIC="$(th TARGET_VR_MODULE_VERMAGIC)"

# ---------- 输出助手 ----------
PASS=0; FAIL=0; UNK=0
ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
no()   { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }
unk()  { UNK=$((UNK+1));  printf '  \033[33mUNKN\033[0m  %s\n' "$1"; }
sec()  { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

export MSYS_NO_PATHCONV=1
# 退出码必须来自 adb 本身——不能过管道（经 tr 后退出码恒 0，会让所有检查假阳性）
sh_() { local out; out=$("$ADB" shell "$@" 2>&1); local rc=$?; printf '%s\n' "$out" | tr -d '\r'; return $rc; }

# ---------- 设备在线 ----------
sec "0. 设备连接"
DEVICES=$("$ADB" devices | sed '1d' | grep -c 'device$')
SERIAL=$("$ADB" get-serialno 2>/dev/null | tr -d '\r')
if [ "$DEVICES" -lt 1 ]; then
    echo "  没有在线设备。请连接手机并确认已授权 USB 调试。" >&2
    exit 3
fi
echo "  序列号: $SERIAL"
"$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r' | sed 's/^/  机型: /'

# ---------- 1. 内核身份闸门 ----------
sec "1. 内核身份（不匹配则不应继续）"
R=$(sh_ uname -r); V=$(sh_ uname -v)
[ "$R" = "$EXP_RELEASE" ] \
  && ok "release 匹配: $R" \
  || no "release 不匹配\n        设备: $R\n        期望: $EXP_RELEASE"
[ "$V" = "$EXP_VERSION" ] \
  && ok "version 匹配" \
  || unk "version 不同（可能是 ROM 更新过）\n        设备: $V\n        期望: $EXP_VERSION"

# ---------- 2. cmdline token ----------
sec "2. cmdline 绑定 token"
CM=$(sh_ cat /proc/cmdline | tr ' ' '\n' | grep -m1 '^product.version=')
[ "$CM" = "$EXP_TOKEN" ] \
  && ok "token 匹配: $CM" \
  || no "token 不匹配 — 设备: $CM / 期望: $EXP_TOKEN"

# ---------- 3. carrier（dumpstate DSO） ----------
sec "3. carrier: $EXP_CARRIER_PATH"
SZ=$(sh_ stat -c %s "$EXP_CARRIER_PATH")
ESZ=$((EXP_CARRIER_SIZE))
if [ "$SZ" = "$ESZ" ]; then ok "size 匹配: $SZ"
elif [ -n "$SZ" ]; then no "size 不匹配 — 设备: $SZ / 期望: $ESZ (0x$(printf %x "$ESZ"))"
else unk "取不到 size（路径不可读？）"; fi

OFF=$((EXP_PATCH_OFF))
PRE=$(sh_ "dd if=$EXP_CARRIER_PATH bs=1 skip=$OFF count=16 2>/dev/null" | xxd -p 2>/dev/null | tr -d '\n')
echo "        patch@0x$(printf %x "$OFF") preimage = $PRE"
echo "        期望（自研推导，含 bti c）= 5f2403d5fd7bbfa9fd0300912000 0090 的低 16 字节"
CSHA=$(sh_ "sha256sum $EXP_CARRIER_PATH 2>/dev/null" | cut -d' ' -f1)
[ "$CSHA" = "$EXP_CARRIER_SHA" ] \
  && ok "carrier sha256 匹配" \
  || echo "        carrier sha256 = ${CSHA:-取不到}（ROM 更新后此值应重新推导）"

# ---------- 4. vr.ko（反汇编素材） ----------
sec "4. vr.ko —— 反 root 卫兵"
VRPATH=""
for p in /vendor_dlkm/lib/modules/vr.ko /vendor/lib/modules/vr.ko \
         /system/lib/modules/vr.ko /odm/lib/modules/vr.ko /vendor_dlkm/lib/modules/vr.ko.xz; do
    if sh_ "test -e $p"; then VRPATH="$p"; break; fi
done
if [ -z "$VRPATH" ]; then
    # 从 /proc/modules 找 vr 的加载状态，再全盘找文件
    VRLINE=$(sh_ "grep -m1 '^vr ' /proc/modules")
    echo "        /proc/modules: ${VRLINE:-（vr 未在加载列表或不可读）}"
    HIT=$(sh_ "find /vendor_dlkm /vendor /odm -name 'vr.ko*' -maxdepth 4 2>/dev/null" | head -3)
    [ -n "$HIT" ] && { VRPATH=$(echo "$HIT" | head -1); echo "        find 命中: $VRPATH"; }
fi
if [ -n "$VRPATH" ]; then
    VSHA=$(sh_ "sha256sum $VRPATH 2>/dev/null" | cut -d' ' -f1)
    [ "$VSHA" = "$EXP_VR_SHA" ] && ok "vr.ko sha256 匹配（$VRPATH）" \
                               || no "vr.ko sha256 不匹配 — 设备: ${VSHA:-?} / 期望: $EXP_VR_SHA"
    VM=$(sh_ "modinfo -F vermagic $VRPATH 2>/dev/null")
    echo "        vermagic = ${VM:-取不到}"
    echo "        期望      = $EXP_VR_VERMAGIC"
    # 注意：vermagic 的 GKI 哈希与运行内核 release 不同（g97704d7faadc vs gdd6fd5c98918），
    # 这是待确认项 #3（见 ROUTES.md §6）。
    ALLVR=$(sh_ 'ls /vendor/lib/modules/ 2>/dev/null' | grep -iE 'vr|vivo' | tr '\n' ' ')
    echo "        相关模块: ${ALLVR:-（不可读）}"
else
    unk "找不到 vr.ko（/vendor/lib/modules/ 是否可读？）"
fi

# ---------- 5. SELinux ----------
sec "5. SELinux 状态（64560 的验收判据就是读这个文件）"
EN=$(sh_ "cat /sys/fs/selinux/enforce 2>/dev/null")
[ -n "$EN" ] && { echo "        enforce = $EN  ($([ "$EN" = "1" ] && echo Enforcing || echo Permissive))"; ok "enforce 可读 —— 64560 的验收判据可用"; } \
              || no "enforce 不可读 —— 64560 验收判据不可用，需另找旁证"
echo "        context = $(sh_ id -Z 2>/dev/null | head -1)"

# ---------- 6. 只读可见性矩阵 ----------
sec "6. 只读可见性（决定哪条校准路线可行）"
for p in /proc/kallsyms /proc/slabinfo /proc/version /sys/fs/selinux/enforce /sys/kernel/btf/vmlinux; do
    if sh_ "head -c 1 $p" >/dev/null 2>&1; then ok "$p 可读"; else no "$p 不可读"; fi
done

# ---------- 7. CPU 拓扑（43499 校准旋钮 #1） ----------
sec "7. CPU 拓扑 —— 43499 的「CPU 核心对」校准旋钮"
echo "        在线 CPU: $(sh_ 'cat /sys/devices/system/cpu/online')"
for c in 0 1 2 3 4 5 6 7; do
    if sh_ "test -f /sys/devices/system/cpu/cpu$c/topology/physical_package_id" >/dev/null 2>&1; then
        PKG=$(sh_ "cat /sys/devices/system/cpu/cpu$c/topology/physical_package_id 2>/dev/null")
        CLU=$(sh_ "cat /sys/devices/system/cpu/cpu$c/topology/cluster_id 2>/dev/null")
        MHZ=$(sh_ "cat /sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq 2>/dev/null")
        [ -n "$PKG" ] && echo "        cpu$c  pkg=$PKG cluster=${CLU:-?} max=${MHZ:-?}kHz"
    fi
done
echo "        （同 cluster 的两个核心才构成可用的 main/trigger 对）"

# ---------- 8. 可选：拉取素材 ----------
if [ "$COLLECT" = 1 ]; then
    sec "8. 拉取素材到 tools/device-dump/"
    mkdir -p "$DUMP"
    # adb pull 的本地路径不能用 MSYS 形式（MSYS_NO_PATHCONV=1 时不会转换）——cd 进目录用相对名
    if [ -n "$VRPATH" ]; then
        ( cd "$DUMP" && "$ADB" pull "$VRPATH" vr.ko 2>&1 | tail -1 ) && [ -f "$DUMP/vr.ko" ] \
            && ok "已拉取 vr.ko ($(stat -c%s "$DUMP/vr.ko") 字节)" \
            || no "vr.ko 拉取失败"
    fi
    ( cd "$DUMP" && "$ADB" pull "$EXP_CARRIER_PATH" libdumpstateaidl.so 2>&1 | tail -1 ) \
        && [ -f "$DUMP/libdumpstateaidl.so" ] && ok "已拉取 carrier DSO"
    "$ADB" shell "cat /proc/version" 2>/dev/null | tr -d '\r' > "$DUMP/proc_version.txt"
    "$ADB" shell "cat /proc/cmdline" 2>/dev/null | tr -d '\r' > "$DUMP/cmdline.txt"
    "$ADB" shell "cat /sys/fs/selinux/enforce" 2>/dev/null | tr -d '\r' > "$DUMP/selinux_enforce.txt"
    "$ADB" shell "grep -E '^vr |^dumpstate' /proc/modules" 2>/dev/null | tr -d '\r' > "$DUMP/proc_modules_vr.txt" || true
    "$ADB" shell "cat /proc/modules" 2>/dev/null | tr -d '\r' > "$DUMP/proc_modules_all.txt" || true
    echo "        已写入 $DUMP"
    [ -f "$DUMP/vr.ko" ] && echo "        下一步：python tools/analyze_vr.py --input $DUMP/vr.ko"
fi

# ---------- 汇总 ----------
printf '\n\033[1m== 汇总 ==\033[0m  PASS=%d FAIL=%d UNKNOWN=%d\n' "$PASS" "$FAIL" "$UNK"
if [ "$FAIL" -gt 0 ]; then
    echo "有 FAIL —— 按项目安全闸，不应继续到写内核的步骤。"
    exit 1
fi
echo "身份闸门通过。"
