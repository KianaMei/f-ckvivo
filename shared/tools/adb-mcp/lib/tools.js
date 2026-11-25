'use strict';
/**
 * adb-mcp · MCP 工具定义与处理逻辑
 * --------------------------------
 * 8 个工具，默认全只读。写能力（adb_push / adb_mutate）编译进代码但被
 * ALLOW_MUTATE 门禁锁死（默认 0），返回明确错误而不是假装成功。
 */

const fs = require('fs');
const path = require('path');
const { config } = require('./config');
const policy = require('./policy');
const { writeAudit } = require('./audit');
const adb = require('./adb');

// ---------- 工具 schema（Zod）----------
const schemas = {
  adb_devices: {},

  adb_shell: {
    type: 'object',
    properties: {
      command: { type: 'string', description: '只读 shell 命令，如 "getprop ro.product.model"' },
    },
    required: ['command'],
  },

  adb_logcat: {
    type: 'object',
    properties: {
      tag: { type: 'string', description: '按 tag 过滤，如 "LSPosed-Bridge" 或 "LSPosed-Bridge:V *:S"' },
      lines: { type: 'number', description: `返回行数上限（默认 800，最大 ${config.maxLogcatLines}）` },
    },
  },

  adb_dumpsys: {
    type: 'object',
    properties: {
      section: { type: 'string', description: 'dumpsys section，如 "activity"、"window"' },
    },
  },

  adb_screencap: {
    type: 'object',
    properties: {
      save: { type: 'boolean', description: '同时落盘到 cache/adb-mcp/screencap/（默认 false）' },
    },
  },

  adb_execout: {
    type: 'object',
    properties: {
      remote_path: { type: 'string', description: '设备绝对路径，如 /proc/meminfo' },
      as_text: { type: 'boolean', description: '文本模式（默认 true）；false 时 base64' },
    },
    required: ['remote_path'],
  },

  adb_push: {
    type: 'object',
    properties: {
      local_path: { type: 'string', description: '本机文件路径' },
      remote_path: { type: 'string', description: '设备目标路径' },
    },
    required: ['local_path', 'remote_path'],
  },

  adb_mutate: {
    type: 'object',
    properties: {
      action: { type: 'string', description: '预留：install/uninstall/pm-clear/kill 等，全部默认拒绝' },
    },
    required: ['action'],
  },
};

// ---------- 内部小工具 ----------
function makeResult(extra) {
  return {
    content: [{ type: 'text', text: JSON.stringify({ ok: true, ...extra }) }],
  };
}

function makeError(reason, extra) {
  return {
    content: [{ type: 'text', text: JSON.stringify({ ok: false, error: reason, ...extra }) }],
    isError: true,
  };
}

async function handle(toolName, args, caller) {
  const t0 = Date.now();
  const audit = (decision, reason, extra) =>
    writeAudit({ tool: toolName, decision, reason, caller, ms: Date.now() - t0, ...extra });
  const argsIn = args || {};

  try {
    switch (toolName) {
      // —— 1. 设备列表 ——
      case 'adb_devices': {
        const r = await adb.listDevices();
        audit('allow', `exit=${r.exitCode}`);
        return makeResult({ device: 'adb devices -l', exit_code: r.exitCode, output: r.text.trim() });
      }

      // —— 2. shell（白名单只读）——
      case 'adb_shell': {
        const verdict = policy.checkShellCommand(argsIn.command);
        if (!verdict.allow) {
          audit('deny', verdict.reason, { detail: argsIn.command });
          return makeError(`被红线策略拒绝：${verdict.reason}`);
        }
        const r = await adb.runAdb(['-s', config.serial, 'shell', String(argsIn.command).trim()]);
        const out = r.stdout.toString('utf8');
        const errS = r.stderr.toString('utf8');
        audit('allow', `exit=${r.exitCode}${r.timedOut ? ' timeout' : ''}`, { exit_code: r.exitCode, detail: argsIn.command });
        return makeResult({
          command: String(argsIn.command).slice(0, 200),
          exit_code: r.exitCode,
          timed_out: !!r.timedOut,
          stdout: out.slice(0, 20000),
          stderr: errS.slice(0, 2000),
        });
      }

      // —— 3. logcat ——
      case 'adb_logcat': {
        const lines = Math.min(parseInt(argsIn.lines || '800', 10) || 800, config.maxLogcatLines);
        const tag = String(argsIn.tag || '').trim().replace(/[^A-Za-z0-9_.*: -]/g, '');
        const args = ['-s', config.serial, 'logcat', '-d', '-t', String(lines)];
        if (tag) args.push(...tag.split(/\s+/).filter(Boolean));
        const r = await adb.runAdb(args, { timeoutMs: config.maxTimeoutMs, maxBytes: 1024 * 1024 });
        const out = r.stdout.toString('utf8');
        audit('allow', `exit=${r.exitCode} lines=${out ? out.split('\n').length : 0}`);
        return makeResult({
          exit_code: r.exitCode,
          lines_returned: out ? out.split('\n').length : 0,
          output: out.slice(0, 40000),
        });
      }

      // —— 4. dumpsys ——
      case 'adb_dumpsys': {
        const section = String(argsIn.section || '').trim().replace(/[^A-Za-z0-9_ .:-]/g, '');
        const r = await adb.runAdb(
          section ? ['-s', config.serial, 'shell', 'dumpsys', section] : ['-s', config.serial, 'shell', 'dumpsys', ''],
          { timeoutMs: config.maxTimeoutMs, maxBytes: 1024 * 1024 }
        );
        const out = r.stdout.toString('utf8');
        audit('allow', `exit=${r.exitCode} section=${section || 'all'}`);
        return makeResult({
          section: section || '(all)',
          exit_code: r.exitCode,
          output: out.slice(0, 40000),
        });
      }

      // —— 5. screencap ——
      case 'adb_screencap': {
        const png = await adb.screencap();
        const content = [{ type: 'text', text: JSON.stringify({ ok: true, size_bytes: png.length }) }];
        let note = '';
        if (argsIn.save) {
          fs.mkdirSync(config.screenDir, { recursive: true });
          const f = path.join(config.screenDir, `screencap-${new Date().toISOString().replace(/[:.]/g, '-')}.png`);
          fs.writeFileSync(f, png);
          note = f;
        }
        audit('allow', `png=${png.length}B${note ? ` saved=${path.basename(note)}` : ''}`);
        return {
          content: [
            ...content,
            { type: 'image', data: png.toString('base64'), mimeType: 'image/png' },
          ],
          // 顺手留一份本机路径，方便后续人工取用
          ...(note ? { savedTo: note } : {}),
        };
      }

      // —— 6. exec-out（管道直拉，不落设备盘）——
      case 'adb_execout': {
        const rp = String(argsIn.remote_path || '');
        const v1 = policy.checkRemoteReadPath(rp);
        if (!v1.allow) { audit('deny', v1.reason, { detail: rp }); return makeError(`路径被拒绝：${v1.reason}`); }
        const buf = await adb.execoutCat(rp);
        if (buf.length > config.maxBinaryKB * 1024) {
          audit('deny', `too-large ${buf.length}B`, { detail: rp });
          return makeError(`文件超过上限（${config.maxBinaryKB}KB）：${rp}`);
        }
        audit('allow', `read ${buf.length}B from ${rp}`);
        if (argsIn.as_text === false) {
          return {
            content: [
              { type: 'text', text: JSON.stringify({ ok: true, size_bytes: buf.length, remote_path: rp, encoding: 'base64' }) },
              { type: 'text', text: buf.toString('base64') },
            ],
          };
        }
        return makeResult({ remote_path: rp, size_bytes: buf.length, output: buf.toString('utf8').slice(0, 40000) });
      }

      // —— 7/8. 写能力：编译进去，默认锁死 ——
      case 'adb_push':
      case 'adb_mutate': {
        if (!config.allowMutate) {
          audit('deny', 'ALLOW_MUTATE=0', { detail: toolName });
          return makeError(
            `写能力已被门禁关闭（ALLOW_MUTATE=0）。需要时在机械革命上设置环境变量 ALLOW_MUTATE=1 并重启 adb-mcp，且每次开启需人工授权。`
          );
        }
        audit('deny', 'ALLOW_MUTATE=1 but not implemented', { detail: toolName });
        return makeError('写能力路径已实现门禁但未放行具体执行器（本期只读版本）。');
      }

      default:
        audit('deny', `unknown tool ${toolName}`);
        return makeError(`未知工具：${toolName}`);
    }
  } catch (e) {
    audit('error', String(e).slice(0, 200));
    return makeError(`执行失败：${String(e).slice(0, 300)}`);
  }
}

module.exports = { schemas, handle };
