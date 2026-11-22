# Vector / KernelSU / 临时 root 全栈笔记

日期：2026-11-16  
适用：PD2545D（vivo X Fold6 / MT6993 / Android 17 / OriginOS 7）  
目的：把「exploit → KernelSU → Zygisk Next → Vector」这条链讲清楚，避免再把
Vector 当 root、把 `ksud soft-reboot` 当真重启、把「模块装了」当「hook 生效」。

---

## 0. 先纠偏四个词

| 词 | 是什么 | 不是什么 |
|---|---|---|
| **KernelSU (KSU)** | root 提供者：内核模块 `kernelsu.ko` + 用户态 `ksud` + 管理器 APK | 不是 Xposed 框架，不负责 hook |
| **Vector** | ART hook 框架（LSPosed 分支，JingMatrix）：Zygisk 模块 + root daemon + 管理器 | **不是 root 管理器**；它自己也要靠 KSU/Magisk 给 root |
| **临时 root** | 用内核漏洞把 `kernelsu.ko` `insmod` 进**正在运行的内核**，boot 分区没改 | 不是「永久 root」；硬重启后 ko 消失 |
| **Zygisk Next** | KSU 上的 Zygisk 实现（`zygisksu` 模块） | 不是 Vector 的一部分；Vector 依赖它注入 zygote |

一句话链：

```
CVE-2026-64560 → uid 0 + 内核读写
  → 中和 vivo vr.ko 的 load_module / sys_exit 探针
  → insmod 打过 slide/符号补丁的 kernelsu.ko
  → ksud install（/data/adb/ksu、su、管理器）
  → ksud 安装/启用 zygisksu（Zygisk Next）
  → ksud 安装/启用 zygisk_vector（Vector）
  → Vector daemon + system_server 里的 zygisk 模块 + 各目标进程里的 libxposed 模块
```

Vector 的模块本体在 `/data/adb/modules/zygisk_vector`，运行时配置/日志/DB 在
`/data/adb/lspd`（沿用 LSPosed 的 daemon 路径）；KSU 的用户态文件在
`/data/adb/ksu`，两者不要混。

---

## 1. 本机实际加载顺序（含证据）

### 1.1 exploit 阶段

- 入口是 shell（uid 2000），不是 root。`root/kiroot-apk-analysis.md` §2 记录了
  kiroot 通过 Shizuku 拿 shell，再跑 `poc_tmp_root`。
- exploit 成功后拿到 uid 0，并具备内核读/写原语（kprobe / kread / kwrite 通道）。
  `_tmp_dev/x300root.sh` 里的 `kread()` / `kwrite()` 就是这条通道。
- 本机主线是 **CVE-2026-64560**（POSIX timer UAF）；43074/43499 的根因已在
  6.12.83/6.12.86 修掉，见 `root/FINDINGS.md` §9。

### 1.2 insmod KernelSU

本机不是「刷了 KSU 的 boot.img」，而是把 KSU 当**可加载内核模块（LKM）**加载：

1. 从已装管理器里取 ko：
   `ksud debug extract-binary android16-6.12_kernelsu.ko /data/local/tmp/kernelsu.ko`
   （或直接用管理器的 `libksud.so` / `assets/kernelsu.ko`）。
2. `ksu_patch_dyn` 按设备 `kallsyms` 和 slide 给 ko 打补丁。
3. vivo `vr.ko` 会在 `load_module` 上挂 kprobe、在 `sys_exit` tracepoint 上挂
   击杀探针；`x300root.sh` 先把这些 target 的 pre_handler / tracepoint funcs
   清零，再 `insmod`。
4. `ksud install` 把 `su`、`ksud`、`busybox` 等铺到 `/data/adb/ksu/bin`，
   并安装管理器。

关键证据：

- `_tmp_dev/out.txt`：`INSMOD .../kernelsu-patched.ko ret=0 ... LSMOD kernelsu ...`
- `_tmp_dev/su_out.txt`：`uid=0(root) ... context=u:r:ksu:s0`
- `root/pd2545-64560/README.md` 的 “vr.ko neutralization (built in)” 一节：
  `__tracepoint_sys_exit.funcs` 置 0、清 `thread_info` flags / tag B。
- `root/kiroot-apk-analysis.md` §4：`POC_INSTALL_KSU=1` 内部 patch+insmod；
  §6 对比了 kiroot 与 Rift/pyb110 两条包装方式。

### 1.3 KSU 之后

- KSU 的模块仍然是 Magisk 风格的 `/data/adb/modules/<id>/`：
  `service.sh`、`post-fs-data.sh`、`module.prop`、`disable`、`remove`。
- 本机实际模块（`root/vector-softreboot/device/new_verbose.log`）：
  `zygisk_vector`、`zygisksu`、`zygisk_shamiko`。
- `ksud module list` 会逐个打印这些路径。

---

## 2. 临时 root 的边界：为什么只能软重启

### 2.1 硬重启

- boot 分区没改，重启后内核里没有 `kernelsu.ko`；
- `/data/adb` 里的模块、管理器、配置**还在**；
- 需要重新跑 exploit + patch + insmod，root 才回来。

### 2.2 `ksud soft-reboot` 到底做了什么

`libksud.so` 里的字符串已经写死：

```
, skip soft_reboot
emulating soft_reboot!
emulated-soft-reboot
stop failed
post-fs-data
start failed
...
wait sys.boot_completed failed
```

所以它是**模拟**软重启，不是 `reboot`：

1. `stop`（停框架）
2. 重跑 `/data/adb/modules/*/post-fs-data.sh` 与 `post-fs-data.d`
3. `start`
4. 等 `sys.boot_completed`
5. 期间 **init 不重启、内核模块不卸载、servicemanager 不清空**

这条路径对 Vector 有两个直接后果：

- `service.sh` 会被**重放**：如果 daemon 的单实例锁失效，就会起第二个 `vectord`；
- servicemanager 的注册表跨软重启保留：旧 daemon 的 `serial` 注册还在，
  新 daemon 会和它互相指。

`root/vector-softreboot/FINDINGS.md` §0–§5 就是这次事故的完整取证。

### 2.3 Vector 自己的 `softReboot()`

Vector daemon 里的 `softReboot()` 做的是：

```kotlin
SystemProperties.set("ctl.restart", "zygote")
```

即重启**主 zygote**，不是 `ksud soft-reboot`，也不负责保留 KSU ko。
两者不要混用：

| 操作 | 内核 ko | servicemanager | 谁重放 service.sh |
|---|---|---|---|
| 硬重启 | 丢 | 新进程 | init |
| `ksud soft-reboot` | 保留 | **保留** | ksud 重放 |
| Vector `softReboot()` | 保留 | 保留（zygote 重启） | 不重放 |

---

## 3. KernelSU 侧的关键机制

### 3.1 KMI 与 LKM

- 本机 `uname -r` 是 `6.12.58-android16-...`，KMI 取 `android16-6.12`。
- KSU 的 ko 必须按 KMI 构建；官方管理器里通常带多个 KMI 的 ko，
  `ksud debug extract-binary <kmi>_kernelsu.ko` 取对应那个。
- 本机是私有 respin（`dd6fd5c98918`），公开 GKI 的符号只能当基线，
  所以需要 `ksu_patch_dyn` 按设备 `kallsyms` + slide 重定位。

### 3.2 `ksud` 的发现

不同 KSU 分支的管理器包名/库名不同，`x300root.sh` 的做法是：

1. 依次尝试 `me.weishu.kernelsu`、`com.sukisu.ultra`、`com.resukisu.resukisu`、
   `org.bakasu.bakasu`、`com.rifsxd.ksunext` 等；
2. 从 `pm path <pkg>` 的 APK 目录下扫 `lib/arm64/*.so`；
3. 对每个 `.so` 跑 `--version`，输出里带 `ksud` 的就是 `ksud`。

### 3.3 vivo `vr.ko` 的两层对抗

`root/ROUTES.md` §2 把机制拆得很细：

- **打标**：app-origin task 的 `task_struct+0x06`（tag A，落在 flags 字内）、
  `+0x2c`（tag B）；
- **探针**：`sys_exit` tracepoint 上挂击杀任何「达到 euid 0」的任务；
- **加载拦截**：`load_module` 上的 kprobe。

上游 6.12 的做法是**数据段中和**：

- `__tracepoint_sys_exit.funcs = 0`（certified r59 地址 `0xffffffc0826d8de0`，
  `funcs` offset `0x48`）；
- 清 root 子进程的 `thread_info` flags 字（先清 0x400 位，脱离 sys_exit 慢路径），
  再清 tag B。

`x300root.sh` 则是遍历 kprobe 的内部节点，找到挂在 `load_module` 上的
`pre_handler` 并清零，然后再重新 `insmod`。两条路都要求「先有内核读写」。

### 3.4 什么时候会失败

- ko 的 vermagic 与运行内核不一致 → `insmod` 直接拒绝；
- 符号/slide 不对 → `insmod` 成功但一用就崩；
- `vr.ko` 的 `load_module` kprobe 没清 → 加载被拦，或加载后触发重启；
- 上一次 exploit 留下 park 的 `t28k` / 半死进程 → 需要干净开机再试
  （`_tmp_dev/worker.sh` 的闸门 0/1/2 就是为此）。

---

## 4. Vector 的组成与运行路径

### 4.1 三个部件

| 部件 | 位置 | 作用 |
|---|---|---|
| Zygisk 模块 | `/data/adb/modules/zygisk_vector`（`zygisk/`、`service.sh`、`daemon.apk`、`manager.apk`、`cli`） | 被 Zygisk Next 注入 zygote/system_server；提供 native binder trap |
| daemon (`vectord`) | `app_process ... org.matrix.vector.daemon.VectorDaemon` | root 侧：模块 DB、scope、dex、日志、dex2oat wrapper |
| manager | `manager.apk`（寄生在 `com.android.shell`，或独立安装） | UI：模块开关、scope、日志、更新 |

`service.sh` 的核心是：

```sh
unshare -m /system/bin/sh -c 'mount -o rslave none /; exec "$0" "$@"' \
  "$MODDIR/daemon" --system-server-max-retry=3
```

`daemon` 脚本再用 `app_process` 起 `vectord`。所以 `vectord` 是 root 进程，
不是普通 App。

### 4.2 rendezvous：`serial` / `serial_vector`

Vector 不注册固定的 AIDL 服务名；它借用真实服务名做会合点，先占坑，等真的
`system_server` 服务来注册时再让位/接管：

- 开机时 `system_server` 还没注册 `serial`，Vector daemon 先占 `serial`；
- 如果 Zygisk 是在系统已经启动后才注入（`ksud late-load`、手动重载），
  真实 `SerialService` 已占 `serial`，daemon 改用 `serial_vector`；
- 判断依据：`activity` 服务是否已经存在（`VectorDaemon.kt` 的
  `startedAfterSystemServer`）。

`zygisk/README.md` 的 “Choosing the rendezvous name” 和 “When system_server
restarts” 两节是权威说明。软重启/zygote 重启时 servicemanager 不清空，
旧 daemon 的注册还在，这正是 3080 双实例互指的土壤。

### 4.3 模块注入流程（应用进程）

1. 应用 fork 后，Zygisk 在 `postAppSpecialize` 向 `activity` 发 `_VEC` 事务；
2. `system_server` 里的 JNI trap 拦下，把 UID/PID/进程名/heartbeat binder
   转给 daemon；
3. daemon 按 `ConfigCache` 判断该进程是否在某个启用模块的 scope 里；
4. 在 scope 里 → 返回 `IFrameworkService`，应用拿到自己的 dex + 混淆表；
5. libxposed 模块进程还会被 daemon 主动推 `IXposedService`（`SEND_BINDER`）。

所以「模块装了」≠「模块被注入」：还要 **模块 enabled + 目标在 scope +
目标进程重新启动**。

---

## 5. Vector 的模块加载规则（以源码为准）

源码：`root/.upstream/Vector-88f8e1fa/daemon/src/main/kotlin/org/matrix/vector/daemon/data/FileSystem.kt`
（v2.2 / 3080），以及 `root/.upstream/vector/...`（canary 3118 / 91e77e4）。

### 5.1 四种策略

`loadModule()` 先读 `META-INF/xposed/module.prop`（Java Properties），
取 `targetApiVersion`，再判断：

| 条件 | 策略 | 读哪个入口 |
|---|---|---|
| `targetApi >= 101` | `MODERN` | `META-INF/xposed/java_init.list`（+ `native_init.list`） |
| 否则有 `assets/xposed_init` | `LEGACY` | `assets/xposed_init`（+ `assets/native_init`） |
| 否则 `targetApi == 100` | `UNSUPPORTED` | 直接拒绝（API 100 已不支持） |
| 否则 | `NONE` | `ModuleLoad.Unusable` |

源码注释写得很清楚：**Priority: API 101+ > Legacy > API 100**。

关键结论：

- **同时带 `assets/xposed_init` 和 `META-INF/xposed/java_init.list` 不会变
  NONE**；只要 `targetApiVersion >= 101`，就是 MODERN。`OriginTweaks/build.sh`
  里「both entries → NONE」的说法是错的。
- 真正会变 NONE 的是：既没有 modern 入口，也没有 legacy 入口，且
  `targetApiVersion` 不是 100/101+。
- 即使 `targetApiVersion >= 101`，如果 `java_init.list` 为空/缺失，
  `moduleClassNames` 为空 → `Unusable`。

### 5.2 manager 的检测规则

`root/.upstream/Vector-88f8e1fa/manager/.../ModuleDetection.kt`：

- 先看 APK 里有没有 `META-INF/xposed/java_init.list`；有 → modern；
- 没有 modern，再看 manifest 的 `xposedminversion`；有 → legacy；
- 两者都没有 → 不是模块。

也就是说：

- 纯 classic 模块（只有 `assets/xposed_init`）**必须**在 manifest 里写
  `xposedminversion`，否则管理器连模块列表都不会显示；
- modern 模块的 `xposedminversion` 只是历史元数据，manager/daemon 都不拿它
  决定加载策略。写成 93 不会导致加载失败，但会误导人。

### 5.3 `staticScope` 到底管什么

`ModuleDetection.kt` 的注释：

> `staticScope` means the module fixes **which apps may be listed**, not which
> of them are hooked. `packages` is the outer limit and the user cannot widen
> it, but which of those packages are in the scope remains theirs to choose.

所以：

- `staticScope=true` + `scope.list` 只是把管理器的可选 App 列表**收窄**到
  这几个包；**不会自动勾选/自动生效**；
- 用户仍然要在 scope 页面把目标打勾；
- `staticScope=true` 但 `scope.list` 为空 → 被当成打包错误，**忽略**
  staticScope，列表重新放开（`FileSystem.readStaticScope` 与
  `ModuleDetection.inspect` 都这么处理）；
- 目标 `system` 是特例：它映射到 `system_server`，按 user 0 存；
  任何持有该模块的用户都可以勾。

### 5.4 模块生效的最小条件

1. APK 被管理器识别为模块（modern 或 legacy）；
2. 模块 `enabled=1`；
3. 目标包在 `scope` 表里（且目标用户持有该模块）；
4. 目标进程在 scope 应用后**重新启动**（或软重启）；
5. 模块入口类能被 daemon 加载（`java_init.list` 里的类在 `classes.dex` 里，
   构造函数/父类正确）。

第 5 条失败时，`/data/adb/lspd/log/modules_*.log` 会有 `Could not load ...`
或 `VectorModuleManager` 的异常；第 3/4 条失败时，模块日志里连
`onPackageLoaded` 都不会出现。

---

## 6. 3080 软重启崩溃：根因与处置

`root/vector-softreboot/FINDINGS.md` 的结论：

1. R8 把 `FileChannel`/`FileLock` 两个 private 字段删了（没人再读），
   channel 被 finalizer 关闭 → POSIX 锁释放 → `tryLock()` 形同虚设；
2. `ksud soft-reboot` 重放 `service.sh` → 起第二个 `vectord`；
3. 两个 daemon 都通过 `serial` 注册回调，servicemanager 把各自的 binder
   交给对方；
4. `SystemServerService.onTransact` 把收到的 transaction 原样转发给
   `originService`，两边互指 → binder 线程栈溢出 → SIGSEGV；
5. 第二次软重启之所以“好”，是因为上一次已经把旧 daemon 全崩死了。

修复：

- `JingMatrix/Vector@5690481`：把 lock 放进 shutdown hook，R8 删不掉；
- `JingMatrix/Vector@eaffc81`：握手失败不再自动
  `ctl.restart zygote_secondary`，并统一 rendezvous 名；
- 发行版 **v2.2 (3080-88f8e1fa)** 含 bug；**canary 3118 (91e77e4)** 含修复。

本机已有 `root/.upstream/v3118/` 与 `Vector-3118-Release.zip`。
**⚠ 2026-11-15 更新：不要再就地升级**——当天把运行中的 3080 就地换成 3118 引发了
连锁灾难（混代崩溃 + DB 被清），详见 §6.7。换版本的合法时机只有"可以立即完成一次
完整开机初始化"的时候；manager 与 daemon 必须同版本。

自检：

```sh
ps -A -o PID,STIME,ARGS | grep vectord          # 必须只有 1 个
su -c 'cat /proc/locks' | grep -c <lock-inode>  # 修好的版本应 >=1
logcat -b crash -d | grep -c 'Cmdline: vectord' # 出现 2 条就是本条 bug
```

---

## 6.5 常见日志词："daemon 有问题" / "映射问题" 到底指什么

先把词对上，不然会一直鸡同鸭讲。

| 日志原文 | 真实含义 | 严重性 / 处置 |
|---|---|---|
| `Cause: stack pointer is not in a rw map; likely due to stack overflow.` | **栈溢出**。栈指针已经越过 stack 的 guard page，不在可读写映射里；不是 mmap 失败 | 3080 双 daemon 互指的直接后果；换 3118，见 §6 |
| `Dex2oat wrapper daemon crashed: java.io.IOException: Address already in use` | 旧 `vectord` 还占着抽象 socket，新 daemon 绑不上 | 同一台机器上有两个 daemon；查 `ps -A | grep vectord` |
| `Caught fatal coroutine exception in background task! ... Context.getAttributionSource() on a null object reference` | daemon 用 `FakeContext` 建通知时 base context 为空；协程抛出，但 daemon 主路径通常还活着 | 3080 的日志噪音/通知路径 bug；不是 root 或模块加载失败的直接原因 |
| `Skipping system_server injection: sepolicy execmem denied` | SELinux 不允许 `system_server` 申请可执行内存（`process execmem`），LSPlant 无法在 system_server 里挂 hook | **真正的映射问题**；`system` scope 的 hook 全部不会加载。需要给 system_server 放行 `execmem`，或放弃 system scope |
| `Failed to map dex` / `mmap failed` / `SharedMemory` 相关异常 | 模块/framework dex 映射失败 | 看同一个 verbose log 的上一行；通常是 fd、SELinux 或共享内存被关闭 |
| `AconfigPackage ... is mapped to ...` | Android 的 aconfig 包名映射，正常日志 | 忽略 |
| `Priority-to-niceness mapping: ...` | 线程优先级映射，正常日志 | 忽略 |
| `Mapping modules and scopes`（`VectorConfigCache`） | 重建“模块 → scope”配置缓存，正常日志 | 忽略 |
| `VectorModuleManager: Loading module ... / Loaded module ... successfully` | 管理器的**模块表**（module map）加载成功 | 这是“模块 map”，不是内存映射；没有这条才是模块检测问题 |
| `System Server died! Clearing caches and re-injecting...` | `system_server` 重启了，daemon 在等 `activity` 重新出现 | 可能是软重启/zygote 重启的正常过渡；若循环出现，查是否 daemon 自己在重启 system_server（3080 的已知行为） |
| `Got system server binder via serial on attempt 1` | rendezvous 成功，daemon 拿到了 system_server 的 binder | 正常 |
| `service list | grep -E 'serial|activity'` 里 `serial` 指向真实 `SerialService` | rendezvous 名已经交还给系统服务 | 正常；如果指向一个陌生 binder，查 daemon 是否双实例 |

一句话：**“stack pointer is not in a rw map”不是“映射坏了”，是“栈爆了”；
“module map”不是“内存映射”；真正的映射问题通常是 `execmem denied` 或
`Failed to map dex`。**

如果你能拿到对方那句话的**上一行/下一行日志**，基本就能定位到上表里的哪一行。

---

## 6.6 为什么“模块重装后 daemon 不认”（appId 缓存过期）

先把两张表分开：

- **进程 → 模块**：`ConfigCache.state.scopes: Map<ProcessScope, List<LoadedModule>>`；
- **模块身份**：`LoadedModule.appId = uid % 100000`（`ConfigCache.kt:463-464`）。

模块重装后 UID 会变，`appId` 也跟着变。注入模块进程时：

```text
ModuleAppService.uidStarts(newUid)
  → ConfigCache.getModuleByUid(newUid)
     = state.modules.values.firstOrNull { it.appId == newUid % 100000 }
  → 找不到 → 直接 return，不推 IXposedService
```

模块进程拿不到 `IXposedService`，就不会有 `libxposed module loaded`，
看起来就是“模块被丢掉了”。`ModuleAppService.ensureModule()` 还会用
`loadedModule.appId != Binder.getCallingUid() % 100000` 再拒绝一次。

### 表为什么不更新

1. `VectorService.dispatchPackageChanged()` 里调
   `ModuleDatabase.updateModuleApkPath(pkg, newPath, force=false)`；
   它只在 **`cached.apkPath != newPath`** 时写 DB 并
   `requestCacheUpdate()`（`ModuleDatabase.kt:354-379`）。
   **路径没变（in-place 重装）→ `count=0`，不请求 rebuild。**
2. 即使别的原因触发了 rebuild，`performCacheUpdate()` 的 fast path 是：

   ```kotlin
   if (oldModule != null && ... apkPath == oldModule.apkPath &&
       File(appInfo.sourceDir).parent == File(apkPath).parent) {
     if (oldModule.appId == -1) { ... }   // 只在 -1 时补
     newModules[pkgName] = oldModule      // 直接复用旧对象
     return@forEach
   }
   ```

   它**不刷新已经变化的 `appId`**，也不比较 `versionCode`/mtime。
   → 新 UID 永远进不了表。
3. 如果包事件里 `getModuleApkPath(appInfo)` 返回 null，
   `updateModuleApkPath()` 直接 `return false`，同样不请求 rebuild；
   `isXposedModule` 变 false，而模块自己通常不是别人的 scope target，
   所以 else 分支也不会请求。
4. 包事件没收到（daemon 没起、软重启时序、广播被漏）→ 根本没有 rebuild。

所以老模块（Ding / MapAdKiller / top.haif）的 UID 和路径都没变，
表里的 `appId` 还匹配，照常注入；新装/重装的模块换了 UID，就卡在
`getModuleByUid()` 这一步。

> 严格说，“旧缓存路径 ≠ DB 新路径 → 丢弃”并不准确：路径不等反而会
> 触发重解析。真正不刷新的是 **appId（UID）**、**same-path fast path**，
> 以及**漏掉的 rebuild 请求**。但表现出来的“旧表不认新模块”是一样的。

### 怎么确认

```sh
adb logcat -s VectorConfigCache VectorDaemon VectorModuleManager | \
  grep -E "dispatchPackageChanged|Executing Cache Update|Map Swap successful|Failed to find path|Could not load"
```

- 重装后没有 `Executing Cache Update...` / `Map Swap successful` → 根本没 rebuild；
- 有 rebuild 但紧接着 `Failed to find path of <pkg>` 或
  `Could not load <pkg>; skipping.` → 新 APK 没解析成功；
- 有 rebuild、没有报错，但模块进程仍无 `libxposed module loaded` →
  多半是 fast path 复用了旧 `appId`，或新进程 UID 与表里的 `appId` 不一致。

### 处置

- **开发期最可靠**：每次重装后重启 daemon（软重启）。daemon 重起后
  `oldState` 为空，`oldModule` 为 null，会按新 `appInfo` 重新解析
  `apkPath` 和 `appId`。3080 软重启有双实例 bug，优先 3118。
- 只改 scope 触发 rebuild 不一定够：same-path 情况下 fast path 仍会复用
  旧 `appId`。
- CLI 可以强制一次配置重建（但同样受 fast path 限制）：

  ```sh
  su -c 'sh /data/adb/modules/zygisk_vector/cli modules disable <pkg>'
  su -c 'sh /data/adb/modules/zygisk_vector/cli modules enable <pkg>'
  ```

- **永久修复**（要改 daemon）：
  1. fast path 总是刷新 `oldModule.appId = appInfo.uid % 100000` 和
     `oldModule.applicationInfo = appInfo`；
  2. 比较 `versionCode` / APK mtime，不只比较 path；
  3. 模块的 `ACTION_PACKAGE_ADDED/CHANGED` 无条件
     `ConfigCache.requestCacheUpdate()`。

---

## 6.7 2026-11-15：3080→3118 就地升级事故与无硬重启恢复实录

> 一天之内叠加了三起独立事故（3080 双 daemon 崩溃、3118 就地升级灾难、手工启动
> 路径错误触发自动重启风暴），最后**在不硬重启、不掉 kiroot 的情况下完整恢复了
> Vector**。本节是可复用的诊断判据 + 恢复 runbook。
> 物证在 `_tmp_vector/`（`vdb2/vdb3.dump`、清空后的 `vdb4.dump`、重建的
> `restored.db`、刷过的 `v3118/`）。

### 6.7.1 时间线（dropbox + daemon 落盘日志对齐）

| 时间 | 事件 | 证据 |
|---|---|---|
| 14:54:22 | 管理器触发 `ksud soft-reboot` → `service.sh` 重放 → 一机三个 `vectord`（3172/15360/15636）在 14:54:44–46 先后 binder 线程栈溢出全部崩死 | dropbox 3×`SYSTEM_TOMBSTONE`（`Cmdline: vectord`，`Cause: stack overflow`）；机制即 §6 |
| ~15–17 时 | 用 `adb logcat -d` 误判"模块没加载"（落盘日志显示 17:02 其实已加载成功）→ 决定升级 3080→canary-3118 | 教训见 §6.7.2 判据 3 |
| 15:47–17:00 | **就地升级**：zygote 内存仍是 3080 原生库，磁盘已换 3118 → system_server 混代崩溃循环；3118 daemon 把 `modules_config.db` v4 迁 v5 时**清空 modules 表**（18 个模块只剩 lspd） | `vdb4.dump` 仅 1,770 B（升级前 `vdb3.dump` 117,606 B）；后从 dump 重建 `restored.db` 推回 |
| 17:28 | 4× `zygote64` abort：`stack corruption detected (-fstack-protector)`（`AndroidRuntime::start` 内）；同刻 daemon 日志出现**3118 源码才有**的 `"Vector reached system_server but its bridge never answered"` → 运行代与磁盘代仍不一致 | dropbox `SYSTEM_TOMBSTONE`；字符串只在 `root/.upstream/vector`（canary）的 `VectorDaemon.kt:252` |
| 17:51/17:54/17:55 | 三次手工启动 daemon **均未 cd 模块目录** → `framework/vector.dex: ENOENT` → 3080 按 `service.sh` 默认 `--system-server-max-retry=3` 在 17:55:36/17:55:53/17:56:02 三连自动重启 system_server，耗尽后停；vectord 消失 | adbd 命令日志 + verbose log `ENOENT` + `Restarting system_server...` |
| 16:33 | （独立事件）vivo 备份 `TransportManager.onPackageChanged` NPE 崩 system_server，与 Vector 无关 | dropbox `system_server_crash` |
| 18:12 | 复核：`kernelsu` LKM 仍 Live；当日 21×`SYSTEM_RESTART`、**0×`SYSTEM_BOOT`**（纯软重启风暴）；zygote 自 17:56 稳定 | `dumpsys dropbox` 计数 |
| 18:30–18:37 | 按 §6.7.3 runbook 恢复成功 | 见 §6.7.4 |

### 6.7.2 诊断判据（动手前先看这四条）

1. **代际一致性（能不能救的总闸）**：
   ```sh
   grep zygisk_vector /proc/$(pidof zygote64)/maps   # 取映射 inode
   ls -li /data/adb/modules/zygisk_vector/zygisk/arm64-v8a.so
   ```
   inode 一致（当日 2581420）→ 可救；不一致 → 任何 daemon 操作都无意义，
   只会继续混代崩溃。
2. **软重启风暴 vs 真重启**：`dumpsys dropbox` 里 `SYSTEM_RESTART` 与
   `SYSTEM_BOOT` 的当日计数。11-15 全天 21:0，即从未整机重启、kiroot 从未丢。
3. **模块是否加载只看 daemon 落盘日志** `/data/adb/lspd/log/verbose_*.log`
   （Vector 自带 logcat 抓取、含历史）；`adb logcat -d` 会被开机日志洪峰冲掉，
   17:02 的误判就是这么来的。
4. **现场三件套**：`pidof vectord` 必须恰为 1 个；`service list | grep serial`
   看 rendezvous 归属；`grep '^kernelsu ' /proc/modules` 确认 root 还在。

### 6.7.3 手工启动的两个路径坑 + 正确写法

| 错误写法 | 报错 | 原因 |
|---|---|---|
| 不 cd 直接跑 daemon | `FileNotFoundException: framework/vector.dex: ENOENT` | daemon 以**相对路径**读 `framework/vector.dex`，cwd 必须是模块目录 |
| `./daemon`（cd 了但用相对路径执行） | `UnsatisfiedLinkError: Expecting an absolute path of the library: ./daemon.apk!/lib/arm64-v8a/libdaemon.so` | native 库从 classpath 展开，要求绝对路径 |

正确写法（**cwd 进模块目录 + 绝对路径执行 + retry=0**）：

```sh
adb shell su -c 'cd /data/adb/modules/zygisk_vector && \
  /data/adb/ksu/bin/busybox unshare --propagation slave -m \
  /data/adb/modules/zygisk_vector/daemon --system-server-max-retry=0 &'
```

`--system-server-max-retry=0` 是关键保险：握手失败时 daemon 直接放弃，
不会像 `service.sh` 默认值 3 那样三连自动重启 system_server。
`su -c` 会重置 PATH，busybox 必须写绝对路径。

### 6.7.4 恢复 runbook（18:34 实测成功）

前提：判据 1–4 全过（`vectord=0`、inode 一致、KSU 在）。

```sh
# 1. 按正确写法启动一个 daemon（上一节的命令），记下 PID —— 必须恰一个
adb shell su -c 'pidof vectord'
# 2. 唯一一次 zygote-only 重启。不要用 ksud soft-reboot（会重放 service.sh，
#    3080 有再造第二个 daemon 的风险，见 §2.2/§6）
adb shell su -c 'setprop ctl.restart zygote'
# 3. 等 ~30s 后验证：
#    a. 新 zygote/system_server PID
#    b. 落盘日志出现 "Loaded module <pkg> successfully" / "dispatchPackageReady"
#    c. service list | grep serial → serial 已还给真实 SerialService
#    d. getprop sys.boot_completed = 1
# 4. 若仍失败：只 kill 第 1 步记下的 PID 止损，绝不追加重启、不换版本
```

原理：桥接装在 system_server 的 specialization 阶段，**daemon 必须先就位**，
zygote 重启只是给 system_server 一次重新初始化的机会。顺序反了（先重启后补
daemon）就只会得到 `No response from bridge`。

当日结果：`vectord` 5935 单实例 / zygote 6356 / system_server 6616；
`io.github.comewhy.ding`、`top.haif.xposed`、`io.github.ldxm666.mapadkiller`
全部 `Loaded ... successfully` 并收到 `dispatchPackageReady`；boot_completed=1。

### 6.7.5 被推翻 / 修正的旧结论

- ❌“回滚后桥接无法手动恢复，只能真重启” → ✅ daemon 先行 + 一次
  `ctl.restart zygote` 即可（本节实测）。
- ❌§6 原“建议换 3118” → 换版本必须伴随一次完整开机初始化窗口；且 3118 的
  DB 迁移不可逆（3080 读不了 v5，只能重灌 v4 dump）。
- `ksud soft-reboot` ≠ `setprop ctl.restart zygote`：前者重放模块脚本
  （3080 双 daemon 风险），后者不重放。恢复场景一律用后者。

### 6.7.6 遗留

- 3080 双 daemon 根因（`5690481` 未合入）仍在：**任何** `service.sh` 重放都可能复发。
- 当日累计 22+ 次 system_server 启动，PackageWatchdog/RescueParty 的 mitigation
  计数只增不减（`eaffc81` 注释）；若日后设备自己弹 recovery 询问"是否清除数据"，
  来源即此——**不要确认清除**。
- zygote 重启会打断无障碍：重启后 enabled 列表只剩 Scene，GKD 需手动复核开关。
- 残留的 `VectorCli log tail`（`cli log tail` 等待客户端）无害，未清理。

---

## 7. 实战检查清单

### 7.1 exploit / KSU 之后

```sh
su -c id                                  # uid=0(root), context=u:r:ksu:s0
grep '^kernelsu ' /proc/modules           # ko 在
ksud --version
ksud module list
getenforce                                # 研究阶段可能是 Permissive
```

### 7.2 Vector 之后

```sh
ps -A -o PID,STIME,ARGS | grep vectord    # 只能 1 个
service list | grep -E 'serial|activity'
ls -l /data/adb/lspd/log/
su -c 'cat /data/adb/lspd/config/modules_config.db' > /tmp/vdb.dump
```

模块生效的判定顺序：

1. `logcat -s VectorModuleManager` 有 `Loading module <pkg>` /
   `Loaded module <pkg> successfully`；
2. 目标进程的 `logcat -s <模块 tag>` 有 `libxposed module loaded` /
   `onPackageLoaded pkg=...`；
3. 具体 hook 的日志（例如 `OriginTweaks`）出现；
4. 功能实际生效。

只有第 1 条 = 模块被识别；只有第 2 条 = 被注入；第 3/4 条才是功能成功。

### 7.3 软重启之前

- 备份 `daemon.apk` / `manager.apk` 到 `/data/adb` 之外；
- 确认只有一个 `vectord`；
- 优先用 3118；
- 不要连着重启，不要在 daemon 半死时反复折腾；
- 不要硬重启（掉 root），不要手动 kill `vectord`。

---

## 8. 证据索引

| 主题 | 文件 |
|---|---|
| 临时 root 主线 / 64560 | `root/README.md`、`root/FINDINGS.md` §9–§11、`root/pd2545-64560/README.md` |
| vr.ko 机制 | `root/ROUTES.md` §2，`root/FINDINGS.md` §“vr.ko 两层中和” |
| kiroot / poc 包装 | `root/kiroot-apk-analysis.md` |
| insmod / patch / 通道 | `_tmp_dev/x300root.sh`、`_tmp_dev/ksu_stage.sh`、`_tmp_dev/worker.sh`、`_tmp_dev/out.txt` |
| KSU 软重启字符串 | `root/.upstream/ksunext/lib/arm64-v8a/libksud.so`（`emulating soft_reboot!`） |
| Vector 3080 崩溃 | `root/vector-softreboot/FINDINGS.md` |
| Vector 模块加载 | `root/.upstream/Vector-88f8e1fa/daemon/.../FileSystem.kt` §loadModule |
| Vector 管理器检测 | `root/.upstream/Vector-88f8e1fa/manager/.../ModuleDetection.kt` |
| Vector rendezvous | `root/.upstream/vector/zygisk/README.md`、`VectorDaemon.kt` |
| Vector 3118 | `root/.upstream/v3118/`、`root/.upstream/Vector-3118-Release.zip` |
| 11-15 升级事故与恢复 | 本文 §6.7；物证 `_tmp_vector/`（vdb\*.dump、restored.db、v3118/） |
| OriginTweaks 复盘 | `../origin-tweaks-REVIEW.md` |
