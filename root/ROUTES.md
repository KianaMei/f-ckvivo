# ROUTES —— 路线优先级复核与整合计划

日期：2026-10-16（2026-10-17 追加 §10 路线重判）。本文基于对上游 [alfzki/ghostlock-app](https://github.com/alfzki/ghostlock-app)
**全仓逐条核对**（`git clone --unshallow` 后本地 grep，非转述），以及外部资料的交叉印证。

> **证据等级约定**：`[已证]` = 上游仓库原文或本机实测可直接复现；`[推断]` = 由已证事实推出，
> 存在其他解释；`[未知]` = 尚无证据。

---

## 1. 闸门记录全量核对：修正三处误读

对本仓库此前三处陈述的复核结论。**全部以 `docs/analysis/device-gates/` 与
`docs/analysis/fd-graph-primitive-scoping.md` 原文为准。**

### 1.1 真实统计：23 条闸门，1 PASS / 22 FAIL `[已证]`

`device-gates/` 共 23 个 `PROFILE-*.md`，编号 01–24（**04 跳号**）。
文件名含 `PASS` 的只有 1 个：`PROFILE-61258-05-20261004-w1-owner-reorder-PASS`。

- 此前本仓库写"**5 PASS / 28 FAIL**"。
- 在上游全仓（含非 `.md` 文件）检索 `5 PASS` / `28 FAIL` / `PASS.*28` **无任何命中**。
- ⇒ **"5 PASS / 28 FAIL" 无法溯源，应作废。**

### 1.2 唯一那条 PASS 与 SELinux 无关 `[已证]`

`PROFILE-61258-05-*.md` 原文（节标题与结论，逐字）：

> ## 说明：W1 仍未翻 SELinux
> 日志 `W1: SELinux attempt 1/15`，`fd_graph: RACE_SUMMARY … delivery=not-implemented
> … chain_hits=0 bit4_hits=0`。
> 即 **W1 的 PI 窗口已能建立**（CMP_REQUEUE 成功），但 `fd_graph` 的投递体仍是批次 A 占位的
> `not-implemented`，因此 W1 不会真正落成 SELinux 0。
>
> ⇒ **但 W1 的实际 SELinux 0 仍待 `fd_graph` 投递体（批次 D）实现，本里程碑仅解除 race 握手。**

该轮实测表格：`CMP_REQUEUE_PI = ret=1 errno=0`（两轮），`enforce(after) = 1`。
**`enforce=1` 即 SELinux 全程 Enforcing——正是"没翻成功"的直接旁证。**

- 此前本仓库写"**W1 / SELinux 写在实机通过**" ⇒ **方向写反了，已作废。**

### 1.3 批次 D 之后全 FAIL，末次闸门从未执行 `[已证]`

时间线（`git log` 实测，非推测）：

| 时间 | 事件 |
|---|---|
| 10-03 21:52 | scoping 定稿（`f080a9b`）；panic 归因于 GhostLock 的 PI-futex 路径，**非 fd_graph** |
| 10-04 07:31 | W1 owner-reorder 修复（`99a326d`）→ 闸门 -05（仅 PI 握手） |
| **10-04 12:24** | **批次 D 投递原语实现**（`4ac7e71` + `114e8d5` BulkFdOwner） |
| **10-05 11:01–12:51** | 闸门 -10…-24（**全部在批次 D 之后**） |
| **10-11 12:20** | `dda9fda add extra files` —— **最后一个提交** |

批次 D 之后的闸门结论逐条：

| 闸门 | 结论原文 |
|---|---|
| -18 R1接线 | `chain` 仍诚实零值，**FAIL** |
| -19 decoy | 15/15 干净执行（**FAIL**） |
| -20 / -21 Q3 | 挂起（watchdog 中止，非崩溃） |
| -22 Q3b | 全轮 EPIPE 弃投（**FAIL**） |
| -23 Q3c | 投递流恢复，`chain` 仍诚实零值（**FAIL**） |
| -24 Q4 | slabinfo 本机不可用，**reclaim 仍未证**（**FAIL**） |

下一次（gate-25）记载于 `61258-gate-standby.md`，开头即：
> **状态：待命记录。adb/device 动作按用户要求推迟；本文件只记录"解禁后第一跑"的输入与判据，
> 不构成门禁证据。**

且其"期望读数"自己写的预期是：`RACE … chain_hit=0`（诚实零值）、
`enforce state=0` + Enforcing + 无 panic = **FAIL 但安全（预期内）**。

⇒ **结论：fd_graph 的写入原语，在任何设备上（含其原生 X300 Pro）从未命中过一次。**

### 1.4 唯一 `chain_hits>0` 的证据不是这条路线 `[已证]`

全 `device-gates/` 目录里唯一 `chain_hits` 非零的行，在 `PROFILE-61258-03-*.pstore.txt:16282`：

```
RESULT PASS pipe_flags_candidate page=0xffffff8281148000 fake_count=16 \
  generation=0x000000000000c255 reclaim_hits=3 chain_hits=3 bit4_hits=1
```

两个理由说明它**不是** fd_graph 的成绩：
1. 它是 **`.pstore.txt`** ——内核崩溃转储，内容是**更早一次运行**的残留内核日志；
2. 其形状（`RESULT PASS pipe_flags_candidate … generation=… reclaim_hits=… chain_hits=… bit4_hits=…`）
   与 `fd-graph-primitive-scoping.md` §2.2 引用的 **`preload.so` 自身成功日志**逐字段同形。

⇒ 这是**另一个实现**（`preload.so`）的残留，不能作为 fd_graph 路线可行的证据。

### 1.5 上游自己的定性 `[已证]`

`fd-graph-primitive-scoping.md` §1 结论：

> 移植只搬走了**词汇表与几何常量**，没有搬**机制**。
> stub 的写入路径是空实现：真机 10 次尝试 `chain_hits=0`，随后 kernel_panic。
>
> 关键纠正：**`preload.so` 的成功写入不经过 `splice`/`vmsplice`**。它是
> **pipe_buffer 槽位回收（reclaim）+ 受控页写入**的竞态。

真正机制（§2.16）：**96 × 256 = 24576 个嵌套 epoll** 构 `epitem.fllink` 链 +
240 根管道 `F_SETPIPE_SZ` 缩放回收 + `GENMARK0` 标记 + 240 路 `write` 投递，
验收判据是**读 `/sys/fs/selinux/enforce`**（不是自读回）。

> ⚠️ **对我们最关键的一条**：这条路线在**原生设备上都还没跑通**。
> 我们手上的 `xf6_fdgraph.bin` 是把 X300 Pro 的偏移（`gff10eaa8f8a4`）
> 换了个 release 串，**去驱动一条在原机上都没命中的原语**。
> 这不是"错配偏移"的问题，是**叠加在未证机制上的额外风险**。
> ⇒ `fdgraph/` 定位改为**存档与研究素材**，不再是"待发弹药"。

---

## 2. vr.ko 完整机制（上游权威实现）

来源：上游 `src/core/session/ancillary/vr_guard.hpp`（源码注释，`[已证]`）。
比你此前整理的资料**多一个标记**，且给出了完整的中和语义。

### 2.1 vr.ko 打 **3 个标记** + 布 **1 个探针** `[已证]`

```c
/* vivo/iQOO vr.ko marks an app-origin task with tag A (task_struct+0x06),
 * tag B (task_struct+0x2c) and the syscall-tracepoint bit
 * (thread_info.flags 0x400), and separately arms a sys_exit tracepoint
 * probe that kills any task reaching euid 0. */
```

| # | 标记 | 位置 | 性质 |
|---|---|---|---|
| 1 | tag A | `task_struct+0x06` | 位于 `thread_info.flags`（32 位字，`task_struct+0x00`）之内的一字节 |
| 2 | tag B | `task_struct+0x2c` | **唯一需要字节粒度写、唯一可配置偏移** |
| 3 | syscall-tracepoint 位 | `thread_info.flags` 的 **bit 10（0x400）** | 同落在 `+0x00` 那个字里 |
| — | sys_exit tracepoint 探针 | `sys_exit_tp + funcs_offset` | **击杀任何达到 euid 0 的任务** |

**打标对象明确是 "app-origin task"**（自 zygote/app 域起源的进程）。

### 2.2 中和是**两层**，不是一个 `detect()` patch `[已证]`

> Neutralization is therefore **two layers**: a global probe disarm at **PreSpawn**
> (so root shells survive) and a per-task tag clear at **PostSpawn**
> (so the exploit child survives W2 verify).

- **PreSpawn（全局拆探针）**：把 `tracepoint.funcs` 置零 →
  `sys_exit_tp_addr + funcs_offset`。`funcs_offset = offsetof(struct tracepoint, funcs)`
  **来自 profile**，不是从内核 release 串猜的。profile 不描述该布局时**不生成计划
  （fail-closed），不会往猜的偏移上写。**
- **PostSpawn（逐任务清标记）**：
  - `VR: flags+tagA` —— 写 `child_task + TASK_THREAD_INFO_FLAGS_OFF`
  - `VR: tagB` —— 仅当 profile 提供 `tag_b_off` 时才写，地址**向下对齐到 8 字节**

### 2.3 写粒度决定了 tag A 为什么没有 profile 字段 `[已证]`

> The exploit write primitive is **64-bit granular**, so tag A and the flag bit are cleared
> together by zeroing the whole thread_info flags word at `task_struct+0x00`.
> That is why they carry **no profile field**: only tag B needs a byte-granular write,
> and only tag B's offset is configurable.

⇒ 一次 8 字节写同时清掉 **tag A + 0x400 位**。

### 2.4 顺序是硬约束 `[已证]`

> **MEMBER ORDER IS EXECUTION ORDER** (clear_tags writes them in this order) and it is
> deliberate: `flags` first, because it carries the syscall-tracepoint bit that takes the
> task **off the sys_exit slow path immediately**, while the tag bytes are **tamper-checked
> by vr.ko**. Clearing the bit first closes the window in which the probe is still armed
> but the tag is already gone. **Do not reorder.**

⇒ **先清 `flags` 字**（立刻脱离 sys_exit 慢路径），**再清 tag B**。
反过来会在"探针还armed但标记已消失"的窗口里被 vr.ko 的防篡改检查抓到。

### 2.5 与你资料的三处交叉印证 `[已证]`

| 你的资料 | 上游实现 | 状态 |
|---|---|---|
| "6.6/6.12=**0x06**，tag B=**0x2c**" | `tag A at task_struct+0x06, tag B at task_struct+0x2c` | **逐字一致** |
| "tagA 覆盖 `task->flags`（为兼容 6.x **保留无条件执行**）" | tag A 落在 flags 字内被**无条件**清（只有 tag B 是 `optional`） | **一致** |
| "tagB 实测把进程写成僵尸（state==0 无法唤醒，管道读挂死到超时），已改为 `GHOSTLOCK_VR_TAG_B` 显式 opt-in、默认跳过" | 只有 tag B 是可配置可选项；`61258-gate-standby.md` 记载上游闸门里 **"vr_guard 默认关闭"** | **一致**，且解释了为什么它必须默认关 |
| "tagA 对 PD2338 是无效功——detect() 只读 `cred->euid` 和 SELinux 上下文，从不看 flags" | 上游仍无条件清 flags 字，但理由是**为了那 0x400 位**（脱离 sys_exit 慢路径），**不是**为了 tag A 本身 | 不矛盾：**上游清 flags 的目的是那一位，不是 tag A** |

> **这条很关键**：你资料里"tagA 在 PD2338 上无效"的取证结论，和上游"仍无条件清 flags 字"
> 并不冲突——上游清那个字的**真实收益来自 bit 0x400**，tag A 只是同字顺带。
> 这解释了为什么"无效功"却一直保留。

### 2.6 与你资料 Tier 3 的差异 `[推断]`

你资料里 5.15 的唯一彻底手段是 **`vrpatch.ko` 文本中和**（`detect` 入口 `.text+0x2ecc`
改 `mov w0,#0; ret`），代价是需要 `ksud insmod`（kallsyms 通道）+ build-id 对账 + 回读核验。

上游在 6.12 上走的是**数据段中和**：直接把 `sys_exit` tracepoint 的 `funcs` 置零。
`[推断]` 这比文本 patch 更干净——不碰模块 `.text`，不需要 build-id 对账，
代价是**需要 profile 提供 `funcs_offset`**（否则 fail-closed 不写）。

⇒ 若我们要做持久化，**优先照搬上游的数据段方案**，把文本 patch 作为回退。

---

## 3. 我们 exploit 对 vr.ko 的**结构性差异**

这一节是新发现，可能改变我们对 vr.ko 难度的估计。

### 3.1 我们的路线**根本不修改 cred** `[已证]`

`xf6-exploit/target.h` 自述的执行链：

1. 用 eventpoll/MCAST 原语标记一个 `pipe_buffer` 可合并、**patch 一个 shell 可读 DSO
   （`/system/lib64/libdumpstateaidl.so`）的 page cache**（carrier 偏移 `0x9220`）
2. `init` 以 **uid 0** 启动 `dumpstatez`，带 `/dev/socket/dumpstate`
3. 被 patch 的 constructor 在 `u:r:dumpstate:s0` 里**以 uid 0** 运行
4. `/system/bin/sh` 以 `executes_no_trans` 在 `u:r:dumpstate:s0` 执行
5. SELinux 确认 Permissive 后，`init` 启动 `bugreportd`；最终 daemon 是
   **init 拥有的 `bugreportd` 服务进程**，仍在 `u:r:dumpstate:s0`

⇒ **全程没有一个 `commit_creds` / `prepare_kernel_cred` / euid 写入。**
它的"root"来自**劫持一个本来就被 init 以 uid 0 启动的系统服务**。

### 3.2 这可能天然绕开 vr.ko 的第一道门 `[推断]`

- vr.ko 的击杀条件是"**达到 euid 0**"，且打标对象是 **app-origin task**。
- 我们的路径是 **init-origin 系统服务**（`dumpstatez` / `bugreportd`），不是 app-origin。
- 反证：`dumpstatez` 在**正常 bugreport 时就以 uid 0 运行**。若 vr.ko 无差别击杀所有
  uid 0 任务，设备日常就会崩——所以它的击杀范围必然是有界的。
- ⇒ `[推断]` 我们的 dumpstate 路线**可能不需要一级/二级对抗就能跑**。

> **`[未知]` 待验证（低成本、值得优先做）**：`u:r:dumpstate:s0` 是否在 vr.ko 击杀范围。
> 验证方式不必跑 exploit——`adb shell` 起一个 uid 0 进程在非 vrp 域即可观察
> （但这本身需要 uid 0，鸡生蛋）。替代：直接反汇编 vr.ko 的 `detect()`，
> 看它比对的域列表/条件。**vr.ko 我们已有 sha256 与 vermagic，镜像可从 `/vendor` 取。**

### 3.3 但持久化仍然要过 vr.ko `[已证 + 推断]`

- 你的资料：vr.ko 在**模块加载 / SELinux 检查 / 模块卸载**三个 kprobe 上布防，
  "只要 insmod 任何内核模块（包括 KernelSU 本体），卫兵立即检测到并重启系统"。
- ⇒ 即使 dumpstate 路线拿到 root shell + SELinux permissive，**装 KernelSU 仍会触发它**。
- ⇒ **持久化 = 必须做 vr.ko 对抗**（§2.2 的两层方案）。

### 3.4 一个值得注意的细节 `[已证]`

`target.h` 里同时钉了两个不同的内核串：

```
TARGET_RELEASE          "6.12.58-android16-6-gdd6fd5c98918-abogki543627742-4k"   ← 运行内核
TARGET_VR_MODULE_VERMAGIC "6.12.58-android16-6-g97704d7faadc-4k SMP preempt …"  ← vr.ko 的 vermagic
```

哈希不同（`gdd6fd5c98918` vs `g97704d7faadc`）。`[推断]` 这反映 GKI/ABO 结构
（`abogki` 即 Android Boot Optimization GKI）：设备内核与某 GKI 基线可不同源。
`[未知]` 含义待查——若 vr.ko 的 `task_struct` 布局与运行内核不同源，
则 §2.2 里 `funcs_offset` / `tag_b_off` **必须按运行内核重推**，不能从 vr.ko 自身取。

---

## 4. 路线优先级重排

> **2026-10-17 重判**：本表的 P1（43499）与 P2（carrier/43074）已因修复进入
> 设备血统而关闭；P0（64560）成为唯一存活路线。证据与新的行动顺序见 §10。

| 优先级 | 路线 | 机制 | 本机构建适配 | 成功证据 | 判定 |
|---|---|---|---|---|---|
| **P0** | **CVE-2026-64560** | POSIX 进程 CPU timer 删除与非 leader `exec()` 竞态 → `struct k_itimer` UAF | **漏洞面成立**（§4.1：本机 6-10 编译，早于 7-11 修复）<br>**但需 24 个内核符号地址**（§4.2） | 参考 PoC 在 Pixel 10 Pro / 6.6.118 **成功率 >50%**；本仓库记录"一加15T @ 6.12.69 已 device-verified 移植" | **原语最干净**（单点 UAF + 布局自校准），但**与 P1 卡在同一约束**：缺内核符号来源 |
| **P1** | CVE-2026-43499（multicast） | futex PI 竞态 + MCAST | carrier `0x9220` **自研推导成功**、`--check` PASS、kernelsnitch 工作 | race 原语 256 轮 **0 命中** = 上游 `RUNTIME_BLOCKED` 特征 | **卡在构建几何**，与上游 PD2564 先例同类，**需内核 Image 才能重推 profile** |
| **P2** | dumpstate carrier 路线（本仓库 `xf6-exploit/`） | page-cache patch DSO → 劫持 uid-0 系统服务 | `--info` / `--check` **实机 PASS** | **写入阶段从未到达**（因 P1 的 race 原语未通） | 本身设计合理且**可能天然绕开 vr.ko**（§3.2），但依赖 P1 的原语 |
| **P3** | fd_graph | 96×256 嵌套 epoll + pipe_buffer reclaim | 偏移为 X300 Pro 构建 | **23 次闸门从未命中**，含唯一 PASS；批次 D 后全 FAIL | **降级为存档素材**（§1） |
| — | CVE-2026-43284 DFRoot | IPsec ESP page-cache | — | **本机实测 NOT VULNERABLE** | 关闭 |
| — | CVE-2025-21479 / 21385 KGSL | Adreno GPU 驱动 | — | — | **N/A：本机 MT6993 为 Mali，非高通 Adreno** |

> **⚠️ 优先级表的关键修正**：P0 与 P1 **不是"备选关系"，而是"共享同一前置"**——
> 两者都需要本构建的内核符号/几何，来源只能是从**内核镜像**（`boot.img`）提取。
> 详见 §4.2 / §4.3。因此"同时押注两条路线"没有意义：先解决前置，再谈选哪条。

**一个战略要点**：你资料里的**一级绕过（不改 euid，只写 fsuid=0 + 全 caps）是 SoC 无关的**，
它来自 21479 那条高通路线，但**原理可以直接搬到我们的路线上**——
前提是我们的写原语能选择目标字段。`[未知]` 需确认 64560 / 43499 的写原语
默认写的是 `euid` 还是可指定字段。

### 4.1 CVE-2026-64560 漏洞面判定 `[已证·二次修正]`（2026-10-17，含实机数据）

> ⚠️ **本节是二次修正。** 第一次判定（10-16）依据 `target.h` 的 `TARGET_VERSION`
> 认为"内核 6-10 编译早于 7-11 修复 ⇒ 在漏洞区间"——**该推理作废**：
> 实机 `uname -v` 显示内核真实构建时间是 **2026-08-05 14:45:35**
> （`target.h` 里的 Jun 10 串是从上游 PD2502 原样继承的过期 pin，10-17 已修正）。
> 以下为基于实机数据与公告归属的**正确**判定。

**结论：本机内核大概率仍在漏洞区间，但存在明确的关闭时限。**

证据链：

| 项 | 值 | 来源 |
|---|---|---|
| 修复提交 | `920f893f…`（Gleixner，2026-07-03，`Cc: stable`） | tip 树 |
| mainline | 标签 `timers-urgent-2026-07-11` | — |
| vanilla 6.12.y 落地 | **6.12.100**（tag 2026-07-30）；≤6.12.99 均未含（逐版实测） | stable 树 |
| **Android 公告归属** | **2026-10-01 安全公告**（十月公告） | source.android.com |
| 本机内核构建 | **2026-08-05 14:45:35**（uname -v 实测） | 实机 |
| 本机 SPL | **2026-09-01**（build/vendor 双确认） | 实机 getprop |
| 本机系统镜像 | 261012214913（10-12 构建）但内核仍是 8-05 的 | 实机 |

推理：
1. 修复以十月公告（2026-10-01 SPL）形式到达 Android 设备侧；
2. 本机 SPL 停在 2026-09-01，且**内核二进制自 8-05 后从未更换**（10-12 的系统镜像仍带 8-05 内核）；
3. ⇒ 本机内核**不含该修复**，除非 vivo 在 8-05 构建里提前 cherry-pick 了 7 月的上游修复
   ——可能性低（公告协调在十月；ACK 的十月 release 才携带）。

**⚠️ 关闭时限（新增的战略约束）**：README 记录的 OTA 正式推送窗口 **10/23–29** 若携带
十月 SPL（2026-10-01），GKI 机型通常**连内核一起换**——64560 窗口随即关闭。
对策见 §5。

**残余不确定性**：vivo 8-05 构建提前合入该修复的可能性。完全确证需内核镜像
（比对 `posix_cpu_timer_del` 的重试循环）或直接跑 PoC（又卡在 §4.2 的 24 个符号上）。

### 4.2 CVE-2026-64560 的移植依赖（纸面评估，无需设备）

结论：**64560 不是"免镜像路线"，它与 43499 卡在同一类约束上。** 依据是 PoC 源码
（`upstream-analysis/cve64560_exploit.c`，5206 行）。

**它做得好的地方** `[已证]`：

| 维度 | 证据 |
|---|---|
| 原语是**单点 UAF** | POSIX CPU timer 删除 vs 非 leader `exec()`，不涉及多层机制叠加 |
| **结构布局自校准** | `load_physical_layout()` / `validate_init_task_layout()` / `validate_kernel_root_cred()` 全部在**运行时推导并交叉验证**（`DP_TASK_STABLE_READS` 次一致性比对），不是一张写死的偏移表。**这是它能移植到一加 15T 的原因** |
| SELinux 处理明确 | `DP_SELINUX_CRED_SIZE = 0x18`（= `struct task_security_struct` = 6×u32），从 init 的 cred 克隆 `osid/sid`（init 处硬校验 `osid==1 && sid==1`） |

**它做不了的地方 —— 也就是移植的硬卡点** `[已证]`：

PoC 硬编码了 **24 个 `LINK_*` 链接期内核符号地址**，且**没有任何运行时发现/回退路径**
（检索 `discover` / `fallback` / `scan_for` 均无命中）：

```
LINK_IMAGE_BASE         0xffffffc080000000   ← 与我们的 TARGET_KERNEL_STATIC_BASE 相同 ✅
LINK_CYCLE_C/D          0xffffffc0821_2e958 / 0xffffffc08212d5d0
LINK_MISC_LIST          0xffffffc082249560
LINK_SYSCTL_BOOTID      0xffffffc08238b2d8
LINK_UHID_MISC/FOPS     0xffffffc082285f40 / 0xffffffc0812e8778
LINK_ASHMEM_IOCTL/OPEN/RELEASE   0xffffffc080c8d908 / …e238 / …e2c0
LINK_CONFIGFS_READ_ITER/BIN_WRITE_ITER  0xffffffc080491eec / 0xffffffc080492418
LINK_MODULE_DIRECT_BASE/PLT_BASE/MEMSTART_ADDR/KIMAGE_VOFFSET/KMALLOC_CACHES  0xffffffc08168_…
LINK_ANON_PIPE_BUF_OPS  0xffffffc081176748
LINK_INIT_TASK          0xffffffc08212e280
LINK_INIT_CRED          0xffffffc082140748   ← **硬门**：不匹配直接拒绝
LINK_SELINUX_BLOB_SIZES 0xffffffc0816849b0
LINK_SELINUX_STATE      0xffffffc08236a2e0
LINK_SDATA              0xffffffc082110000
LINK_END                0xffffffc0823b0000
```

其中 `LINK_INIT_CRED` 被用作**硬门禁**：

```c
if (init_snapshot.real_cred != LINK_INIT_CRED + kernel_slide || …)   // 不匹配 → 拒跑
uint64_t init_cred = LINK_INIT_CRED + kernel_slide;
```

⇒ 这 24 个地址是 **Pixel 10 Pro / 6.6.118（`blazer`）构建**的；本机 `6.12.58 MT6993`
**一个都对不上**（`LINK_IMAGE_BASE` 恰好相同是巧合——那是 arm64 内核 VA 基址惯例；
但 `LINK_SELINUX_STATE - LINK_IMAGE_BASE = 0x236a2e0`，我们的对应偏移是 `0x27c6520`，
两者不同，印证是不同构建）。

**⇒ 修正后的 64560 定位** `[推断]`：

- 它需要的**不是**竞态几何（43499 的卡点），而是**内核符号地址**；
- 内核符号在设备上**拿不到**（`/proc/kallsyms` 与 `/sys/kernel/btf/` 均 Permission denied，见 `FINDINGS.md` §1）；
- ⇒ **两条路线（43499 / 64560）最终都指向同一个根本约束：没有内核镜像 / 没有内核符号来源。**

### 4.3 因此真正的瓶颈与解锁点 `[推断]`

**瓶颈不是"选哪条漏洞"，而是"如何拿到本构建的内核符号或等价几何"。**

| 路线 | 当前卡点 | 解锁需要 |
|---|---|---|
| 43499 (multicast) | race 运行时几何（6 月常数 = `RUNTIME_BLOCKED`） | 本构建的 profile 推导 → 需 `kallsyms`/内核 Image |
| 64560 | 24 个内核符号地址 | 同上 |
| dumpstate carrier | 依赖 43499 的原语 | 同 43499 |

**⇒ 项目 README 的目标 #1（拿到官方全量包）其实是两条 root 路线的共同前置，不只是独立目标。**
理由链（`[已证]`，见 `FINDINGS.md` §4 与上游流程）：

```
全量 ROM zip → boot.img / vendor_boot.img → 提取 kallsyms + vr.ko
            → 43499 的 profile 推导   ✅ 解锁
            → 64560 的 24 个符号地址  ✅ 解锁
            → vr.ko 本体（§2 对抗素材）✅ 解锁
```

**这与 README 记录的 OTA 时间窗高度吻合**：正式推送 **10/23–29 放量**（今天 10-16，约一周后）。
`tools/live_replica.py` 在那之前就绪即用。

**同时也说明 `fdgraph/` 的降级是对的**：它连"符号地址"这一层都没进入
（上游在原生机上 `chain_hits=0`），把它当作并发推进的选项不划算。

---

## 5. 下一步（按可执行性排序）

> **🚨 10-17 新增的最高优先事项：抢在 OTA 前锁定当前内核的素材。**
> 64560 窗口大概率在 **10/23–29 正式推送**（若带 2026-10-01 SPL）时关闭——GKI 月度更新通常连内核一起换。
> 在那之前必须完成：① 不升级；② 想办法拿到**当前**内核 `gdd6fd5c98918` 的镜像/符号
> （全量包是最直接来源——见 §4.3，注意要抓**升级前版本**的包）。

0. **✅ 已完成（2026-10-16/17）**：64560 二次判定（§4.1）、实机采集（§9）、
   `target.h` 两个过期 pin 修正、工具链与构建复现（§8）。

1. **（需设备）设备侧事实采集**：`./tools/phone_preflight.sh --collect`
   - 身份闸门（release / version / cmdline token / carrier size+sha256+偏移 preimage）
   - 拉取 `vr.ko` 到 `tools/device-dump/`
   - SELinux `enforce` 可读性（**这就是 64560 的验收判据**）
   - 只读可见性矩阵（`/proc/slabinfo` 等）
   - **CPU 拓扑**（43499 的"CPU 核心对"校准旋钮，见本文件 §6 表）
   - 期望值全部从 `target.h` 读取，脚本内不硬编码（避免"自洽但永远为真"的假闸门）

2. **（需设备，采集后即可做）反汇编 vr.ko 的 `detect()`**
   - 回答 §3.2 的 `[未知]`：`u:r:dumpstate:s0` 是否在击杀范围
   - 命令：`python tools/analyze_vr.py --input tools/device-dump/vr.ko`
     （该脚本扫 `euid` / `tracepoint` / 小立即数 `ldrb`/`strb`（tag 偏移）/ hook，
     pyelftools 0.33 已就位）
   - 需要时再用 NDK 自带 `llvm-objdump` 做指令级确认

3. **64560 移植评估**（可先做**纸面**部分，不必等设备）
   - 参考 PoC 是 Pixel 10 Pro / 6.6.118 布局；与本机 6.12.58 的差异需逐项列出
     （`k_itimer` / `timerqueue_node` / `signal_struct` 布局，`posix_cpu_timer_del` 语义）
   - ⚠️ 与 43499 的困境同类：**没有内核镜像时结构偏移只能靠公开源码 + 运行时回读**
   - 但 64560 的优势是**单点 UAF**，不涉及 24576 个 fd 的图原语那种多层机制

4. **（不建议现在做）fd_graph 实机**
   - 理由见 §1.5：**在原生机上都没跑通的机制**，叠加错配偏移，收益极低、风险不变。
   - 保留 `fdgraph/` 作素材；若上游后续发布批次 D 的实机 PASS 记录，再重新评估。

---

---

## 6. 待确认清单

| # | 问题 | 影响 | 如何确认 |
|---|---|---|---|
| 1 | ~~本机构建是否含 64560 修复~~ | — | **已结论：在漏洞区间**（§4.1）。残余不确定：vivo 是否反推过该补丁 —— 需设备侧确证 |
| 2 | `u:r:dumpstate:s0` 是否在 vr.ko 击杀范围 | 决定是否需要一级/二级对抗 | `tools/phone_preflight.sh --collect` 取 vr.ko → `tools/analyze_vr.py` |
| 3 | vr.ko vermagic `g97704d7faadc` ≠ 运行内核 `gdd6fd5c98918` 的含义 | 决定 tag/probe 偏移该从哪取 | 取 `/proc/version` 与 vr.ko 的 `.modinfo` 对拍 |
| 4 | 64560 / 43499 的写原语能否指定写 `fsuid`+caps 而非 `euid` | 决定能否直接套用一级绕过 | 读两条路线的写原语实现 |
| 5 | 上游是否会在批次 D 后发布实机 PASS | 决定 fd_graph 是否值得重启 | 跟踪 alfxki/ghostlock-app |

---

## 7. 本次复核改动的仓库文件

| 文件 | 改动 |
|---|---|
| `fdgraph/FDGRAPH-ROUTE.md` | 修正"5 PASS/28 FAIL"→23 条 1P/22F；修正"W1/SELinux 写实机通过"→PI 握手；头部加"存档素材"定位 |
| `FINDINGS.md` §8 | 同上修正 + 补批次 D 时间线与"末次闸门未执行" |
| `README.md` 状态表 | fd_graph 行降级；新增 64560 行为最高优先级候选 |
| `ROUTES.md` | 本文件（新增） |

---

## 8. 构建环境与源码补全（2026-10-16）

### 8.1 一个已修复的断链：`xf6-exploit/` 缺 8 个源文件

原仓库 `xf6-exploit/` **只提交了 `main.c` + `target.h`**，但 `main.c` 首行即
`#include "common.h"` —— 该头文件**从未提交过**，`git log --all` 全历史无记录。
⇒ 补全前 `build/exploit_xf6_v3` **无法重新编译**。

与上游比对确认了真实关系：

| 文件 | 与上游 `pd2502-6.12-reference` 的关系 |
|---|---|
| `main.c` | 内容一致，**仅 1 处**本机改动：`carrier_preimage[]`（改为自研推导的带 `bti c` 版） |
| `target.h` | **5 处**本机适配：`0x9410→0x9220`、`0x10be8→0x10c00`、`TARGET_RELEASE`、cmdline token、expected patch offset |
| 其余 8 个源文件 | **未改动，此前未提交** —— 已从上游原样补入 |

补齐的文件：`common.h`、`util.c`、`patch.c`、`page.c`、`pipe.c`、`late_refs.c`、`su.c`、
`payloads.S`、`kernelsnitch/{futex_hash,kernelsnitch,timeutils,utils}.h`（共 3821 行），
以及上游 `NOTICE`（**Apache-2.0，保留 NOTICE 是许可要求**）。

### 8.2 工具链（本机已就位）

| 组件 | 版本 | 位置 |
|---|---|---|
| Android NDK | **r29 / 29.0.14206865** | `D:\D-code\tools\android\android-ndk-r29` |
| adb / platform-tools | 37.0.1-15733141 | `D:\D-code\tools\android\platform-tools` |
| d8（Java 探针） | r8 9.5.26 | `D:\D-code\tools\android\r8.jar`（Java 21 已在 PATH） |
| jadx（逆向） | jadx-headless-mcp 0.7.1 | `D:\D-code\tools\jadx-headless-mcp`（此前已有） |

NDK 是从 `mirrors.cloud.tencent.com` 下的（与 Google 官方**字节数完全一致** 833850862，
跨源断点续传后 `unzip -t` 无错误）——国内直连 Google 约 9 MB/min，腾讯源约 12 MB/s。

### 8.3 构建已验证

```
./xf6-exploit/build.sh
→ 362296 字节 / ELF64 AArch64 EXEC（静态）
→ sha256 16c2fd33bfd0ae4a07e534c36ac70f22afba027d458b238e1097e3f18017b600
```

**规模与 `FINDINGS.md` §5 记录的"362KB 静态 aarch64"吻合**；
`-Werror` 零告警（上游门槛要求）。脚本二次运行 sha256 位级一致。

---

## 9. 实机采集记录（2026-10-17，授权后首次全量采集）

设备 `10AG632QLS002CF`（V2545A），全部只读。产物在 `tools/device-dump/`。

### 9.1 身份与闸门（修正后全部对齐）

| 项 | 实测值 | 与 pin 对照 |
|---|---|---|
| release | `6.12.58-android16-6-gdd6fd5c98918-abogki543627742-4k` | ✅ |
| **内核构建时间** | **`#1 SMP PREEMPT Wed Aug 5 14:45:35 UTC 2026`** | ❗ 发现 pin 过期（原 Jun 10 为上游残留），**已修** |
| cmdline token | `product.version=PD2545_A_17.1.12.10.W10` | ✅ |
| carrier size | `68608 (0x10c00)` | ✅ |
| carrier preimage@0x9220 | `5f2403d5 fd7bbfa9 fd030091 20000090` | ✅ **自研推导获得独立复核**（本地拉取逐字节一致） |
| **carrier sha256** | **`740c53b4…`** | ❗ 原 pin `9a046a14…` 为上游残留，**已修** |
| SPL | `2026-09-01`（build+vendor） | （十月公告未装 ⇒ §4.1 判定成立） |
| 系统镜像 | `261012214913`（10-12 构建）**仍带 8-05 内核** | 内核自 8-05 未换过 |
| vbmeta | `locked` | — |

### 9.2 vr 模块实况

- `/proc/modules`: `vr 45056 0 [permanent], Live 0x0000000000000000 (O)`
  —— 与资料描述完全一致（永久标记、OOT、地址被清零）。
- **vr.ko 文件本体不可达**：不在 `/vendor_dlkm/lib/modules`（315 个 .ko，无 vr）、
  `/vendor/lib/modules`、`/odm`、`/system_dlkm`。已加载 663 个模块 vs 磁盘可见 315 个
  ——**约半数模块（含 vr）从 shell 不可见的位置加载，最可能在 vendor_boot 的 initramfs 里
  （一阶段 init 加载，开机后不落盘可见）**。
- ⇒ `target.h` 的 `TARGET_VR_MODULE_SHA256/VERMAGIC`（继承自 iQOO 15 实测）**无法在本机核验**，
  已加 ⚠ 注释；本机 vr.ko 只能从全量包（vendor_boot.img）取。

### 9.3 dumpstate 服务链（carrier 路线前提验证）

`/system/etc/init/dumpstate.rc` 实测——三个服务**全部 `user root`**：

```
service dumpstate  /system/bin/dumpstate -s   (socket dumpstate stream 0660 shell log, oneshot)
service dumpstatez /system/bin/dumpstate -S   (socket dumpstate stream 0660 shell log, oneshot)
service bugreportd /system/bin/dumpstate -w   (oneshot)
```

⇒ `target.h` 的前提（"init 以 uid 0 启动 dumpstatez 并带 /dev/socket/dumpstate"）**实机成立**。

### 9.4 只读可见性（修正脚本假阳性后复核）

| 路径 | 状态 |
|---|---|
| `/sys/fs/selinux/enforce` | ✅ 可读（=1 Enforcing）——**64560 验收判据可用** |
| `/proc/slabinfo` | ✅ 可读（诊断通道在） |
| `/proc/version`、`/proc/cmdline` | ✅ |
| `/proc/kallsyms` | ❌ Permission denied（与 FINDINGS §1 一致；**首轮"可读"是脚本假阳性，已修**） |
| `/sys/kernel/btf/vmlinux` | ❌ |

### 9.5 CPU 拓扑（43499 校准旋钮 #1 —— **已结案**）

```
cluster 0（LITTLE 2.7GHz）: cpu0 cpu1 cpu2 cpu3
cluster 1（MID    3.5GHz）: cpu4 cpu5 cpu6
cluster 2（BIG    4.2GHz）: cpu7   ← 单核，无法构对
```

**结论（2026-10-17 实机验证）**：`late_refs.c` 里 race 对的真实钉核是
`trigger → cpu0`、`main_reclaim → cpu1`；两处 `sched_setaffinity` 实测 `rc=0` 且
`actual` 与请求一致（`role=trigger requested=0 actual=0` / `role=main_reclaim requested=1 actual=1`）。
在 MT6993 实测拓扑下 **(1,0) 同属 cluster 0 ⇒ 本身就是合法同簇对**。
⇒ **FINDINGS「已知待校准旋钮」里"需实测同簇对"的疑问解除：该旋钮本来就正确，无需调整。**

**更正我自己的一个误读**：本节初版曾据探针日志里的 `cpu=7` 怀疑 target 跑在独簇。实为误读——
带 `cpu=7` 的 `identity`/`environment` 行来自**父进程**（pid 11633），钉核日志来自**子进程**
（pid 11636）；父进程从不钉核，落在哪个核都正常，它不是 race 参与者。

---

## 10. 2026-10-17 路线重判（证据级，取代 §4 的 P1/P2 可行性）

> **结论先行**：43074 与 43499 的修复都已进入设备构建的血统；两者的
> 0/256 是**根因不存在**，不是 `RUNTIME_BLOCKED`/几何问题。**64560 是唯一
> 存活的内核路线**，已构建 PD2545D 端口并准备只读 preflight。

### 10.1 三条路线的修复证据

设备内核 `6.12.58-...-gdd6fd5c98918-abogki543627742-4k`（`uname -v`
**2026-08-05**，SPL **2026-09-01**）。公开 GKI 2025-12 分支最后一轮是
`android16-6.12-2025-12_r59`（**2026-07-28**）；设备的 `gdd6fd5c98918` 是
r59 之后的私有安全 respin（r60+ tag 404，未公开）。

| CVE | 官方修复 | stable | ACK r59 实测 | 判定 |
|---|---|---|---|---|
| 43074 | `eventpoll: defer struct eventpoll free to RCU grace period` | 6.12.83 | `eventpoll.c` 有 `struct rcu_head rcu` + “defer freeing past ep_get_upwards_depth_proc() RCU walk” | **已修**（8-05 > r59） |
| 43499 | `rtmutex: Use waiter::task instead of current in remove_waiter()` | 6.12.86 | `remove_waiter()` 用 `waiter_task = waiter->task`，含 `waiter::task != current` 注释 | **已修**（8-05 > r59） |
| 64560 | `posix-cpu-timers: Prevent UAF caused by non-leader exec() race` | 6.12.100（7-30） | `posix-cpu-timers.c` **无** `timer_lock_sighand`，仍是 `pid_task → lock_task_sighand` | **大概率存活**（修复晚于 r59/构建） |

- 43074/43499 的修复在 5-6 月就进了 stable；8-05 的 abogki 构建在 r59
  （7-28）之后，按 GKI 血统应当带上。此前把 0/256 写成 `RUNTIME_BLOCKED`
  是**误判**（PD2564 8.8 的失败形态不同：它停在 known-page/collision，
  本机 known-page 是通的）。
- **残余不确定性**：私有 respin 是否完整继承 r59。`tools/pd2545_preflight.sh`
  会在设备上只读检查 tracefs 的 `timer_lock_sighand`/`ep_free` 名字；
  拿不到 tracefs 时以当前构建的 Image/BTF 为准。
- **fd_graph/carrier page-cache** 都依赖 43074，随 43074 一并关闭。

### 10.2 64560：符号已解决，后桥已换

- **符号来源陷阱**：debug all-syms（`0x36a0000`，151,293 符号）与
  certified 量产（`0x2820000`）是不同布局；all-syms 的地址已弃用（连
  `posix_cpu_timer_del` 都不同）。PD2545D 端口用 **certified Image**
  （`boot-6.12.img.kernel`，sha256 `fe3ef364…`，**128,886 符号**）的地址，
  BTF 也从该 Image 读。6.12 布局（`task_struct`：`tasks=0x638`、
  `cred=0x900`、`comm=0x910`、`thread_node=0x7b8`；`file_operations` 的
  `read_iter=0x28`/`write_iter=0x30`/`ioctl=0x50`；`cred=0xb8` 不变）。
- 一加 15T（PLZ110 / 6.12.69）设备验证移植证明 **C ashmem 在 6.12 已被
  Rust 重写，原后桥整条不可用**；它用 **uhid + configfs**（`bridge-6.12`）
  替换，已在真机拿到 root。本仓库 PD2545D 端口直接复用该桥。
- **vivo vr.ko 两层中和已内置**：PreSpawn 将 `__tracepoint_sys_exit.funcs`
  （certified `0xffffffc0826d8de0`，`funcs` 偏移 `0x48`）置 0，拆掉 sys_exit
  击杀探针；PostSpawn 先清 root child 的 `task+0x0` flags（tag A + 0x400），
  再清 `task+0x2c` tag B。
- PD2545D 产物：`pd2545-64560/build/cve-2026-64560-pd2545`，
  518,712 B 静态 aarch64，sha256 `aa13e1ea…`；profile
  `pd2545-dd6fd5c98918`；`--preflight` 只读，`--dump-kallsyms` 为只读桥模式。
- **`--run` 已可直接尝试**：`tools/pd2545_run.sh` 先做只读 preflight gate，
  再以 `FG_ROOT_EXEC` 跑 root proof 脚本；vr.ko 两层已内置，**boot.img 不是
  前置**。2026-10-17 22:25 的 `timer_goal=55962` 运行在 attempt 1 喷洒后
  设备掉线（FINDINGS §11），尚未进入 stage8；恢复后先用
  `tools/pd2545_preflight.sh` 读 `posix_timers` slab 几何与重启证据，再决定
  是否用干净基线 + 20s drain + `tools/pd2545_watch_dump.sh` 重跑。

### 10.3 行动顺序

1. `tools/pd2545_preflight.sh`（设备连上后；只读）。
2. **不要更新系统**；10/23-29 OTA 会换内核。当前构建的 boot.img 是精确
   符号源；次选是设备 `/proc/iomem` 的一次性 root 只读。
3. 精确 Image → 重跑 kallsyms/BTF/derive（一加流水线可直接复用）→ 重新
   render/build → 再讨论 `--run`。

**⚠️ 附带结论：「扫 CPU 对」这条思路已证伪，不要再做。**
理由三条：① 上游对 0/256 命中的定性是 `RUNTIME_BLOCKED`（需按构建重推 profile），
该团队从未靠调参修复同类问题；② 10-17 实跑的扫描里，除 `(0,1)` 一组完整跑完（同样全 0）外，
其余各点**在 `Kernelsnitch address phase` 即失败，根本没进到 race**——零可用数据；
③ 扫描把负载顶到 3000+，使 kernelsnitch 时序测量失效、运行被 SIGKILL，并遗留
803 个 fork 孤儿进程（已清理，设备无恙：uptime 未重置、SELinux 仍 Enforcing）。
`tools/race_sweep.sh` 因此**不是校准手段**，仅供安静环境下的单点复核；
已加 `trap` 清理保护与负载闸门，避免再次留下残留。

### 9.6 本次采集修正的三个"自洽陷阱"

1. `target.h TARGET_VERSION`（Jun 10）—— 上游残留，导致我在 §4.1 第一次判定时得出错误结论；
2. `target.h TARGET_CARRIER_SHA256`（9a046a14）—— 上游残留（size/offset/preimage 都改了，唯独 sha 漏改）；
3. 首版 `phone_preflight.sh` 的 `sh_()` 经管道丢退出码 —— 可见性检查全部假阳性。
   三者都是同一类问题：**拿"自洽的常量"当"实测的真值"**——正是 `expected_vr_tags.tsv`
   头注警告过的模式，这次在三个不同位置各中招一次。
