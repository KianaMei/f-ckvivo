# AGENTS.md — 仓库工作规则与导航

> 2026-11-15 由根 `README.md` 合并扩充而来：README 的目录导航与各战线用法全部并入本文，
> 并新增设备红线、环境工具链、Vector/KSU 运维速查。`README.md` 现在只是入口指针。
> 任何 agent / 协作者动手前**必须先读完 §1 红线**。

## 0. 这是什么仓库

对一台 **vivo X Fold6（PD2545D / V2545A，MT6993，Android 17 / OriginOS 7，
内核 `6.12.58-android16-6-gdd6fd5c98918`，2026-08-05 构建）** 的单机研究仓库。
几条战线并行、互不依赖：

| 目录 | 是什么 | 入口 |
|---|---|---|
| `root/` | **临时 root 战线**（CVE-2026-64560 等），本仓库最初的正文 | [`root/README.md`](root/README.md) |
| `root/vector-softreboot/` | Vector 3080 双 daemon 崩溃取证 + 11-15 恢复实录（→ `VECTOR-KSU.md §6.7`） | [`root/vector-softreboot/FINDINGS.md`](root/vector-softreboot/FINDINGS.md) |
| `modules/fq-fold-pad/` | **番茄小说折叠屏双列** LSPosed/Vector 模块 | [`modules/fq-fold-pad/README.md`](modules/fq-fold-pad/README.md) |
| `modules/origin-tweaks/` | **OriginOS 运行时 tweaks**（拖放/超级拖拽/工作台/分屏；libxposed API 102 工作副本） | [`modules/origin-tweaks/README.md`](modules/origin-tweaks/README.md) |
| `modules/origin-tweaks-REVIEW.md` | OriginTweaks 复盘：Vector 模块加载规则、弯路与打包/验证契约 | [`modules/origin-tweaks-REVIEW.md`](modules/origin-tweaks-REVIEW.md) |
| `coolapk/` | **酷安 16.7.3** 逆向：易盾加固分析、运行时类导出、净化模块适配 | [`coolapk/NOTES.md`](coolapk/NOTES.md) |
| `douyin/` | 抖音逆向临时工作区 | 本文 §5 |
| `xhs/` | 静盒×小红书适配（锚点迁移工具） | `xhs/` 内文档 |
| `xhook/` | 自研 X/Twitter hook 模块（XHook，libxposed API 102）+ Re:X/上游模块审计 | [`xhook/README.md`](xhook/README.md) |
| `originos/` | 上游 OriginOS 魔改/LSPosed 模块的**只读参考克隆**（各自带 `.git`） | — |
| `root/.upstream/` | 上游 GKI / CVE 复现仓库只读克隆（很大，gitignore） | — |

## 1. 设备红线（最高优先级，覆盖一切默认行为）

> **1.0 2026-11-15 事故后追加（最高优先级，覆盖本节其余条款）**
>
> - **禁止任何可能导致重启的操作**：不只是 reboot/断电，还包括任何可能卡死/崩溃
>   `system_server`、`PackageInstaller`、`zygote` 的动作——`adb install/uninstall`、
>   `pm install`、`am broadcast`、`force-stop` 系统应用、kill 系统进程、
>   重启 daemon、`setprop ctl.*`、软重启。
> - **安装 APK 三条件**：屏幕已解锁、用户在场、只用 `adb install`；失败立即停止。
>   **禁止**用 root `pm install` 绕过 PackageInstaller，**禁止**在屏幕 asleep 时安装。
> - **任何 `PACKAGE_*` 广播都按高风险处理**：本机 vivo 备份
>   `TransportManager.onPackageChanged` 有已知 NPE，会崩 system_server
>   （16:33、19:19 两次实测）。
> - **违反一次即停止全部设备操作**，直到用户明确恢复授权；恢复授权只覆盖当次。
> - 事故记录：`root/INCIDENT-2026-11-15-packageinstaller.md`。

1. **默认只读**。任何非只读动作（kill / 写设备文件 / 装 APK / 软重启 /
   `setprop ctl.*` / 提权执行变更类命令）必须先向用户说明
   **"做什么 + 影响 + 风险"并等同意**，拿到授权才动手；授权范围只覆盖当次。
2. **绝不硬重启 / 断电**：临时 root 是 kiroot 注入的 `kernelsu.ko`（LKM，late-load），
   硬重启即丢，恢复需重跑 exploit。不得把"重启可恢复"当兜底理由。
3. **软重启也要最小化**，连续快速软重启是 11-15 事故的放大器；动框架前先问
   用户是否有依赖无障碍的东西在跑（GKD / Scene）——zygote 重启会打断它们。
4. **kill 只按精确 PID**；`pkill -f` 会匹配到自己的 shell 造成自杀。
5. **禁 OTA / 全量包 / 源码包**方向；设备自身只读采集物（bugreport、slabinfo、
   dropbox、logcat）仍可用。
6. **panic 之后**：`/data/local/tmp` 最近写入的文件可能整段变全零（大小不变、
   静默损坏），复跑任何 payload 前必须重推并校验 sha256/魔数。
   （用户已明言 panic 记录与 tmp 清零本身可接受，不要拿它劝阻实验。）
7. **数据底线**：不影响用户数据。若设备因 PackageWatchdog 计数触发 recovery
   询问"是否清除数据"，**绝不确认清除**（背景见 `root/VECTOR-KSU.md` §6.7.6）。

## 2. 环境与工具链（含踩坑，Windows + Git Bash）

| 组件 | 位置 / 版本 |
|---|---|
| adb | `C:\Users\jaqenze\AppData\Local\AndroidSdk\platform-tools\adb.exe`（**PATH 里没有 adb**，脚本里写全路径） |
| Android SDK | `C:\Users\jaqenze\AppData\Local\AndroidSdk`：`platforms/android-35`、`build-tools/35.0.0`（aapt2 / d8 / zipalign / apksigner） |
| JDK 21 | `C:\Program Files\jdk-21.0.8+9` |
| NDK r29 | `D:\D-code\tools\android\android-ndk-r29`（`ANDROID_NDK_ROOT` 可覆盖） |
| jadx 1.5.4 | `_tools/jadx`（`bin/jadx.bat`；`--show-bad-code` 可救拒编方法） |
| 设备序列 | `10AG632QLS002CF`（transport 不定，用 `adb devices` 确认） |

**Git Bash 路径坑（连踩多次，务必照抄）**：

- 传 `/data/...` 给 `adb shell` 前先 `export MSYS2_ARG_CONV_EXCL='/data'`，
  否则被改写成 `C:/Program Files/Git/data/...`。
- `MSYS_NO_PATHCONV=1` 会让**本地** `/d/...` 路径失效（push 找不到文件）；
  仅 `adb exec-out` 拉远程绝对路径时按需使用。
- `adb shell cat` 拉二进制/SQLite 会被 pty 污染（LF→CRLF，db 必报 malformed）；
  改用 `adb exec-out su -c 'cat ...' > local`。
- `su -c` 会**重置 PATH**：busybox 等必须写绝对路径（如
  `/data/adb/ksu/bin/busybox`）。
- 轮询 `adb devices` 状态用 `awk 'NR==2{print $2}'`；`unauthorized` 时须
  解锁亮屏 + 重插才会弹授权框。

## 3. root/ 战线（临时 root）

`root/README.md` 是正文（目标、状态表、路线重判），`FINDINGS.md` / `ROUTES.md`
是逐次实测取证。脚本按"运行目录 = `root/`"组织：

```sh
cd root
./tools/pd2545_preflight.sh      # 设备只读预检
./tools/pd2545_watch_dump.sh     # 等待 + 抓 kallsyms
```

`pd2545-64560/` 自带 README（构建与 `--preflight` 用法）。当前路线状态见
`root/README.md` 状态表（43074/43499/46242 已修，64560 为唯一存活线）。

## 4. Vector / KernelSU 运维速查

当前现场（2026-11-15 恢复后）：**KernelSU 官方管理器 `me.weishu.kernelsu`
v3.3.1(32681) + kernelsu.ko(LKM) + Zygisk Next 1.5.3(880) + Vector 3080-88f8e1fa**，
模块 `zygisk_vector` / `zygisksu` / `zygisk_shamiko`。

日常自检（全部只读）：

```sh
adb shell su -c 'pidof vectord'                       # 必须恰 1 个
adb shell su -c 'service list | grep -E "serial|activity"'
adb shell su -c 'grep "^kernelsu " /proc/modules'
adb shell su -c 'ls -t /data/adb/lspd/log/verbose_*.log | head -1'   # 模块加载看这里
```

关键语义（详见 `root/VECTOR-KSU.md` §2）：

| 操作 | 内核/KSU | 重放模块 `service.sh` | 风险 |
|---|---|---:|---|
| 硬重启 | **丢 root** | — | 红线 |
| `ksud soft-reboot` | 保留 | **会** | 3080 可能再造第二个 daemon |
| `setprop ctl.restart zygote` | 保留 | 不会 | 恢复场景一律用这个 |

铁律：

- **模块是否加载只看 daemon 落盘日志** `/data/adb/lspd/log/verbose_*.log`，
  不要信 `adb logcat -d`（开机日志洪峰会冲掉证据，11-15 的升级灾难就是误判引起）。
- **绝不运行中就地升级/回滚 Vector 或框架类模块**；升级前必须
  `sqlite3 modules_config.db .dump` 备份（3118 的 v4→v5 迁移会清空 modules 表）。
- 手工启动 daemon 的正确写法与两个路径坑（cwd + 绝对路径 + `retry=0`）、
  以及**无硬重启恢复 runbook**：`root/VECTOR-KSU.md` §6.7。

## 5. 各模块战线用法

**fq-fold-pad**（只依赖 JDK + Android SDK）：

```sh
cd modules/fq-fold-pad
VERSION_NAME=1.4.0 VERSION_CODE=5 ./build.sh   # → dist/fq-fold-pad-1.4.0.apk
```

**origin-tweaks**（modern libxposed API 102，不要放 `assets/xposed_init`）：

```sh
cd modules/origin-tweaks
./build.sh        # 默认 0.1.6 / versionCode 6 → dist/OriginTweaks-0.1.6.apk
```

装后≠生效：Vector 里启用 + scope 打勾 + 重启目标进程。验证链与弯路见
`modules/origin-tweaks-REVIEW.md`。

**coolapk**（净化 + 回复过滤合并模块）：

```sh
cd coolapk/module-merged
./build.sh                    # 以 work/purifier-now.apk 为底座
adb install -r dist/coolapk-purifier-merged-*.apk
```

运行时类导出模块用后要 disable，否则酷安每次启动都强载几千个类。

**douyin**：`tools/dy_dex_strings.py` / `dy_rules_fingerprint.py` 吃参数、只读、
不依赖工作目录。

**xhook / XHook**（X/Twitter，libxposed API 102，独立 Gradle 工程）：

```sh
cd xhook/ours
./gradlew :app:assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
cp app/build/outputs/apk/debug/app-debug.apk ../out/XHook-<ver>-debug.apk
```

- 只 hook `com.twitter.android` 主进程；scope 只勾 `com.twitter.android`。
- **模块 APK 更新后必须先打开一次模块 App**（或 CLI disable/enable 再启动 App），
  否则 Vector 2.2 会静默跳过该模块。
- 装/启用/重启 X 均属设备变更，需用户明确同意（见 §1）。
- 当前实测（X 12.42.0-prod.01，2026-11-15）：视频抓取/按钮、Home 重选守卫、
  回前台刷新守卫 **已装**；0.2.9 去广告完全无效（12.42 删掉了 entryId 前缀字符串，
  `discovery: 0 candidate`）。0.3.0 改为 promotedMetadata 判定 + 渲染边界跳过
  （`h#a`/`a2#a`/`a2#c`），**待上机验证**；锚点见 `xhook/notes/X-12.42-ANCHORS.md`。
- 验证只看日志：`adb logcat -s XHook`；模块是否加载看 `/data/adb/lspd/log/verbose_*.log`。

## 6. 约定

- 每个战线自带 `tools/` 与 `logs/`，不跨目录引用。
- 大文件（APK、解包产物、上游克隆、bugreport zip）一律 gitignore，只留本地。
- `_tmp_*/`、`_tools/` 是不入库的临时工作区（部分已在 gitignore）；其中
  `_tmp_vector/` 保留着 11-15 事故的 DB dump 物证，不要清。
- 结论必须附证据文件路径；日志类证据落 `logs/` 或对应战线目录。
- 归档脚本 `root/tools/reorganize-2026-11-14.sh` 是一次性整理脚本，**不要重跑**。

## 7. 文档索引

| 主题 | 文档 |
|---|---|
| root 战线正文 / 状态表 | `root/README.md` |
| 全部实测发现 | `root/FINDINGS.md`（§1–§11） |
| 各路线取证 | `root/ROUTES.md` |
| KSU/Vector 全栈笔记（含 11-15 事故与恢复 runbook） | `root/VECTOR-KSU.md`（§6.7） |
| 3080 双 daemon 崩溃取证 | `root/vector-softreboot/FINDINGS.md` |
| OriginTweaks 复盘 | `modules/origin-tweaks-REVIEW.md` |
| 酷安逆向 | `coolapk/NOTES.md` |
| XHook 状态/构建/实测 | `xhook/README.md`、`xhook/ours/README.md` |
| X 12.42 结构锚点（去广告失效根因） | `xhook/notes/X-12.42-ANCHORS.md` |
| Re:X → XHook 移植台账 | `xhook/notes/PORTING.md` |
