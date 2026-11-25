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

// —— 设备写入路径特征（红线：禁止在手机上留任何临时文件）——
const DEVICE_WRITE_PATTERNS = [
  { re: /(^|[\s"'=])(\/|\.\.\/)?(sdcard|storage\/emulated|data\/local\/tmp|data\/user\/0|data\/adb)\b/i,
    reason: '疑似写入设备存储（/sdcard、/data/local/tmp、/data/user/0、/data/adb 等）——「设备不是草稿纸」红线' },
  { re: /\b(tee|dd(?=[^\n]*\bof=)|cp|mv|rm(?=\s)|mkdir(?=\s)|touch(?=\s))\b/i,
    reason: '疑似设备侧写文件操作（tee/dd/cp/mv/rm/mkdir/touch）' },
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
  { re: /\bsetprop\b/i, reason: '禁止 setprop（设备只读红线）' },
  { re: /\bsettings\s+(put|delete)\b/i, reason: '禁止 settings put/delete（设备只读红线）' },
  { re: /\bpm\s+(clear|grant|revoke|reset)\b/i, reason: '禁止 pm clear/grant/revoke/reset' },
  { re: /\btcpip\b|\bconnect:?\d*/i, reason: '禁止 adb tcpip / connect（重启 adbd、丢 USB）' },
  { re: /\bemulator\b|\bemu\b/i, reason: 'emulator 指令禁入' },
];

// —— 只读白名单动词（shell 命令必须以此开头之一）——
const READONLY_PREFIXES = [
  'cat ', 'ls ', 'grep ', 'head ', 'tail ', 'find ', 'stat ', 'wc ',
  'ps ', 'pidof ', 'top -n', 'dumpsys ', 'getprop ', 'settings get',
  'logcat ', 'id ', 'date ', 'uptime ', 'df ', 'free ', 'mount ',
  'cmd ', 'pm list', 'pm path', 'pm dump', 'am stack list', 'service list',
  'toybox ', 'busybox ', 'sha256sum ', 'md5sum ', 'du ', 'which ', 'echo $',
];

function checkShellCommand(cmd) {
  const c = String(cmd || '').trim();
  if (!c) return { allow: false, reason: '空命令' };
  if (c.length > 4000) return { allow: false, reason: '命令过长（>4000 字符）' };
  if (/[\u0000-\u0008\u000b-\u001f]/.test(c)) return { allow: false, reason: '命令含控制字符' };

  for (const p of DEVICE_WRITE_PATTERNS) {
    if (p.re.test(c)) return { allow: false, reason: p.reason };
  }
  for (const p of DANGEROUS_PATTERNS) {
    if (p.re.test(c)) return { allow: false, reason: p.reason };
  }

  // 多行命令：逐行过白名单
  const lines = c.split(/\n|&&|\|\||;|\|/g).map((s) => s.trim()).filter(Boolean);
  for (const line of lines) {
    const ok = READONLY_PREFIXES.some((pre) => line.startsWith(pre)) ||
               line.startsWith('su -c ') || line.startsWith('su 0 ');
    if (!ok) {
      return { allow: false, reason: `命令不在只读白名单内：${line.slice(0, 80)}` };
    }
  }
  return { allow: true, reason: 'ok' };
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

// —— remote path 校验（用于 exec-out cat）：只许读，路径不许带 shell 元字符 ——
function checkRemoteReadPath(p) {
  const s = String(p || '').trim();
  if (!s.startsWith('/')) return { allow: false, reason: '必须以 / 开头的绝对路径' };
  if (/[;&|`$><\\\n]/.test(s)) return { allow: false, reason: '路径含 shell 元字符' };
  return { allow: true, reason: 'ok' };
}

module.exports = { checkShellCommand, checkExecoutCmd, checkRemoteReadPath };
