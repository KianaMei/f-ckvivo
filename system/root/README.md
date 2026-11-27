# f-ckvivo

vivo X Fold6（PD2545D / V2545A，MediaTek MT6993）在 **"永远拿不到内核镜像/全量包"** 约束下的
root 与固件包研究日志。

> 一切操作对象为作者自己的设备。所有内核级实验均遵循"只读/可回退/可重启恢复"原则；
> 真正写入内核的步骤（临时 root）实施前单独评估。

## 目标

1. 拿到 `PD2545D_A_17.1.12.x.W10.V000L1` 的官方全量包（全量 ROM zip）
2. 在不依赖 boot.img / 全量包的前提下取得临时 root（**仅 CVE-2026-64560 存活**；43074/43499 已修，见下）

## 当前状态（2026-10-17，路线重判）

| 战线 | 状态 |
|---|---|
| OTA API 直查全量包 | ❌ 尝鲜图未开放（正式推送 10/23-29 放量），anon 重放全 210 |
| VDFP 指纹复刻 | ❌ sectee TEE 服务对 shell 域完全不可达（AIDL 惰性/ HIDL 不可见 / ctl.start 被拒） |
| 抓包（代理/TLS/logcat） | ❌ 全被系统级封死（详见 FINDINGS.md §3） |
| DFRoot (CVE-2026-43284) | ❌ 本机实测 `DEVICE NOT VULNERABLE`（9 月补丁已修） |
| **CVE-2026-43074（eventpoll/page-cache）** | ❌ **根因已修**（6.12.83；ACK `2025-12_r59` 已含 “defer struct eventpoll free to RCU grace period” 的 `rcu_head`）→ carrier/fd_graph 的 page-cache 原语在本构建不可用 |
| **CVE-2026-43499（multicast / futex PI）** | ❌ **根因已修**（6.12.86；ACK `2025-12_r59` 的 `remove_waiter()` 已用 `waiter->task`）→ 此前 race 256 轮 0 命中是**根因不存在**，不是几何/RUNTIME_BLOCKED |
| **fd_graph 路线（vivo 验证线）** | 📦 **存档**：上游 23 次闸门该写入原语从未命中（含唯一 PASS），批次 D 后 -18…-24 全 FAIL、末次闸门"未执行"；其 43074 根因也已在本构建修复 → 双重不可用。详见 `ROUTES.md` §1/§10 |
| **CVE-2026-64560（POSIX timer UAF）** | ✅ **唯一存活路线**：修复在 6.12.100（7-30）/ 十月公告；ACK `2025-12_r59`（7-28）**没有** `timer_lock_sighand`，且 r59 的独立 `vuln-check` 判 **VULNERABLE**；8-05 构建设备大概率仍在漏洞区间。r59 公开 GKI 符号/BTF 已提取；**PD2545D 端口已构建**（`pd2545-64560/`，518712 B，sha256 `aa13e1ea…`，含 `--dump-kallsyms` 只读桥）；vr.ko 两层已内置。2026-10-17 22:25 用 `timer_goal=55962` 跑到 attempt 1 喷洒后设备掉线（FINDINGS §11），尚未进入 stage8；设备恢复后先读 `posix_timers` slab 几何，再决定受控重跑 |
| 全量包获取（物理渠道） | ⏳ 公开推送（10/23-29）/ 社区 dump / 售后 ROM。**升级会换内核，先不要更新设备**。当前没有 boot.img/源码包可用；精确化只能靠设备自身只读几何（`/proc/slabinfo`、`/sys/kernel/slab/posix_timers/*`）与受控探测 |

### 10-17 重判：为什么放弃 43074/43499，只打 64560

| 项 | 证据 |
|---|---|
| 设备构建 | `6.12.58-...-gdd6fd5c98918-abogki543627742-4k`，`uname -v` **2026-08-05**，SPL 2026-09-01 |
| 43074 修复 | `eventpoll: defer struct eventpoll free to RCU grace period`，stable **6.12.83**；ACK `android16-6.12-2025-12_r59`（2026-07-28）的 `fs/eventpoll.c` 已含 `struct rcu_head rcu` 与 “defer freeing past ep_get_upwards_depth_proc() RCU walk” |
| 43499 修复 | `rtmutex: Use waiter::task instead of current in remove_waiter()`，stable **6.12.86**；ACK r59 的 `remove_waiter()` 已用 `waiter_task = waiter->task` |
| 64560 修复 | `posix-cpu-timers: Prevent UAF caused by non-leader exec() race`，stable **6.12.100**（7-30）/ Android 十月公告；ACK r59 的 `posix-cpu-timers.c` **没有** `timer_lock_sighand` |
| 结论 | 43074/43499 的 0/256 是**根因不存在**（不是 RUNTIME_BLOCKED）；**64560 是唯一存活线**。r59 是公开 GKI 2025-12 的最后一轮；设备的 `dd6fd5c98918` 是 8-05 私有 respin，符号/BTF 以 r59 为基线 |

完整的 r59 符号/BTF 与 PD2545D 端口的构建、`--preflight` 用法见
[`pd2545-64560/README.md`](pd2545-64560/README.md) 与 `FINDINGS.md` §9–§10。

拿到临时 root 之后的用户态栈（KernelSU LKM、`ksud`、Zygisk Next、Vector
manager/daemon、模块打包与 scope 规则）见 [`VECTOR-KSU.md`](VECTOR-KSU.md)。

## 目录

> 本文件所在目录（`root/`）就是这条战线的根；`tools/`、`logs/` 等路径都相对 `root/`。
> 仓库里还有另外两块互不相干的内容：`fq-fold-pad/`（番茄双列模块）与 `douyin/`（抖音逆向），
> 见仓库根的 `README.md`。

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
pd2545-64560/           CVE-2026-64560 PD2545D 端口：一加 15T 6.12 的 uhid+configfs 桥
                        + r59 公开 GKI 符号/BTF；build/ 产物；只读 preflight
FINDINGS.md             全部技术发现（设备事实 / 封锁清单 / 生态地图 / 校准结果）
VECTOR-KSU.md           exploit→KernelSU→Zygisk Next→Vector 全栈笔记：临时 root 边界、
                        `ksud soft-reboot` 语义、Vector 模块加载规则、3080 崩溃根因
```

## 关键事实速览

- 内核：`6.12.58-android16-6-gdd6fd5c98918-abogki543627742-4k`（2026-08-05 构建，SPL 2026-09-01）
- 本机与已公开案例（PD2502/PD2545 @ 6 月内核 `g0a092cc0037a`）**不是同一构建**
- `libdumpstateaidl.so` 补丁偏移已自研推导：**0x9220**（ELF `.init_array[0]`，带 BTI），
  size `0x10c00`，preimage `5f 24 03 d5 fd 7b bf a9 fd 03 00 91 20 00 00 90`
- kernelsnitch（futex 时序 KASLR 破解）在本机 **实测工作**
- KSuRoot 4.1.2 全部 129 载荷中 vivo 仅覆盖 6.6.x 族，**6.12 vivo 无公开载荷**
- 2026-10-17 重判：43074（6.12.83）与 43499（6.12.86）的修复都已进 ACK `2025-12_r59`（7-28），8-05 设备在其后 → 两条路线根因已修
- 64560 修复在 6.12.100（7-30）/十月公告；r59 **无** `timer_lock_sighand`，r59 独立 `vuln-check` 判 **VULNERABLE** → 8-05 设备是唯一存活线
- r59 **certified 量产 Image**（`0x2820000`，128,886 符号；sha256 `fe3ef364…`）与 BTF（`0x19ea3e4`）已提取；debug all-syms 镜像（`0x36a0000`）是不同布局，151,293 个地址已弃用；`task_struct`/`file_operations` 的 6.12 偏移与一加 15T 端口逐项一致
- PD2545D 64560 载荷：518,712 B 静态 aarch64，sha256 `aa13e1eae51b194185feafde156621bef226cfe9150b9655f22052bda8eeb972`；vr.ko 两层中和已内置（sys_exit funcs 置 0 + task flags/tag B 清理），`tools/pd2545_run.sh` 直接 preflight→`--run`。`--dump-kallsyms` 只读桥与 `tools/pd2545_watch_dump.sh`（USB/logcat 监视）用于先取设备符号；2026-10-17 22:25 的 55962 运行在 attempt 1 喷洒后掉线，详见 FINDINGS §11

## Vector / KSU 隐藏与美团检测面（2026-11-23）

> 本节合并公开一手资料、当日只读设备证据和本机美团 APK 的静态反编译。没有改 KSU/ZN/Vector 配置、
> 启停进程、安装隐藏模块或重启。**存在检测面 ≠ 美团实际命中该项；当前触发项仍未知。**

### 结论与隐藏边界

- **Vector scope 只控制业务框架注入。** Vector v2.2 固定源码显示，普通 App specialization 时先发 `_VEC`
  Binder 请求询问 daemon；无 scope 时才不加载 framework DEX/ART hooks，并请求 Zygisk
  `DLCLOSE_MODULE_LIBRARY`。所以“不勾 scope”不等于从未发生 ZN/Vector native 回调，也不清理全局
  `system_server`、`vectord`、`/data/adb`。[Vector module.cpp](https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/zygisk/src/main/cpp/module.cpp#L223-L377) · [ipc_bridge.cpp](https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/zygisk/src/main/cpp/ipc_bridge.cpp#L232-L297)
- **KSU「卸载模块」只还原指定 App mount namespace 中登记的模块挂载。** 它不删除 `/data/adb`、不卸载
  `kernelsu.ko`、不停 daemon、不清内存注入、不隐藏管理器包名、不改 Verified Boot/Play Integrity。
  v3.3.1 源码的非 Root 默认 `umount_modules=true`，但本机目标 App 的实际视图仍须按 PID 验证，不能用
  shell namespace 替代。[App Profile](https://github.com/tiann/KernelSU/blob/0c0fdb960178fa12d80d6cbcfdcb6ad8be6a865d/website/docs/zh_CN/guide/app-profile.md#L108-L121) · [allowlist.c](https://github.com/tiann/KernelSU/blob/0c0fdb960178fa12d80d6cbcfdcb6ad8be6a865d/kernel/policy/allowlist.c#L41-L58)
- **ZN 1.5.3 已整合大部分 Shamiko 功能。** `enabled/enforced` 的官方语义是“禁止代码注入并还原挂载”；
  `just_umount` 仍允许代码注入。内置 linker 与匿名内存只降低部分 linker/maps 痕迹，且可能有模块兼容
  问题；都不是不可检测承诺。外部 Shamiko 核心为预编译 `.so`，ZN 1.3+ 不再加载它。HMA 主要过滤
  PackageManager 包可见性，不能隐藏 su、mount、maps、LKM、内存或完整性信号。[ZN 1.3.0 release](https://github.com/Dr-TSNG/ZygiskNext/releases/tag/v1.3.0) · [ZN 1.5.3 release](https://github.com/Dr-TSNG/ZygiskNext/releases/tag/v1.5.3)
- **不要重复 umount。** ZN 官方 WebUI 明确警告 KSU kernel umount 与 ZN 重复卸载的配置风险。先确认由哪层
  负责，再决定是否单变量调整；不要同时打开所有“隐藏”开关。
- **临时 late-loaded LKM 保留的是厂商启动链，不是运行态清白。** 活跃时仍有 KSU/ZN/Vector 进程、文件、
  挂载与内存面；本机 exploit 还额外中和了 vivo `vr.ko` 防护。完整重启会丢这些运行态修改但保留
  `/data/adb`，且在本机属于禁止操作，不作为实验方案。

### 本机当日只读事实

| 项 | 当前证据 | 能推出什么 |
|---|---|---|
| 美团 | `com.sankuai.meituan` **12.69.402 (1200690402)**；APK SHA-256 `153cca6aecc462af09b54e7738ba3d4e044e9b4a1a549e21f46f3c22e93ca251` | 后续静态结论只对这一版本负责 |
| Vector scope | `io.github.offlineglass` enabled，scope 明确含 `com.sankuai.meituan/0` | 美团不是“无业务模块注入”对照；LiquidTab 源码即使单 App 功能关闭，仍先加载模块并安装 Activity/Application 生命周期 Hook |
| Shamiko | 磁盘有 `zygisk_shamiko v1.2.5 (414)`；ZN 持久日志 11-15 21:40:57 对 32/64 位均写 `skip shamiko`，`modules_info` 只登记 `zygisk_vector` | 外部 Shamiko 核心未加载；这是 ZN 内置实现的替代关系，**不等于完全没有内置隐藏** |
| ZN 运行参数 | `denylist_policy=0`、`enforce_denylist=0 (disabled)`、`memory_type=0 (default)`、`linker=1 (builtin)`；32/64 位均只加载 Vector | 内置 linker 已开；匿名内存未开；不要把“再开 linker/重装 Shamiko”当新优化。KSU 默认 umount 与 ZN 策略要分开核对 |
| su / LKM | 非 Root shell 可看到 `/system/bin/su` 与 `/proc/modules` 中 `kernelsu` | 是明确候选信号；普通 App/美团 namespace 是否相同尚未验证 |
| ADB / 开发者状态 | global `adb_enabled=1`、`development_settings_enabled=1`、`adb_wifi_enabled=1` | 是独立于 Root 的环境风险候选；关闭 ADB 会断开当前研究通道，不在取证前擅自修改 |
| 启动属性 | `user/release-keys`、`ro.debuggable=0`、bootloader locked、verified boot green/enforcing | 没有先加 PlayIntegrityFix/TrickyStore 的证据；本地属性也不代替硬件证明或美团 verdict |

### 美团 12.69.402 精确静态证据

对本机 APK 做 DEX 字符串引用反查，再以 JADX 单类反编译复核：

1. `com.meituan.android.common.fingerprint.FingerprintManager.root()` 循环检查 8 个目录下的 `su`，任一
   `File.exists()` 为真就返回 Root；`com.meituan.metrics.util.g.u()` 也独立检查 11 个 su 路径。两者都包含
   **`/system/bin/su`**。这证明存在真实 Root 检查逻辑，但尚不证明本次提示一定由它触发。
2. `com.meituan.android.hades.impl.widget.RiskUtils` 读取 `adb_enabled`、
   `development_settings_enabled`、`adb_wifi_enabled`；ADB 分支会记录 `local_debug`。其中 ADB 使用旧
   `Settings.Secure` API，而本机命令行 secure/global 视图不同，故“实际返回及是否参与当前封控”仍需运行证据。
3. `FingerprintManager.detection()` 查询 `de.robv.android.xposed.installer` 等包并形成指纹位；这不能扩张为
   “它只查这些包”，也不证明当前 KSU/Vector 管理器包名已被命中。
4. `com.meituan.android.yoda.util.j` 采集 `TracerPid`、native bridge、`magisk.version`、`magisk_hide`、
   `ro.debuggable`、`ro.secure`、ADB/开发者状态等环境字段。
5. APK 还含 `libmtguard.so`、Defender 和 `/proc/self/maps` 等字符串，但部分 maps 引用属于动态库加载器，
   **不能把所有字符串都当成 Root 检测证据**。美团当前完整 native/服务端算法仍未知。

美团官方旧文章只作背景：2018 年《[Android Hook技术防范漫谈](https://tech.meituan.com/2018/02/02/android-anti-hooking.html)》讨论包列表、maps 与 Java/native 反 Hook；它不是当前客户端检测清单。未找到美团使用 Play Integrity 的一手证据。

### 后续最小区分顺序（均需保持只读或另行当次授权）

1. **保存症状原文/截图与复现路径**，区分 Root 提示、环境风险、功能限制、账号/交易风控；不再同时改多个变量。
2. **用户手动打开美团后，按 PID 只读核对** `/proc/<pid>/root/system/bin/su`、`mountinfo`、`maps`、进程启动时间，
   与 shell 视图分开记录；访问被拒要记为未知，不写成“无痕迹”。
3. **LiquidTab 对照**：若用户愿意暂时失去美团玻璃底栏，可单独移除 LiquidTab 的美团 Vector scope，再由用户
   正常关闭/重开并确认是新建 PID。仅在 LiquidTab UI 关功能不等于模块未注入。此变更需另行授权，不重启系统。
4. **ADB 对照**：研究通道可中断时，由用户单独关闭 USB/无线调试及开发者选项再复测；不要为保留 ADB 再往美团
   注入一个伪造设置的模块，那会增加 Hook 面。
5. **严格隐藏与保留 LiquidTab存在取舍**：严格路线是不 scope 美团并按已核实的 KSU/ZN 单一责任链阻止注入、
   还原挂载；`just_umount` 可保留注入但不是完全隐藏。anonymous/builtin linker 只在有内存层证据时单变量评估。
6. **不把外部 Shamiko、HMA、PlayIntegrityFix 当第一轮答案**，不保证任何组合能绕过；单变量无差异就停止叠加。

## 临时 Root 工具生态逆向与内核内存泄漏分析（2026-11-27）

> 基于本地静态逆向（JADX / DEX 提取 / ELF SHA-256 跨包对账）对比分析 vivo 生态常见提权工具：
> `Shark 原版 (c6603cd)`、`kiroot.apk`、`x300s.apk (com.x300pro.auto)`、`shark vivo.apk (XF3 Pro 改版)`、`Rift_1.1.apk`、`白鹿提权助手 1.2.0.apk`。
> 全程离线分析，不触发实际漏洞利用，旨在记录底层资源管理机理与自研工具设计准则。

### 1. 载荷指纹与衍生关系

| 工具 | 核心载荷指纹 (SHA-256) | 架构与适配目标 | 工程包装特征 |
|---|---|---|---|
| **kiroot.apk** | `poc_tmp_root`: `02ae7e78...`<br>`kernelsu.ko`: `b98df71a...`<br>`ksu_patch.txt`: `468e27c1...` | 天玑 9300+ / 6.12.58<br>(`gdd6fd5c98918`) | 极简 Java + Shizuku，仅内嵌单目标载荷，单发执行，用完即退。 |
| **x300s.apk**<br>(`com.x300pro.auto`) | APK: `1de99ba6...`<br>3 份 ELF PIE (`95,416 B`, Clang 19.0.1): `auto_x300p` / `auto_x300p_old` / `auto_x300p_c3e244f`<br>2 份 LKM: `kernelsu_61258.ko` / `sukisunova_android16-6.12.ko` | 6.12.58 (`tag 3127 / 7742`)<br>6.12.81-android16-6-gc3e244f20fca (`tag 5657`, 首测 `verified=false`) | 零网络权限、零遥测，仅申请 Shizuku `API_V23`；通过 `AutoFlow.java` 7 步状态机驱动 `/data/local/tmp/x300p_auto`。3 份二进制同源 `plant_core.inc` 架构，仅内嵌不同内核偏移常量。 |
| **Rift 1.1** | 与 kiroot **逐字节完全相同** (100% 相同 SHA-256) | 天玑 9300+ / 6.12.58 等多目标 | Compose 壳，引入复杂的常驻脚本通道（`worker.sh`、`dispatch.sh`、`t28k`），外置动态提取支持。 |
| **Shark 原版** | 包含完整 C 源码基线 (`c6603cd`)，符号一致 | 覆盖 v2505a 等多端口 | 内置看门狗（Watchdog）、`RunLock` 互斥、退出闭环清理与内存还原逻辑。 |
| **shark vivo** | 本地清空 so，改由云端动态拉取并阅后即焚 | X Fold3 Pro (6.1.172) | 二次打包壳，增加卡密鉴权与脱敏日志，防止载荷被静态直接提取。 |
| **白鹿提权助手 1.2.0** | 包含自身专用 native so | 仅适配 6.6.127 (iQOO Neo10 Pro+) | 前端毛玻璃 UI 与只读探测 (`KernelProbe.java`) 设计出色，但无 OS7 门禁绕过，不适用当前环境。 |

### 2. 为什么 Rift 会造成数 GB 内存丢失，而 kiroot / Shark 不会

实机与逆向比对证实，即便底层二进制载荷相同，包装层工程设计的优劣对内核健康度有决定性影响：

1. **内核 ftrace 环形缓冲区膨胀未还原（GB 级内存蒸发主因）**：
   - **机制**：物理页分配探测阶段必须临时调大 `/sys/kernel/tracing/buffer_size_kb`。在 Linux 内核中，该缓冲区是**按每一个 CPU 核心独立分配物理页**的。在 8 核天玑 9300+ 上，256MB 的设置会直接霸占 `256MB × 8 = 2GB` 的物理不可换出内存（`SUnreclaim`）。
   - **Shark**：在退出/异常钩子中强制重置 `echo 0 > tracing_on; echo 1 > buffer_size_kb` 并调用 `echo 3 > /proc/sys/vm/drop_caches` 释放借用页。
   - **kiroot**：单次运行并施加压力上限（`POC_PRESSURE_MB=700`），进程退出由内核统一收回。
   - **Rift**：封装脚本（`x300root.sh` / `run98260.sh`）在异常或多轮重试时**彻底遗漏复位命令**，导致数 GB 物理内存被永久锁定在内核区，直至整机重启。
2. **Rift 脚本死循环与常驻进程泄漏（Fork 枯竭）**：
   - Rift 在 `assets/pyb110/worker.sh` 注释中明确记录了此缺陷：plant 每次触发都会 fork 脚本，若后台常驻通信脚本 `dispatch.sh` 未受控退出，将导致孤儿 root 进程堆积。
   - 当系统 PID/Fork 耗尽后，脚本的 `sleep` 随之失败，退化为死循环空转忙等，造成 100% CPU 满载、发热严重并拖垮内存。
3. **Pipe Array 与 SLAB 占位无序堆积**：
   - 堆喷占位需要申请海量管道与 SLAB 块。
   - **Shark** 具有超时看门狗与互斥锁，上一轮失败必先释放旧 holder；**kiroot** 保持单发；
   - **Rift** 粗暴并发/连发重试（甚至混跑 `t28k` 与 `poc_tmp_root`），旧句柄未释放即开新喷洒，导致内核 SLAB 内存长期驻留无法回收。

### 3. `x300s.apk`（跨缓存 `skbuff` 堆喷系）vs `kiroot.apk`（原位修补系）内存代价与原料对比

除包装脚本层面的泄漏外，`x300s.apk` 这类基于 `plant` / `shark` 演进的工具在底层机制上同样会产生固定的物理内存驻留损耗，而 `kiroot.apk` 则完全不会。两者的本质差异源于**内存利用架构**与**前置原料维度**的不同：

| 对比维度 | `kiroot.apk`（定制手术刀 / 原位数据修补） | `x300s.apk`（通用堆喷 / 跨缓存页替换） |
|---|---|---|
| **核心内存机制** | 用户态内存施压（`POC_PRESSURE_MB=700`）促进回收后，直接定位子进程自身的合法 `struct cred` 做原位偏移修补（`STEP1 cred+0x40` → `STEP3 cred+0x42`）。 | 使用 Socket Buffer（`skbuff`）跨缓存区喷射（单轮最高 1 GB，最多 3 轮；部分衍生版在 6.12.58 下调至 ~256 MB），将伪造的 `cred`/`task` 结构布置在 Socket 缓冲区物理页中。 |
| **为何 `x300s` 必然丢内存** | **无常驻损耗（0 MB 残留）**：不依赖寄生于 Socket 的伪造对象。挂载 `kernelsu.ko` 后子进程正常退出，用户态 VMA 与管道 FD 由内核按标准生命周期 100% 自动回收。 | **不可释放的生命周期绑定（丢失 1~2 GB）**：内核活跃指针已指向寄生在 `skbuff` 中的伪造结构。一旦关闭对应 Socket FD，引用计数归零并释放变形 Slab 时会**立即触发 Kernel Panic**。工具只能派生常驻进程 `t61269-hold`（`PARKED=1`，`cred pool frozen`）永久冻结该内存池直至重启。 |
| **所需前置“原料”** | **重度原料（225 个精确内核符号）**：依赖 `ksu_patch.txt` 提供特定编译版号（`6.12.58-gdd6fd5c98918`）的 225 个链接期符号地址（如 `commit_creds`、`init_cred`、`find_task_by_vpid`、`sys_init_module` 等）。 | **极简原料（约 15 个通用偏移）**：仅需从公开 `boot.img` 通过 `recover.py` 解析 BTF 与头部，提取约 15 个结构体几何参数（`k_itimer` 大小、`task_struct` 大小、`cred` 偏移、`timer_site_low21`、`copy_process_low21` 等）。 |
| **工程优劣与泛化性** | 体验极致干净、零内存残留；但死绑单一内核编译哈希，OTA 升级任意小版本均需重新逆向全量符号。 | 适配门槛极低（拿到新 `boot.img` 跑脚本即可生成配置），靠大体量堆喷抹平符号未知的缺陷；代价是牺牲物理内存与后台驻留。 |

### 4. 针对新内核（如 `6.12.81`）制作 `kiroot` 式“零内存损耗”方案的可行性评估

针对能否在新内核（如 `6.12.81-android16-6-gc3e244f20fca`）上实现 `kiroot` 式不丢内存的效果，从系统架构与内核逆向角度的定性结论为：**理论上完全可行，但无法仅凭 `x300s` 现有原料实现，且单版本逆向与维护成本极高。**

1. **现有原料的局限性**：
   - `x300s` 当前仅具备从 `boot.img` BTF 提取的十几个结构体大小与低位偏移，缺乏内核关键函数符号表，在架构上无法直接改为原位修补，必须补齐原料并重构底层引擎。
2. **理论实现路径（三大核心条件）**：
   - **全量符号表重构**：从新固件 `boot.img` 解包未压缩的 ARM64 内核 `Image`，利用 IDA Pro / Ghidra 配合 Linux 6.12 符号控制流特征比对（BinDiff），重新定位 `6.12.81` 中对应的 200+ 个核心函数静态地址，生成新内核专用的符号映射表。
   - **纯数据流篡改（Data-Only Patch）规避硬件防线**：现代 ARMv9（天玑 9300/9400 Cortex-X4）与 Android 16 GKI 强制启用 kCFI（控制流完整性）与 PAC/BTI（指针认证），篡改代码段或函数指针会直接触发 Panic。必须沿用 `kiroot` 的纯数据流路径——在受控写原语仍存活的前提下，仅原位修改进程自身的凭证数据字段（`uid` / `gid` / `cap_effective` 等不受 PAC/CFI 保护的数据域）。
   - **模块签名校验临时放行与干净退出**：定位新内核的模块签名检查点（`CONFIG_MODULE_SIG_FORCE` / `module_sig_ok` 流程），原位临时放行以加载针对 `6.12.81` 编译的 `kernelsu.ko`，随后恢复校验位并退出全部辅助进程，即可实现零内存锁定的闭环回收。
3. **现实工程瓶颈**：
   - 阻碍该方案普及的并非技术不可行，而是**极高的单版本维护成本（版本脆弱性）**。`kiroot` 模式每次面对厂商月度 OTA 小版本更新，200+ 符号地址均会重排失效，需要耗费大量人工逆向时间；而 `x300s` 模式仅需几秒脚本即可从 `boot.img` 提取十几个通用偏移，因此社区工具普遍在“开发维护成本”与“用户运行时 1GB 内存代价”之间选择了后者。

### 5. 自研调试工具与 IDE 底层设计准则

面向后续自研 IDE / 手机端调试集成工具的架构经验：

1. **Clean-on-exit 退出闭环**：凡是修改过内核参数、启用 tracing、挂载管道或拉大系统水位的逻辑，必须在信号处理器（Signal Handler）与 `atexit` 中注册严格的对偶复原逻辑。
2. **拒绝多层常驻脚本桥接**：避免在设备侧使用粗暴的 Shell `while true` 轮询脚本做 IPC，优先使用标准 I/O 管道（stdio）或抽象 Unix LocalSocket，杜绝孤儿进程和 Fork 枯竭风险。
3. **坚持单发与幂等性**：失败时切忌无脑并发重试，必须执行“探测 -> 验证 -> 失败清理 -> 退出”的清晰状态机，保持设备纯净（设备不是草稿纸）。
4. **AI 辅助分析的上下文安全隔离（防网关误拦截）**：归档文档若包含高危历史命令序列或原始利用细节，被整篇拉入会话上下文时易触发上游云端网关（`daily-cloudcode-pa.googleapis.com`）的内容安全拦截；文档记录与工具分析应坚持纯架构、内存生命周期与防御排障视角，按需分段读取。

## 上游致谢与链接

- [CyberMeowfiaNS](https://github.com/XingChenRS/CyberMeowfiaNS) —— 6.12.58 参考源码与案例矩阵
- [RootMyVivo](https://github.com/zenyxx-xd/RootMyVivo) / [RootMyVivo-Payloads](https://github.com/zenyxx-xd/RootMyVivo-Payloads)
- [GhostLock-App](https://github.com/YuKongA/ghostlock-app) —— 提取器 / CLI / 内核 profile 框架
- [KSuRoot (hmascs)](https://github.com/hmascs/KSuRoot) —— 122 条厂商载荷 / 载荷构建 / vr.ko 绕过
- [NebuSec/CyberMeowfia](https://github.com/NebuSec/CyberMeowfia) —— CVE-2026-64560 PoC
- [feiguang-ai/plz110-6.12.69-cve-2026-64560-ksu](https://github.com/feiguang-ai/plz110-6.12.69-cve-2026-64560-ksu) —— 一加 15T 6.12.69 设备验证移植；本仓库 PD2545D 端口的 `bridge-6.12`（uhid+configfs）与 profile 流水线来源（Apache-2.0）
- [DFRoot](https://github.com/diabl0w/DFRoot) / [DirtyFrag](https://github.com/mitschud/DirtyFrag) —— CVE-2026-43284
- [JerryTse-OSS/VIVO-OTA-Tracker](https://github.com/JerryTse-OSS/VIVO-OTA-Tracker) —— OTA 协议参考

## 安全声明

仅用于自有设备的研究记录。内核漏洞利用工具存在导致内核 panic / 重启的风险（本仓库的
所有实机探测均选择了受控/可回退模式，且无持久化写入）。请勿在非自有设备上使用。

**PD2545D 64560 端口的执行顺序**：`tools/pd2545_run.sh` 先跑只读
`--preflight` 闸门，再直接 `--run`，并用 `FG_ROOT_EXEC` 执行 root proof
脚本；vr.ko 的 sys_exit 探针拆除与 tag 清理已内置，**不要求 boot.img**。
风险仍是 r59 与设备私有 respin 的符号/PHYS_DELTA 差：失败可能 panic/重启，
但不写分区、可恢复。2026-10-17 22:25 的 55962 运行已在 attempt 1 喷洒后掉线，
当前设备未回到 USB；恢复后先跑只读 `tools/pd2545_preflight.sh`，确认
`posix_timers` slab 几何和是否重启，再用 `tools/pd2545_watch_dump.sh`
做受控重跑。没有 boot.img/源码包也可以继续，精确化靠设备自身的只读几何。
