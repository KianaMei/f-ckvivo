> 仓库导航入口。工作规则、设备红线、工具链与各战线用法已合并至
> **[`AGENTS.md`](AGENTS.md)**（2026-11-15 起）；本文件只保留目录索引。

# 目录总览

| 目录 | 是什么 | 入口 |
|---|---|---|
| `root/` | **临时 root 战线**（CVE-2026-64560 等），本仓库最初的正文 | [`root/README.md`](root/README.md) |
| `drag-all/` | **跨 App 拖拽** LSPosed/Vector 模块（文字/图片拖拽、播放器禁拖） | [`drag-all/README.md`](drag-all/README.md) |
| `fq-fold-pad/` | **番茄小说折叠屏双列** LSPosed/Vector 模块（内屏双列，外屏不动） | [`fq-fold-pad/README.md`](fq-fold-pad/README.md) |
| `origin-tweaks/` | **OriginOS 运行时 tweaks**（拖放/超级拖拽/工作台/分屏；libxposed API 102 工作副本） | [`origin-tweaks/README.md`](origin-tweaks/README.md) |
| `origin-tweaks-REVIEW.md` | **OriginTweaks 复盘**：Vector 模块加载规则、弯路与打包/验证契约 | [`origin-tweaks-REVIEW.md`](origin-tweaks-REVIEW.md) |
| `coolapk/` | **酷安 16.7.3** 逆向：易盾加固分析、运行时类结构导出、净化模块适配 | [`coolapk/NOTES.md`](coolapk/NOTES.md) |
| `douyin/` | **抖音逆向**临时工作区 | [`AGENTS.md` §5](AGENTS.md) |
| `nuke/` | **NukeApp-OSS 逆向 + fork**（抖音模块；`nuke/src` 是我们维护的源码） | [`nuke/README.md`](nuke/README.md) |
| `xhs/` | 静盒×小红书适配（锚点迁移工具） | `xhs/` 内文档 |
| `liquidtab-mine/` | **LiquidTab fork**（小红书/B站 界面精简，GPL-3.0） | [`liquidtab-mine/README-MINE.md`](liquidtab-mine/README-MINE.md) |
| `xhook/` | 自研 X/Twitter hook 模块（XHook） | `xhook/` 内文档 |
| `originos/` | 上游 OriginOS 魔改/LSPosed 模块的**只读参考克隆**（各自带 `.git`） | — |
| `root/.upstream/` | 上游 GKI / 他人 CVE 复现仓库只读克隆（很大，不入库） | — |

```
ganvivo/
├── AGENTS.md                     工作规则 + 工具链 + 各战线用法（先读这个）
├── README.md                     ← 你在这里（仅导航）
├── root/                         临时 root 战线
│   ├── README.md                 研究正文：目标、现状、路线重判
│   ├── FINDINGS.md               实测记录与结论（§1–§11）
│   ├── ROUTES.md                 各条路线的细节取证
│   ├── VECTOR-KSU.md             exploit→KernelSU→Zygisk Next→Vector 全栈笔记
│   │                             （§6.7 = 2026-11-15 升级事故与无硬重启恢复 runbook）
│   ├── vector-softreboot/        3080 双 daemon 崩溃取证
│   ├── pd2545-64560/             PD2545D 端口的 CVE-2026-64560 构建与文档
│   ├── xf6-exploit/              早期 exploit 骨架（43074/43499 线，已存档）
│   ├── fdgraph/                  fd_graph 路线的 bin 格式与 profile（已存档）
│   ├── probes/                   DFP/UFS 只读探针（Java）
│   ├── upstream-analysis/        上游复现仓库的静态分析笔记
│   ├── tools/                    该战线的脚本（adb/NDK/BTF/抓包…），从 root/ 下运行
│   ├── logs/                     该战线的实测日志与截图
│   └── .upstream/                上游克隆（gitignore）
├── drag-all/                     跨 App 拖拽模块（文字/图片、播放器禁拖）
├── fq-fold-pad/                  番茄双列模块
├── origin-tweaks/                OriginOS 运行时 tweaks
├── origin-tweaks-REVIEW.md       OriginTweaks 复盘
├── originos/                     上游参考克隆（只读）
├── coolapk/                      酷安逆向（NOTES / module-inspect / module-merged / tools）
├── douyin/                       抖音逆向工作区（apk / work / tools，gitignore）
├── nuke/                         NukeApp-OSS 逆向 + 我们维护的 src/（Gradle，MIT）
├── xhs/                          静盒×小红书适配
├── liquidtab-mine/               LiquidTab fork（小红书/B站，GPL-3.0）
└── xhook/                        X/Twitter hook 模块
```

设备红线（只读默认、禁硬重启、软重启最小化、kill 按 PID…）与
adb/Git Bash 的路径坑，全部在 [`AGENTS.md`](AGENTS.md) §1–§2，动手前必读。
