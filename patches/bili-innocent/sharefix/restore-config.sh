#!/bin/sh
# 重装补丁版后，还原原模块在 Vector 里的全部配置 / 作用域 / 启用状态。
# 在 PC 的 Git Bash 里运行（不是设备端）。
#
# 前提：补丁版已安装且包名不变（com.Bilibili_Innocent_Lab.xposedmodule）。
# 卸载原版会级联清空 modules/scope/configs 三表中该模块的行；本脚本：
#   1) sqlite3 重放 291 条 hook_config（CLI 不管模块偏好，只能 SQL，逐字节还原）
#   2) vector-cli scope add 还原作用域、modules enable 启用（daemon 官方通道）
# 全程只写本模块自己的行，不碰其它模块；结尾只读校验。
# 注意：root 命令通过 stdin 整块喂给 `adb shell su`，避开 su -c 多层引号吞参问题。
set -eu

PKG=com.Bilibili_Innocent_Lab.xposedmodule
HOST=tv.danmaku.bili
CLI=/data/adb/modules/zygisk_vector/cli
DB=/data/adb/lspd/config/modules_config.db
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CFG_SQL="${LAB_CACHE:-$HERE/../../../../fuckmyphone-cache}/bases/innocent/backup/restore_03_configs.sql"
REMOTE_SQL=/data/local/tmp/innocent_restore_configs.sql

# adb 探测：本机可能不在 PATH
ADB=$(command -v adb || true)
if [ -z "$ADB" ]; then
  for p in "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
           "$HOME/Android/Sdk/platform-tools/adb.exe"; do
    [ -x "$p" ] && { ADB="$p"; break; }
  done
fi
[ -n "$ADB" ] || { echo "找不到 adb；请把 platform-tools 加进 PATH" >&2; exit 1; }
[ -f "$CFG_SQL" ] || { echo "缺少 $CFG_SQL" >&2; exit 1; }

echo "== 0. 确认补丁版已安装 =="
"$ADB" shell pm path "$PKG"

echo "== 1. 推送 291 条 hook_config SQL =="
"$ADB" push "$CFG_SQL" "$REMOTE_SQL" >/dev/null

echo "== 2. root 块：重放配置 + 还原作用域 + 启用 =="
"$ADB" shell su <<EOF
sqlite3 '$DB' "DELETE FROM configs WHERE module_pkg_name='$PKG';"
sqlite3 '$DB' '.read $REMOTE_SQL'
rm -f '$REMOTE_SQL'
'$CLI' scope add '$PKG' '$HOST/0'
'$CLI' modules enable '$PKG'
EOF

echo "== 3. 只读校验 =="
"$ADB" shell su <<EOF
echo "-- configs 行数（应为 291）："
sqlite3 '$DB' "SELECT count(*) FROM configs WHERE module_pkg_name='$PKG';"
echo "-- 两个分享开关（末尾字节 01=true）："
sqlite3 '$DB' "SELECT key, quote(substr(data,length(data),1)) FROM configs WHERE module_pkg_name='$PKG' AND key IN ('purify_share_content','share_mini_program_direct_link');"
echo "-- scope（应含 $HOST）："
'$CLI' scope ls '$PKG'
echo "-- modules 状态（应 enabled）："
'$CLI' modules ls | grep -i innocent
EOF

echo
echo "还原完成。下一步：force-stop 并重启 B 站让新入口与配置生效（属设备变更，需另行确认）。"
