'use strict';
/**
 * adb-mcp · 单一守护进程（零窗口）
 * --------------------------------
 * 由 Startup\adb-mcp.vbs 以「node daemon.js + 隐藏窗口」拉起，整条链路没有任何
 * cmd/console 窗口：
 *   - 子进程 1：server.js（MCP HTTP 端点），崩溃自动重启（指数退避）
 *   - 子进程 2：tunnel-sync.js（cloudflared 守护 + TXT 同步，内部自带重启循环）
 *   - adb server 保活：启动时 + 每 30 分钟 `adb start-server`（stdio ignore，无窗口）
 *   - 状态落盘：cache/adb-mcp/daemon-state.json（两个子进程 pid/重启数/上次退出码）
 *
 * 日志：cache/adb-mcp/logs/{daemon,server,tunnel-sync}.json
 * 手动拉起（无窗口）：run-mechrevo.cmd（PowerShell Start-Process Hidden 包装本文件）
 */

const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const net = require('net');
const path = require('path');
const { config } = require('./lib/config');

const NODE = process.execPath;
const LOG_DIR = path.join(config.cacheDir, 'logs');
const STATE_FILE = path.join(config.cacheDir, 'daemon-state.json');
const LOCK_PIPE = process.platform === 'win32'
  ? `\\\\.\\pipe\\adb-mcp-daemon-${config.port}`
  : path.join(config.cacheDir, `daemon-${config.port}.sock`);
fs.mkdirSync(LOG_DIR, { recursive: true });

let shuttingDown = false;
const activeChildren = new Map();
let lockServer = null;

function log(msg) {
  const line = `[daemon] ${new Date().toISOString()} ${msg}`;
  // 无控制台（VBS 隐藏窗口拉起）时 console.log 可能抛异常，必须隔离
  try { console.log(line); } catch {}
  try { fs.appendFileSync(path.join(LOG_DIR, 'daemon.log'), line + '\n'); } catch {}
}

function writeState(state) {
  const prev = { started: null, server: {}, tunnel: {} };
  try { Object.assign(prev, JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'))); } catch {}
  try {
    fs.writeFileSync(STATE_FILE, JSON.stringify({ ...prev, ...state, updated: new Date().toISOString() }, null, 2));
  } catch {}
}

function startAdb() {
  try {
    const p = spawn(config.adbPath, ['start-server'], { windowsHide: true, stdio: 'ignore' });
    p.on('error', (e) => log(`adb start-server 失败：${e.message}`));
  } catch (e) {
    log(`adb start-server 异常：${e.message}`);
  }
}

/**
 * 单实例互斥锁：Windows 下采用内核 Named Pipe，进程崩溃/断电由内核立即回收句柄，
 * 不开任何 TCP 端口、不触发防火墙、无残留锁文件或 PID 复用误判。
 */
function acquireSingleInstanceLock() {
  return new Promise((resolve) => {
    const srv = net.createServer((sock) => sock.end());
    srv.once('error', (err) => {
      if (err && err.code === 'EADDRINUSE') {
        log(`检测到已有 daemon 实例在运行（pipe=${LOCK_PIPE}），本次启动幂等退出`);
        resolve(false);
      } else {
        log(`单实例锁异常（${err.message}），继续启动`);
        resolve(true);
      }
    });
    srv.listen(LOCK_PIPE, () => {
      lockServer = srv;
      resolve(true);
    });
  });
}

function killChildTree(child) {
  if (!child || !child.pid || child.exitCode !== null) return;
  if (process.platform === 'win32') {
    // 连带清理孙进程（如 tunnel-sync.js 拉起的 cloudflared.exe）
    try {
      spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
      return;
    } catch {}
  }
  try { child.kill(); } catch {}
}

/**
 * 拉起并监视一个 node 子进程（全隐藏窗口；输出追加到日志文件）。
 * 退避：3s 起、翻倍至 60s 封顶；存活 >5 分钟则视为稳定、计数归零。
 */
function supervise(script, logName, stateKey) {
  let backoffMs = 3000;
  let restarts = 0;
  let child = null;

  function write() {
    writeState({
      [stateKey]: {
        pid: child && child.exitCode === null ? child.pid : null,
        restarts,
        lastExit: child ? child.exitCode : null,
      },
    });
  }

  function boot() {
    if (shuttingDown) return;
    let out;
    try { out = fs.openSync(path.join(LOG_DIR, logName), 'a'); } catch { out = 'ignore'; }
    // stdin 用 pipe 保持父子生命线：即使 Windows 下 daemon 被 TerminateProcess 强杀，
    // 内核关闭管道写端也会立即触发子进程 stdin EOF 自退，杜绝孤儿进程
    child = spawn(NODE, [path.join(__dirname, script)], {
      windowsHide: true,
      stdio: ['pipe', out, out],
      env: { ...process.env, ADB_MCP_DAEMON: '1' },
    });
    if (child.stdin) child.stdin.on('error', () => {});
    // 子进程已继承 fd，父进程立即关闭避免重连泄漏句柄
    if (typeof out === 'number') {
      try { fs.closeSync(out); } catch {}
    }
    activeChildren.set(stateKey, child);
    const born = Date.now();
    restarts++;
    log(`${script} 启动 pid=${child.pid}（第 ${restarts} 次）`);
    write();

    child.on('exit', (code) => {
      activeChildren.delete(stateKey);
      if (shuttingDown) return;
      const livedMs = Date.now() - born;
      if (livedMs > 5 * 60_000) backoffMs = 3000;
      log(`${script} 退出 code=${code}，${backoffMs / 1000}s 后重启`);
      write();
      setTimeout(boot, backoffMs);
      backoffMs = Math.min(backoffMs * 2, 60_000);
    });
  }

  boot();
}

// ---- 收尾：daemon 退出时带走全部子进程树 ----
function killAll() {
  if (shuttingDown) return;
  shuttingDown = true;
  log('daemon 收到退出信号，正在清理子进程');
  for (const c of activeChildren.values()) {
    killChildTree(c);
  }
  activeChildren.clear();
  if (lockServer) {
    try { lockServer.close(); } catch {}
  }
  process.exit(0);
}
process.on('SIGTERM', killAll);
process.on('SIGINT', killAll);
process.on('SIGHUP', killAll);

// ---- 主流程 ----
(async () => {
  const locked = await acquireSingleInstanceLock();
  if (!locked) process.exit(0);

  writeState({ started: new Date().toISOString(), supervisor: process.pid });
  log(`daemon 启动 pid=${process.pid}（token fp 由子进程自检）`);
  startAdb();
  setInterval(startAdb, 30 * 60_000).unref();

  supervise('server.js', 'server.log', 'server');
  if (process.env.ADB_MCP_DISABLE_TUNNEL !== '1') {
    setTimeout(() => supervise('tunnel-sync.js', 'tunnel-sync.log', 'tunnel'), 1500);
  }

  log('守护就绪：server.js + tunnel-sync.js + adb keepalive(30min)');
})();
