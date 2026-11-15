# Vector 软重启即崩（vectord 双实例互转导致栈溢出）

> 现场：PD2545D / Android 17 / 临时 root（KernelSU 内核模块） / Vector v2.2 (3080-88f8e1fa)
> 结论一句话：**软重启把 `service.sh` 重放了一遍，起出第二个 `vectord`；两个 daemon 抢同一个
> rendezvous 名 `serial`，servicemanager 把各自的 binder 交给对方，于是每个 transaction 在两者之间
> 来回转发，binder 线程栈爆掉 → SIGSEGV。第二次软重启之所以“好”，是因为上一次已经把所有旧 daemon 都崩死了。**
>
> 根因已由上游确认：`JingMatrix/Vector@5690481`（"Keep the daemon's lock where R8 cannot reach it"），
> 发行版 v2.2 还没带上。

## 0. 背景：为什么只能用软重启

临时 root = 只有内核里的 `kernelsu.ko` 在，boot 镜像没改。`ksud soft-reboot` 只停/起框架
（`stop`/`start`、重跑 post-fs-data + service.d、重启 zygote），内核与已加载的 ko 不动 → root 还在。
真重启会重新加载 stock boot → ko 没了 → root 丢。所以本机所有“重启”都必须是 `ksud soft-reboot`。

注意 `ksud` 的软重启是**模拟**的（`ksud::soft_reboot: emulating soft_reboot!`），它走的是：
`zygiskd exit` → `post-fs-data`（重跑 `/data/adb/modules/*/post-fs-data.sh`）→
`services`（重跑 `/data/adb/modules/*/service.sh`）→ 等 `sys.boot_completed`。
**servicemanager 不会被重启**（pid 849，从 11-13 开机起活到现在，uptime 2d16h）。

## 1. 现场：两台 `vectord` 同时活着

```
$ ps -A -o PID,STIME,ARGS | grep vectord      # 崩溃那一代
 3172   (上一代, 软重启前就在)
15360  14:54:29 (这一代, service.sh 重放后新起)
```

三次独立证据：

1. **两份 tombstone**，`Cmdline` 都是 `vectord`：
   - `14:54:30.574  pid 15360  tid 15494 (binder:15360_3)` ← 新代
   - `14:54:30.906  pid  3172  tid 4128  (binder:3172_7)`   ← 老代
   （见 `device/crash-buffer.txt`，两次都是 `SEGV_ACCERR`、`512 total frames`、
   `Cause: ... likely due to stack overflow`）
2. **新代拿不到 dex2oat wrapper socket**：
   `14:54:29.554 E/VectorDex2Oat Dex2oat wrapper daemon crashed / java.io.IOException: Address already in use`
   —— 抽象 socket 还在老代手里，说明老代没死。
3. **老代自己在喊 system_server 死了**：
   `14:54:24.937 W/VectorDaemon System Server died! Clearing caches and re-injecting...`
   之后一直 `` `activity` service not ready, waiting 1s... `` 到 14:54:30.962 崩溃为止。
   它根本没被软重启杀掉，只是丢了对端。

## 2. 崩溃形态：binder 线程自我递归

两份 tombstone 的帧是同一个循环（简化）：

```
#NN  dk.onTransact                                  <- Java, daemon.apk 里的类
#NN  android.os.Binder.execTransactInternal
#NN  JavaBBinderExt::onTransact                     <- vivo 改造过的 libandroid_runtime
#NN  IPCThreadState::executeCommand                 <- 收到 transaction
#NN  IPCThreadState::transact                       <- 立刻又发 transaction
#NN  BpBinder::transact
#NN  android_os_BinderProxy_transact
#NN  android.os.BinderProxy.transact                <- Java 侧是“远端”
#NN  dk.onTransact                                  <- 又回到自己
```

`dk` 是谁，可以直接从 `daemon.apk` 反编译确认（`device/apk/smali/dk.smali`）：

```smali
.class public final Ldk;
.super Landroid/os/Binder;
.implements Landroid/os/IBinder$DeathRecipient;

.field public static final a:Ldk;          # 单例 = SystemServerService
.field public static b:Landroid/os/IBinder;   # originService
.field public static c:Z                      # systemServerRequested
```

```smali
.method public final onTransact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
    sget-object v0, Ldk;->b:Landroid/os/IBinder;
    if-eqz v0, :cond_c
    invoke-interface {v0, p1, p2, p3, p4}, Landroid/os/IBinder;->transact(...)Z   # 转发
    return p0
```

即 `SystemServerService.onTransact` 的第一件事就是“**把收到的请求原样转发给 originService**”。
`originService` 一旦指回自己/同伴，一次 transaction 就变成无限递归，每层吃掉一帧栈，
`JavaBBinderExt::onTransact` 里那句 `__android_log_print` 只是恰好压在最后一页栈上的那帧。

> 注意：stderr 里“同一 tid 递归”不是矛盾——`IPCThreadState::waitForResponse` 收到嵌套
> `BR_TRANSACTION` 时会就地 `executeCommand`，所以“等回包”的线程可以再被叫进去处理新请求。
> 两个进程各自的栈同时长，谁先摸到守卫页谁先死，所以两次崩溃只差 0.33s。

## 3. 为什么会出现两个 daemon：单实例锁形同不存在

`VectorDaemon.main` 第一行是 `if (!FileSystem.tryLock()) exitProcess(0)`，本意是“只许一个 daemon”。
实测这个锁**根本没生效**：

```
$ su -c 'cat /proc/locks'            # 没有 /data/adb/lspd/lock (inode 2412714) 的任何记录
$ ls -l /proc/7132/fd | grep lspd/lock   # 当前健康 daemon 的 fd 表里也没有 lock fd
```

原因在 dex 里看得一清二楚（`apk/smali/org/matrix/vector/daemon/VectorDaemon.smali`，tryLock 被内联）：

```smali
invoke-static {v6, v7, v8}, FileChannel;->open(...)Ljava/nio/channels/FileChannel;
move-result-object v5
if-eqz v5, :cond_35
invoke-virtual {v5}, FileChannel;->tryLock()Ljava/nio/channels/FileLock;
move-result-object v5          # ← channel 在这里被丢弃
...
invoke-virtual {v5}, FileLock;->isValid()Z   # ← FileLock 也只读一次就丢弃
```

源码里本来是 `private var lockChannel` / `private var fileLock` 两个字段持有它们，但 **R8 发现
这两个字段再没人读过，把字段和赋值一起删了** → channel 立刻不可达 → 它的 finalizer/cleaner
关掉 fd → **关掉该文件的任一 fd 会释放本进程在该文件上的所有 POSIX 锁** →
release 版 daemon 全程“裸奔”，debug 版反而正常。

于是软重启重放 `service.sh` → `unshare -m daemon --system-server-max-retry=3` 照常起第二个 daemon，
`tryLock` 返回 true，一个设备上就有了两代 daemon。

（上游原文，`5690481`：*"tryLock kept the channel and the lock in two private fields nothing read
again, and R8 removes a field nothing reads: the channel is then unreachable, its finalizer closes
the descriptor, and closing any descriptor drops every POSIX lock the process holds on that file.
... Unlocked, a replayed service.sh starts a second daemon. Both claim the same rendezvous name,
servicemanager hands each the other's binder, neither can tell a sibling from the real service, and
the pair forwards one transaction back and forth until a binder thread's stack gives out. A KernelSU
soft reboot is one such replay."*）

## 4. 为什么会互指：`serial` 这个 rendezvous 名

- 名字来源：`zygisk/src/main/cpp/module.cpp:418` → `bridgeServiceName = "serial"`；
  daemon 侧 `VectorDaemon.proxyServiceName = "serial"`。**`serial` 同时是 system_server 真实的
  `SerialService` 注册名**，Vector 故意先占坑，等真的来注册再让位/接管。
- 3080 的 `registerProxyService()` 顺序是：先 `registerForNotifications("serial", cb)`，
  再 `ServiceManager.addService("serial", SystemServerService)`。
  - 先注册回调：servicemanager 会**立刻**把“当前已登记的那个 binder”回调给你 → 第二个 daemon 拿到老 daemon；
  - 再 addService（覆盖）：servicemanager 又给该名字的所有回调发一次注册通知 → 老 daemon 拿到新 daemon。
- 两边的 `onRegistration` 都只挡了“同进程同一个 Java 对象”（`binder !== this`），
  挡不住“隔壁那台 daemon”。于是 `originService` 互相指向。
- servicemanager 跨软重启存活、注册表不清空，是这件事能成立的前提。

此刻的对照（健康态）：`service list | grep serial` → `serial: [android.hardware.serial.ISerialManager]`，
即名字已经交还给真的 SerialService，daemon 在把流量转发给它。

## 5. 为什么“再软重启一次就好”

第一次软重启：2 个 daemon 全崩（两份 tombstone）。
第二次软重启：机器上已经没有任何旧 daemon，`service.sh` 只起一个 → 握手正常 → Vector 恢复。
所以现象天然是“坏一次、好一次”，与用户描述完全一致。

## 6. 次生危害：v2.2 会自己重启 system_server

3080 的 `sendToBridge()` 3 次握手失败后调用 `restartSystemServer()`
（`SystemProperties.set("ctl.restart", "zygote_secondary")`）。也就是说握手一旦失败，
Vector 会再触发一次框架重启，从而**再造一个孤儿 daemon**，把事情放大。
上游 `eaffc81` 已删除该行为，理由写得很直白：每次 system_server 启动都喂给 PackageWatchdog
一个只增不减的 mitigation 计数，走到底不是重启而是进 recovery 问要不要清数据。

## 7. 处置建议（都不需要真重启）

1. **首选：换 canary-3118**（`Vector-v2.2-3118-Release.zip`，built from `91e77e4`）。
   它含 `5690481`（锁修好）+ `eaffc81`（不再自动重启 system_server、rendezvous 名统一）。
   注意 manager 与 daemon 必须同版本（parasitic manager 无需额外处理）。
   风险点：这是 canary；换之前把现有 `daemon.apk`/`manager.apk` 备份到 `/data/adb/…` 外的目录，
   出错时用一次软重启 + 原 APK 回滚。
2. **保守：什么都不动，接受“崩一次、再软重启一次”**。每次软重启后自检：
   - `ps -A -o PID,STIME,ARGS | grep vectord` → 必须只有 1 个
   - `su -c 'cat /proc/locks' | grep -c 2412714`（lock inode）→ 修好的版本应 ≥1；现在是 0
   - `logcat -b crash -d | grep -c "Cmdline: vectord"` → 出现 2 条就是本条 bug
3. **自建补丁**（不改上游、不换版本）：给 `daemon.apk` 的 dex 打小补丁，把 `FileChannel`/`FileLock`
   存进**静态字段**（R8 已经跑完了，静态强引用就足以防 finalizer，不需要有人再读）。
   失败的判据同样简单：软重启后 `/proc/locks` 里应出现 `POSIX ADVISORY WRITE <pid> ... 2412714`。
   注意：**这项操作本身要改模块文件，务必先备份，且做完只能用软重启验证。**
4. **别做的**：
   - 连着快速软重启、或在“daemon 已死”的半死状态下反复折腾（每轮都会再造一次孤儿进程）
   - 硬重启（掉 root）
   - 手动 kill `vectord`（会立刻进入无 daemon 状态，且不解决锁的问题）

> **2026-11-15 后记**：第 1 条“换 canary-3118”当天被实测证伪了一半——在运行中的
> 系统上**就地**换 3118 引发混代崩溃，且 3118 把 `modules_config.db` v4 迁 v5 时
> 清空了 modules 表。此外本文的崩溃当天 14:54 实际复发为**三**个 daemon
> （3172/15360/15636）连环栈溢出。完整时间线、诊断判据与无硬重启恢复 runbook 见
> [`../VECTOR-KSU.md` §6.7](../VECTOR-KSU.md)。本文其余机制分析不受影响。

## 8. 未决 / 待验证

- 崩溃的**触发者**推断是新 system_server 在 specialization 阶段的 zygisk 握手
  （15360 起于 14:54:29.542，崩于 14:54:30.574）。日志里没有 module 侧的
  `Attempting injection into system_server.`，因为本轮 logcat 已被后续刷掉。
  若要坐实：下次软重启前先 `logcat -b all -v time > /sdcard/vec_before_reboot.txt &` 再复现。
- 第二个 tombstone（老代 3172）在同伴死后的 0.33s 仍在涨栈，说明它自己也在互指环里；
  与“互指”一致，但与“单侧自指”不符——若将来能拿到两端同一时刻的 `/proc/<pid>/stack`
  可以进一步分辨。

## 9. 证据索引

```
device/crash-buffer.txt        两份 tombstone 原文（logcat -b crash）
device/old_verbose.log        崩溃那一代 daemon 的 verbose 日志（log.old，14:54 代）
device/new_verbose.log        当前（健康）这一代 daemon 的日志
device/kmsg_old.log           崩溃前一分钟的 kmsg（含 KernelSU/servicemanager 行）
device/module_daemon.sh       /data/adb/modules/zygisk_vector/daemon
device/module_service.sh      .../service.sh（unshare -m daemon --system-server-max-retry=3）
device/znctx.txt / abort_msg.txt   Zygisk Next 的上下文与退出原因
device/daemon.apk             (gitignore) 出事的 daemon.apk，用于反编译复核
device/apk/smali/dk.smali     SystemServerService 反编译，onTransact 第一行就是转发
device/apk/smali/.../VectorDaemon.smali  tryLock 内联现场（第 540-700 行）
```

上游对照：

- `JingMatrix/Vector@5690481` Keep the daemon's lock where R8 cannot reach it (#1049) — 2026-10-30
- `JingMatrix/Vector@eaffc81` Hold one rendezvous name through a late load and a restart (#1034)
- `JingMatrix/Vector@3eae64f` Isolate the IServiceCallback registration behind its own class (#926)
- 发行版 v2.2 = `88f8e1fa`（含 bug）；canary-3116 之后含修复
