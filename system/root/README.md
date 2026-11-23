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
