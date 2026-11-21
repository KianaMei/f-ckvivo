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
| `drag-all/` | **跨 App 拖拽** LSPosed/Vector 模块（文字/图片拖拽、播放器禁拖） | [`drag-all/README.md`](drag-all/README.md) |
| `fq-fold-pad/` | **番茄小说折叠屏双列** LSPosed/Vector 模块 | [`fq-fold-pad/README.md`](fq-fold-pad/README.md) |
| `origin-tweaks/` | **OriginOS 运行时 tweaks**（拖放/超级拖拽/工作台/分屏；libxposed API 102 工作副本） | [`origin-tweaks/README.md`](origin-tweaks/README.md) |
| `origin-tweaks-REVIEW.md` | OriginTweaks 复盘：Vector 模块加载规则、弯路与打包/验证契约 | [`origin-tweaks-REVIEW.md`](origin-tweaks-REVIEW.md) |
| `coolapk/` | **酷安 16.7.3** 逆向：易盾加固分析、运行时类导出、净化模块适配 | [`coolapk/NOTES.md`](coolapk/NOTES.md) |
| `memory-analysis/` | **内存与后台保留分析**：15GB 预算对账、zram 公式、single-cleaner 链路、待做的 Fold3 Pro 对比 | [`memory-analysis/README.md`](memory-analysis/README.md) |
| `douyin/` | 抖音逆向临时工作区 | 本文 §5 |
| `nuke/` | **NukeApp-OSS 逆向 + fork**（抖音模块：107 项开关 / MCP / QuickJS / 风控）；`nuke/src` 是我们维护的源码 | [`nuke/README.md`](nuke/README.md) |
| `xhs/` | 静盒×小红书适配（锚点迁移工具 + UI-07/08 冷启动/后台不刷新） | [`xhs/NOTES.md`](xhs/NOTES.md) |
| `HOOK-DEBUG-PLAYBOOK.md` | **跨战线 hook 排障方法论**（libxposed/Vector）：证据分级、探针+栈定位、返回值形态、classloader 坑 | [`HOOK-DEBUG-PLAYBOOK.md`](HOOK-DEBUG-PLAYBOOK.md) |
| `pilibro/` | PiliBro（哥哥科技 Flutter B站客户端）逆向：血缘/许可证、与 PiliPlus 源码级对比、液态玻璃路线研究 | [`pilibro/NOTES.md`](pilibro/NOTES.md)、[`pilibro/COMPARISON.md`](pilibro/COMPARISON.md) |
| `liquidtab-mine/` | **LiquidTab fork**（小红书/B站 界面精简，GPL-3.0，含 xhsfix 分支记录） | [`liquidtab-mine/README-MINE.md`](liquidtab-mine/README-MINE.md) |
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

| 组件 | 要求 / 约定（多机路径不固定，仓库不写死绝对位置） |
|---|---|
| adb | 不在 PATH 时自行定位 platform-tools；命令里用全路径或先加进 PATH |
| Android SDK | 各模块 `build.sh` 自动探测常见 SDK 目录，并选最高版本的 platform 与 build-tools（aapt2 / d8 / zipalign / apksigner）；用 `ANDROID_SDK_ROOT`（或 `ANDROID_HOME`）、`ANDROID_BUILD_TOOLS` 覆盖 |
| JDK 17+（实际用 21） | `build.sh` 优先用 `JAVA_HOME`，否则按 PATH 上的 `javac` 和常见 JDK 目录探测 |
| NDK | 仅 root/ 原生线需要；用 `ANDROID_NDK_ROOT` 指定，缺了再装 |
| jadx | 逆向用，按机器自备（仓库不存副本；`nuke/README.md` 里是可直接照抄的复现命令） |
| 设备序列 | `10AG632QLS002CF`（transport 不定，用 `adb devices` 确认） |

构建脚本不依赖系统 `zip`：精简版 Git Bash 没有 `zip` 时自动回退到 build-tools 自带的 `aapt add/remove`（各 `build.sh` 里的 `zip_add` / `zip_del`）。这些 `.sh` 在 Git Bash 里跑；drag-all 是 Gradle 工程，SDK 由 Gradle 自行解析。

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
cd fq-fold-pad
VERSION_NAME=1.4.0 VERSION_CODE=5 ./build.sh   # → dist/fq-fold-pad-1.4.0.apk
```

**origin-tweaks**（modern libxposed API 102，不要放 `assets/xposed_init`）：

```sh
cd origin-tweaks
./build.sh        # 默认 0.1.6 / versionCode 6 → dist/OriginTweaks-0.1.6.apk
```

装后≠生效：Vector 里启用 + scope 打勾 + 重启目标进程。验证链与弯路见
`origin-tweaks-REVIEW.md`。

**coolapk**（净化 + 回复过滤合并模块）：

```sh
cd coolapk/module-merged
./build.sh                    # 以 work/purifier-now.apk 为底座
adb install -r dist/coolapk-purifier-merged-*.apk
```

运行时类导出模块用后要 disable，否则酷安每次启动都强载几千个类。

**douyin**：`tools/dy_dex_strings.py` / `dy_rules_fingerprint.py` 吃参数、只读、
不依赖工作目录。

**nuke**（NukeApp-OSS fork；`nuke/src` 是 Gradle 工程，改动只允许落在这一层）：

```sh
cd nuke/src
export ANDROID_HOME=<SDK>                       # 有 platforms/android-37* + build-tools 即可
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:compileDebugKotlin
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:testDebugUnitTest -x checkProtocolRust \
  --tests "<测试类>"                       # 纯 JVM 单测；别跑全量（PacketExecutorTest 会挂住）
```

- 原生（zygisk/protocol）另需 `python3` + Rust + NDK 29，`assembleDebug` 会连带跑
  `buildZygiskNative`/`buildProtocolNative`；只做 Kotlin 编译/单测时不需要。
- 签名：`nuke/src/local.properties` 指向 `signing/debug.keystore`（跨机相对路径），
  构建时会把该证书摘要注入 native；设备上装的官方包是上游私钥签名，**不能** `install -r`。
- 分析结论与逐项改造状态：`nuke/ANALYSIS.md`、`nuke/PLAN-nuke-upgrade.md`。

**liquidtab-mine**（LiquidTab fork，GPL-3.0；底栏/顶栏液态玻璃，**多 App 逐个适配中**）：

```sh
cd liquidtab-mine
./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/app-release.apk (~10.7MB)
```

- **只发 release**（debug 84MB、无 R8）；`lint { checkReleaseBuilds = false }` 已配置，
  Xposed 非 SDK 接口的 lintVital 误报不用管。
- **签名红线**：release 用 `~/.android/debug.keystore`（`ANDROID_DEBUG_KEYSTORE` 可覆盖），
  与装机包同一签名 → `adb install -r` 直接覆盖，**不需要卸载**。换签名 = 必须卸载 =
  `PACKAGE_*` 广播风险（§1.0）。
- 装机三件事：① `install -r` 后 vivo 会弹安装确认框，**必须亮屏 + 用户点确认**；
  ② **打开一次模块 App**（Vector 2.2 更新怪癖 + provider 注册）；
  ③ 模块 App 被 vivo 冻结时宿主日志会出现
  `Unknown authority io.github.offlineglass.settings` + `WAITING`（玻璃不渲染）——
  拉起模块 App 即恢复，建议在最近任务里锁定它。
- 宿主进程必须重启才加载新代码：`am force-stop <pkg>` 后再启动。
- **当前状态（2026-11-17 晚，versionCode 239 / `2.1.0-detailalign`，用户真机确认）**：
  xhsfix1 宽屏热词胶囊隐藏 + xhsfix2 顶栏高度滑条 + coverfix2/3 返回动画修复
  （快速返回、后台 5 分钟回来均已修）+ 配置链四层兜底
  （RAM committed → provider → Vector 镜像 → 宿主本地存档
  `Android/data/<pkg>/files/liquidtab_last_good_config.json`）。
  **vivo 现场认知**：模块 App 退后台数秒即被冻结（provider 变 Unknown authority、
  libxposed service 绑定常失败）→ 镜像发布需前台重试（`ModernSettingsBridge.tryPublish`）+
  读取策略放宽（`StartupPreferencesPolicy` revision 有效即可用）。
  遗留：宿主主进程偶发未被 Vector 注入（子进程正常注入），与配置链无关，待查。
  另有排查开关「详情页玻璃」「导航栏侵入写入」（已证与动画无关，留作排查工具）。
- **关键根因（对所有 App 适配都适用）**：底栏玻璃的**每帧 backdrop 取景采样**
  （`GlassHostLayout.installCaptureLoop` 的 OnPreDraw pass）在宿主**返回转场期间任其活跃**，
  会让窗口在返回瞬间立即产出终帧，WindowManager 跳过宿主的收缩动画
  （现象：返回直接闪现；vivo 折叠机上内屏必现、外屏首次后永坏；B 站同症状）。
  **修复 recipe**：① 在 adapter 里识别宿主的详情/次级页面（Activity，Fragment 则挂
  `onFragmentResumed/Paused`）；② 用 WeakHashMap 维护 enter/exit 的 cover 状态，
  窗口 = 详情打开 → 返回动画结束（`onStop` 在动画后触发，正好覆盖）；③
  `AppNavigationState.pauseBackdropWork(host)` 返回该状态以暂停采样；
  ④ **exit 时必须唤醒 host**（`postInvalidateOnAnimation` + ~80ms 再补一次 invalidate），
  否则底栏标签（如"首页"）会冻结消失、要切 tab 才恢复。
  范本：`XhsDetailCover` + `XhsNavigationState.pauseBackdropWork`（XHS）；
  B 站是下一个候选。**别走弯路**：hide 原生顶栏 / 恢复 native 布局 / viewport 只写一次
  均已实测无效（mine3–mine6 失败史与证据链见 README-MINE.md）。

**xhs / 静盒 QuietBox**（小红书 9.54.1，源码构建 + 本地 patch）：

```sh
cd xhs
./build-src.sh            # → dist/quietbox-0.9.27-xhs9.54.1.apk（本地号 0.9.27 / v41）
./build-src.sh --audit    # 同上 + 对机内宿主 APK 做锚点审计
```

- 上游 CodeFloe `quiet-tools/quietbox`；本地 overlay = 自有签名 + `release-trust.properties`；
  本地扩展全在 [`patches/ui78-coldstart-restore.patch`](xhs/patches/ui78-coldstart-restore.patch)
  （`build-src.sh` 自动应用；改完功能记得**重新生成 patch**，别只改 `work/` 克隆）。
- 网络不稳：`git -C work/upstream-* config http.sslBackend openssl`（clone 有该 flag，pull 没有）。
- **UI-07 冷启动不刷新（2026-11-21 修好，用户真机验收）**：闸门在 `djd.f#b(djd.k)`
  （所有 feed 请求唯一执行器），冷启动窗口内命中
  `INITIAL_REFRESH|WK_CACHE_REFRESH + noteIndex=0 + 无 cursor + launchScenario=1` 时
  返回 **`Observable.just(emptyList())`**——不能返回 `Observable.empty()`（会让下拉刷新永久禁用），
  反射必须用宿主 classloader（否则静默放行）。原 `zid.m0#u0` 锚点冷启动**从不调用**，已降级为兜底。
- **UI-08 后台回来不刷新**：`zid.m0#G0`，`SILENT_BACK_REFRESH`/`FEED_30MIN_HOT_LAUNCH_REFRESH` 跳过。
- 宿主升版复验/再适配 SOP + 探针模板见 [`xhs/NOTES.md`](xhs/NOTES.md)；
  通用 hook 排障方法论见 [`HOOK-DEBUG-PLAYBOOK.md`](HOOK-DEBUG-PLAYBOOK.md)。
- 验收只认落盘证据（`/data/data/com.xingin.xhs/files/ui78-diag.log` 或
  `shared_prefs/status_report.xml` 的 `meta.session`）；logcat 会被 chatty 吞，不作数。

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
  回前台刷新守卫 **已装**；去广告 0.3.1 **已上机验证生效**（promotedMetadata 判定 +
  `com.x.android.main.hl1#invoke` 渲染跳过，4 次 skip、无崩溃）。0.2.9 的 entryId 方案
  在 12.42 完全无效。锚点与验证：`xhook/notes/X-12.42-ANCHORS.md` §6–§7。
- 验证只看日志：`adb logcat -s XHook`；模块是否加载看 `/data/adb/lspd/log/verbose_*.log`。

## 6. 约定

- 每个战线自带 `tools/` 与 `logs/`，不跨目录引用。
- 大文件（APK、解包产物、上游克隆、bugreport zip）一律 gitignore，只留本地。
- **签名材料一律入库**（2026-11-17 用户明确要求）：各战线 `.signing/*.keystore`、
  `signing/debug.keystore`、`nuke/src/local.properties`、`xhook/ours/local.properties`
  都在 git 里；密码、别名、证书指纹见 [`signing/README.md`](signing/README.md)。
  别把 `*.keystore` / `*.jks` / `local.properties` 重新加进任何 `.gitignore`。
- `_tmp_*/`、`_tools/` 是不入库的临时工作区（部分已在 gitignore）；其中
  `_tmp_vector/` 保留着 11-15 事故的 DB dump 物证，不要清。
- 结论必须有依据（命令输出、文件路径、实测记录）；但 `logs/` **只保留支撑当前结论的证据**，被新证据取代的旧记录直接删，不堆积历史。
- 归档脚本 `root/tools/reorganize-2026-11-14.sh` 是一次性整理脚本，**不要重跑**。

## 7. 文档索引

| 主题 | 文档 |
|---|---|
| root 战线正文 / 状态表 | `root/README.md` |
| 全部实测发现 | `root/FINDINGS.md`（§1–§11） |
| 各路线取证 | `root/ROUTES.md` |
| KSU/Vector 全栈笔记（含 11-15 事故与恢复 runbook） | `root/VECTOR-KSU.md`（§6.7） |
| 3080 双 daemon 崩溃取证 | `root/vector-softreboot/FINDINGS.md` |
| OriginTweaks 复盘 | `origin-tweaks-REVIEW.md` |
| NukeApp-OSS 逆向正文 / 改造清单 | `nuke/ANALYSIS.md`、`nuke/PLAN-nuke-upgrade.md`、`nuke/src/UPSTREAM.md` |
| 酷安逆向 | `coolapk/NOTES.md` |
| XHook 状态/构建/实测 | `xhook/README.md`、`xhook/ours/README.md` |
| X 12.42 结构锚点（去广告失效根因） | `xhook/notes/X-12.42-ANCHORS.md` |
| Re:X → XHook 移植台账 | `xhook/notes/PORTING.md` |
| LiquidTab fork 正文（补丁史/根因/通用适配 recipe） | `liquidtab-mine/README-MINE.md` |
| 静盒×小红书 9.54 适配 | `xhs/NOTES.md` |
| **跨战线 hook 排障方法论（证据分级/探针/返回值/classloader）** | `HOOK-DEBUG-PLAYBOOK.md` |
| PiliBro 逆向（血缘/许可证/玻璃路线） | `pilibro/NOTES.md` |
| PiliBro 2.8.0 vs PiliPlus 2.2.1 源码级完整对比 | `pilibro/COMPARISON.md` |
