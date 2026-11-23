# AGENTS.md — 仓库工作规则与总控导航

> 本文件是跨平台唯一指令真源（`CLAUDE.md` 直接 `@AGENTS.md`）。
> **任何 agent / 协作者动手前必须先读完 §1 设备红线。**
> 单战线的构建参数、锚点、排障流水账全部下沉到各自目录唯一的 `README.md`，不再堆在本文件。

## 0. 仓库布局与主仓路由表

研究对象：**vivo X Fold6（PD2545D / V2545A，MT6993，Android 17 / OriginOS 7，内核 `6.12.58-android16-6-gdd6fd5c98918`）**。
采用「**主仓代码区 + 忽略区（`forks/` + `cache/`）**」一体目录布局——日常在同一工作区浏览编辑，但 `.gitignore` 通过 `/forks/` 与 `/cache/` 彻底隔离：

```text
fuckmyphone/
├── shared/                 # 共享构建库 / ADB只读库 / 统一签名 / 公共桩与库 / PLAYBOOK
├── system/                 # 设备底层研究（root / 内存分析）
├── mods/                   # 100% 自研模块（drag-all / fq-fold-pad / origin-tweaks / vivoportal / xhook）
├── patches/                # 上游薄补丁与双入口合包（xhs-quietbox / bili-innocent / coolapk-purifier / ding-vector）
├── forks/                  # [主仓 .gitignore 屏蔽] 各 Fork 与上游参考（内部各自独立 .git，不入主仓）
└── cache/                  # [主仓 .gitignore 屏蔽] 唯一 jadx 1.5.6 / 宿主 APK / 底座 APK / 分区镜像 / scratch
```

（可通过环境变量 `FORKS_DIR` / `LAB_CACHE` 覆盖 `forks/` 与 `cache/` 的默认位置。）

### 主仓路由表

| 路径 | 定位 | 状态 | 入口 |
|---|---|---|---|
| `shared/build-lib.sh` | 公共构建函数库（SDK/JDK/zip/d8/aapt2/apksigner） | 共享底座 | [build-lib.sh](shared/build-lib.sh) |
| `shared/adb-lib.sh` | ADB 只读封装（MSYS2 路径坑 + 健康自检） | 共享底座 | [adb-lib.sh](shared/adb-lib.sh) |
| `shared/signing/` | 全部 11 把签名 keystore 统一登记与对账 | 共享底座 | [signing/README.md](shared/signing/README.md) |
| `shared/PLAYBOOK.md` | 跨战线 Hook 排障方法论（证据分级/探针/classloader） | 方法论 | [PLAYBOOK.md](shared/PLAYBOOK.md) |
| `shared/tools/douyin/` | 抖音 41.2 只读 DEX 逆向脚本（`dy_dex_strings.py` 等） | 共享工具 | `shared/tools/douyin/` |
| `system/root/` | 临时 root（CVE-2026-64560）、Vector/KSU 事故恢复 runbook | 维护 | [system/root/README.md](system/root/README.md) |
| `system/memory-analysis/` | 15GB 内存预算、zram 公式、single-cleaner 链路 | 活跃 | [system/memory-analysis/README.md](system/memory-analysis/README.md) |
| `mods/drag-all/` | 跨 App 拖拽模块（Gradle，libxposed 102） | 活跃 | [mods/drag-all/README.md](mods/drag-all/README.md) |
| `mods/fq-fold-pad/` | 番茄小说折叠屏内屏双列模块（`build.sh`） | 稳定维护 | [mods/fq-fold-pad/README.md](mods/fq-fold-pad/README.md) |
| `mods/origin-tweaks/` | OriginOS 7 运行时 tweaks（`build.sh`，libxposed 102） | 稳定维护 | [mods/origin-tweaks/README.md](mods/origin-tweaks/README.md) |
| `mods/vivoportal/` | vivo 文件库动作栏扩展 VPortal Plus（`build.sh`） | 活跃 | [mods/vivoportal/README.md](mods/vivoportal/README.md) |
| `mods/xhook/` | X/Twitter 去广告与抓视频（Gradle，libxposed 102） | 稳定维护 | [mods/xhook/README.md](mods/xhook/README.md) |
| `patches/xhs-quietbox/` | 静盒×小红书源码补丁构建（驱动 `forks/quietbox`） | 活跃 | [patches/xhs-quietbox/README.md](patches/xhs-quietbox/README.md) |
| `patches/bili-innocent/` | Bilibili-Innocent 分享净化双入口合包 + 折叠补丁 | 活跃 | [patches/bili-innocent/README.md](patches/bili-innocent/README.md) |
| `patches/coolapk-purifier/` | 酷安 16.7.3 逆向与净化双入口合包 | 稳定维护 | [patches/coolapk-purifier/README.md](patches/coolapk-purifier/README.md) |
| `patches/ding-vector/` | 钉钉 Ding 1.16 上游全量保留 + Vector 放行补丁合体版 | 活跃 | [patches/ding-vector/README.md](patches/ding-vector/README.md) |

## 0.1 上游 Fork 与参考项目仓库清单（换机找回指南）

> `forks/` 与 `cache/` 不随主仓入库。若换新电脑仅 `git clone` 本仓，可按此表一键拉回所有 Fork 基线与上游参考仓库。
> **提示**：标记「⚠️ 本地自有魔改」的项目，原作者仓库只有原始基线；若要跨机同步自己的修改，请先为该子目录绑定个人 GitHub 远端 `origin` 并推送，或把 `.patch` 归档回主仓 `patches/`。

### 直接 Fork 与源码工作副本（`forks/` 内，各自独立 `.git`）

| 本地路径 | 上游 Git 地址 | 基准版本 / Commit | 许可 | 换机还原 / 本地状态说明 |
|---|---|---|---|---|
| `forks/quietbox/` | <https://codefloe.com/quiet-tools/quietbox.git> | tag `v0.9.13` (`1844b0040676`) | MIT | 直接运行 `sh patches/xhs-quietbox/build-src.sh` 自动克隆并叠加主仓 `patches/` |
| `forks/xposed-core/` | <https://codefloe.com/quiet-tools/xposed-core.git> | `c2a95fff8081` | MIT | 同上，`build-src.sh` 自动克隆（`quietbox` 复合构建依赖） |
| `forks/innocent/` | <https://github.com/jichuo1/Bilibili_Innocent_Lab.git> | tag `v1.2.2` (`a2af4f118e87`) | GPL-3.0 | 克隆 `v1.2.2` 后应用主仓 `patches/bili-innocent/source-v1.2.2-local-r3.patch` 与 `source-v1.2.2-r3-new-files/` 即可还原 r3 折叠补丁 |
| `forks/nuke/` | <https://github.com/dartcv/NukeApp-OSS.git> | `22d272d5a5f2` (`0.1.2`) | MIT | ⚠️ **含大量本地自有魔改**（`UPSTREAM.md` 有台账；当前本地独立仓未绑个人 `origin`，换机需同步自有 Fork 远端） |
| `forks/liquidtab-mine/` | <https://github.com/big-dictator/LiquidTab.git> | tag `v2.1.0` | GPL-3.0 | ⚠️ **含本地自有魔改**（`README-MINE.md` 有台账；当前本地独立仓未绑个人 `origin`，换机需同步自有 Fork 远端） |
| `forks/DouAi/` | <https://github.com/yudigaga/DouAi.git> | 41.2 融合版参考 | — | ⚠️ 含本地 `pinned` 签名及 41.2 适配调整（本地独立仓未绑个人 `origin`） |
| `forks/Doula/` | <https://github.com/jhihhe/Doula-Douyin-LSPosed.git> | — | — | 只读参考（抖音模块功能与 UI 参考） |
| `forks/piliplus/` | <https://github.com/bggRGjQaUbCoE/PiliPlus.git> | tag `2.2.1` (`217fb54f51f6`) | GPL-3.0 | 只读参考（`PILIBRO-RESEARCH.md` 记录了对比研究） |
| `forks/freerate/` | <https://github.com/ucxn/Bili.Libre.Speed.git> | `Canary` 分支 / tag `2.8.0` (`df2d061cffd0`) | GPL-3.0 | 只读参考（B 站客户端分支源码参考） |

### 按战线按需参考的外部上游仓库（主仓与 `forks/` 不常驻克隆）

| 所属战线 | 仓库地址 | 基准 / 用途 |
|---|---|---|
| **框架与 API 底座** | <https://github.com/JingMatrix/Vector><br><https://github.com/libxposed/api><br><https://github.com/libxposed/service> | Vector 2.2 源码（`88f8e1faa8b4`）与 libxposed API 102 / Service 接口契约参考 |
| **OriginOS / 拖拽**<br>(`mods/origin-tweaks`, `drag-all`) | <https://github.com/QIANMIANMIANMIAN/DragDropPlus> | `3f63251ce8fe`（`1.0.3`）：vivo 拖放直达白名单定位思路参考 |
| **LiquidTab 渲染依赖** | <https://github.com/Kyant0/AndroidLiquidGlass> | Apache-2.0：LiquidTab 液态玻璃折射方案上游 |
| **X / Twitter 模块**<br>(`mods/xhook`) | `python -I mods/xhook/tools/fetch_upstream.py` 自动按 [fetch-manifest.json](mods/xhook/tools/fetch-manifest.json) 拉取：<ul><li><https://github.com/Xposed-Modules-Repo/one.dot.rex> (`1.8.2`, 闭源 APK)</li><li><https://github.com/MrxSiN/TwitterHideAds> (`v3.1.0`)</li><li><https://github.com/qifangcat/x-video-save> (`v0.4`)</li><li><https://github.com/Jieoz/x-video-catcher> (`v1.58.0`)</li><li><https://github.com/JinShichang/X-ADBlockModule> (`v0.1.5`)</li><li><https://github.com/Mangi-11/TwiFucker> (`v0.1.2`)</li><li><https://github.com/BeepSleepySheep/X-region> (`v0.1`)</li><li><https://github.com/Karuizawakeii/XShortLinkCleaner> (`v1.0.0`)</li><li><https://github.com/lewisdoesstuff/birdify> (`v1.3.0`)</li><li><https://github.com/crimera/piko> (`v3.11.1`)</li></ul> | 全部由 `fetch-manifest.json` 锁死版本与 SHA-256，换机运行一次脚本即可全量拉回 |
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
- **Git Bash 路径坑**（已封装进 [shared/adb-lib.sh](shared/adb-lib.sh)）：
  - 传 `/data/...` 前先 `export MSYS2_ARG_CONV_EXCL='/data'`；
  - `MSYS_NO_PATHCONV=1` 仅在 `adb exec-out` 拉远程路径时临时加，避免破坏本地路径；
  - 拉二进制/SQLite 必须用 `adb exec-out su -c 'cat ...' > local`（禁 `adb shell cat`）；
  - `su -c` 会重置 PATH，busybox 写 `/data/adb/ksu/bin/busybox`。

## 3. Vector / KernelSU 运维铁律

当前现场：**KernelSU `me.weishu.kernelsu` v3.3.1 + kernelsu.ko(LKM) + Zygisk Next 1.5.3 + Vector 3080-88f8e1fa**。

- **只读自检**：`source shared/adb-lib.sh && vector_health_check`（`pidof vectord` 必须恰 1 个）。
- **模块是否加载只看落盘日志** `/data/adb/lspd/log/verbose_*.log`，不信被洪峰冲掉的 `logcat -d`。
- **更新模块 APK 后必须先打开一次模块 App**（Vector 2.2 更新与 provider 怪癖）。
- **绝不运行中就地升级/回滚 Vector 或框架类模块**；恢复优先 `setprop ctl.restart zygote` 而非 `ksud soft-reboot`（详细 runbook 见 [VECTOR-KSU.md](system/root/VECTOR-KSU.md) §6.7）。

## 4. 仓库约定

- **单文档制**：每个战线目录只维护唯一 1 份 `README.md`；跨战线方法论统一归口 [shared/PLAYBOOK.md](shared/PLAYBOOK.md)。
- **中间产物绝不入库**：`build/`、`dist/`、`.gradle/`、`*.apk`、`*.dex`、`*.img`、抓图与临时 XML 全部放 `cache/` 或直接清理。
- **Fork 绝不入主仓**：所有上游源码与 Fork 统一放 `forks/`（各自独立 `.git`，主仓 `.gitignore` 屏蔽），主仓只保留自研代码与轻量 `.patch`。
- **签名材料一律入库**：`shared/signing/*.keystore` 严禁被 `.gitignore` 忽略；仓库转公开前须另行换签评估。
