'use strict';
/**
 * adb-mcp · 隧道同步守护
 * ----------------------
 * 职责：
 *   1. 拉起 cloudflared quick tunnel（指向 127.0.0.1:8787）
 *   2. 从隧道日志抓 trycloudflare URL
 *   3. 把 URL 发布到 Cloudflare DNS TXT：adb-mcp-tunnel.mamba-out.com → "tunnel=<url>"
 *      （kaslana 侧 Resolve-AdbMcp.ps1 定期读 TXT 更新 mcp_config.json）
 *   4. 隧道进程退出 → 指数退避重启，并刷新 TXT
 *
 * 状态落盘：cache/adb-mcp/tunnel-state.json（当前 URL / 最近心跳 / 重启次数）
 * CF token 读取：cache/adb-mcp/secrets/cf-token.txt（dns_records:edit 即可）
 *
 * 用法：node tunnel-sync.js   （由 run-mechrevo.cmd 常驻调用）
 */

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const { config, loadTokenFile } = require('./lib/config');

const CLOUDFLARED = path.join(config.cacheDir, 'bin', 'cloudflared.exe');
const CF_TOKEN = process.env.CF_TOKEN || loadTokenFile(path.join(config.cacheDir, 'secrets', 'cf-token.txt'));
const ZONE_ID = process.env.CF_ZONE_ID || 'd234ff68f758b35ac771522769d52d3d';
const DNS_NAME = process.env.CF_DNS_NAME || 'adb-mcp-tunnel.mamba-out.com';
const STATE_FILE = path.join(config.cacheDir, 'tunnel-state.json');
const LOCAL_PORT = config.port;

function log(msg) {
  const line = `[tunnel-sync] ${new Date().toISOString()} ${msg}`;
  console.log(line);
  try {
    fs.mkdirSync(path.join(config.cacheDir, 'logs'), { recursive: true });
    fs.appendFileSync(path.join(config.cacheDir, 'logs', 'tunnel-sync.log'), line + '\n');
  } catch {}
}

function writeState(state) {
  try {
    fs.writeFileSync(STATE_FILE, JSON.stringify({ ...state, updated: new Date().toISOString() }, null, 2));
  } catch {}
}

async function cfApi(method, apiPath, body) {
  const res = await fetch(`https://api.cloudflare.com/client/v4${apiPath}`, {
    method,
    headers: {
      authorization: `Bearer ${CF_TOKEN}`,
      'content-type': 'application/json',
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const j = await res.json().catch(() => ({}));
  if (!j.success) {
    throw new Error(`CF API ${method} ${apiPath} 失败: ${JSON.stringify(j.errors || j).slice(0, 300)}`);
  }
  return j.result;
}

async function publishTxt(url) {
  const content = `tunnel=${url}`;
  // 查现有记录
  const existing = await cfApi(
    'GET',
    `/zones/${ZONE_ID}/dns_records?type=TXT&name=${encodeURIComponent(DNS_NAME)}&per_page=5`
  );
  const rec = Array.isArray(existing) ? existing[0] : null;
  if (rec) {
    if (rec.content === content) {
      log(`TXT 已是最新（${DNS_NAME}）`);
      return;
    }
    await cfApi('PUT', `/zones/${ZONE_ID}/dns_records/${rec.id}`, {
      type: 'TXT',
      name: DNS_NAME,
      content,
      ttl: 60,
    });
    log(`TXT 已更新（${DNS_NAME} → tunnel=${url}）`);
  } else {
    await cfApi('POST', `/zones/${ZONE_ID}/dns_records`, {
      type: 'TXT',
      name: DNS_NAME,
      content,
      ttl: 60,
    });
    log(`TXT 已创建（${DNS_NAME} → tunnel=${url}）`);
  }
}

function waitForUrl(child, timeoutMs) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    const timer = setInterval(() => {
      try {
        const logTxt = fs.readFileSync(TUNNEL_LOG, 'utf8');
        const m = logTxt.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/);
        if (m) {
          clearInterval(timer);
          resolve(m[0]);
        } else if (Date.now() > deadline) {
          clearInterval(timer);
          reject(new Error('等待隧道 URL 超时'));
        }
      } catch {
        if (Date.now() > deadline) {
          clearInterval(timer);
          reject(new Error('等待隧道 URL 超时（读日志失败）'));
        }
      }
    }, 500);
    child.on('exit', (code) => {
      clearInterval(timer);
      reject(new Error(`cloudflared 提前退出 code=${code}`));
    });
  });
}

const TUNNEL_LOG = path.join(config.cacheDir, 'logs', 'cloudflared.log');

async function runTunnelOnce() {
  fs.mkdirSync(path.join(config.cacheDir, 'logs'), { recursive: true });
  try { fs.writeFileSync(TUNNEL_LOG, ''); } catch {}
  const out = fs.openSync(TUNNEL_LOG, 'a');
  const child = spawn(CLOUDFLARED, [
    'tunnel', '--url', `http://127.0.0.1:${LOCAL_PORT}`,
    '--no-autoupdate', '--protocol', 'quic',
  ], { stdio: ['ignore', out, out], windowsHide: true });
  const url = await waitForUrl(child, 60000);
  return { child, url };
}

async function main() {
  if (!fs.existsSync(CLOUDFLARED)) throw new Error(`cloudflared 不存在：${CLOUDFLARED}`);
  if (!CF_TOKEN) throw new Error('未找到 CF_TOKEN（cache/adb-mcp/secrets/cf-token.txt）');

  let backoffMs = 3000;
  let restarts = 0;
  let lastUrl = null;

  while (true) {
    let tunnel;
    try {
      tunnel = await runTunnelOnce();
    } catch (e) {
      log(`隧道启动失败：${e.message}，${backoffMs / 1000}s 后重试`);
      writeState({ status: 'starting-failed', error: e.message, restarts });
      await new Promise((r) => setTimeout(r, backoffMs));
      backoffMs = Math.min(backoffMs * 2, 60000);
      continue;
    }

    lastUrl = tunnel.url;
    restarts++;
    backoffMs = 3000;
    log(`隧道已上线：${tunnel.url}（第 ${restarts} 次启动）`);
    writeState({ status: 'up', url: lastUrl, restarts, pid: tunnel.child.pid });

    // 发布 TXT（失败不影响守护，下轮重试）
    try {
      await publishTxt(lastUrl);
    } catch (e) {
      log(`TXT 发布失败：${e.message}`);
    }

    // 监视进程 + 每小时主动核对一次 TXT（防外因清掉记录）
    let txtVerifiedAt = Date.now();
    await new Promise((resolve) => {
      tunnel.child.on('exit', (code) => {
        log(`cloudflared 退出（code=${code}），即将重启`);
        resolve();
      });
      const mon = setInterval(async () => {
        if (tunnel.child.exitCode !== null || tunnel.child.killed) {
          clearInterval(mon);
          return;
        }
        if (Date.now() - txtVerifiedAt > 3600_000) {
          txtVerifiedAt = Date.now();
          try {
            await publishTxt(lastUrl);
          } catch (e) {
            log(`TXT 定期核对失败：${e.message}`);
          }
          writeState({ status: 'up', url: lastUrl, restarts, pid: tunnel.child.pid });
        }
      }, 30_000);
      tunnel.child.once('exit', () => clearInterval(mon));
    });
  }
}

process.on('SIGTERM', () => process.exit(0));
process.on('SIGINT', () => process.exit(0));
main().catch((e) => {
  console.error(`[tunnel-sync][fatal] ${e.message}`);
  process.exit(1);
});
