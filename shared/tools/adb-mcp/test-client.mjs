#!/usr/bin/env node
/**
 * adb-mcp · 本机端到端测试客户端
 * -----------------------------
 * 不依赖任何 MCP SDK 客户端，直接对 /mcp 发 JSON-RPC：
 *   1) initialize  2) tools/list  3) 逐个调只读工具  4) 红线反例（应全部被拒）
 * 用法：node test-client.mjs [baseUrl] （默认 http://127.0.0.1:8787）
 */

const base = process.argv[2] || 'http://127.0.0.1:8787';
const token = (await import('node:fs')).readFileSync(
  String.raw`C:\mycode\somethingsmall\fuckmyphone\cache\adb-mcp\secrets\token.txt`,
  'utf8'
).trim();

let sessionId = null;
let pass = 0;
let fail = 0;

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

function check(name, cond, extra) {
  if (cond) {
    pass++;
    console.log(`  ✅ ${name}${extra ? ` · ${extra}` : ''}`);
  } else {
    fail++;
    console.log(`  ❌ ${name}${extra ? ` · ${extra}` : ''}`);
  }
}

// ---------- 0. 无 token 应 401 ----------
{
  const res = await fetch(`${base}/mcp`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: '1', method: 'ping' }),
  });
  check('401 without token', res.status === 401);
}

// ---------- 1. initialize ----------
{
  const r = await rpc('initialize', {});
  check('initialize', r.result?.serverInfo?.name === 'adb-mcp');
}

// ---------- 2. tools/list ----------
{
  const r = await rpc('tools/list', {});
  const names = (r.result?.tools || []).map((t) => t.name).sort();
  check('tools/list 8 个工具', names.length === 8, names.join(','));
}

// ---------- 3. adb_devices ----------
{
  const r = await rpc('tools/call', { name: 'adb_devices', arguments: {} });
  const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
  check('adb_devices', d.ok === true && /10AG632QLS002CF/.test(d.output || ''), d.output?.split('\n')[1]?.trim());
}

// ---------- 4. adb_shell 只读 ----------
{
  const r = await rpc('tools/call', { name: 'adb_shell', arguments: { command: 'getprop ro.product.model' } });
  const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
  check('adb_shell getprop', d.ok === true && /V2545A/.test(d.stdout || ''), (d.stdout || '').trim());
}

// ---------- 5. adb_shell 红线反例 ----------
{
  const cases = [
    ['echo hi > /sdcard/x.txt', '写 /sdcard'],
    ['tee /data/local/tmp/x', 'tee 写设备'],
    ['reboot', '重启'],
    ['am broadcast -a android.intent.action.PACKAGE_ADDED', '广播'],
    ['setprop persist.sys.x 1', 'setprop'],
    ['rm -rf /data/adb', 'rm 设备路径'],
    ['echo pwn > /storage/emulated/0/x', '写 /storage'],
  ];
  for (const [cmd, why] of cases) {
    const r = await rpc('tools/call', { name: 'adb_shell', arguments: { command: cmd } });
    const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
    check(`deny: ${why}`, d.ok === false && /被红线策略拒绝/.test(d.error || ''));
  }
}

// ---------- 6. adb_logcat ----------
{
  const r = await rpc('tools/call', { name: 'adb_logcat', arguments: { lines: 50 } });
  const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
  check('adb_logcat', d.ok === true && (d.lines_returned || 0) > 0, `lines=${d.lines_returned}`);
}

// ---------- 7. adb_dumpsys ----------
{
  const r = await rpc('tools/call', { name: 'adb_dumpsys', arguments: { section: 'window' } });
  const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
  check('adb_dumpsys window', d.ok === true && /Window/.test(d.output || ''), `${(d.output || '').length} chars`);
}

// ---------- 8. adb_screencap ----------
{
  const r = await rpc('tools/call', { name: 'adb_screencap', arguments: {} });
  const img = (r.result?.content || []).find((c) => c.type === 'image');
  check('adb_screencap 返回 PNG', !!img && /^iVBOR/.test(img.data || ''), img ? `${Math.round((img.data.length * 3) / 4 / 1024)}KB` : '');
}

// ---------- 9. adb_execout ----------
{
  const r = await rpc('tools/call', { name: 'adb_execout', arguments: { remote_path: '/proc/meminfo' } });
  const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
  check('adb_execout /proc/meminfo', d.ok === true && /MemTotal/.test(d.output || ''), `${d.size_bytes}B`);
}

// ---------- 10. execout 红线：元字符路径 ----------
{
  const r = await rpc('tools/call', { name: 'adb_execout', arguments: { remote_path: '/etc/passwd; reboot' } });
  const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
  check('deny execout 元字符', d.ok === false);
}

// ---------- 11. 写能力锁死 ----------
{
  for (const name of ['adb_push', 'adb_mutate']) {
    const r = await rpc('tools/call', { name, arguments: {} });
    const d = JSON.parse(r.result?.content?.[0]?.text || '{}');
    check(`locked: ${name}`, d.ok === false && /ALLOW_MUTATE/.test(d.error || ''));
  }
}

console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);
