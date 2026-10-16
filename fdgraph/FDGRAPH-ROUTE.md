# fd_graph 路线 — X Fold6 适配记录

> 来源战役：[alfzki/ghostlock-app](https://github.com/alfzki/ghostlock-app) —— 一位研究者对
> **vivo X300 Pro（同为 MT6993 / 6.12.58 / android16-6）** 的 fd_graph 路线完整设备战役。
> 本目录承载该战役中可用于 X Fold6 (`6.12.58-android16-6-gdd6fd5c98918`) 的全部适配产物。

## 背景

GhostLock 官方（YuKongA）只有三条路线：`tcp_zerocopy` / `select_stack` / `multicast_waiter`。

`fd_graph`（RouteKind = **4**）是第四条路线，**只存在于 alfxki 分支**：
- select_stack 在其 6.12.58 vivo 构建上被判定架构性不可行（14-qword gap > 15-qword ceiling）
- fd_graph = eventpoll/pipe 图原语路线，其 `preload.so` 在 X300 Pro 上被验证可用
- 战役记录（`docs/analysis/device-gates/`）：**5 PASS / 28 FAIL**
  - 关键 PASS：`PROFILE-61258-05-20261004-w1-owner-reorder-PASS`（**W1 / SELinux 写在实机通过**）
  - FAIL 系列均为**安全失败**（设备无恙、出诊断日志、可继续下一轮）
- 最终 profile bin 于 2026-10-11 重新打包（即本目录的 `x300pro-original.bin`）

## 产物

| 文件 | 说明 |
|---|---|
| `x300pro-original.bin` | alfxki 的最终配置原件（release=`gff10eaa8f8a4`，X300 Pro） |
| `xf6_fdgraph.bin` | **本仓库改造版**：他们的全部数据（fd_graph 几何 / X300 Pro 符号偏移 / `vr_guard` vivo 反 root 防护块）+ release 串改为本机（`gdd6fd5c98918`） |
| `../tools/bin_tool.py` | wire v2 格式解析器/重序列化器（对原件**往返字节级一致**，格式由官方 `NativeProfile.kt` 规范确认） |

### xf6_fdgraph.bin 内容摘要

```
route = 4 (FD_GRAPH)
release = 6.12.58-android16-6-gdd6fd5c98918-abogki543627742-4k
fd_graph 几何: eventpoll_size=0xd0 epitem_ep=0x48 epitem_fllink=0x50 pipe_buffer=0x28
               graph_width=96 graph_fanout=256 graph_edges=24576 objects_per_order3=16 (共16项)
kernel: kernel_phys_load=0x80000000 kernel_phys_offset=0x80000000
offsets (来自 X300 Pro 构建，非本机——这是已知不确定性):
  selinux_enforcing=0x27c6960 init_task=0x253dac0 init_cred=0x2553830 ...
vr_guard: off_vr_sys_exit_tp=0x26d8d60 (vivo vr.ko 防护处理)
```

## 运行命令（就绪，待 GO）

```sh
adb push ghostlock /data/local/tmp/ghostlock_alfzki   # 带 fd_graph 的 CLI（alfzki release 资产）
adb push xf6_fdgraph.bin /data/local/tmp/profile_xf6_fdgraph.bin
adb shell chmod 755 /data/local/tmp/ghostlock_alfzki
adb shell /data/local/tmp/ghostlock_alfzki --load-prebuilt-profile /data/local/tmp/profile_xf6_fdgraph.bin \
    --dump-kernel-log /data/local/tmp/gl_log
```

CLI 行为已实测：release 不符时**干净拒绝**（零内核接触）；换串版本通过该门后即开始完整攻击链。

## 风险与状态（2026-10-16 停放点）

- 这是**错配构建攻击**（X300 Pro 的符号偏移跑在本机 `gdd6fd` 内核上）
- 最坏情况：内核 panic → 自动重启（来源战役 28 次失败均为该级别，无变砖记录）
- 无 boot/分区改动，重启即恢复；按项目安全闸**等待机主 GO 后再执行**
- 若长期无 GO：等待社区发布 `gdd6fd5c98918` 专用 profile / boot 镜像后重新推导

## 引用

- alfxki fork：https://github.com/alfzki/ghostlock-app （release `pre-release`：CLI / profiles / extractor / APK）
- 上游：https://github.com/YuKongA/ghostlock-app
- 官方 bin 格式规范：`profile-core/src/main/kotlin/com/ghostlock/app/data/NativeProfile.kt`（wire v2）
