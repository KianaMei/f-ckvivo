# adb-mcp · 手机只读 ADB 网关（MCP over Cloudflare Tunnel）

> 单文档制：本战线唯一 README。跨战线方法论见 `shared/PLAYBOOK.md`。

## 当前进度（2026-11-25 中断点存档）

**已验证可用**：
- 本机 19/19 端到端测试（含 7 个红线反例、写能力双锁）
- 公网链路：TXT 发现 → 隧道 → 真机返回（`adb_devices`/`adb_shell` ✅，无 token 401 ✅，攻击样例被红线拒 ✅）
- 隧道守护 + CF DNS TXT 发布（`adb-mcp-tunnel.mamba-out.com`）
- kaslana 侧部署脚本 `kaslana/Resolve-AdbMcp.ps1`（DoH + 合并写 mcp_config.json + 可选 5 分钟计划任务）

**中断点（下次从这里继续）**：
1. **架构决策已定**：kaslana 上 Antigravity 直接连 adb-phone MCP，跳过 paseo 一层（MCP 已把工具面全部封装，paseo 无增量价值）。
2. **待办**：定位 Antigravity IDE 的 MCP 配置入口（app.asar 里已探到 `McpPage.js`/`McpWorker.js`/`guestWebMcpController.js` 组件，具体配置 key/文件未确认；agy CLI 侧 `~/.gemini/config/mcp_config.json` 已确认支持 `url`+`headers`）。确认后更新 `kaslana/INSTALL.txt` 第 3 步。
3. **待验证**：零窗口 daemon 修复（`daemon.js` 的 `console.log` 在无控制台句柄时会同步抛异常致秒死，已包 try/catch；因用户叫停未复验）。恢复方法：双击 `run-mechrevo.cmd` 或重启登录。验证点：`/healthz` 返回 + 无任何黑窗。
4. daemon 当前处于停机状态（排障时杀掉），重启后隧道 URL 会换、TXT 自动更新，kaslana 侧 5 分钟任务自动跟上。

**密钥入库**（用户确认：本仓密钥随 git）：
- `secrets/token.txt` — Bearer token（ADB_MCP_TOKEN，kaslana 部署要用，随 git 同步）
- `cache/adb-mcp/secrets/cf-token.txt` — Cloudflare API token（**仅本机用，留在 cache 不入库**：GitHub Push Protection 会拦截 `cfut_` 前缀，且它不需要出机械革命）
- 读取优先级：env → 入库 secrets → cache 兼容

## 这是什么

把**插在机械革命上的 vivo X Fold6**（`10AG632QLS002CF`）以 **MCP 工具**的形式安全地交给**外地 kaslana 电脑上的 paseo/antigravity agent** 使用。

```
手机 ─USB─▶ 机械革命(DESKTOP-QJDKLTH)
              adb server   127.0.0.1:5037     （adb server 永不对外）
              adb-mcp      127.0.0.1:8787     （Bearer + 红线策略 + 审计）
                   │ 出站长连 QUIC（不开任何入站端口，不碰公网服务器）
                   ▼
              cloudflared quick tunnel ──▶ xxx.trycloudflare.com
                   │ URL 动态发布到
                   ▼
              Cloudflare DNS TXT: adb-mcp-tunnel.mamba-out.com = "tunnel=<url>"
                   ▲ kaslana 每 5 分钟查 TXT 更新本地配置
                   │ HTTPS + Bearer
              kaslana: paseo(agy acp) ── ~/.gemini/config/mcp_config.json 的 adb-phone
```

设计原则：**agent 侧（kaslana）拿到的不是 adb，是一组白名单工具**。adb 是无认证裸协议，绝不穿越公网；穿过公网的只有 HTTPS + Bearer token，且手机侧写操作在**服务端代码层**被拒绝。

## 组件

| 文件 | 作用 |
|---|---|
| `server.js` | MCP Streamable HTTP 端点（`/mcp` + `/healthz`），JSON-RPC 手写实现，零依赖 Node ≥18 |
| `lib/config.js` | 配置装载（env 优先，密钥走 `cache/adb-mcp/secrets/`） |
| `lib/policy.js` | **红线策略引擎**：shell 命令白名单 + 设备写入路径拒绝 + 破坏性动作拒绝 |
| `lib/adb.js` | adb 执行器（spawn 数组参数、超时、输出上限、PNG 魔数定位修复 vivo 多屏警告前缀） |
| `lib/audit.js` | 审计 JSONL → `cache/adb-mcp/audit/`（含 token 指纹、耗时、拒绝原因） |
| `lib/tools.js` | 8 个 MCP 工具的实现与门禁 |
| `tunnel-sync.js` | 隧道守护：拉起 cloudflared → 抓 URL → 发布 CF DNS TXT → 崩溃重启循环 + 每小时 TXT 核对 |
| `daemon.js` | **单一守护进程（零窗口）**：隐藏式拉起并监督 server.js + tunnel-sync.js + adb 保活（30 分钟），子进程崩 挨指数退避重启 |
| `run-mechrevo.cmd` | 手动启动（零窗口：PowerShell Hidden → node daemon.js）；登录自启由 Startup\adb-mcp.vbs 同样拉 daemon.js |
| `kaslana/Resolve-AdbMcp.ps1` | kaslana 侧部署/同步：DoH 查 TXT → 合并写 `~/.gemini/config/mcp_config.json` → 可选注册 5 分钟计划任务 |
| `kaslana/INSTALL.txt` | kaslana 部署说明（新架构：Antigravity 直连） |
| `install-task.cmd` | （可选）管理员注册 SYSTEM 开机任务，登录前自愈 |
| `test-client.mjs` | 端到端测试（19 项：功能 + 红线反例 + 锁死的写能力） |

## 工具面（8 个）

| 工具 | 默认 | 说明 |
|---|---|---|
| `adb_devices` | ✅ | 设备列表 |
| `adb_shell` | ✅ | 只读白名单 shell（`cat/ls/grep/dumpsys/getprop/logcat/…`；`su -c` 前缀放行但内容仍过红线） |
| `adb_logcat` | ✅ | `logcat -d -t N`（可按 tag） |
| `adb_dumpsys` | ✅ | 指定 section |
| `adb_screencap` | ✅ | 截图 → MCP image block（PNG 魔数定位，天然免疫 vivo `[Warning] Multiple displays` 前缀） |
| `adb_execout` | ✅ | `exec-out su -c "cat <path>"` 管道直拉（路径校验：绝对路径、无元字符、大小上限） |
| `adb_push` | 🔒 | 编译在内，`ALLOW_MUTATE=0` 门禁默认拒绝 |
| `adb_mutate` | 🔒 | 同上（install/uninstall/kill/reboot 全并入拒绝） |

### 红线编码（`lib/policy.js`，对应 AGENTS.md）

- 设备写入路径（`/sdcard`、`/storage/emulated`、`/data/local/tmp`、`/data/user/0`、`/data/adb`）→ 拒绝
- `tee/dd/cp/mv/rm/mkdir/touch` → 拒绝
- `reboot / ctl.* / ksud soft-reboot / install / uninstall / force-stop / kill / pkill / am broadcast / setprop / settings put / pm clear|grant|revoke / tcpip / connect` → 拒绝（2026-11-15 PackageInstaller 事故条款全覆盖）
- shell 白名单动词前缀制；多行/管道命令逐段检查
- 拉取一律 `exec-out` 管道直拉 → **从源头消灭"先落设备盘再 pull"**

## 运维

### 启动/自愈（零窗口）

- 全链路窗口状态：VBS style 0（真隐藏）→ node daemon.js（windowsHide）→ server.js / tunnel-sync.js / cloudflared（windowsHide）。
  **运行链路上没有 cmd，也就没有任何黑窗**（旧版“style 7 + start /min”会挂任务栏，已废弃）。
- 登录自启：`C:\Users\<you>\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\adb-mcp.vbs`
- 登录前自愈（可选，管理员）：右键“以管理员身份运行”`install-task.cmd`
- 手动：双击 `run-mechrevo.cmd`（隐藏拉起 daemon，幂等）

### 状态速查

```bash
curl http://127.0.0.1:8787/healthz                                  # 服务活着？
type cache\adb-mcp\tunnel-state.json                                # 隧道 URL + 重启计数
nslookup -type=TXT adb-mcp-tunnel.mamba-out.com                     # 公网侧 TXT（kaslana 读这个）
tail cache\adb-mcp\audit\audit-YYYYMMDD.jsonl                       # 谁在调、调了啥、被拒了啥
npm test    # = node test-client.mjs，19 项端到端（本机）
```

### kaslana 侧部署

见 `kaslana/INSTALL.txt`（需要拷的就是 `kaslana/Resolve-AdbMcp.ps1` + `secrets/token.txt`，均随主仓 git）。

### 密钥与凭据（用户确认：随主仓入库，`secrets/`）

| 密钥 | 位置 | 说明 |
|---|---|---|
| `ADB_MCP_TOKEN` | `secrets/token.txt`（入库） | Bearer token（64 hex），泄露重生成 + kaslana 重跑安装 |
| `CF_TOKEN` | `cache/adb-mcp/secrets/cf-token.txt`（不入库） | Cloudflare API token，仅 `dns_records:edit`；GitHub Push Protection 拦 `cfut_` 前缀，且仅本机 DNS 同步用，无需出仓 |
| cloudflared.exe | `cache/adb-mcp/bin/` | 2026.11.2，quick tunnel 无需凭据（二进制不入库，重下即可） |

运行期状态/日志/审计仍在 `cache/adb-mcp/`（不入库）：`daemon-state.json`、`tunnel-state.json`、`logs/`、`audit/`、`screencap/`、`pull/`。

### 已知边界

1. **quick tunnel** 无 SLA、URL 随重启变化 → TXT 同步 + kaslana 5 分钟定时同步 + agent Reload 兜底。若嫌抖动：给 CF token 补 `Tunnel:Edit` 权限或 `cloudflared tunnel login`，改走命名隧道（`mcp.mamba-out.com` 固定域名），`tunnel-sync.js` 只需换启动参数，架构不变。
2. agent Reload 时机：URL 变化后 kaslana 配置 5 分钟内自动更新，但 **agy 只在 agent 启动时读 MCP 配置** → 工具调不通先 Reload agent。
3. 带宽：命令/日志/截图（~6MB PNG base64 ≈ 8MB 过公网）可用；`screenrecord`/`scrcpy` 别走隧道。
4. 本机 FlClash TUN 与 cloudflared 并存正常（QUIC 直连 region2，实测通过）；本机 curl 测试公网 URL 需等 quick tunnel 注册 ~15s。
5. 审计目录、截图落盘、拉取文件全在 `cache/adb-mcp/`（不入库），符合「中间产物绝不入库」。

## 安全模型（谁拿到什么）

| 角色 | 拿到 | 拿不到 |
|---|---|---|
| kaslana agent | 6 个只读工具 | adb 二进制、shell 全权、手机写权限 |
| 公网扫描者 | trycloudflare URL（随机）+ 401 | 任何 MCP 调用（无 token） |
| DNS 观察者 | TXT 里的隧道 URL | token（只在 kaslana `~/.adb-mcp/` 与机械革命 cache） |
| 手机 | 只读命令 | 任何文件落盘、任何重启/安装 |

## 测试

```
19 passed / 0 failed
✅ 401 without token · initialize · tools/list(8)
✅ adb_devices · adb_shell getprop(V2545A) · adb_logcat · adb_dumpsys · adb_screencap(PNG 5.7MB) · adb_execout(/proc/meminfo)
✅ deny ×7：写 /sdcard、tee、reboot、am broadcast、setprop、rm /data/adb、写 /storage
✅ deny execout 元字符注入 · locked: adb_push · locked: adb_mutate
公网(TXT 发现→隧道→真机)：adb_devices ✅ · getprop ✅ · 401 ✅ · 红线拒绝 ✅
```

## 升级路径（本期未做）

- **kaslana Antigravity IDE 直连**（新架构主线的下一步）：定位 IDE MCP 配置入口后，`kaslana/Resolve-AdbMcp.ps1` 增加对 IDE 配置位的同步
- 命名隧道（固定域名 + Cloudflare Access 还能加邮箱白名单；需给 CF token 补 Tunnel:Edit 权限，URL 不再变化 → agent 长期免 Reload）
- `adb_mutate` 执行器（`ALLOW_MUTATE=1` 后按 AGENTS.md 三条件实现：屏锁状态检查、单次授权、审计升级）
- apk 安装专用工具（需用户在场 + `adb install` 白名单）
