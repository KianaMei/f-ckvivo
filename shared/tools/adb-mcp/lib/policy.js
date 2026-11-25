'use strict';
/**
 * adb-mcp · 红线策略引擎
 * --------------------
 * 把 AGENTS.md「设备不是草稿纸」与「设备红线」编码成命令级过滤。
 * 任何 shell 命令在送进 adb 之前必须先过这里。
 *
 * 判定输出：
 *   { allow: true, reason: 'ok' }
 *   { allow: false, reason: '人话解释' }
 */

const {
  config,
} = require('./config');

// —— 设备写入与命令替换特征（红线：禁止在手机上留任何临时文件）——
// 允许唯一的无害错误丢弃 `2>/dev/null`；其余一切 `>` / `>>` 输出重定向全部拦截
const SAFE_STDERR_NULL_RE = /\b2>\s*\/dev\/null(?=\s|$|[;&|])/g;

const DEVICE_WRITE_PATTERNS = [
  { re: /[0-9&]*>>?/,
    reason: '禁止输出重定向写文件（> / >>）——「设备不是草稿纸」红线' },
  { re: /`|\$\(/,
    reason: '禁止命令替换（`...` / $(...)）' },
  { re: /\b(tee|dd|cp|mv|rm|rmdir|unlink|mkdir|mktemp|touch|truncate|chmod|chown|chcon|ln|sed\s+-i|tar\s+[^\n;|]*-[A-Za-z]*x|unzip|gunzip|wget|curl\s+[^\n;|]*(-o|-O|--output))\b/i,
    reason: '禁止设备侧写/改/删文件操作（tee/dd/cp/mv/rm/mkdir/touch/chmod/sed -i 等）——「设备不是草稿纸」红线' },
];

// —— 破坏性/系统级动作（红线 §1：默认只读；§1.0：禁止重启类）——
const DANGEROUS_PATTERNS = [
  { re: /\b(reboot|shutdown|poweroff)\b/i, reason: '禁止重启/关机（临时 root 是 LKM，硬重启即丢）' },
  { re: /\bctl\.(restart|stop|start|reboot)\b/i, reason: '禁止 setprop ctl.* / ctl.* 重启类动作' },
  { re: /\bksud\b.*\b(soft-?reboot|reboot)\b/i, reason: '禁止 ksud soft-reboot' },
  { re: /(^|\s)install(\s|$)/i, reason: '禁止 adb install / pm install（PackageInstaller 事故红线）' },
  { re: /\buninstall\b/i, reason: '禁止卸载' },
  { re: /\bforce-?stop\b/i, reason: '禁止 force-stop 系统应用（system_server 崩溃事故红线）' },
  { re: /\bkill\b|-\b9\b|\bpkill\b|\bkillall\b/i, reason: '禁止 kill/pkill（只按精确 PID 由人工操作）' },
  { re: /\bam\b.*\bbroadcast\b/i, reason: '禁止 am broadcast（PACKAGE_* 广播 NPE 崩 system_server）' },
  { re: /\bam\s+(start|startservice|start-foreground-service|stopservice|instrument|hang|crash)\b/i, reason: '禁止 am 启动/停止组件（设备只读红线）' },
  { re: /\bsetprop\b/i, reason: '禁止 setprop（设备只读红线）' },
  { re: /\bsettings\s+(put|delete)\b/i, reason: '禁止 settings put/delete（设备只读红线）' },
  { re: /\bpm\s+(clear|grant|revoke|reset|enable|disable|disable-user|hide|unhide|suspend|unsuspend|compile)\b/i, reason: '禁止 pm 状态变更指令' },
  { re: /\blogcat\b[^\n;|]*\s(-c|--clear)(\s|$)/i, reason: '禁止 logcat -c 清空设备日志缓冲' },
  { re: /\binput\s+(tap|swipe|keyevent|text|motionevent|press|roll|draganddrop)\b/i, reason: '禁止 input 注入触控/按键（设备只读红线）' },
  { re: /\bsvc\s+(power|wifi|data|bluetooth|usb|nfc)\b/i, reason: '禁止 svc 切换系统开关（设备只读红线）' },
  { re: /\btcpip\b|\bconnect:?\d*/i, reason: '禁止 adb tcpip / connect（重启 adbd、丢 USB）' },
  { re: /\bemulator\b|\bemu\b/i, reason: 'emulator 指令禁入' },
  { re: /(^|[\s;|&])(eval|exec|sh|bash|ash|mksh)(\s|$)/i, reason: '禁止嵌套 shell / eval / exec 绕过白名单' },
];

// —— 可无参或带参执行的纯只读命令动词 ——
const READONLY_VERBS = new Set([
  'cat', 'ls', 'grep', 'egrep', 'fgrep', 'head', 'tail', 'find', 'stat', 'wc',
  'file', 'readlink', 'realpath', 'ps', 'pidof', 'pgrep', 'dumpsys', 'getprop',
  'logcat', 'id', 'whoami', 'uname', 'date', 'uptime', 'df', 'free', 'mount',
  'sha256sum', 'md5sum', 'du', 'which', 'cut', 'sort', 'uniq', 'tr', 'strings',
]);

// —— 必须匹配完整多词前缀的只读子命令 ——
const READONLY_MULTI_PREFIXES = [
  'top -n',
  'settings get ', 'settings list ',
  'pm list', 'pm path ', 'pm dump ',
  'am stack list',
  'service list', 'service check ',
  'cmd package list', 'cmd package path ', 'cmd package dump ', 'cmd package resolve-activity ',
  'cmd overlay list', 'cmd settings get ', 'cmd settings list',
  'echo $',
];

function stripOuterQuotes(s) {
  const t = s.trim();
  if (t.length >= 2) {
    if (t.startsWith("'") && t.endsWith("'") && !t.slice(1, -1).includes("'")) {
      return t.slice(1, -1).trim();
    }
    if (t.startsWith('"') && t.endsWith('"') && !/(?<!\\)"/.test(t.slice(1, -1))) {
      return t.slice(1, -1).replace(/\\(["\\])/g, '$1').trim();
    }
  }
  return t;
}

function isAllowedSegment(rawLine, depth) {
  if (depth > 4) return { allow: false, reason: '命令包装嵌套过深' };
  const line = rawLine.trim();
  if (!line) return { allow: true, reason: 'ok' };

  // 解包 su -c / su 0：必须对内层命令递归执行完整红线与白名单检查
  const suMatch = line.match(/^su(?:\s+(?:0|root))?(?:\s+-c)?\s+([\s\S]+)$/);
  if (suMatch) {
    const inner = stripOuterQuotes(suMatch[1]);
    if (!inner) return { allow: false, reason: 'su 缺少内层只读命令' };
    return checkShellCommandInternal(inner, depth + 1);
  }

  // 解包 busybox / toybox（含 AGENTS.md §2 约定的 /data/adb/ksu/bin/busybox 绝对路径）
  const boxMatch = line.match(/^(?:\/data\/adb\/ksu\/bin\/)?(?:busybox|toybox)\s+([\s\S]+)$/);
  if (boxMatch) {
    const inner = boxMatch[1].trim();
    if (!inner) return { allow: false, reason: 'busybox/toybox 缺少子命令' };
    return isAllowedSegment(inner, depth + 1);
  }

  if (READONLY_MULTI_PREFIXES.some((pre) => line === pre.trim() || line.startsWith(pre))) {
    return { allow: true, reason: 'ok' };
  }

  const firstToken = line.split(/\s+/)[0];
  if (READONLY_VERBS.has(firstToken)) {
    return { allow: true, reason: 'ok' };
  }

  return { allow: false, reason: `命令不在只读白名单内：${line.slice(0, 80)}` };
}

/**
 * 感知单/双引号的管道与逻辑连接符切分器：
 * 仅在引号外切分 \n / ; / | / || / &&；若引号未闭合直接拒绝，杜绝未闭合引号藏匿语法。
 */
function splitShellSegments(s) {
  const segs = [];
  let cur = '';
  let quote = null;
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (quote === "'") {
      cur += ch;
      if (ch === "'") quote = null;
      continue;
    }
    if (quote === '"') {
      cur += ch;
      if (ch === '\\' && i + 1 < s.length) {
        cur += s[++i];
        continue;
      }
      if (ch === '"') quote = null;
      continue;
    }
    if (ch === "'" || ch === '"') {
      quote = ch;
      cur += ch;
      continue;
    }
    if (ch === '\n' || ch === ';') {
      if (cur.trim()) segs.push(cur.trim());
      cur = '';
      continue;
    }
    if (ch === '|' || ch === '&') {
      if (s[i + 1] === ch) {
        i++; // || 或 &&
      } else if (ch === '&') {
        // 单独后台符 `&` 直接拒绝（只读命令不允许后台挂起）
        return { ok: false, reason: '禁止后台执行符 &' };
      }
      if (cur.trim()) segs.push(cur.trim());
      cur = '';
      continue;
    }
    cur += ch;
  }
  if (quote !== null) return { ok: false, reason: '命令引号未闭合' };
  if (cur.trim()) segs.push(cur.trim());
  return { ok: true, segments: segs };
}

function checkShellCommandInternal(cmd, depth) {
  const c = String(cmd || '').trim();
  if (!c) return { allow: false, reason: '空命令' };
  if (c.length > 4000) return { allow: false, reason: '命令过长（>4000 字符）' };
  if (/[\u0000-\u0008\u000b-\u001f]/.test(c)) return { allow: false, reason: '命令含控制字符' };

  const sanitized = c.replace(SAFE_STDERR_NULL_RE, '');

  for (const p of DEVICE_WRITE_PATTERNS) {
    if (p.re.test(sanitized)) return { allow: false, reason: p.reason };
  }
  for (const p of DANGEROUS_PATTERNS) {
    if (p.re.test(sanitized)) return { allow: false, reason: p.reason };
  }

  // 多行 / 管道 / 逻辑连接命令：引号感知切分后逐段过白名单
  const split = splitShellSegments(sanitized);
  if (!split.ok) return { allow: false, reason: split.reason };
  if (split.segments.length === 0) return { allow: false, reason: '空命令' };
  for (const line of split.segments) {
    const v = isAllowedSegment(line, depth);
    if (!v.allow) return v;
  }
  return { allow: true, reason: 'ok' };
}

function checkShellCommand(cmd) {
  return checkShellCommandInternal(cmd, 0);
}

// —— exec-out 允许的设备侧读取命令（管道直拉，不落设备盘）——
const EXECOUT_CMDS = ['cat', 'screencap', 'uiautomator'];

function checkExecoutCmd(cmd) {
  const c = String(cmd || '').trim();
  const first = c.split(/\s+/)[0];
  if (!EXECOUT_CMDS.includes(first)) {
    return { allow: false, reason: `exec-out 只允许 ${EXECOUT_CMDS.join('/')}，收到：${first}` };
  }
  return { allow: true, reason: 'ok' };
}

// —— remote path 校验（用于 exec-out cat）：只许读，路径不许带引号/控制符/shell 元字符 ——
function checkRemoteReadPath(p) {
  const s = String(p || '').trim();
  if (!s.startsWith('/')) return { allow: false, reason: '必须以 / 开头的绝对路径' };
  if (/['";&|`$><\\()\r\n\u0000-\u001f]/.test(s)) return { allow: false, reason: '路径含引号或 shell 元字符' };
  return { allow: true, reason: 'ok' };
}

module.exports = { checkShellCommand, checkExecoutCmd, checkRemoteReadPath };
