# 本机 pselect / futex 栈几何（X Fold3 Pro 6.1.145）

从 `kernel_image`（boot.img 内裸 Image，基址 `0xffffffc008000000`）反汇编得到。
结论：`futex_wait_requeue_pi` 的 `rt_mutex_waiter` 在 `SP_top-0x2d8`，
`pselect6` 的 `stack_fds` 在 `SP_top-0x200`，即 **waiter 比 fd_set 低 0xd8 字节
（−27 个 8 字节字，=−216 字节）**。这与官方 extractor 的
`pselect overlap -216` / `pselect route not feasible` 完全一致。

## pselect6 路径

```
__arm64_sys_pselect6 @ 0xffffffc0084318c0
  sub sp, sp, #0x90          ; SP = SP_top-0x90
  bl  core_sys_select @ 0xffffffc008430af8

core_sys_select
  sub sp, sp, #0x1c0         ; SP = SP_top-0x250
  add x23, sp, #0x50         ; stack_fds = SP_top-0x200
```

nfds=320 → `size = FDS_BYTES(320) = 40`（0x28）≤ 0x2b，走栈内 `stack_fds`。
in/out/ex 分别在 stack_fds+0、+0x28、+0x50，共 15 个 8 字节字。

## futex WAIT_REQUEUE_PI 路径

```
__arm64_sys_futex
  sub sp, sp, #0x70          ; SP = SP_top-0x70
  bl  do_futex

do_futex @ 0xffffffc0081b03b4
  sub sp, sp, #0xd0          ; SP = SP_top-0x140
  bl  futex_wait_requeue_pi @ 0xffffffc0081b3670（调用点 0x1b0678）

futex_wait_requeue_pi
  sub sp, sp, #0x1c0         ; SP = SP_top-0x300
  add x29, sp, #0x160
```

函数内局部布局（由反汇编确认）：

| 局部对象 | 地址 | 依据 |
|---|---|---|
| `struct rt_mutex_waiter rt_waiter` | `sp+0x28` | `RB_CLEAR_NODE` 自指针写：`str x27,[sp,#0x28]`（tree_entry）、`str x9,[sp,#0x40]`（pi_tree_entry）；`str xzr,[sp,#0x58]`（task=NULL） |
| `struct hrtimer_sleeper timeout` | `sp+0x80` | `add x1, sp, #0x80` 传给 `futex_setup_timer` |
| `struct futex_q q` | `sp+0xd0`（=`x29-0x90`） | 从 `futex_q_init` 模板拷贝 0x80 字节；`futex_wait_setup(..., x29-0x90, ...)` |

因此：

```
rt_waiter  = SP_top-0x300+0x28 = SP_top-0x2d8
fd_set     = SP_top-0x200
shift      = (waiter - fd_set)/8 = -0xd8/8 = -27
```

## 为什么 pselect/fake-lock 路线不可行

exploit 通过 `pselect` 的 fd_set 字（global word = shift + waiter_word）去伪造
`struct rt_mutex_waiter` 的字段。要写齐 `tree_entry`(0x00)、`pi_tree_entry`(0x18)、
`task`(0x30)、`lock`(0x38)、`wake_state`(0x40) 等，需要 shift ≥ 0。
本构建 shift = **-27**，`tree_entry`/`pi_tree_entry` 整段落在 fd_set 覆盖范围之外
（fd_set 只能从 waiter+0xd8 开始写），无法伪造完整 waiter。

- KSuRoot 的 `libbaseline_6_1.so`（tokay/Pixel 9 的 pselect 栈布局）在本机运行日志：
  `label=p9_cp2a_260605_012_truephone slide=pselect main=pselect`，
  `KernelSnitch mm_struct leak failed` → panic。布局完全不同，属预期失败。
- pd2324 参考源码的 fake-lock 路线：`PSELECT_WAITER_WORD_SHIFT=0/1` 实机均在
  consumer `sched_setattr` 后立刻 panic（安全失败），与 -27 的推导一致。
- 官方 extractor 对本镜像输出 `pselect route not feasible`，route 选 `tcp_zerocopy`；
  alfxki `extract-pselect-fallback-plan.md` 记录 6.1 家族同样降级为 tcp。

## 对照

PD2324（MT6989）的 fake-lock 路线在它的构建上可用，说明该几何是**逐构建**的，
不能跨 SoC/工具链平移。本机唯一可行路线是 `tcp_zerocopy`（见 ATTEMPTS 文档）。
