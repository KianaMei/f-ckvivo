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

# 日常只读健康自检：框架守护进程必须恰 1 个。
# 进程名随框架变：官方 LSPosed 为 `lspd`，Vector（JingMatrix 分支）为 `vectord`；
# 本机 2026-11-26 起为 LSPosed v2.2.1，只有 `lspd`，所以两个名字都试，并打印命中的那一个。
# 输出里 `daemon=none` 才是真没起来；只看到其中一个空不代表框架挂了。
framework_health_check() {
  # 先分开“没连上设备”与“框架挂了”，否则拔线/睡眠会被误报成 daemon=none
  echo "=== 设备连接 ==="
  state=$(adb devices | awk -v s="$ADB_SERIAL" '$1==s {print $2}')
  if [ "$state" != "device" ]; then
    printf '  %s 状态=[%s]  ⚠ 设备不在线，后面的检查都没有意义\n' "$ADB_SERIAL" "${state:-未列出}"
    echo "  用 adb devices -l 看 USB/无线；重新插拔后重跑，不要根据这次输出判断框架"
    return 1
  fi
  printf '  %s 已连接\n' "$ADB_SERIAL"

  echo "=== 框架守护进程（lspd 优先，回退 vectord；期望恰 1 个）==="
  daemon=none
  for name in lspd vectord; do
    pids=$(adb_ro shell su -c "pidof $name" | tr -d '\r')
    # 统一成单行，避免 pidof 返回多个 PID 时的多行输出打乱下面的判断
    count=$(printf '%s' "$pids" | wc -w | tr -d ' ')
    printf '  %-8s pids=[%s] count=%s\n' "$name" "$(printf '%s' "$pids" | tr '\n' ' ')" "$count"
    if [ "$count" -gt 0 ]; then
      [ "$daemon" = none ] && daemon=$name
    fi
  done
  case "$daemon" in
    none) echo "  => daemon=none  ⚠ 框架没在跑或自检路径变了，先查再动手" ;;
    *)    echo "  => daemon=$daemon（健康）" ;;
  esac

  echo "=== kernelsu 模块 ==="
  adb_ro shell su -c 'grep "^kernelsu " /proc/modules'
  echo "=== 最新 lspd verbose 日志 ==="
  adb_ro shell su -c 'ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1'
  echo "=== system_server / zygote64（重启或混乱时 PID 会变）==="
  printf '  system_server=[%s] zygote64=[%s]\n' \
    "$(adb_ro shell su -c 'pidof system_server' | tr -d '\r')" \
    "$(adb_ro shell su -c 'pidof zygote64' | tr -d '\r')"
}

# 旧名保留，历史文档与脚本里的 vector_health_check 仍可用
vector_health_check() { framework_health_check "$@"; }
