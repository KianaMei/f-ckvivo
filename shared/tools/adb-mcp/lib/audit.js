'use strict';
/**
 * adb-mcp · 审计日志
 * ------------------
 * 每次工具调用一行 JSONL，落 cache/adb-mcp/audit/（本机 cache，不入库）。
 * 字段：ts / tool / decision(allow|deny) / reason / caller(token指纹) / 退出码 / 耗时。
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { config } = require('./config');

function tokenFingerprint(token) {
  if (!token) return 'no-token';
  return crypto.createHash('sha256').update(String(token)).digest('hex').slice(0, 12);
}

function writeAudit(entry) {
  try {
    fs.mkdirSync(config.auditDir, { recursive: true });
    const day = new Date().toISOString().slice(0, 10);
    const line = JSON.stringify({
      ts: new Date().toISOString(),
      tool: entry.tool,
      decision: entry.decision,
      reason: entry.reason || '',
      caller: entry.caller || '',
      exit_code: entry.exit_code,
      ms: entry.ms,
      detail: entry.detail ? String(entry.detail).slice(0, 500) : '',
    }) + '\n';
    fs.appendFileSync(path.join(config.auditDir, `audit-${day}.jsonl`), line, 'utf8');
  } catch (e) {
    // 审计失败不许影响主流程，但别静默吞掉
    try { process.stderr.write(`[audit] write failed: ${String(e)}\n`); } catch {}
  }
}

module.exports = { writeAudit, tokenFingerprint };
