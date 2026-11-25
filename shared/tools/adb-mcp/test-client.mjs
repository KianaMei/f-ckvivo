#!/usr/bin/env node
/**
 * adb-mcp · 端到端与离线协议/红线测试客户端
 * -----------------------------------------
 * 不依赖任何 MCP SDK 客户端，直接对 /mcp 发 JSON-RPC：
 *   - 默认（真机模式）：node test-client.mjs [baseUrl] （默认 http://127.0.0.1:8787）
 *   - 离线模式（无手机）：node test-client.mjs --offline
 *     自动在临时端口拉起本机 server.js + 验证 Streamable HTTP 202/204/405 协议、
 *     红线策略引擎（放行合法只读 + 拦截重定向/命令替换/su 绕过/引号注入）及 daemon 单实例锁。
 */

import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { config } = require('./lib/config');
const policy = require('./lib/policy');

const args = process.argv.slice(2);
const offline = args.includes('--offline');
const baseArg = args.find((a) => !a.startsWith('--'));
const offlinePort = 18787;
const base = baseArg || (offline ? `http://127.0.0.1:${offlinePort}` : `http://127.0.0.1:${config.port}`);
const token = config.token;

if (!token) {
  console.error('[fatal] 未找到 token（secrets/token.txt 或 ADB_MCP_TOKEN）');
  process.exit(1);
}

let sessionId = null;
let pass = 0;
let fail = 0;

function check(name, cond, extra) {
  if (cond) {
    pass++;
    console.log(`  ✅ ${name}${extra ? ` · ${extra}` : ''}`);
  } else {
    fail++;
    console.log(`  ❌ ${name}${extra ? ` · ${extra}` : ''}`);
  }
}

async function waitForHealth(url, timeoutMs = 8000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${url}/healthz`);
      if (r.ok) return true;
    } catch {}
    await new Promise((r) => setTimeout(r, 150));
  }
  return false;
}

async function rpc(method, params) {
  const res = await fetch(`${base}/mcp`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${token}`,
      ...(sessionId ? { 'mcp-session-id': sessionId } : {}),
    },
    body: JSON.stringify({ jsonrpc: '2.0', id: Math.random().toString(36).slice(2), method, params }),
  });
  const sid = res.headers.get('mcp-session-id');
  if (sid) sessionId = sid;
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  return res.json();
}

let serverProc = null;
const testAdbFallback = fs.existsSync(config.adbPath) ? config.adbPath : process.execPath;
if (offline && !baseArg) {
  serverProc = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    env: { ...process.env, ADB_MCP_PORT: String(offlinePort), ADB_PATH: testAdbFallback },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
  let bootErr = '';
  serverProc.stderr.on('data', (d) => { bootErr += d.toString('utf8'); });
  const up = await waitForHealth(base);
  if (!up) {
    console.error(`[fatal] 离线测试 server.js 启动超时: ${bootErr.trim()}`);
    serverProc.kill();
    process.exit(1);
  }
}

try {
  // ---------- 0. healthz 与无 token 401 ----------
  {
    const h = await fetch(`${base}/healthz`);
    const hj = await h.json();
    check('GET /healthz 200', h.status === 200 && hj.ok === true);

    const res = await fetch(`${base}/mcp`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: '1', method: 'ping' }),
    });
    check('401 without token', res.status === 401);
  }

  // ---------- 1. initialize + Streamable HTTP notification 202 + GET 405 ----------
  {
    const r = await rpc('initialize', {});
    check('initialize', r.result?.serverInfo?.name === 'adb-mcp' && !!sessionId);

    const notif = await fetch(`${base}/mcp`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        authorization: `Bearer ${token}`,
        'mcp-session-id': sessionId,
      },
      body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
    });
    const notifBody = await notif.text();
    check('notifications/initialized -> 202 空响应', notif.status === 202 && notifBody === '');

    const getRes = await fetch(`${base}/mcp`, {
      method: 'GET',
      headers: { authorization: `Bearer ${token}` },
    });
    check('GET /mcp -> 405', getRes.status === 405 && /POST/.test(getRes.headers.get('allow') || ''));
  }

  // ---------- 2. tools/list ----------
  {
    const r = await rpc('tools/list', {});
    const tools = r.result?.tools || [];
    const names = tools.map((t) => t.name).sort();
    const logcatSchema = tools.find((t) => t.name === 'adb_logcat')?.inputSchema;
    check('tools/list 8 个工具', names.length === 8, names.join(','));
    check('adb_logcat 已移除写参数 clear', !logcatSchema?.properties?.clear);
  }

  // ---------- 3. 红线策略单测：合法只读放行 ----------
  {
    const allowCases = [
      ['id', '无参动词 id'],
      ['uptime', '无参动词 uptime'],
      ['df -h', 'df -h'],
      ['ls -la /data/adb/modules 2>/dev/null', '只读查 /data/adb + 2>/dev/null'],
      ['cat /sdcard/Android/data/com.xingin.xhs/files/modern_framework_status.txt', '只读查 /sdcard 模块状态'],
      ["su -c 'pidof vectord && ls /data/adb/lspd/log'", 'su -c 复合只读'],
      ['/data/adb/ksu/bin/busybox find /data/adb -maxdepth 2', 'KSU busybox 只读子命令'],
    ];
    for (const [cmd, label] of allowCases) {
      const v = policy.checkShellCommand(cmd);
      check(`policy allow: ${label}`, v.allow === true, v.allow ? '' : v.reason);
    }
  }

  // ---------- 4. adb_shell 红线反例（走 HTTP RPC + 审计）----------
  {
    const denyCases = [
      ['echo hi > /sdcard/x.txt', '写 /sdcard'],
      ['echo 1 > /proc/sys/vm/drop_caches', '写非目录黑名单路径（通用 > 重定向）'],
      ['tee /data/local/tmp/x', 'tee 写设备'],
      ['reboot', '重启'],
      ['am broadcast -a android.intent.action.PACKAGE_ADDED', '广播'],
      ['setprop persist.sys.x 1', 'setprop'],
      ['rm -rf /data/adb', 'rm 设备路径'],
      ['echo pwn > /storage/emulated/0/x', '写 /storage'],
      ["su -c 'input tap 100 200'", 'su -c 绕过注入触控'],
      ["su -c 'svc wifi disable'", 'su -c 绕过切开关'],
      ['/data/adb/ksu/bin/busybox rm /data/adb/a', 'busybox 包装写命令'],
      ['cat $(touch /tmp/pwn)', '$() 命令替换'],
      ['logcat -c', '清空 logcat'],
    ];
    for (const [cmd, why] of denyCases) {
      const r = await rpc('tools/call', { name: 'adb_shell', arguments: { command: cmd } });
      const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
      check(`deny: ${why}`, d.ok === false && /被红线策略拒绝/.test(d.error || ''));
    }
  }

  // ---------- 5. execout 红线：元字符与单引号逃逸 ----------
  {
    for (const badPath of ['/etc/passwd; reboot', "/proc/version' -exec", '/proc/"test"']) {
      const r = await rpc('tools/call', { name: 'adb_execout', arguments: { remote_path: badPath } });
      const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
      check(`deny execout: ${badPath}`, d.ok === false && /路径被拒绝/.test(d.error || ''));
    }
  }

  // ---------- 6. 写能力锁死 ----------
  {
    for (const name of ['adb_push', 'adb_mutate']) {
      const r = await rpc('tools/call', { name, arguments: {} });
      const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
      check(`locked: ${name}`, d.ok === false && /ALLOW_MUTATE/.test(d.error || ''));
    }
  }

  // ---------- 7. 真机专属测试 vs 离线 daemon 单实例锁测试 ----------
  if (!offline) {
    {
      const r = await rpc('tools/call', { name: 'adb_devices', arguments: {} });
      const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
      check('adb_devices', d.ok === true && /10AG632QLS002CF/.test(d.output || ''), d.output?.split('\n')[1]?.trim());
    }
    {
      const r = await rpc('tools/call', { name: 'adb_shell', arguments: { command: 'getprop ro.product.model' } });
      const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
      check('adb_shell getprop', d.ok === true && /V2545A/.test(d.stdout || ''), (d.stdout || '').trim());
    }
    {
      const r = await rpc('tools/call', { name: 'adb_logcat', arguments: { lines: 50 } });
      const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
      check('adb_logcat', d.ok === true && (d.lines_returned || 0) > 0, `lines=${d.lines_returned}`);
    }
    {
      const r = await rpc('tools/call', { name: 'adb_dumpsys', arguments: { section: 'window' } });
      const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
      check('adb_dumpsys window', d.ok === true && /Window/.test(d.output || ''), `${(d.output || '').length} chars`);
    }
    {
      const r = await rpc('tools/call', { name: 'adb_screencap', arguments: {} });
      const img = (r.result?.content || []).find((c) => c.type === 'image');
      check('adb_screencap 返回 PNG', !!img && /^iVBOR/.test(img.data || ''), img ? `${Math.round((img.data.length * 3) / 4 / 1024)}KB` : '');
    }
    {
      const r = await rpc('tools/call', { name: 'adb_execout', arguments: { remote_path: '/proc/meminfo' } });
      const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
      check('adb_execout /proc/meminfo', d.ok === true && /MemTotal/.test(d.output || ''), `${d.size_bytes}B`);
    }
  } else {
    // 离线验证 daemon.js：Named Pipe 单实例幂等退出 + 退出时连带回收子进程 server.js
    const daemonPort = 18788;
    const daemonEnv = {
      ...process.env,
      ADB_MCP_PORT: String(daemonPort),
      ADB_MCP_DISABLE_TUNNEL: '1',
      ADB_PATH: testAdbFallback,
    };
    const d1 = spawn(process.execPath, [path.join(__dirname, 'daemon.js')], {
      env: daemonEnv,
      stdio: 'ignore',
      windowsHide: true,
    });
    const d1Up = await waitForHealth(`http://127.0.0.1:${daemonPort}`, 8000);
    check('daemon 拉起子进程 server.js', d1Up);

    // 第二个 daemon 实例应立即命中单实例锁并以 exitCode=0 退出
    const d2Exit = await new Promise((resolve) => {
      const d2 = spawn(process.execPath, [path.join(__dirname, 'daemon.js')], {
        env: daemonEnv,
        stdio: 'ignore',
        windowsHide: true,
      });
      d2.on('exit', (code) => resolve(code));
    });
    check('daemon 第二实例幂等退出 (code=0)', d2Exit === 0);

    // 读取 daemon-state.json 拿子进程 server.js 的 pid，验证强杀 daemon 后子进程自动随管道 EOF 退出
    const stateFile = path.join(config.cacheDir, 'daemon-state.json');
    let serverPid = null;
    try {
      serverPid = JSON.parse(fs.readFileSync(stateFile, 'utf8'))?.server?.pid;
    } catch {}
    d1.kill();
    await new Promise((r) => setTimeout(r, 800));
    let serverAlive = false;
    if (serverPid) {
      try {
        process.kill(serverPid, 0);
        serverAlive = true;
        process.kill(serverPid);
      } catch {}
    }
    check('daemon 退出后子进程自动回收（无孤儿进程）', !!serverPid && !serverAlive, `serverPid=${serverPid}`);
  }

  // ---------- 8. DELETE /mcp 关闭会话 -> 204 ----------
  {
    const delRes = await fetch(`${base}/mcp`, {
      method: 'DELETE',
      headers: {
        authorization: `Bearer ${token}`,
        'mcp-session-id': sessionId,
      },
    });
    check('DELETE /mcp -> 204', delRes.status === 204);
  }
} finally {
  if (serverProc) {
    try { serverProc.kill(); } catch {}
  }
}

console.log(`\n结果：${pass} 通过 / ${fail} 失败${offline ? '（offline 模式）' : ''}`);
process.exit(fail ? 1 : 0);
