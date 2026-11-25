# adb-mcp · 手机只读 ADB 网关（MCP over Cloudflare Tunnel）

> 单文档制：本战线唯一 README。跨战线方法论见 `shared/PLAYBOOK.md`。

## 当前进度（2026-11-25 晚：切换命名隧道 / 固定域名）

**2026-11-25 21:13 起改走 Cloudflare 命名隧道**：公网地址固定 `https://adb-mcp.mamba-out.com/mcp`。
- 隧道 `adb-mcp-mechrevo`（id `5b158e91-6391-465e-b426-eab8de3df823`），远程配置（`config_src=cloudflare`）ingress `adb-mcp.mamba-out.com → http://127.0.0.1:8787`，兜底 `http_status:404`；DNS `adb-mcp` CNAME → `<id>.cfargotunnel.com`（proxied）
- 连接器 token 入库 `secrets/cf-tunnel-token.txt`；`tunnel-sync.js` 发现该文件即走 `cloudflared tunnel run`（token 走 `TUNNEL_TOKEN` 环境变量，不进命令行），找不到才退回 quick tunnel + TXT
- kaslana 不再需要 DoH 发现与 5 分钟计划任务（旧任务 `powershell -WindowStyle Hidden` 在 Windows Terminal 作默认终端时每 5 分钟闪黑窗，已删除）；`Resolve-AdbMcp.ps1` 降为一次性写配置
- **机械革命侧切换步骤**：`git pull` → 重启 daemon（见下「运维」）→ `curl https://adb-mcp.mamba-out.com/healthz` 返回 200 即完成；之后可删 TXT `adb-mcp-tunnel.mamba-out.com`

**此前已验证可用**：
- 本机真机 19/19 端到端测试 + **离线协议/策略/守护 36/36 自测（`npm run test:offline`）**
- 公网链路：TXT 发现 → 隧道 → 真机返回（`adb_devices`/`adb_shell` ✅，无 token 401 ✅，攻击样例被红线拒 ✅）
- **Antigravity 直连配置已实锤并就位**：Antigravity App (2.0) / IDE / CLI 三端共用全局 `~/.gemini/config/mcp_config.json`（标准字段 `serverUrl` + `headers.Authorization`）；`kaslana/Resolve-AdbMcp.ps1` 支持自动读仓内 `secrets/token.txt`、Google+Cloudflare 双源 DoH、电池模式 5 分钟计划任务
- **二轮完整性加固（kaslana 完成）**：
  - `server.js`：补齐 Streamable HTTP `notifications/* → 202 Accepted`（空体）与 `DELETE /mcp → 204`，`sessions` 环形上限防泄漏
  - `daemon.js` + `tunnel-sync.js`：Windows Named Pipe 单实例互斥锁（真幂等，防双开抢隧道覆盖 TXT）+ 父子 `stdin` 管道生命线（即使 `TerminateProcess` 强杀 daemon，子进程也自动随 EOF 退出，零孤儿进程/零句柄泄漏）
  - `lib/policy.js`：封堵通用 `>`/`>>` 重定向、`` ` ``/`$()` 命令替换、`su -c`/`busybox` 内层绕过与 `exec-out` 引号逃逸；放行只读查看 `/data/adb`、`/sdcard`、`2>/dev/null`、无参只读动词（`id`/`df`/`uptime`）及 `/data/adb/ksu/bin/busybox`
  - `lib/audit.js` / `lib/config.js` / `.cmd`：修复 `catch (e)` 漏参、`adb` 多路径探测（`ADB_PATH` → `ANDROID_SDK_ROOT` → `LOCALAPPDATA` → `PATH`），全面清除写死盘符路径

**待机械革命恢复联网后唯一一步**：
1. 在机械革命上 `git pull` 拉取最新修复，双击 `run-mechrevo.cmd` 拉起零窗口守护（验证 `/healthz` 返回 + 无黑窗 + TXT 自动刷新）。
2. kaslana 侧 5 分钟定时任务自动同步新隧道 URL 后，在 Antigravity **Customizations → Installed → MCP Servers** 对 `adb-phone` 点 **Restart** 即可直连真机。

**密钥入库**（用户确认：本仓密钥随 git）：
- `secrets/token.txt` — Bearer token（ADB_MCP_TOKEN，kaslana 部署要用，随 git 同步）
- `cache/adb-mcp/secrets/cf-token.txt` — Cloudflare API token（**仅本机用，留在 cache 不入库**：GitHub Push Protection 会拦截 `cfut_` 前缀，且它不需要出机械革命）
- 读取优先级：env → 入库 secrets → cache 兼容

## 这是什么

把**插在机械革命上的 vivo X Fold6**（`10AG632QLS002CF`）以 **MCP 工具**的形式安全地交给**外地 kaslana 电脑上的 Antigravity（App / IDE / CLI）** 直接使用。

```
手机 ─USB─▶ 机械革命(DESKTOP-QJDKLTH)
              adb server   127.0.0.1:5037     （adb server 永不对外）
              adb-mcp      127.0.0.1:8787     （Bearer + 红线策略 + 审计）
                   │ 出站长连 QUIC（不开任何入站端口，不碰公网服务器）
                   ▼
              cloudflared 命名隧道 adb-mcp-mechrevo ──▶ https://adb-mcp.mamba-out.com（固定）
                   ▲ HTTPS + Bearer（Streamable HTTP 直连，跳过 paseo）
                   │
              kaslana: Antigravity (App / IDE / CLI) ── ~/.gemini/config/mcp_config.json 的 adb-phone
```

设计原则：**agent 侧（kaslana）拿到的不是 adb，是一组白名单工具**。adb 是无认证裸协议，绝不穿越公网；穿过公网的只有 HTTPS + Bearer token，且手机侧写操作在**服务端代码层**被拒绝。

## 组件

| 文件 | 作用 |
|---|---|
| `server.js` | MCP Streamable HTTP 端点（`/mcp` POST/DELETE + `202` 通知兼容 + `/healthz`），零依赖 Node ≥18 |
| `lib/config.js` | 配置装载（`env → secrets/token.txt → cache`；`adb` 自动探测 `ADB_PATH/ANDROID_SDK_ROOT/LOCALAPPDATA/PATH`） |
| `lib/policy.js` | **红线策略引擎**：输出重定向/命令替换拦截 + 引号感知分段 + `su -c`/`busybox` 递归解包白名单 + 破坏性动作拒绝 |
| `lib/adb.js` | adb 执行器（spawn 数组参数、超时、输出上限、PNG 魔数定位修复 vivo 多屏警告前缀） |
| `lib/audit.js` | 审计 JSONL → `cache/adb-mcp/audit/`（含 token 指纹、耗时、拒绝原因，异常自隔离） |
| `lib/tools.js` | 8 个 MCP 工具的实现与门禁 |
| `tunnel-sync.js` | 隧道守护：有 `secrets/cf-tunnel-token.txt` → 命名隧道（固定域名）；否则 quick tunnel → 抓 URL → 发布 CF DNS TXT；崩溃重启循环 |
| `daemon.js` | **单一守护进程（零窗口）**：Named Pipe 单实例互斥锁 + 父子 `stdin` 管道防孤儿回收，监督 `server.js` / `tunnel-sync.js`（指数退避）+ adb 保活（30 分钟） |
| `run-mechrevo.cmd` | 手动/计划任务启动（`%~dp0` 相对路径、零窗口、真幂等）；登录自启由 `Startup\adb-mcp.vbs` 同样拉 `daemon.js` |
| `kaslana/Resolve-AdbMcp.ps1` | kaslana 侧一次性部署：合并写 `~/.gemini/config/mcp_config.json`（固定 `serverUrl` + `timeoutSeconds: 30`），并删除旧 `adb-mcp-resolve` 计划任务 |
| `kaslana/INSTALL.txt` | kaslana 部署说明（Antigravity App / IDE / CLI 三端直连） |
| `install-task.cmd` | （可选）管理员注册 SYSTEM 开机任务，登录前自愈 |
| `test-client.mjs` | 端到端与离线测试（`npm run test:offline` 离线 36 项 + `npm test` 真机端到端） |

## 工具面（8 个）

| 工具 | 默认 | 说明 |
|---|---|---|
| `adb_devices` | ✅ | 设备列表 |
| `adb_shell` | ✅ | 只读白名单 shell（`cat/ls/grep/dumpsys/getprop/id/df/…`；`su -c` 与 `busybox/toybox` 递归解包校验内层白名单） |
| `adb_logcat` | ✅ | `logcat -d -t N`（可按 tag 过滤，已移除 `-c` 清空写参数） |
| `adb_dumpsys` | ✅ | 指定 section |
| `adb_screencap` | ✅ | 截图 → MCP image block（PNG 魔数定位，天然免疫 vivo `[Warning] Multiple displays` 前缀） |
| `adb_execout` | ✅ | `exec-out su -c "cat '<path>'"` 管道直拉（绝对路径、禁单双引号/控制符/元字符、大小上限） |
| `adb_push` | 🔒 | 编译在内，`ALLOW_MUTATE=0` 门禁默认拒绝 |
| `adb_mutate` | 🔒 | 同上（install/uninstall/kill/reboot 全并入拒绝） |

### 红线编码（`lib/policy.js`，对应 AGENTS.md）

- 除无害 `2>/dev/null` 外的一切 `>`/`>>` 输出重定向、`` `...` ``/`$()` 命令替换、未闭合引号、后台符 `&`、嵌套 `sh/bash/eval/exec` → 拒绝（而 `ls`/`cat` 只读查看 `/data/adb`、`/sdcard` 正常放行）
- `tee / dd / cp / mv / rm / rmdir / mkdir / mktemp / touch / truncate / chmod / chown / chcon / ln / sed -i / tar -x / unzip / wget / curl -o` → 拒绝
- `reboot / ctl.* / ksud soft-reboot / install / uninstall / force-stop / kill / pkill / am broadcast|start / setprop / settings put|delete / pm clear|grant|revoke|enable|disable|compile / input / svc / logcat -c / tcpip / connect` → 拒绝（2026-11-15 PackageInstaller 事故条款全覆盖）
- 引号感知分段后逐段匹配只读动词白名单；`su -c` 与 `(/data/adb/ksu/bin/)busybox`、`toybox` 自动剥离外层包装递归校验内层命令
- 拉取一律 `exec-out` 管道直拉（禁引号与元字符）→ **从源头消灭"先落设备盘再 pull"**

## 运维

### 启动/自愈（零窗口、单实例幂等）

- 全链路窗口状态：VBS style 0（真隐藏）→ `node daemon.js`（`windowsHide` + Named Pipe 单实例锁）→ `server.js` / `tunnel-sync.js` / `cloudflared`（`windowsHide` + `stdin` 管道生命线防孤儿）。
  **运行链路上没有 cmd，没有任何黑窗，且重复启动自动幂等退出**。
- 登录自启：`C:\Users\<you>\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\adb-mcp.vbs`
- 登录前自愈（可选，管理员）：右键“以管理员身份运行”`install-task.cmd`
- 手动：双击 `run-mechrevo.cmd`（隐藏拉起 daemon，幂等）

### 状态速查

```bash
curl http://127.0.0.1:8787/healthz                                  # 服务活着？
type cache\adb-mcp\daemon-state.json                                # 守护与子进程 PID / 重启计数
type cache\adb-mcp\tunnel-state.json                                # 隧道 URL + 重启计数
nslookup -type=TXT adb-mcp-tunnel.mamba-out.com                     # 公网侧 TXT（kaslana 读这个）
tail cache\adb-mcp\audit\audit-YYYYMMDD.jsonl                       # 谁在调、调了啥、被拒了啥
npm run test:offline                                                # 36 项离线协议/红线/单实例守护自测（无手机可跑）
npm test                                                            # 真机全量端到端测试（机械革命本机）
```

### kaslana 侧部署

见 `kaslana/INSTALL.txt`：克隆主仓后直接运行 `powershell -ExecutionPolicy Bypass -File .\shared\tools\adb-mcp\kaslana\Resolve-AdbMcp.ps1 -Install -Schedule` 即可一键完成（也支持仅拷贝 `Resolve-AdbMcp.ps1` + `secrets/token.txt` 两文件独立部署）。

### 密钥与凭据（用户确认：随私有主仓入库，`secrets/`）

| 密钥 | 位置 | 说明 |
|---|---|---|
| `ADB_MCP_TOKEN` | `secrets/token.txt`（入库） | Bearer token（64 hex），泄露或仓库转公开前须重生成 + kaslana 重跑 `-Install` |
| `CF_TOKEN` | `cache/adb-mcp/secrets/cf-token.txt`（不入库） | Cloudflare API token，仅 quick tunnel 兜底模式发布 TXT 用；GitHub Push Protection 拦 `cfut_` 前缀 |
| 隧道连接器 token | `secrets/cf-tunnel-token.txt`（入库） | 只能以 `adb-mcp-mechrevo` 身份接入该隧道，不能改 DNS/账号；泄露时在 CF 后台 Tunnels 里轮换 |
| cloudflared.exe | `cache/adb-mcp/bin/` | 二进制不入库，重下即可 |

运行期状态/日志/审计仍在 `cache/adb-mcp/`（不入库）：`daemon-state.json`、`tunnel-state.json`、`logs/`、`audit/`、`screencap/`、`pull/`。

### 已知边界

1. **命名隧道**地址固定；机械革命断网/重启后 cloudflared 自动重连，kaslana 端无需动作（MCP 连接若已断开，Restart 一次即可）。
2. 删除 `secrets/cf-tunnel-token.txt` 会自动退回 quick tunnel + TXT 旧模式（此时 kaslana 需自行处理 URL 变化）。
3. 带宽：命令/日志/截图（~6MB PNG base64 ≈ 8MB 过公网）可用；`screenrecord`/`scrcpy` 别走隧道。
4. 本机 FlClash TUN 与 cloudflared 并存正常（QUIC 直连 region2，实测通过）；本机 curl 测试公网 URL 需等 quick tunnel 注册 ~15s。
5. 审计目录、截图落盘、拉取文件全在 `cache/adb-mcp/`（不入库），符合「中间产物绝不入库」。

## 安全模型（谁拿到什么）

| 角色 | 拿到 | 拿不到 |
|---|---|---|
| kaslana agent | 6 个只读工具 | adb 二进制、shell 全权、手机写权限 |
| 公网扫描者 | trycloudflare URL（随机）+ 401 | 任何 MCP 调用（无 token） |
| DNS 观察者 | TXT 里的隧道 URL | token（仅存私有仓 `secrets/token.txt` 与 kaslana `~/.adb-mcp/`，不进 DNS） |
| 手机 | 只读命令 | 任何文件落盘、任何重启/安装 |

## 测试

```
Offline（无手机，kaslana / 任意机器）：36 passed / 0 failed
✅ GET /healthz 200 · 401 without token · initialize · notifications/initialized(202) · GET /mcp(405) · DELETE /mcp(204)
✅ tools/list(8, logcat 无 clear) · policy allow ×7（无参动词 / 只读 /data/adb & /sdcard / 2>/dev/null / su -c 复合 / KSU busybox）
✅ deny ×13（写路径、通用 > 重定向、tee、reboot、broadcast、setprop、rm、su -c 触控/开关绕过、busybox 写、$() 替换、logcat -c）
✅ deny execout ×3（元字符与单/双引号逃逸） · locked ×2（adb_push / adb_mutate）
✅ daemon 单实例锁幂等退出(code=0) + 退出后子进程随管道 EOF 自动回收（无孤儿进程）

真机端到端（机械革命）：19+ passed / 0 failed
✅ adb_devices · adb_shell getprop(V2545A) · adb_logcat · adb_dumpsys · adb_screencap(PNG 5.7MB) · adb_execout(/proc/meminfo)
✅ 公网(TXT 发现→隧道→真机)：adb_devices ✅ · getprop ✅ · 401 ✅ · 红线拒绝 ✅
```

## 升级路径（本期未做）

- Cloudflare Access 加邮箱/Service Token 白名单（命名隧道已就位，可在 Bearer 之外再加一层）
- `adb_mutate` 执行器（`ALLOW_MUTATE=1` 后按 AGENTS.md 三条件实现：屏锁状态检查、单次授权、审计升级）
- apk 安装专用工具（需用户在场 + `adb install` 白名单）
