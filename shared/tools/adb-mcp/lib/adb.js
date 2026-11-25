'use strict';
/**
 * adb-mcp · adb 命令执行器
 * -----------------------
 * 全部走 spawn(adb.exe)，从源头保证：
 *   - 单进程、带超时、无 shell 注入面（参数不经 cmd/bash）
 *   - exec-out 二进制直拉（不落设备盘）
 *   - 输出体积上限保护
 */

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const { config } = require('./config');

function adbPath() {
  if (!fs.existsSync(config.adbPath)) {
    throw new Error(`adb 不存在：${config.adbPath}（用 ADB_PATH 环境变量覆盖）`);
  }
  return config.adbPath;
}

/** 跑 adb（同步等结果）。args 为数组，绝不拼字符串。 */
function runAdb(args, { timeoutMs, maxBytes } = {}) {
  const timeout = Math.min(timeoutMs || config.maxTimeoutMs, config.maxTimeoutMs);
  const cap = maxBytes || 512 * 1024;
  return new Promise((resolve) => {
    const child = spawn(adbPath(), args, {
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let out = Buffer.alloc(0);
    let err = Buffer.alloc(0);
    let killed = false;
    const timer = setTimeout(() => {
      killed = true;
      child.kill();
    }, timeout);

    child.stdout.on('data', (d) => {
      if (out.length < cap) out = Buffer.concat([out, d.subarray(0, cap - out.length)]);
    });
    child.stderr.on('data', (d) => {
      if (err.length < cap) err = Buffer.concat([err, d.subarray(0, cap - err.length)]);
    });
    child.on('error', (e) => {
      clearTimeout(timer);
      resolve({ exitCode: -1, stdout: out, stderr: Buffer.concat([err, Buffer.from(String(e))]), timedOut: false, error: String(e) });
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({ exitCode: code, stdout: out, stderr: err, timedOut: killed, error: null });
    });
  });
}

/** 设备列表（格式化文本） */
async function listDevices() {
  const r = await runAdb(['devices', '-l'], { timeoutMs: 8000 });
  return {
    exitCode: r.exitCode,
    text: r.stdout.toString('utf8') + (r.stderr.length ? `\n[stderr] ${r.stderr.toString('utf8')}` : ''),
  };
}

/** 截图 → PNG Buffer（exec-out screencap -p，不落设备盘） */
async function screencap() {
  const r = await runAdb(['-s', config.serial, 'exec-out', 'screencap', '-p'], { timeoutMs: config.maxTimeoutMs, maxBytes: 24 * 1024 * 1024 });
  if (r.exitCode !== 0 || r.stdout.length === 0) {
    throw new Error(`screencap 失败 exit=${r.exitCode} stderr=${r.stderr.toString('utf8').slice(0, 200)}`);
  }
  // vivo/多屏机型：screencap 会先往 stdout 打一行 [Warning]，PNG 魔数之后才是真图
  let png = r.stdout;
  const magic = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  let idx = png.indexOf(magic);
  if (idx < 0) {
    // 兼容 \r\n 污染的魔数（部分 exec-out 链路）：清洗后再找
    png = Buffer.from(png.toString('binary').replace(/\r\n/g, '\n'), 'binary');
    idx = png.indexOf(magic);
  }
  if (idx < 0) {
    throw new Error(`screencap 输出里找不到 PNG 魔数（前 64 字节：${r.stdout.subarray(0, 64).toString('utf8').replace(/\n/g, '⏎')}）`);
  }
  if (idx > 0) png = png.subarray(idx);
  return png;
}

/** exec-out 直拉设备文件（cat），返回 Buffer */
async function execoutCat(remotePath) {
  const r = await runAdb(['-s', config.serial, 'exec-out', 'su', '-c', `cat '${remotePath}'`], {
    timeoutMs: config.maxTimeoutMs,
    maxBytes: config.maxBinaryKB * 1024,
  });
  if (r.exitCode !== 0) {
    throw new Error(`exec-out cat 失败 exit=${r.exitCode} stderr=${r.stderr.toString('utf8').slice(0, 200)}`);
  }
  return r.stdout;
}

module.exports = { runAdb, listDevices, screencap, execoutCat, adbPath };
