'use strict';
/**
 * adb-mcp · 配置装载模块
 * --------------------
 * 环境变量优先，缺省值满足「机械革命 + vivo X Fold6」本机现状。
 * 密钥（ADB_MCP_TOKEN / CF_TOKEN）不写死在代码/配置里，一律走环境变量或 secrets 文件。
 */

const fs = require('fs');
const path = require('path');

const APP_ROOT = path.join(__dirname, '..');       // shared/tools/adb-mcp
const REPO_ROOT = path.join(APP_ROOT, '..', '..', '..'); // 仓库根
const CACHE_DIR = path.join(REPO_ROOT, 'cache', 'adb-mcp');

function loadTokenFile(p) {
  try {
    const s = fs.readFileSync(p, 'utf8').trim();
    return s || null;
  } catch {
    return null;
  }
}

const config = {
  // —— 服务 ——
  host: process.env.ADB_MCP_HOST || '127.0.0.1',   // 只绑 loopback；公网入口全部走隧道
  port: parseInt(process.env.ADB_MCP_PORT || '8787', 10),

  // —— 鉴权 ——
  token: process.env.ADB_MCP_TOKEN || loadTokenFile(path.join(CACHE_DIR, 'secrets', 'token.txt')),

  // —— adb ——
  adbPath: process.env.ADB_PATH ||
    path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
  serial: process.env.ADB_SERIAL || '10AG632QLS002CF',

  // —— 红线策略 ——
  allowMutate: process.env.ALLOW_MUTATE === '1',    // 写能力默认关
  maxTimeoutMs: parseInt(process.env.ADB_MCP_MAX_TIMEOUT_MS || '20000', 10),
  maxLogcatLines: parseInt(process.env.ADB_MCP_MAX_LOGCAT_LINES || '3000', 10),
  maxBinaryKB: parseInt(process.env.ADB_MCP_MAX_BINARY_KB || '8192', 10),

  // —— 审计（落本机 cache/，不入库）——
  cacheDir: CACHE_DIR,
  auditDir: path.join(CACHE_DIR, 'audit'),
  screenDir: path.join(CACHE_DIR, 'screencap'),
  pullDir: path.join(CACHE_DIR, 'pull'),

  // —— 常量 ——
  REPO_ROOT,
};

module.exports = { config, loadTokenFile };
