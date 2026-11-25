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
const SECRETS_DIR = path.join(APP_ROOT, 'secrets');   // 密钥随主仓入库（本仓惯例：签名材料一律入库）

function resolveDefaultAdb() {
  if (process.env.ADB_PATH) return process.env.ADB_PATH;
  const exe = process.platform === 'win32' ? 'adb.exe' : 'adb';
  const candidates = [
    process.env.ANDROID_SDK_ROOT && path.join(process.env.ANDROID_SDK_ROOT, 'platform-tools', exe),
    process.env.ANDROID_HOME && path.join(process.env.ANDROID_HOME, 'platform-tools', exe),
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', exe),
  ].filter(Boolean);
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    if (dir) candidates.push(path.join(dir.trim(), exe));
  }
  for (const c of candidates) {
    try {
      if (fs.existsSync(c)) return c;
    } catch {}
  }
  return candidates[0] || exe;
}
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

  // —— 鉴权 ——（密钥入库：shared/tools/adb-mcp/secrets/，env 优先，cache 兼容旧位置）
  token: process.env.ADB_MCP_TOKEN ||
    loadTokenFile(path.join(SECRETS_DIR, 'token.txt')) ||
    loadTokenFile(path.join(CACHE_DIR, 'secrets', 'token.txt')),

  // —— adb ——（ADB_PATH -> ANDROID_SDK_ROOT -> LOCALAPPDATA -> PATH）
  adbPath: resolveDefaultAdb(),
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
