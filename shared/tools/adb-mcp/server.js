'use strict';
/**
 * adb-mcp · 主服务
 * ----------------
 * MCP Streamable HTTP（/mcp 端点）+ Bearer 鉴权 + 常量密钥比较。
 * 只绑 127.0.0.1 —— 公网暴露全部交给 cloudflared 隧道（见 start-tunnel.cmd）。
 *
 * 启动：node server.js
 * 环境变量见 lib/config.js。
 */

const http = require('http');
const crypto = require('crypto');
const { config } = require('./lib/config');
const { schemas, handle } = require('./lib/tools');
const { tokenFingerprint } = require('./lib/audit');

// ---------- MCP 协议层（Streamable HTTP, 最小实现）----------
// 端点：POST /mcp  {jsonrpc:"2.0", id, method, params}
// 会话：MCP-Session-Id 头（initialize 分配；本网关无状态，任何 id 都接受）

const PROTOCOL_VERSION = '2025-06-18';
const TOOLS_LIST = Object.entries(schemas).map(([name, s]) => ({
  name,
  description: {
    adb_devices: '列出 adb 设备（只读，无参数）',
    adb_shell: '在手机上执行只读 shell 命令（白名单过滤，写操作会被拒绝）',
    adb_logcat: '读取 logcat 缓冲（-d，只读；可按 tag/行数过滤）',
    adb_dumpsys: '读取 dumpsys 指定 section（只读）',
    adb_screencap: '截图并返回 PNG（exec-out 管道直拉，不落设备盘）',
    adb_execout: 'exec-out su -c "cat <path>" 直拉设备文件（文本或 base64，不落设备盘）',
    adb_push: '[已锁] 推文件到设备（ALLOW_MUTATE 门禁，默认拒绝）',
    adb_mutate: '[已锁] install/uninstall/kill 等变更操作（ALLOW_MUTATE 门禁，默认拒绝）',
  }[name] || '',
  inputSchema: Object.keys(s).length ? { type: 'object', ...s } : { type: 'object', properties: {} },
}));

const sessions = new Set();

function jsonRpcError(id, code, message) {
  return { jsonrpc: '2.0', id, error: { code, message } };
}

function makeSessionId() {
  return crypto.randomBytes(16).toString('hex');
}

async function dispatch(reqBody) {
  const { id, method, params } = reqBody;
  switch (method) {
    case 'initialize':
      return {
        jsonrpc: '2.0',
        id,
        result: {
          protocolVersion: PROTOCOL_VERSION,
          capabilities: { tools: {} },
          serverInfo: { name: 'adb-mcp', version: '1.0.0', title: 'ADB Phone Gateway (read-only)' },
          instructions:
            'vivo X Fold6 只读 ADB 网关。shell 命令过白名单；写设备文件 / install / kill / reboot 等会被策略拒绝并记入审计。',
        },
      };
    case 'notifications/initialized':
      return null; // 通知无响应体
    case 'ping':
      return { jsonrpc: '2.0', id, result: {} };
    case 'tools/list':
      return { jsonrpc: '2.0', id, result: { tools: TOOLS_LIST } };
    case 'tools/call': {
      const name = params && params.name;
      const args = (params && params.arguments) || {};
      const caller = tokenFingerprint(authTokenFromRequest);
      const r = await handle(name, args, caller);
      return { jsonrpc: '2.0', id, result: r };
    }
    default:
      return jsonRpcError(id, -32601, `method not found: ${method}`);
  }
}

// ---------- HTTP 层 ----------
let authTokenFromRequest = null; // 每请求重置（单线程事件循环下安全）

function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) {
    crypto.timingSafeEqual(ba, ba); // 定时兜底
    return false;
  }
  return crypto.timingSafeEqual(ba, bb);
}

function readBody(req, cap = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (d) => {
      size += d.length;
      if (size > cap) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(d);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const started = Date.now();
  const url = (req.url || '').split('?')[0];

  // 健康检查（无鉴权，只回版本，不回任何设备信息）
  if (req.method === 'GET' && url === '/healthz') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, service: 'adb-mcp', version: '1.0.0', uptime_s: process.uptime() | 0 }));
    return;
  }

  if (url !== '/mcp') {
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'not found', endpoints: ['/mcp (POST)', '/healthz (GET)'] }));
    return;
  }

  // 鉴权
  const auth = req.headers.authorization || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  authTokenFromRequest = token;
  if (!config.token || !token || !safeEqual(token, config.token)) {
    res.writeHead(401, { 'content-type': 'application/json', 'www-authenticate': 'Bearer realm="adb-mcp"' });
    res.end(JSON.stringify({ error: 'unauthorized' }));
    return;
  }

  if (req.method !== 'POST') {
    res.writeHead(405, { 'content-type': 'application/json', allow: 'POST' });
    res.end(JSON.stringify({ error: 'method not allowed' }));
    return;
  }

  // 会话头（宽松处理：无状态网关，接受任意/缺失 session id）
  let sessionId = req.headers['mcp-session-id'];
  if (!sessionId || !sessions.has(sessionId)) {
    sessionId = makeSessionId();
    sessions.add(sessionId);
  }

  let body;
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    res.writeHead(400, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'invalid json' }));
    return;
  }

  // 批量与单条都兼容
  const isBatch = Array.isArray(body);
  const items = isBatch ? body : [body];
  const results = [];
  for (const item of items) {
    if (!item || typeof item.jsonrpc !== 'string') {
      results.push(jsonRpcError(null, -32600, 'invalid request'));
      continue;
    }
    try {
      const out = await dispatch(item);
      if (out) results.push(out);
    } catch (e) {
      results.push(jsonRpcError(item.id, -32603, String(e).slice(0, 200)));
    }
  }

  const outBody = isBatch ? results : results[0];
  res.writeHead(200, {
    'content-type': 'application/json',
    'mcp-session-id': sessionId,
    'cache-control': 'no-store',
  });
  res.end(JSON.stringify(outBody ?? {}));
  if (Date.now() - started > 3000) {
    process.stderr.write(`[slow] ${(Date.now() - started)}ms ${(items[0] || {}).method}\n`);
  }
});

// ---------- 启动 ----------
(async () => {
  if (!config.token) {
    process.stderr.write('[fatal] 未配置 ADB_MCP_TOKEN（cache/adb-mcp/secrets/token.txt 或环境变量）\n');
    process.exit(1);
  }
  try {
    require('./lib/adb').adbPath();
  } catch (e) {
    process.stderr.write(`[fatal] ${e.message}\n`);
    process.exit(1);
  }
  server.listen(config.port, config.host, () => {
    process.stdout.write(
      `[adb-mcp] listening http://${config.host}:${config.port}/mcp (token fp ${tokenFingerprint(config.token)}, mutate=${config.allowMutate ? 'ON' : 'off'})\n`
    );
  });
})();

process.on('SIGTERM', () => process.exit(0));
process.on('SIGINT', () => process.exit(0));
