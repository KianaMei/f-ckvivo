# fd_graph 路线 — X Fold6 适配记录

> 来源战役：[alfzki/ghostlock-app](https://github.com/alfzki/ghostlock-app) —— 一位研究者对
> **vivo X300 Pro（同为 MT6993 / 6.12.58 / android16-6）** 的 fd_graph 路线完整设备战役。
> 本目录承载该战役中可用于 X Fold6 (`6.12.58-android16-6-gdd6fd5c98918`) 的全部适配产物。
>
> ⚠️ **2026-10-16 复核后的定位：本目录是"存档与研究素材"，不是"待发弹药"。**
> 上游该路线的写入原语从未命中过（23 次闸门 `chain_hits=0`，含唯一那条 PASS），
> 且 profile 内的符号偏移是 X300 Pro 构建（`gff10eaa8f8a4`），与本机 `gdd6fd5c98918` 不同源。
> 详见 `../ROUTES.md`。

## 背景

GhostLock 官方（YuKongA）只有三条路线：`tcp_zerocopy` / `select_stack` / `multicast_waiter`。

`fd_graph`（RouteKind = **4**）是第四条路线，**只存在于 alfxki 分支**：
- select_stack 在其 6.12.58 vivo 构建上被判定架构性不可行（14-qword gap > 15-qword ceiling）
- fd_graph = eventpoll/pipe 图原语路线，其 `preload.so` 在 X300 Pro 上被验证可用
- 战役记录（`docs/analysis/device-gates/`）：**23 条闸门记录，1 PASS / 22 FAIL**（编号 01–24，04 跳号）
  > ⚠️ **2026-10-16 复核修正。** 本文件此前写作"5 PASS / 28 FAIL"，并称 `PROFILE-61258-05` 是
  > "W1 / SELinux 写在实机通过"。**该表述与上游记录不符，已作废。** 上游原文：
  > `fd_graph: RACE_SUMMARY … delivery=not-implemented … chain_hits=0 bit4_hits=0`，
  > 且该记录小节标题即为 **「说明：W1 仍未翻 SELinux」**，结论写明
  > **「W1 的实际 SELinux 0 仍待 `fd_graph` 投递体（批次 D）实现，本里程碑仅解除 race 握手」**；
  > 当轮 `enforce(after)=1`（SELinux 全程 Enforcing）正是"未翻成功"的直接旁证。
  > 详见 `ROUTES.md` §1 的逐条核对。
  - 唯一 PASS 的真实内容：`CMP_REQUEUE_PI` 由 `EDEADLK(35)` 变为 `ret=1`，**只是 PI 竞争窗口建立**，
    与写入/提权无关；两轮均 `chain_hits=0 bit4_hits=0`。
- 批次 D（投递原语）于 2026-10-04 12:24 实现（`4ac7e71 feat(route): implement fd_graph delivery primitive stages`）
- **批次 D 之后的闸门（-18 … -24）全部 FAIL**，其中 -24 明确记载 **"reclaim 仍未证"**；
  最后一次闸门在 `61258-gate-standby.md` 中标注 **"adb 推迟中——未执行"**，作者自述**"不构成门禁证据"**
- **截至上游最后提交（`dda9fda`，2026-10-11，仅 `add extra files`），该路线的写入原语在任何设备上从未命中过一次。**

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
