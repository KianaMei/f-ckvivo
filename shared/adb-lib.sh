#!/bin/sh
# 跨战线共享 ADB 只读辅助库（封装 Windows Git Bash 路径坑；严格只读，不含任何变更操作）
# 用法：source "$repo_root/shared/adb-lib.sh"

# 防止 Git Bash 把 /data/... 改写成 C:/Program Files/Git/data/...
export MSYS2_ARG_CONV_EXCL='/data;/system;/vendor;/proc;/sys;/sdcard'

ADB_SERIAL=${ADB_SERIAL:-10AG632QLS002CF}

adb_ro() {
  adb -s "$ADB_SERIAL" "$@"
}

# 拉取远端二进制 / SQLite（绕过 pty 的 LF->CRLF 污染）
adb_pull_su_bin() {
  remote_path=$1
  local_path=$2
  MSYS_NO_PATHCONV=1 adb -s "$ADB_SERIAL" exec-out su -c "cat '$remote_path'" > "$local_path"
}

# 日常只读健康自检（vectord 必须恰 1 个、kernelsu 已加载、最新 daemon 日志）
vector_health_check() {
  echo "=== vectord PID (expect 1) ==="
  adb_ro shell su -c 'pidof vectord'
  echo "=== kernelsu module ==="
  adb_ro shell su -c 'grep "^kernelsu " /proc/modules'
  echo "=== latest lspd verbose log ==="
  adb_ro shell su -c 'ls -t /data/adb/lspd/log/verbose_*.log | head -1'
}
