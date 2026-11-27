# AGENTS.md — 仓库工作规则与总控导航

> 本文件是跨平台唯一指令真源（`CLAUDE.md` 直接 `@AGENTS.md`）。
> **任何 agent / 协作者动手前必须先读完「设备不是草稿纸」与 §1 设备红线。**
> 单战线的构建参数、锚点、排障流水账全部下沉到各自目录唯一的 `README.md`，不再堆在本文件。

## 设备不是草稿纸（最高优先级，覆盖一切默认行为）

**严禁在手机上留下任何临时文件。** 这是项目级硬约定，不因战线、工具或调试便利而例外。

- **一律落回本机 `cache/`**：抓图、屏幕录制、`uiautomator` dump、反编译产物、日志、探针文件、调试 JSON、临时 APK、中间脚本输出。
- **禁止写入设备路径**：`/data/local/tmp`、`/sdcard`、`/storage/emulated/0`、`/data/user/0/<包名>/files`、`/data/adb` 等。模块运行必需的常驻配置不算临时文件，但须在所属战线 `README.md` 登记。
- **需要设备上的内容时用管道直拉**：`adb exec-out su -c 'cat /path' > cache/...`，不要在设备先落盘再 `adb pull`。截图/录屏同理（`adb exec-out screencap -p > cache/...`）。
- **模块调试不许写设备文件**：优先 `XposedBridge.log` / logcat；确实要看数据时，把观测点做成本机侧可读的日志，不要在目标应用私有目录建探针文件。
- **每次设备操作收尾自查**：确认本轮没有在设备上新增文件；发现历史遗留的临时文件，先汇报清单再清理。

## 0. 仓库布局与主仓路由表

研究对象：**vivo X Fold6（PD2545D / V2545A，MT6993，Android 17 / OriginOS 7，内核 `6.12.58-android16-6-gdd6fd5c98918`）**。
采用「**主仓代码区 + 忽略区（`forks/` + `cache/`）**」一体目录布局——日常在同一工作区浏览编辑，但 `.gitignore` 通过 `/forks/` 与 `/cache/` 彻底隔离：

```text
fuckmyphone/
├── shared/                 # 共享构建库 / ADB只读库 / 统一签名 / 公共桩与库 / PLAYBOOK
├── system/                 # 设备底层研究（root / 内存分析 / 侧边栏直捞）
├── original-modules/       # 原创模块：实现代码全部自研，无上游代码基线（drag-all / fq-fold-pad / origin-tweaks / tg-clients / vivo-ime / vivo-workbench / vivoportal / xhook）
├── upstream-derived/       # 上游二改：薄补丁、双入口合包与整树维护源码（xhs-quietbox / bili-innocent / coolapk-purifier / ding-vector / zhihu-zhiliao / qqmod / liquidtab-mine / nuke）
├── forks/                  # [主仓 .gitignore 屏蔽] 各 Fork 与上游参考（内部各自独立 .git，不入主仓）
└── cache/                  # [主仓 .gitignore 屏蔽] 唯一 jadx 1.5.6 / 宿主 APK / 底座 APK / 分区镜像 / scratch
```

目录归属只看**是否有作为修改对象的上游代码基线**：

- **`original-modules/` = 原创模块**。模块实现代码由我们自己编写，不从某个上游项目的源码或发布产物改起。使用公开 API、第三方依赖库，或参考其他项目的思路与实现做独立重写，仍属于原创。
- **`upstream-derived/` = 上游二改**。凡是基于明确上游项目的源码、APK 或其他发布产物做修改都放这里，不限于 `.patch` 文件，也包括双入口合包和整树维护源码；必须记录上游地址、基准版本与本地改动。
- 旧目录名 `mods/` 与 `patches/` 已废弃，新的代码、脚本和文档不得再把它们当作当前根目录使用；项目内部的 `patches/` 补丁子目录不受此限制。

（可通过环境变量 `FORKS_DIR` / `LAB_CACHE` 覆盖 `forks/` 与 `cache/` 的默认位置。）

### 主仓路由表

| 路径 | 定位 | 状态 | 入口 |
|---|---|---|---|
| `shared/build-lib.sh` | 公共构建函数库（SDK/JDK/zip/d8/aapt2/apksigner） | 共享底座 | [build-lib.sh](shared/build-lib.sh) |
| `shared/adb-lib.sh` | ADB 只读封装（MSYS2 路径坑 + 健康自检） | 共享底座 | [adb-lib.sh](shared/adb-lib.sh) |
| `shared/signing/` | 全部 13 把签名 keystore 统一登记与对账 | 共享底座 | [signing/README.md](shared/signing/README.md) |
| `shared/PLAYBOOK.md` | 跨战线 Hook 排障方法论（证据分级/探针/classloader） | 方法论 | [PLAYBOOK.md](shared/PLAYBOOK.md) |
| `shared/tools/adb-mcp/` | 只读 ADB MCP 网关（机械革命手机 → Cloudflare 隧道 → kaslana Antigravity 直连） | 稳定维护 | [adb-mcp/README.md](shared/tools/adb-mcp/README.md) |
| `shared/tools/douyin/` | 抖音 41.2 只读 DEX 逆向脚本（`dy_dex_strings.py` 等） | 共享工具 | `shared/tools/douyin/` |
| `system/root/` | 临时 root（CVE-2026-64560）、Vector/KSU 事故恢复 runbook | 维护 | [system/root/README.md](system/root/README.md) |
| `system/memory-analysis/` | 15GB 内存预算、zram 公式、single-cleaner 链路 | 活跃 | [system/memory-analysis/README.md](system/memory-analysis/README.md) |
| `system/sidebar-media-grabber/` | vivo 智能侧边栏机制逆向与前台原图/原 GIF 直捞计划（零 Hook 小窗版 → `CONVENIENT_TOOL` 插件版） | 规划中 | [system/sidebar-media-grabber/README.md](system/sidebar-media-grabber/README.md) |
| `original-modules/drag-all/` | 跨 App 拖拽模块（Gradle，libxposed 102） | 活跃 | [original-modules/drag-all/README.md](original-modules/drag-all/README.md) |
| `original-modules/fq-fold-pad/` | 番茄小说折叠屏内屏双列模块（`build.sh`） | 稳定维护 | [original-modules/fq-fold-pad/README.md](original-modules/fq-fold-pad/README.md) |
| `original-modules/origin-tweaks/` | OriginOS 7 运行时 tweaks（`build.sh`，libxposed 102） | 稳定维护 | [original-modules/origin-tweaks/README.md](original-modules/origin-tweaks/README.md) |
| `original-modules/tg-clients/` | Telegram 第三方客户端研究（ExteraLess 插件矩阵、3.56.2 折叠双栏适配与原创插件） | 活跃 | [original-modules/tg-clients/README.md](original-modules/tg-clients/README.md) |
| `original-modules/vivo-ime/` | vivo 输入法 Pro（`com.vivo.ai.ime.nex`）增强模块（`build.sh`，当前 `0.2.2` 双版本） | 活跃 | [original-modules/vivo-ime/README.md](original-modules/vivo-ime/README.md) |
| `original-modules/vivoportal/` | vivo 文件库动作栏扩展 VPortal Plus（`build.sh`） | 活跃 | [original-modules/vivoportal/README.md](original-modules/vivoportal/README.md) |
| `original-modules/vivo-workbench/` | OriginOS 7 原子工作台增强：外屏解锁（纯 SystemUI + Recents，不含 `system`）与后续布局调参（`build.sh`，libxposed 102） | 活跃 | [original-modules/vivo-workbench/README.md](original-modules/vivo-workbench/README.md) |
| `original-modules/xhook/` | X/Twitter 去广告与抓视频（Gradle，libxposed 102） | 稳定维护 | [original-modules/xhook/README.md](original-modules/xhook/README.md) |
| `upstream-derived/xhs-quietbox/` | 静盒×小红书源码补丁构建（驱动 `forks/quietbox`，当前 `0.9.34` 适配国服 9.56.0） | 活跃 | [upstream-derived/xhs-quietbox/README.md](upstream-derived/xhs-quietbox/README.md) |
| `upstream-derived/bili-innocent/` | Bilibili-Innocent 上游 `main` + 本地 r9 综合合并（分享双入口 / 折叠 / 直播 WebView / 弹幕标记） | 活跃 | [upstream-derived/bili-innocent/README.md](upstream-derived/bili-innocent/README.md) |
| `upstream-derived/coolapk-purifier/` | 酷安 16.7.3 逆向与净化双入口合包 | 稳定维护 | [upstream-derived/coolapk-purifier/README.md](upstream-derived/coolapk-purifier/README.md) |
| `upstream-derived/ding-vector/` | 钉钉 Ding 1.18 上游全量保留 + Vector 放行补丁合体版 | 活跃 | [upstream-derived/ding-vector/README.md](upstream-derived/ding-vector/README.md) |
| `upstream-derived/zhihu-zhiliao/` | 知了（Zhiliao）× 知乎 11.14.0 源码补丁构建（驱动 `forks/zhiliao`，当前 `26.02.03-adapt1` 全量 15 项修复） | 活跃 | [upstream-derived/zhihu-zhiliao/README.md](upstream-derived/zhihu-zhiliao/README.md) |
| `upstream-derived/qqmod/` | QQ/TIM 增强模块战线：TCQT / FunBox 的仓库、TG 频道、签名指纹与版本门槛（当前仅登记，补丁位预留） | 参考 | [upstream-derived/qqmod/README.md](upstream-derived/qqmod/README.md) |
| `upstream-derived/liquidtab-mine/` | LiquidTab 本机维护 fork（v2.1.2 基线 + 280→289 本地补丁，整树入库） | 活跃 | [README-MINE.md](upstream-derived/liquidtab-mine/README-MINE.md) |
| `upstream-derived/nuke/` | NukeApp 本机维护源码（含大量本地魔改，台账见 `UPSTREAM.md`）**只走 root/LSPosed，只构建 `:apps:module`，不构建也不安装 patcher** | 活跃 | [UPSTREAM.md](upstream-derived/nuke/UPSTREAM.md) |

## 0.1 上游 Fork 与参考项目仓库清单（换机找回指南）

> `forks/` 与 `cache/` 不随主仓入库。若换新电脑仅 `git clone` 本仓，可按此表一键拉回所有 Fork 基线与上游参考仓库。
> **例外**：`upstream-derived/liquidtab-mine/` 与 `upstream-derived/nuke/` 是整树入库的本地维护源码，clone 即得；两者原先独立的 `.git` 历史 bundle 已清理，代码本体与补丁台账均以主仓为准。
> **提示**：标记「⚠️ 本地自有魔改」的项目，原作者仓库只有原始基线；若要跨机同步自己的修改，请先为该子目录绑定个人 GitHub 远端 `origin` 并推送，或把 `.patch` 归档回该项目在 `upstream-derived/` 下的目录。

### 直接 Fork 与源码工作副本（`forks/` 内，各自独立 `.git`）

| 本地路径 | 上游 Git 地址 | 基准版本 / Commit | 许可 | 换机还原 / 本地状态说明 |
|---|---|---|---|---|
| `forks/quietbox/` | <https://codefloe.com/quiet-tools/quietbox.git> | tag `v0.9.13` (`1844b0040676`) | MIT | 直接运行 `sh upstream-derived/xhs-quietbox/build-src.sh` 自动克隆并叠加 `upstream-derived/xhs-quietbox/` 中的本地补丁 |
| `forks/xposed-core/` | <https://codefloe.com/quiet-tools/xposed-core.git> | `c2a95fff8081` | MIT | 同上，`build-src.sh` 自动克隆（`quietbox` 复合构建依赖） |
| `forks/innocent/` | <https://github.com/jichuo1/Bilibili_Innocent_Lab.git> | `main` `3418e18ed1c8` + `local/r8-main`（当前 r9 = `42668c47`） | GPL-3.0 | 检出 `3418e18ed1c8` 后应用主仓 `upstream-derived/bili-innocent/source-main-3418e18-local-r9.patch` 与 `source-main-3418e18-r9-new-files/` 即可还原当前综合合并源码（分享双入口 + 折叠 + 直播间广告图 + 弹幕标记/跳转 + r9 标记安装/对齐修复）；r8 的 patch 与 new-files 以及 v1.2.2 的 r3/r5/r7 补丁保留作历史对照 |
| `forks/zhiliao/` | <https://github.com/shatyuka/Zhiliao.git> | `26.02.03` (`988eac3efb5d`) | GPL-3.0 | 直接运行 `sh upstream-derived/zhihu-zhiliao/build-src.sh` 自动套用 `upstream-derived/zhihu-zhiliao/patches/adapt-zhihu-11.14.0.patch` 与自有签名并构建 |
| ~~`forks/DouAi/`~~（已弃用清理） | <https://github.com/yudigaga/DouAi.git> | 41.2 融合版参考 | — | **已迁移至 `upstream-derived/nuke/`**：41.2 适配成果在 `nuke/src` 与 `shared/tools/douyin/`，迁移文档（COMPARE/GAP/PLAN）可在 git 历史（`74680d2`、`b91b663`）恢复；签名仍用 debug B |
| `forks/Doula/` | <https://github.com/jhihhe/Doula-Douyin-LSPosed.git> | — | — | 只读参考（抖音模块功能与 UI 参考） |
| `forks/piliplus/` | <https://github.com/bggRGjQaUbCoE/PiliPlus.git> | tag `2.2.1` (`217fb54f51f6`) | GPL-3.0 | 只读参考（`PILIBRO-RESEARCH.md` 记录了对比研究） |
| `forks/freerate/` | <https://github.com/ucxn/Bili.Libre.Speed.git> | `Canary` 分支 / tag `2.8.0` (`df2d061cffd0`) | GPL-3.0 | 只读参考（B 站客户端分支源码参考） |

### 整树入库的本地维护源码（`upstream-derived/` 内，随主仓 Git 跟踪）

| 本地路径 | 上游 Git 地址 | 基准版本 / Commit | 许可 | 说明 |
|---|---|---|---|---|
| `upstream-derived/liquidtab-mine/` | <https://github.com/big-dictator/LiquidTab.git> | **v2.1.2 基线 + 本地合并**（当前 287 / `2.1.2-mine7`，合并记录见 `MERGE-PLAN-2.1.2.md`，补丁台账见 `README-MINE.md`） | GPL-3.0 | 2.1.2 冲突盘点见 `UPSTREAM-2.1.2-AUDIT.md`。原独立仓历史 bundle 已清理 |
| `upstream-derived/nuke/` | <https://github.com/dartcv/NukeApp-OSS.git> | `22d272d5a5f2` (`0.1.2`) → 2026-11-27 合并上游 dev `c53c444`，本地版本 `0.1.22` / `26` | MIT | 本地改法与合并记录见 `UPSTREAM.md`（前身 DouAi 已迁移至此）。原独立仓历史 bundle 已清理 |

> 这两条原本在 `forks/` 下各自独立成仓、无个人 `origin`，2026-11-23 移入主仓（现位于 `upstream-derived/`）并整树纳入 Git；嵌套 `.git` 未直接入库（否则只会变成子模块指针）。原独立历史 bundle 已清理，代码本体与补丁台账以主仓为准。

### 本机常驻参考仓（2026-11-23 forks 换血后）

上表中的 quietbox / xposed-core / Doula / piliplus / freerate 可按需用对应构建脚本或重克隆恢复；我们的修改全部在主仓 `upstream-derived/` 对应目录留有台账与补丁（`xhs-quietbox/patches`、`bili-innocent/source-main-3418e18-local-r9.patch` 等）。`forks/` 现常驻一批 OriginOS 系统 tweak 只读参考仓（均干净、有上游 origin、无未推送改动）：

| 本地路径 | 上游 Git 地址 |
|---|---|
| `forks/AnyBuds-OriginOS/` | <https://github.com/Mitchelded/AnyBuds-OriginOS.git> |
| `forks/Background-Customize-vivo/` | <https://github.com/chanjic/Background-Customize-vivo.git> |
| `forks/BetterOriginOS/` | <https://github.com/MengWanYu/BetterOriginOS.git> |
| `forks/DragDropPlus/` | <https://github.com/QIANMIANMIANMIAN/DragDropPlus.git> |
| `forks/FuckOrigin/` | <https://github.com/hmascs/FuckOrigin.git> |
| `forks/OriGoogle/` | <https://github.com/kailashrs/OriGoogle.git> |
| `forks/OriTweaks/` | <https://github.com/kailashrs/OriTweaks.git> |
| `forks/OriginCeiler/` | <https://github.com/unvoiddd/OriginCeiler.git> |
| `forks/OriginDuo/` | <https://github.com/Gengo0202/OriginDuo.git> |
| `forks/VivoDaemonNotifyMute/` | <https://github.com/GT001well/VivoDaemonNotifyMute.git> |
| `forks/VivoHfrAlwaysOn/` | <https://github.com/Sillybomo/VivoHfrAlwaysOn.git> |
| `forks/VivoMultiWindowUnlocker/` | <https://github.com/ReverieBizarre/VivoMultiWindowUnlocker.git> |
| `forks/ime-land-fix/` | <https://github.com/MikasaAckerrman/ime-land-fix.git> |
| `forks/regblock/` | <https://github.com/Greatday2233/regblock.git> |
| `forks/vivo-islandfix/` | <https://github.com/000896979/vivo-islandfix.git> |

另有 `forks/device-apks/`（1.4G 宿主 APK 样本，无 .git，cache 性质无需版本管理）。

### 按战线按需参考的外部上游仓库（主仓与 `forks/` 不常驻克隆）

| 所属战线 | 仓库地址 | 基准 / 用途 |
|---|---|---|
| **框架与 API 底座** | <https://github.com/JingMatrix/Vector><br><https://github.com/libxposed/api><br><https://github.com/libxposed/service> | Vector 2.2 源码（`88f8e1faa8b4`）与 libxposed API 102 / Service 接口契约参考 |
| **OriginOS / 拖拽**<br>(`original-modules/origin-tweaks`, `original-modules/drag-all`) | <https://github.com/QIANMIANMIANMIAN/DragDropPlus> | `3f63251ce8fe`（`1.0.3`）：vivo 拖放直达白名单定位思路参考 |
| **LiquidTab 渲染依赖** | <https://github.com/Kyant0/AndroidLiquidGlass> | Apache-2.0：LiquidTab 液态玻璃折射方案上游 |
| **X / Twitter 模块**<br>(`original-modules/xhook`) | `python -I original-modules/xhook/tools/fetch_upstream.py` 自动按 [fetch-manifest.json](original-modules/xhook/tools/fetch-manifest.json) 拉取：<ul><li><https://github.com/Xposed-Modules-Repo/one.dot.rex> (`1.8.2`, 闭源 APK)</li><li><https://github.com/MrxSiN/TwitterHideAds> (`v3.1.0`)</li><li><https://github.com/qifangcat/x-video-save> (`v0.4`)</li><li><https://github.com/Jieoz/x-video-catcher> (`v1.58.0`)</li><li><https://github.com/JinShichang/X-ADBlockModule> (`v0.1.5`)</li><li><https://github.com/Mangi-11/TwiFucker> (`v0.1.2`)</li><li><https://github.com/BeepSleepySheep/X-region> (`v0.1`)</li><li><https://github.com/Karuizawakeii/XShortLinkCleaner> (`v1.0.0`)</li><li><https://github.com/lewisdoesstuff/birdify> (`v1.3.0`)</li><li><https://github.com/crimera/piko> (`v3.11.1`)</li></ul> | 全部由 `fetch-manifest.json` 锁死版本与 SHA-256，换机运行一次脚本即可全量拉回 |
| **底层 Root / 内核**<br>(`system/root`) | 完整 9 个上游仓见 [system/root/README.md](system/root/README.md#上游致谢与链接)：<br>核心包含 <https://github.com/alfzki/ghostlock-app>、<https://github.com/YuKongA/ghostlock-app>、<https://github.com/feiguang-ai/plz110-6.12.69-cve-2026-64560-ksu>、<https://github.com/NebuSec/CyberMeowfia>、<https://github.com/hmascs/KSuRoot>、<https://github.com/XingChenRS/CyberMeowfiaNS>、<https://github.com/zenyxx-xd/RootMyVivo>、<https://github.com/diabl0w/DFRoot> / <https://github.com/mitschud/DirtyFrag>、<https://github.com/JerryTse-OSS/VIVO-OTA-Tracker> | MT6993 / 6.12 GKI / CVE-2026-64560 / fd_graph / vr.ko 绕过与 OTA 协议参考 |

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
> - 事故记录：[INCIDENT-2026-11-15-packageinstaller.md](system/root/INCIDENT-2026-11-15-packageinstaller.md)。

1. **默认只读**。任何非只读动作（kill / 写设备文件 / 装 APK / 软重启 / `setprop ctl.*` / 提权变更）必须先说明 **"做什么 + 影响 + 风险"** 并等用户明确同意；授权只覆盖当次。
2. **绝不硬重启 / 断电**：临时 root 是 kiroot 注入的 `kernelsu.ko`（LKM），硬重启即丢。
3. **软重启最小化**；动框架前先确认 GKD / Scene 等无障碍服务状态。
4. **kill 只按精确 PID**；禁 `pkill -f`（会自杀）。
5. **禁 OTA / 全量包 / 源码包方向**；设备只读采集物仍可用。
6. **panic 之后**：`/data/local/tmp` 最近写入文件可能静默全零，复跑前须重推并校验 sha256。
7. **数据底线**：不影响用户数据；若触发 recovery 询问清除数据，**绝不确认清除**（见 [VECTOR-KSU.md](system/root/VECTOR-KSU.md) §6.7.6）。

## 2. 环境、工具链与 Windows Git Bash 坑

- **SDK / JDK / 签名**：轻量模块统一 `source shared/build-lib.sh` 自动探测（`ANDROID_SDK_ROOT` / `JAVA_HOME` / `SIGNING_DIR` / `LAB_CACHE` / `FORKS_DIR` 均可环境变量覆盖）；签名统一存放在 `shared/signing/`（全部入库以保证跨机同签名 `adb install -r`）。
- **工具与宿主样本**：`jadx 1.5.6`、宿主 APK、底座 APK 与分区镜像均放在 `cache/`，主仓禁止提交任何中间构建产物、APK 或反编译目录。
- **设备序列**：`10AG632QLS002CF`（以 `adb devices` 为准）。
- **异地协同与真机/编译接入（kaslana ⇄ 机械革命 `jige`）**：
  - **代码实时同步**：`kaslana` 的 `D:\D-code\ganvivo` 与机械革命（鸡哥 `DESKTOP-QJDKLTH`，`C:\mycode\somethingsmall\fuckmyphone`）已通过 Tailscale Syncthing 1 秒级双向实时同步（仅同步主仓源码/文档/签名，屏蔽 `.git`、`cache/`、`forks/`、`build/`）；在 `kaslana` 直接用本地读写工具改文件即可，Git 提交统一在机械革命执行。
  - **远程调真机 ADB 与机械革命构建**：本机 `adb devices` 为空时，直接走 Tailscale 免密 `ssh jige`（`jaqenze@100.82.129.5`，默认 PowerShell 7，`adb` 已在 PATH；备用只读网关见 [adb-mcp/README.md](shared/tools/adb-mcp/README.md)）：
    - 真机 ADB（**同样受本文顶栏「设备不是草稿纸」与 §1 设备红线约束**）：`ssh jige "adb devices -l"`、`ssh jige "adb logcat -d -t 200"`，抓图/管道输出一律落机械革命 `C:/mycode/somethingsmall/fuckmyphone/cache/...`。
    - 原地编译与查 `cache/`/`forks/`：保存文件等 1–2 秒同步后执行 `ssh jige "& 'C:\Program Files\Git\bin\bash.exe' -lc 'cd /c/mycode/somethingsmall/fuckmyphone && bash <模块路径>/build.sh'"`。
- **Git Bash 路径坑**（已封装进 [shared/adb-lib.sh](shared/adb-lib.sh)）：
  - 传 `/data/...` 前先 `export MSYS2_ARG_CONV_EXCL='/data'`；
  - `MSYS_NO_PATHCONV=1` 仅在 `adb exec-out` 拉远程路径时临时加，避免破坏本地路径；
  - 拉二进制/SQLite 必须用 `adb exec-out su -c 'cat ...' > local`（禁 `adb shell cat`）；
  - `su -c` 会重置 PATH，busybox 写 `/data/adb/ksu/bin/busybox`。
- **WSL 才是完整构建机（Windows 编不了）**：`tools/*.py` 里 `import fcntl`（Windows Python 没有），NDK 预编译目录是 `linux-x86_64`。
  本机 WSL Ubuntu 24.04 已备：`/opt/android-sdk`（NDK `29.0.13113456`、cmake `4.1.2`）、JDK 21、Rust `1.90.0`（`aarch64-linux-android`）。
  跑法：`export ANDROID_HOME=/opt/android-sdk` → `java -Dorg.gradle.appname=gradlew -jar gradle/wrapper/gradle-wrapper.jar <tasks> --no-daemon`。
  新机器上先跑一遍 `shared/PLAYBOOK.md` §8.1 的环境探测，再开始任何编译。

### 2.1 作业纪律（墙钟优先，2026-11-27 复盘后立）

> 一轮 2 小时 44 分的任务是这么被浪费的：编译等待 38 分（必要）、jadx 卡死近 50 分（全废）、
> 等一个根本未启动的后台构建 6 分、等设备重连 7 分+。**token 快 ≠ 任务快**，堵点在等待和调度。

- **开工先探环境**：`command -v cargo python3 cmake`、`python -c "import fcntl"`、NDK 的 `prebuilt/` 目录是
  `windows-x86_64` 还是 `linux-x86_64`。10 秒能查完的东西，不要等“合并完整棵树”之后才撞出来。
- **长任务后台化、判活用 pid**：`setsid bash -c '<cmd>' > "$HOME/build.log" 2>&1 & echo $! > pid`，
  `kill -0 $(cat pid)` 判活。**禁用 `nohup ... &` 套在 `wsl.exe -lc` 里**（会被回收），
  **禁用 `pgrep -f <关键字>`**（会匹配到自己那条命令行而误报 RUNNING）。
- **反编译工具按问题选**：“某方法现在被改成什么”用 `dexdump -d` / baksmali（秒级、看字节码）；
  只有需要读大段控制流时才上 jadx，**且必须 `timeout 120`**。详见 [PLAYBOOK §8.3](shared/PLAYBOOK.md)。
- **互不依赖的战线并行起跑**：下载上游包 / 装工具链 / 编模块 A / 分析模块 B 之间没有依赖，
  串行做会把 40 分钟拖成 2 小时 40 分。
- **装机对账批量做**：版本 + 设备侧 SHA-256 + `system_server`/`lspd`/`zygote64` 的 PID 不变，
  这三项写进一个循环脚本（范例 `cache/scratch/install_batch.sh`），不要逐个手打。
- **汇报先说“接下来是纯等待 N 分钟”**，再报结果，别让人误以为一直在干活。

## 3. 框架 / KernelSU 运维铁律

当前现场（2026-11-27 实测）：**KernelSU `me.weishu.kernelsu` v3.3.1 + kernelsu.ko(LKM) + Zygisk Next 1.5.3 + LSPosed v2.2.1 (7912)**。

- **守护进程叫 `lspd`，不叫 `vectord`**。`/data/adb/modules/` 下只有 `zygisk_lsposed` 与 `zygisksu`；之前的 Vector（2.2 / 3080-88f8e1fa）已不在机，2026-11-26 20:31 起换回官方 LSPosed。查框架在不在用 `pidof lspd`；只有旧日志/旧 runbook 里还会出现 `vectord`。
- **只读自检**：`source shared/adb-lib.sh && framework_health_check`（旧名 `vector_health_check` 仍可用）。它会先试 `lspd` 再回退 `vectord`，打印实际命中的进程名与 PID；两者都空才是真没起来。
- **落盘日志 `/data/adb/lspd/log/verbose_*.log` 只在守护进程重启时写**，不是实时判据；`logcat -d` 的 main 环形缓冲只有 4 MiB（约 5–8 分钟），贴吧这类刷屏宿主会把模块日志挤掉，**不能以“没有日志”判定未注入**。某进程是否生效，用宿主状态文件、模块自带诊断落盘或 UI 行为确认。
- **“更新模块 APK 后必须打开一次模块 App”是过度概括**：配置镜像跨更新保留，只有三种情况需要打开——
  ① 首次安装、模块从未运行过（镜像不存在）；② 改过设置，需要它把新 revision 发布出去；
  ③ 宿主状态文件的 `keys` 明显偏少（镜像不完整）。判定：
  `adb shell cat /sdcard/Android/data/<宿主包名>/files/modern_framework_status.txt` 与
  `startup_preferences_status.txt`，`accepted=true revision=… keys=310` 即完整，此时 `detached=true` 属正常。
- **绝不运行中就地升级/回滚框架类模块**；恢复优先 `setprop ctl.restart zygote` 而非 `ksud soft-reboot`（Vector 时期的事故 runbook 见 [VECTOR-KSU.md](system/root/VECTOR-KSU.md) §6.7，其中 `vectord` 相关路径按当前 `lspd` 理解）。

## 4. 仓库约定

- **单文档制**：每个战线目录只维护唯一 1 份 `README.md`；跨战线方法论统一归口 [shared/PLAYBOOK.md](shared/PLAYBOOK.md)。
- **中间产物绝不入库**：`build/`、`dist/`、`.gradle/`、`*.apk`、`*.dex`、`*.img`、抓图与临时 XML 全部放 `cache/` 或直接清理。
- **Fork 默认不入主仓**：上游源码与只读 Fork 统一放 `forks/`（各自独立 `.git`，主仓 `.gitignore` 屏蔽）；主仓代码区按 `original-modules/`（原创）与 `upstream-derived/`（上游二改）分类。
  唯一例外是两条**本地维护源码**：`upstream-derived/liquidtab-mine/` 与 `upstream-derived/nuke/` 整树入库（上游历史已压缩，原独立历史 bundle 已清理），因为它们没有个人远端、放在 `forks/` 等于无备份。新增此类例外必须同时满足：无个人 `origin`、有本地台账、且整树入库后主仓仍可控。
- **签名材料与网关凭据入库**：`shared/signing/*.keystore` 与 `shared/tools/adb-mcp/secrets/token.txt` 随私有主仓入库以保证跨机同步；仓库转公开前须另行换签并轮换 token。
