# AGENTS.md — 仓库工作规则与总控导航

> 本文件是跨平台唯一指令真源（`CLAUDE.md` 直接 `@AGENTS.md`）。
> **任何 agent / 协作者动手前必须先读完 §1 设备红线。**
> 单战线的构建参数、锚点、排障流水账全部下沉到各自目录唯一的 `README.md`，不再堆在本文件。

## 0. 仓库与外层三区布局

研究对象：**vivo X Fold6（PD2545D / V2545A，MT6993，Android 17 / OriginOS 7，内核 `6.12.58-android16-6-gdd6fd5c98918`）**。
采用「**轻量主仓 + 外层统一 Fork 目录 + 外层统一缓存目录**」三区隔离（均与本仓同级，环境变量 `FORKS_DIR` / `LAB_CACHE` 可覆盖）：

```text
<parent>/
├── fuckmyphone/            # 本 Git 主仓（仅存源码、补丁、脚本、单文档、共享底座与签名）
│   ├── shared/             # 共享构建库 / ADB只读库 / 统一签名 / 公共桩与库 / PLAYBOOK
│   ├── system/             # 设备底层研究（root / 内存分析）
│   ├── mods/               # 100% 自研模块（drag-all / fq-fold-pad / origin-tweaks / vivoportal / xhook）
│   └── patches/            # 上游薄补丁与双入口合包（xhs-quietbox / bili-innocent / coolapk-purifier）
├── fuckmyphone-forks/      # 所有 Fork 与上游参考源码（独立 Git，不入主仓）
└── fuckmyphone-cache/      # 统一本地缓存：唯一 jadx 1.5.6 / 宿主 APK / 底座 APK / 分区镜像 / scratch
```

### 主仓路由表

| 路径 | 定位 | 状态 | 入口 |
|---|---|---|---|
| `shared/build-lib.sh` | 公共构建函数库（SDK/JDK/zip/d8/aapt2/apksigner） | 共享底座 | [build-lib.sh](shared/build-lib.sh) |
| `shared/adb-lib.sh` | ADB 只读封装（MSYS2 路径坑 + 健康自检） | 共享底座 | [adb-lib.sh](shared/adb-lib.sh) |
| `shared/signing/` | 全部 10 把签名 keystore 统一登记与对账 | 共享底座 | [signing/README.md](shared/signing/README.md) |
| `shared/PLAYBOOK.md` | 跨战线 Hook 排障方法论（证据分级/探针/classloader） | 方法论 | [PLAYBOOK.md](shared/PLAYBOOK.md) |
| `shared/tools/douyin/` | 抖音 41.2 只读 DEX 逆向脚本（`dy_dex_strings.py` 等） | 共享工具 | `shared/tools/douyin/` |
| `system/root/` | 临时 root（CVE-2026-64560）、Vector/KSU 事故恢复 runbook | 维护 | [system/root/README.md](system/root/README.md) |
| `system/memory-analysis/` | 15GB 内存预算、zram 公式、single-cleaner 链路 | 活跃 | [system/memory-analysis/README.md](system/memory-analysis/README.md) |
| `mods/drag-all/` | 跨 App 拖拽模块（Gradle，libxposed 102） | 活跃 | [mods/drag-all/README.md](mods/drag-all/README.md) |
| `mods/fq-fold-pad/` | 番茄小说折叠屏内屏双列模块（`build.sh`） | 稳定维护 | [mods/fq-fold-pad/README.md](mods/fq-fold-pad/README.md) |
| `mods/origin-tweaks/` | OriginOS 7 运行时 tweaks（`build.sh`，libxposed 102） | 稳定维护 | [mods/origin-tweaks/README.md](mods/origin-tweaks/README.md) |
| `mods/vivoportal/` | vivo 文件库动作栏扩展 VPortal Plus（`build.sh`） | 活跃 | [mods/vivoportal/README.md](mods/vivoportal/README.md) |
| `mods/xhook/` | X/Twitter 去广告与抓视频（Gradle，libxposed 102） | 稳定维护 | [mods/xhook/README.md](mods/xhook/README.md) |
| `patches/xhs-quietbox/` | 静盒×小红书源码补丁构建（对接外层 `quietbox`） | 活跃 | [patches/xhs-quietbox/README.md](patches/xhs-quietbox/README.md) |
| `patches/bili-innocent/` | Bilibili-Innocent 分享净化双入口合包 + 折叠补丁 | 活跃 | [patches/bili-innocent/README.md](patches/bili-innocent/README.md) |
| `patches/coolapk-purifier/` | 酷安 16.7.3 逆向与净化双入口合包 | 稳定维护 | [patches/coolapk-purifier/README.md](patches/coolapk-purifier/README.md) |

### 外层 Fork 目录（`../fuckmyphone-forks/`，全部独立 Git，不入主仓）

| 目录 | 定位 | 许可 |
|---|---|---|
| `nuke/` | NukeApp-OSS 魔改源码（抖音模块，附 `FORK-NOTES.md`） | MIT |
| `liquidtab-mine/` | LiquidTab 魔改源码（底栏/顶栏液态玻璃，附 `README-MINE.md`） | GPL-3.0 |
| `innocent/` | Bilibili-Innocent v1.2.2 源码副本（r3 折叠不刷新） | GPL-3.0 |
| `quietbox/` & `xposed-core/` | 静盒上游源码（由 `patches/xhs-quietbox/build-src.sh` 自动拉取并打补丁） | MIT |
| `piliplus/` & `freerate/` | B 站客户端参考源码（`piliplus/PILIBRO-RESEARCH.md`） | GPL-3.0 |
| `DouAi/` & `Doula/` | 抖音参考/41.2 融合模块源码 | — |

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
- **工具与宿主样本**：`jadx 1.5.6`、宿主 APK、底座 APK 与分区镜像均放在外层 `../fuckmyphone-cache/`，主仓绝不存放中间构建产物、APK 或反编译目录。
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
- **中间产物绝不入库**：`build/`、`dist/`、`.gradle/`、`*.apk`、`*.dex`、`*.img`、抓图与临时 XML 全部放外层 `../fuckmyphone-cache/` 或直接清理。
- **Fork 绝不入主仓**：所有上游源码与 Fork 统一放外层 `../fuckmyphone-forks/`，主仓只保留自研代码与轻量 `.patch`。
- **签名材料一律入库**：`shared/signing/*.keystore` 严禁被 `.gitignore` 忽略；仓库转公开前须另行换签评估。
