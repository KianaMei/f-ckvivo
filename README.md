# f-ckvivo

vivo X Fold6（PD2545D / V2545A，MediaTek MT6993）在 **"永远拿不到内核镜像/全量包"** 约束下的
root 与固件包研究日志。

> 一切操作对象为作者自己的设备。所有内核级实验均遵循"只读/可回退/可重启恢复"原则；
> 真正写入内核的步骤（临时 root）实施前单独评估。

## 目标

1. 拿到 `PD2545D_A_17.1.12.x.W10.V000L1` 的官方全量包（全量 ROM zip）
2. 在不依赖 boot.img / 全量包的前提下取得临时 root（CVE-2026-43499 / 43074 / 64560 路线）

## 当前状态（2026-10-16）

| 战线 | 状态 |
|---|---|
| OTA API 直查全量包 | ❌ 尝鲜图未开放（正式推送 10/23-29 放量），anon 重放全 210 |
| VDFP 指纹复刻 | ❌ sectee TEE 服务对 shell 域完全不可达（AIDL 惰性/ HIDL 不可见 / ctl.start 被拒） |
| 抓包（代理/TLS/logcat） | ❌ 全被系统级封死（详见 FINDINGS.md §3） |
| DFRoot (CVE-2026-43284) | ❌ 本机实测 `DEVICE NOT VULNERABLE`（9 月补丁已修） |
| **43499 实机校准（multicast 路线）** | ⚠️ `--info` ✅ / carrier **自研推导** ✅ `--check` PASS / kernelsnitch ✅ 工作 / race 原语 256 轮 0 命中（= 上游文档 `RUNTIME_BLOCKED` 特征，需按构建重推 profile） |
| **fd_graph 路线（vivo 验证线）** | 🎯 **弹药就绪待发**：alfzki 的 X300 Pro fd_graph 战役全部产物已获取；profile 已重序列化为本机构建（`fdgraph/`）；CLI 验证门已实测通过；**等待机主 GO 执行**（错配攻击，最坏可恢复重启） |
| 全量包获取（物理渠道） | ⏳ 公开推送（10/23-29）/ 社区 dump / 售后 ROM |

## 目录

```
tools/                  OTA API 查询与探测脚本（真机参数级复刻）
probes/                 设备侧 app_process 探针（UFS ID / sectee TEE 调用链）
xf6-exploit/            CyberMeowfia 6.12.58 参考源码的本机适配版
                        （已含自研推导的 carrier 偏移：0x9220）
upstream-analysis/      上游资料存档（CVE-64560 PoC、KSuRoot 6.12 baseline、
                        iQOO 15 GhostLock profile、vivo vr.ko tag 实测表）
logs/                   实机探测日志（--probe-epoll / --probe-pipe 完整输出）
fdgraph/                fd_graph 路线（vivo X300 Pro 战役产物 + 本机适配版 bin + 路线文档）
                        └ tools/bin_tool.py 为 profile.bin 解析/重序列化器（往返字节级验证）
FINDINGS.md             全部技术发现（设备事实 / 封锁清单 / 生态地图 / 校准结果）
```

## 关键事实速览

- 内核：`6.12.58-android16-6-gdd6fd5c98918-abogki543627742-4k`（2026-08-05 构建，SPL 2026-09-01）
- 本机与已公开案例（PD2502/PD2545 @ 6 月内核 `g0a092cc0037a`）**不是同一构建**
- `libdumpstateaidl.so` 补丁偏移已自研推导：**0x9220**（ELF `.init_array[0]`，带 BTI），
  size `0x10c00`，preimage `5f 24 03 d5 fd 7b bf a9 fd 03 00 91 20 00 00 90`
- kernelsnitch（futex 时序 KASLR 破解）在本机 **实测工作**
- KSuRoot 4.1.2 全部 129 载荷中 vivo 仅覆盖 6.6.x 族，**6.12 vivo 无公开载荷**

## 上游致谢与链接

- [CyberMeowfiaNS](https://github.com/XingChenRS/CyberMeowfiaNS) —— 6.12.58 参考源码与案例矩阵
- [RootMyVivo](https://github.com/zenyxx-xd/RootMyVivo) / [RootMyVivo-Payloads](https://github.com/zenyxx-xd/RootMyVivo-Payloads)
- [GhostLock-App](https://github.com/YuKongA/ghostlock-app) —— 提取器 / CLI / 内核 profile 框架
- [KSuRoot (hmascs)](https://github.com/hmascs/KSuRoot) —— 122 条厂商载荷 / 载荷构建 / vr.ko 绕过
- [NebuSec/CyberMeowfia](https://github.com/NebuSec/CyberMeowfia) —— CVE-2026-64560 PoC
- [DFRoot](https://github.com/diabl0w/DFRoot) / [DirtyFrag](https://github.com/mitschud/DirtyFrag) —— CVE-2026-43284
- [JerryTse-OSS/VIVO-OTA-Tracker](https://github.com/JerryTse-OSS/VIVO-OTA-Tracker) —— OTA 协议参考

## 安全声明

仅用于自有设备的研究记录。内核漏洞利用工具存在导致内核 panic / 重启的风险（本仓库的
所有实机探测均选择了受控/可回退模式，且无持久化写入）。请勿在非自有设备上使用。
