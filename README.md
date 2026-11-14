> 仓库导航。同一台 vivo X Fold6 上并行的几条战线，各自独立成目录，互不依赖。

# 目录总览

| 目录 | 是什么 | 入口 |
|---|---|---|
| `root/` | **临时 root 战线**（CVE-2026-64560 等），本仓库最初的正文 | [`root/README.md`](root/README.md) |
| `modules/fq-fold-pad/` | **番茄小说折叠屏双列** LSPosed/Vector 模块（内屏双列，外屏不动） | [`modules/fq-fold-pad/README.md`](modules/fq-fold-pad/README.md) |
| `coolapk/` | **酷安 16.7.3** 逆向：易盾加固分析、运行时类结构导出、净化模块 profile 适配 | [`coolapk/NOTES.md`](coolapk/NOTES.md) |
| `douyin/` | **抖音逆向**的临时工作区与反编译产物 | 本文 §4 |
| `root/.upstream/` | 上游 GKI / 他人 CVE 复现仓库的只读克隆（很大，不入库） | — |

```
ganvivo/
├── README.md                     ← 你在这里（导航）
├── root/                         临时 root 战线
│   ├── README.md                 研究正文：目标、现状、路线重判
│   ├── FINDINGS.md               实测记录与结论（§1–§11）
│   ├── ROUTES.md                 各条路线的细节取证
│   ├── pd2545-64560/             PD2545D 端口的 CVE-2026-64560 构建与文档
│   ├── xf6-exploit/              早期 exploit 骨架（43074/43499 线，已存档）
│   ├── fdgraph/                  fd_graph 路线的 bin 格式与 profile
│   ├── probes/                   DFP/UFS 只读探针（Java）
│   ├── upstream-analysis/        上游复现仓库的静态分析笔记
│   ├── tools/                    该战线的脚本（adb/NDK/BTF/抓包…），从 root/ 下运行
│   ├── logs/                     该战线的实测日志与截图
│   ├── requirements.txt          python 依赖（requests / pycryptodome）
│   └── .upstream/                上游克隆（gitignore）
├── modules/
│   └── fq-fold-pad/              番茄双列模块（源码 / 编译脚本 / 逆向小工具）
├── coolapk/                      酷安逆向
│   ├── NOTES.md                  勘察结论 + 加固分析 + 16.7.3 适配进度（附一~附四）
│   ├── coolapk_target_manifest.json  从净化模块里抠出的 profile 表
│   ├── module-inspect/           运行时类结构导出模块（local.codex.coolapkinspect）
│   ├── tools/                    memdump / rebuild_dex / strings_dump / members_dump / match_profile
│   ├── apk/  work/  backup/      原始 APK / 导出 dump / 配置快照（gitignore）
├── douyin/                       抖音逆向工作区
│   ├── apk/                      原始 APK（gitignore）
│   ├── work/                     解包与 jadx 输出（gitignore）
│   └── tools/                    dex 字符串 / 规则指纹脚本
```

## 1. root/（临时 root 战线）

`root/README.md` 是正文（目标、当前状态表、路线重判），`FINDINGS.md` / `ROUTES.md` 是逐次实测与取证。
脚本都在 `root/tools/`，并且按"运行目录 = `root/`"组织：

```sh
cd root
./tools/pd2545_preflight.sh          # 设备只读预检
./tools/pd2545_watch_dump.sh         # 等待 + 抓 kallsyms
```

`pd2545-64560/` 自带 `README.md`（构建与 `--preflight` 用法），NDK 路径走 `ANDROID_NDK_ROOT` / `NDK` 环境变量，默认 `D:/D-code/tools/android/android-ndk-r29`，与本仓库位置无关。

## 2. modules/fq-fold-pad/（番茄双列模块）

只依赖 JDK + Android SDK，和 `root/` 完全无关：

```sh
cd modules/fq-fold-pad
VERSION_NAME=1.4.0 VERSION_CODE=5 ./build.sh   # 产出 dist/fq-fold-pad-1.4.0.apk
```

行为：折叠屏展开（内屏）→ 平板布局 + 阅读页双列 + 横屏判定；折叠（外屏）→ 完全不介入。
细节、逆向链路、`adb logcat -s FqFoldPad` 的读法见该目录 README。

## 3. coolapk/（酷安 16.7.3）

目标：让「酷安净化」模块在 16.7.3 上重新生效。沿途产物：

- 易盾（`com.netease.nis.wrapper`）加固结构逆向：98 MB 壳 dex = 10 个真实 dex 首尾相接，
  仅 header 被抹、`class_defs`/`class_data` 被重排、字符串做前辍压缩 → 静态不可直接反编译
- 运行时导出模块：`Class.forName` 暴力枚举 `[a-z0-9]{1,4}` + 静态类名清单，**导出 25,107 个类的完整结构**
- 已给净化模块打上 `16.7.3 / 2611121` 的 profile（`work/manifest-1673.json`），
  开屏广告 + 信息流赞助实测生效；`replySponsor` 因新版卡片架构（DataBinding → binder）失效

重新跑导出（注意跑完要 disable，否则每次启动酷安都会强制加载几千个类）：

```sh
su -c '/data/adb/lspd/cli modules enable local.codex.coolapkinspect'
su -c 'touch /data/user/0/com.coolapk.market/files/fqi/brute /data/user/0/com.coolapk.market/files/fqi/trigger'
```

## 4. douyin/（抖音逆向工作区）

- `apk/`：`douyin-41-2-0.apk`、`Douyin-Unified-41.2-modern-slim.apk`
- `work/`：解包产物（`classes.dex`、`jadx-out/`、`assets`/`assets2`、`tree.json` 等）
- `tools/`：`dy_dex_strings.py`（dex 字符串表）、`dy_rules_fingerprint.py`（规则集指纹）

这两个脚本吃命令行参数、只读，不依赖工作目录：

```sh
python douyin/tools/dy_dex_strings.py douyin/work/classes.dex "cpu"
```

## 约定

- 每个战线自带 `tools/` 与 `logs/`，不要跨目录引用。
- 大文件（APK、解包产物、上游克隆、bugreport zip）一律 gitignore，只在本地留存。
- 归档记录：`root/tools/reorganize-2026-11-14.sh` 是本次目录整理的一次性脚本，留作参考，**不要重跑**。
