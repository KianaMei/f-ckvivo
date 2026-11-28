#!/system/bin/sh
set -eu

MODE="${1:-}"
TARGET_UID="${2:-}"
TARGET_USER="${3:-}"
DEADLINE="${4:-}"
TARGET='com.vivo.globaldragdrop'
ACTIVITY='com.vivo.globaldragdrop/com.vivo.transisland.ui.settings.SuperDraggingSettingActivity'

fail() {
    printf 'DRAG_ALL_ERROR:%s\n' "$1"
    exit 1
}

case "$MODE" in preview|restart) ;; *) fail 'invalid_mode' ;; esac
for NUMBER in "$TARGET_UID" "$TARGET_USER" "$DEADLINE"; do
    case "$NUMBER" in ''|*[!0-9]*) fail 'invalid_identity' ;; esac
done
[ "$(id -u)" = 0 ] || fail 'root_required'
[ "$((TARGET_UID % 100000))" -ge 10000 ] || fail 'system_uid_refused'
[ "$((TARGET_UID / 100000))" -eq "$TARGET_USER" ] || fail 'wrong_user'

check_deadline() {
    read -r NOW UNUSED < /proc/uptime
    [ "${NOW%%.*}" -lt "$DEADLINE" ] || fail 'request_expired'
}

guard_state() {
    for CORE in system_server zygote64 zygote; do
        CORE_PIDS="$(pidof "$CORE" || true)"
        if [ "$CORE" != zygote ]; then
            [ -n "$CORE_PIDS" ] || return 1
        fi
        case "$CORE_PIDS" in *' '*) return 1 ;; esac
        printf '%s=%s\n' "$CORE" "$CORE_PIDS"
    done
    # Vector 的 root 侧守护进程是 vectord，LSPosed 的是 lspd（--nice-name=lspd）。
    # 两者必须恰有一个存活且 PID 唯一；同时出现说明框架装重了，停止操作。
    DAEMON_STATE=''
    for DAEMON in vectord lspd; do
        DAEMON_PIDS="$(pidof "$DAEMON" || true)"
        case "$DAEMON_PIDS" in
            '') continue ;;
            *' '*) return 1 ;;
        esac
        [ -z "$DAEMON_STATE" ] || return 1
        DAEMON_STATE="$DAEMON=$DAEMON_PIDS"
    done
    [ -n "$DAEMON_STATE" ] || return 1
    printf 'daemon=%s\n' "$DAEMON_STATE"
}

targets() {
    ps -A -o PID,UID,NAME | awk -v uid="$TARGET_UID" -v pkg="$TARGET" \
        '$2 == uid && ($3 == pkg || index($3, pkg ":") == 1) {print $1}'
}

start_ticks() {
    sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}'
}

check_deadline
GUARD_BEFORE="$(guard_state)" || fail 'framework_not_ready'
[ "$(getprop sys.boot_completed)" = 1 ] || fail 'boot_not_complete'
# Vector 与 LSPosed 共用 /data/adb/lspd/config；数据库文件名与表结构按框架版本探测。
# LSPosed v2.2+ 用 modules_state + scope(module_pkg_name)，旧版/Vector 用 modules(mid,enabled) + scope(mid)。
ALLOWED_SCOPES="'com.vivo.globaldragdrop','com.vivo.accessibilityenhance','org.BroTech.gege.pilibro','tv.danmaku.bili','com.twitter.android'"
SCOPE_SQL_V2="SELECT ms.enabled || ':' || sum(s.app_pkg_name='com.vivo.globaldragdrop') || ':' || sum(s.app_pkg_name NOT IN ($ALLOWED_SCOPES)) FROM modules_state ms JOIN scope s ON ms.module_pkg_name=s.module_pkg_name AND ms.user_id=s.user_id WHERE ms.module_pkg_name='dev.ganvivo.dragall' AND ms.user_id=$TARGET_USER GROUP BY ms.module_pkg_name;"
SCOPE_SQL_V1="SELECT m.enabled || ':' || sum(s.app_pkg_name='com.vivo.globaldragdrop') || ':' || sum(s.app_pkg_name NOT IN ($ALLOWED_SCOPES)) FROM modules m JOIN scope s ON m.mid=s.mid WHERE m.module_pkg_name='dev.ganvivo.dragall' AND s.user_id=$TARGET_USER GROUP BY m.mid;"
SCOPE_STATE=''
for CONFIG_DB in /data/adb/lspd/config/modules_config.db /data/adb/lspd/config/modules.db; do
    [ -r "$CONFIG_DB" ] || continue
    for SQL in "$SCOPE_SQL_V2" "$SCOPE_SQL_V1"; do
        if SCOPE_STATE="$(/system/bin/sqlite3 -readonly "$CONFIG_DB" "$SQL" 2>/dev/null)" && [ -n "$SCOPE_STATE" ]; then
            break 2
        fi
        SCOPE_STATE=''
    done
done
[ "$SCOPE_STATE" = '1:1:0' ] || fail 'scope_unverified'
TARGET_PIDS="$(targets)"
printf 'DRAG_ALL_TARGETS:%s\n' "$TARGET_PIDS"
printf '%s\n' "$GUARD_BEFORE"
if [ "$MODE" = preview ]; then
    printf 'DRAG_ALL_PREVIEW_OK\n'
    exit 0
fi

OLD_RECORDS=''
for TARGET_PID in $TARGET_PIDS; do
    check_deadline
    case "$TARGET_PID" in ''|*[!0-9]*) fail 'invalid_pid' ;; esac
    [ "$TARGET_PID" -gt 1 ] || fail 'protected_pid'
    [ -r "/proc/$TARGET_PID/status" ] || continue
    ACTUAL_UID="$(awk '/^Uid:/{print $2}' "/proc/$TARGET_PID/status")"
    [ "$ACTUAL_UID" = "$TARGET_UID" ] || fail 'uid_changed'
    ACTUAL_NAME="$(tr '\000' '\n' < "/proc/$TARGET_PID/cmdline" | head -n 1)"
    case "$ACTUAL_NAME" in "$TARGET"|"$TARGET":*) ;; *) fail 'process_changed' ;; esac
    START_TICKS="$(start_ticks "$TARGET_PID")"
    case "$START_TICKS" in ''|*[!0-9]*) fail 'process_identity_missing' ;; esac
    [ "$(guard_state)" = "$GUARD_BEFORE" ] || fail 'framework_changed'
    [ "$(start_ticks "$TARGET_PID")" = "$START_TICKS" ] || fail 'pid_reused'
    kill -TERM "$TARGET_PID" || fail 'termination_failed'
    OLD_RECORDS="$OLD_RECORDS $TARGET_PID:$START_TICKS"
    printf 'DRAG_ALL_TERMINATED:%s\n' "$TARGET_PID"
done

ATTEMPT=0
while :; do
    ALIVE=0
    for RECORD in $OLD_RECORDS; do
        OLD_PID="${RECORD%%:*}"
        OLD_START="${RECORD#*:}"
        if [ -r "/proc/$OLD_PID/stat" ] && [ "$(start_ticks "$OLD_PID")" = "$OLD_START" ]; then
            ALIVE=1
        fi
    done
    [ "$ALIVE" = 1 ] || break
    [ "$ATTEMPT" -lt 8 ] || fail 'target_did_not_exit'
    check_deadline
    sleep 1
    ATTEMPT=$((ATTEMPT + 1))
done

check_deadline
[ "$(guard_state)" = "$GUARD_BEFORE" ] || fail 'framework_changed'
am start -W --user "$TARGET_USER" -n "$ACTIVITY" || fail 'launch_failed'
[ "$(guard_state)" = "$GUARD_BEFORE" ] || fail 'framework_changed'
NEW_PIDS="$(targets)"
[ -n "$NEW_PIDS" ] || fail 'target_not_running'
printf 'DRAG_ALL_NEW_PIDS:%s\n' "$NEW_PIDS"
printf 'DRAG_ALL_RESTART_OK\n'
