# FINDINGS —— X Fold6 (PD2545D/V2545A, MT6993) 研究全记录

日期：2026-10-12 ~ 2026-10-17。设备当前系统：`PD2545D_A_17.1.12.10.W10`（OriginOS 7 / Android 17）。

---

## 1. 设备与环境事实（实测）

| 项目 | 值 |
|---|---|
| 内核 release | `6.12.58-android16-6-gdd6fd5c98918-abogki543627742-4k` |
| 构建时间 | 2026-08-05（clang 19.0.1, LTO+BOLT+PGO） |
| 安全补丁 | 2026-09-01 |
| verified boot | green（user/release-keys） |
| 页大小 / MTE | 4096 / 未启用 |
| fingerprint | `vivo/PD2545/PD2545:17/CP2A.260605.016_V000L1/261012214913:user/release-keys` |
| SoC | MT6993（`ro.boot.hardware=mt6993`） |

### /proc & sysfs 可见性矩阵（shell 域）

| 路径 | 结果 |
|---|---|
| `/proc/version`、`/proc/cmdline`、`/proc/uptime`、`/proc/meminfo`、`/proc/slabinfo` | ✅ 可读 |
| `/proc/kallsyms` | ❌ Permission denied（cat/head/dd 全拒） |
| `/sys/kernel/btf/`（整个目录） | ❌ Permission denied |
| `/proc/iomem`、`/proc/bootconfig` | ❌ Permission denied |
| `/sys/block/sda/device/` | ❌ Permission denied |
| `/dev/block/by-name/boot_a` | ❌ Permission denied |
| `/data/ota_package/` | ❌ Permission denied（下载包落盘处，root-only） |

另有：`adb root` 拒绝（production build）；vivo recovery **不提供 adb**（实测：`adb reboot recovery` 后无任何 USB adb 端点）；全机无 debuggable 系统应用。

## 2. OTA API 研究（sysupgrade.vivo.com.cn）

- 协议：jvq_param = AES-128-CBC（密钥自 libvivoseckey_n4.so 提取）+ 自封装头，原生协议复刻可用。
- 真机参数真值（脚本常见推导错误已修正）：`model=PD2545`、`hwVer=PD2545MA`（无 D）、
  `version=PD2545_N_PD2545MA_...`、`fullVer=PD2545D_A_...`（有 D）、`mtype=FULL_SC`、`vgcSwVer=17.1.1`。
- 常规接口 `/vgc/v2/getVgcAndPatch.do`：本机全基准（16.0.x/17.x）批量重放 **retcode 210**（尝鲜图未开放）；
  对照机型 V2408A 匿名出包 → 接口本身正常。
- 尝鲜接口 `/beta/queryBetaOrTaste.do`：匿名重放被**接受**（retcode 0）但无数据 —— 门槛在设备/账号侧。
- 升级器 UI 逆向（jadx）：参数全集、`persist.sys.isfull.value` 等调试开关（setprop 被 SELinux 拒）、
  `LocalUpgradeActivity` 等组件清单（无任何文件导出通道）。

### VDFP 指纹（hwFingerprint）调用链 —— 对 shell 完全封闭（证据级）

1. `SecurityKeyUtils.invokeCmd` → `SecurityKeyProxy.vivoSecurityKeyOperate`
2. 首先 `ServiceManager.waitForDeclaredService("vendor.vivo.hardware.sectee.ISecTee/default")`
   —— 该服务在 `service list` 可见，但为**惰性声明服务**：`getService`=null，
   shell 触发不了拉起（`setprop ctl.start` 被拒），首次事务即 DeadObject（"small parcel"）。
3. 回退 HIDL `vendor.vivo.hardware.sectee.V1_0.ISecTee.getService()` → shell 不可见（NoSuchElementException）。
4. 调用签名已完全解码：`(int mode, int operateType, int encryptType, int keyType, String pkg,
   int keyVersion, String keyName, String pubKey, byte[] data, int dataLen, ISecurityKeyCB cb)`；
   签名公钥已从 Updater.apk 提取（X.509 SPKI Base64 已备）；**服务本体不可达，路线关闭**。

## 3. 被封锁渠道清单（全部实测）

| 渠道 | 结果 |
|---|---|
| PC 代理抓手机流量 | `settings put global http_proxy` 被静默拒绝；App 侧 HttpURLConnection 硬编码 `Proxy.NO_PROXY` |
| TLS 解密 | 需系统 CA → 需 root → 不可得；全流量 https |
| logcat 抓 URL | 升级器进程在真实下载窗口 **0 行日志**（release 版抑制 app 日志），update_engine 亦无 URL |
| 读已下载包 | `/data/ota_package` root-only；且 398MB 那次是**增量包**（非全量），装完已自动删除 |
| 读 boot 分区 | root-only |
| recovery 模式 | vivo recovery 无 adb 端点 |
| CDN 枚举 | 阿里云 OSS 私有桶（AccessDenied）；pkName 为不可猜的随机 id |
| 官方全量包页面 | 无（新机型不提供公开 ROM 下载） |
| 售后/维修渠道 | chimeratool 等站当前仅到 16.0.x（`PD2545C_A_16.0.15.6`），未见 17.x |

## 4. 漏洞生态地图（2026-10-16 全网盘点）

| CVE | 修复线 | 本机构建状态 | 生态 |
|---|---|---|---|
| CVE-2026-43284（IPsec ESP page-cache，DFRoot） | 2026-09 补丁已含 | **本机实测 NOT VULNERABLE** | 一加15T/13 等已移植 |
| CVE-2026-43499（futex PI UAF，GhostLock） | 6.1.175 / 6.6.140 / **6.12.86** | 6.12.58 « 6.12.86，**在漏洞区间** | RootMyVivo/GhostLock/KSuRoot 多工具；**iQOO 15(PD2505) @ `g0a092cc0037a` 已实机 RESULT PASS（fd_graph 路线）** |
| CVE-2026-43074（eventpoll/page-cache，CyberMeowfia） | — | 未知 | **PD2502/PD2564/PD2545 @ 6 月内核 DEVICE_PASS/OPERATOR_CONFIRMED**（PD2545 即本机型！） |
| CVE-2026-64560（POSIX timer UAF，NebuSec） | **mainline 2026-07-11 / vanilla 6.12.100（07-30）** | **本机在漏洞区间**（内核二进制 2026-06-10 编译，早于修复合并）—— 见 `ROUTES.md` §4.1 | 一加15T @ 6.12.69 已 device-verified 移植；参考 PoC Pixel 10 Pro 成功率 >50% |

**关键结论**：MT6993 系的 **6 月内核世代（`g0a092cc0037a-abogki521987229`）已被社区全面攻破，包括本机型 PD2545**；
**8 月世代（`gdd6fd5c98918-abogki543627742`）无任何公开载荷/偏移** —— 本仓库工作即围绕这一代差展开。

### KSuRoot 4.1.2 解剖结论

- 129 个动态库载荷：vivo 全部为 **6.6.x 家族**（6630/6657/6689/66127 等），**无任何 6.12 vivo**；
- 6.12 路线架构 = 基线引擎 + ~24 个符号偏移 + KMI 结构偏移 + `TARGET_ASHMEM_FOPS_UNSYMBOLED`
  （ashmem Rust 重写后从 miscdevice fops 槽运行时读取）；
- vivo vr.ko 反 root per-task 标记（实测值）：**6.1=0x04，6.6/6.12=0x06**，tag B=0x2c
  （来源：`expected_vr_tags.tsv`，6.12 值实测于 iQOO 15 / `g0a092cc0037a`）；
- 「载荷构建」需 boot.img / vendor_boot.img / OTA zip（取 kallsyms + vr.ko）→ 无镜像不可用；
- 零命中闸门 = **构建期**安全门（偏移零命中时拒绝出包）。

## 5. 实机校准里程碑（2026-10-16 夜）—— 无镜像依赖

将 CyberMeowfiaNS `pd2502-6.12-reference` 源码适配本机（改 release 门 + 自研 carrier），
产物 `xf6-exploit/build/exploit_xf6_v3`（362KB 静态 aarch64），手机 `/data/local/tmp/` 运行：

| 阶段 | 结果 | 说明 |
|---|---|---|
| `--info` | ✅ PASS | release_match=1（放开门后）、页 4K、无 MTE、指纹检查禁用 |
| `--check` | ✅ **PASS** | **carrier 偏移自研推导**：从设备可读库解析 ELF `.init_array[0]` → 新偏移 **0x9220**（旧 0x9410），size `0x10c00`，preimage 带 `bti c`。写入 `target.h` 两处 define + `main.c` preimage 后全绿（`kernel_write=0`） |
| `--probe-epoll` | ⚠️ 混合 | **kernelsnitch 时序原语实测工作**（futex 哈希碰撞地址真实找到）；但 MCAST race **256 轮 0 命中**；每轮 `trigger_errno=17`（已定位：trigger 的 `epoll_ctl ADD` 返回 EEXIST——fd 已在 epfd 中，第 0 轮即如此，**疑似原设计固有形态**，非主要干扰项） |
| `--probe-pipe` | ⚠️ 同上 | pipe rediect 256 轮 0 命中，同一 EEXIST 形态 |
| `--probe-epoll` 复测（vivo「停止限制子进程」开启后） | ⚠️ 同上 | 仍 0/256；子进程限制器非主因（log: `logs/probe_epoll_run2.log`） |

设备全程零损伤（受控页设计：`persistent_target_write=0`），可正常使用。

### 校准定论（2026-10-16 深夜）

对照上游 `docs/public/pitfalls/PITFALLS_AND_NEGATIVE_RESULTS.md`：
本机现象（kernelsnitch/collision 正常 → known-page 回收标记 256 轮 0 命中 → 无 panic 稳定停止）
**与该文档记录的 `RUNTIME_BLOCKED` 特征一字不差**（先例：PD2564 8.8 vs 11.7，同机型不同构建，
上游团队亦未仅靠调参修复该类）。结论：**6 月构建的常数无法平移到 8 月构建，
需要为本构建（`gdd6fd5c98918`）做完整 profile 推导**（上游 `extract_rs` / KSuRoot「载荷构建」
流程，均需内核 Image）。已实测项：

- ✅ kernelsnitch 时序原语（构建无关）
- ✅ KMI 结构偏移（与 iQOO 15 可用 profile 逐字段吻合）
- ✅ carrier（自研推导 0x9220）
- ❌ race 运行时几何（6 月常数，`RUNTIME_BLOCKED`）
- 🚧 vivo「停止限制子进程」已开启（对复测无影响，保留备用）

### 已知待校准旋钮

1. **CPU 核心对**：当前配置照抄 PD2502（main=1 / trigger=0），MT6993 拓扑不同，需实测同簇对
2. **trigger EEXIST(17)**：疑似 MCAST 参与者未正确构造/清理，或组地址固定导致重复加入
3. **最终写目标**：`selinux_image_off=0x27c6520` 为 6 月值 —— 待原语打通后靠运行时回读校验，
   必要时用读原语现场推导（本构建的符号表）

## 6. 复现指引

- 探针编译（**2026-10-16 实测通过，可复现**）：`./xf6-exploit/build.sh` → 产物 `build/exploit_xf6_v3`，
  `362296` 字节，`sha256 16c2fd33bfd0ae4a07e534c36ac70f22afba027d458b238e1097e3f18017b600`。
  参数逐字取自上游 `build_vivo.cmd`；需 NDK r29（实测 `29.0.14206865`），
  可用 `ANDROID_NDK_ROOT` 指定。**`-Werror` 下零告警**（上游门槛要求）。
- 运行：`adb push exploit_xf6_v3 /data/local/tmp/ && adb shell /data/local/tmp/exploit_xf6_v3 --info|--check|--probe-epoll|--probe-pipe`
- OTA 查询：`python tools/live_replica.py`（正式推送开放后应可直接出全量包直链）
- 设备侧探针（**2026-11-28 已删，源码在 git 历史**）：原 `probes/*.java` 是 d8 编译后 `app_process` 跑的
  一次性探针——`DfpProbe*` 对 vivo 私有服务发 binder 事务查设备指纹，`UfsId` 反射 `android.util.FtDeviceInfo`
  取 UFS CID。结论已进本文件，重做时按同样套路（d8 → app_process）几分钟可复原。

## 7. 更新日志

- 2026-10-16：carrier 自研推导成功（0x9220）；kernelsnitch 实机验证；race 校准进行中
- 2026-10-17：**授权后首次全量实机采集**（详见 `ROUTES.md` §9）——修正 `target.h` 两个上游残留
  过期 pin（`TARGET_VERSION` Jun10→实机 Aug 5 14:45:35；`TARGET_CARRIER_SHA256` 9a046a14→
  实机 740c53b4）；自研 carrier 偏移 0x9220 获得本地拉取逐字节复核；dumpstate 三服务
  `user root` 实机确认；SPL 2026-09-01（十月公告未装）；vr.ko 已加载但文件在 shell 不可见
  位置（疑 vendor_boot initramfs）；CPU 拓扑 4+3+1 实测（cpu7 独簇）

## 8. fd_graph 路线（2026-10-16 深夜新增）

- 发现 [alfzki/ghostlock-app](https://github.com/alfzki/ghostlock-app)：**vivo X300 Pro（MT6993 / 6.12.58 / android16-6）**
  的 fd_graph（RouteKind=4）完整设备战役仓库；官方 app 无此路线。
- 其 device-gates 记录（**2026-10-16 复核修正**）：实为 **23 条闸门，1 PASS / 22 FAIL**。
  > 此前写的"5 PASS / 28 FAIL"及"`PROFILE-61258-05` = **W1（SELinux 写）实机通过**"**均已作废**——
  > 上游该记录原文写着 **「W1 仍未翻 SELinux」**，PASS 的内容只是 PI 竞争窗口建立
  > （`CMP_REQUEUE_PI` 由 `EDEADLK` 变 `ret=1`），当轮 `chain_hits=0 bit4_hits=0`、
  > `enforce(after)=1`。批次 D（投递原语）10-04 实现后，-18…-24 闸门**全部 FAIL**，
  > -24 明确"reclaim 仍未证"；最后一次闸门标注"adb 推迟中——未执行"。逐条核对见 `ROUTES.md` §1。
  > FAIL 系列确为**安全失败**（无变砖）这一点成立。最终 bin 打包于 2026-10-11，但**该路线的写入原语从未命中过**。
- profile.bin 格式（wire v2）已由官方 `NativeProfile.kt` 规范确认并实现解析/重序列化
  （`tools/bin_tool.py`，对原件往返字节级一致）。
- 已生成 `upstream-analysis/ghostlock-fdgraph/xf6_fdgraph.bin`：他们的全部数据（fd_graph 几何 / X300 Pro 符号偏移 /
  `vr_guard` vivo 反 root 防护块）+ release 串换为本机构建。
- CLI 行为实测：release 不符 → **干净拒绝（零内核接触）**；换串后通过该门即进入完整攻击链。
- **停放点（机主安全闸）**：错配构建攻击（他机构建偏移），最坏可恢复重启；
  **等待机主 GO 后执行**（见 `upstream-analysis/ghostlock-fdgraph/FDGRAPH-ROUTE.md` 运行命令）。

---

## 9. 2026-10-17 路线重判（证据级，取代 §5 的 RUNTIME_BLOCKED 定性）

> 本节回答"43074/43499 的 0 命中到底是几何不对，还是根因已经不存在"。
> 结论：**两条都已修**，0 命中是根因不存在；**64560 是唯一存活线**。

### 9.1 修复时间线与设备构建

设备内核 `6.12.58-...-gdd6fd5c98918-abogki543627742-4k`，`uname -v` 为
**2026-08-05 14:45:35 UTC**，SPL **2026-09-01**。设备的 `gdd6fd5c98918`
**不在** GKI 2025-12 的公开 respin 表里；公开表最后一轮是
`android16-6.12-2025-12_r59`（**2026-07-28**，内核 6.12.58），r60 之后是私有
安全 respin（拉取 404）。因此 r59 是设备构建前**最近的可核对公开基线**。

| CVE | 官方修复标题 | stable 修复版本 | ACK 2025-12_r59 实测 | 对设备 |
|---|---|---|---|---|
| 43074 | `eventpoll: defer struct eventpoll free to RCU grace period` | **6.12.83** | `fs/eventpoll.c` 已含 `struct rcu_head rcu` 与 `defer freeing past ep_get_upwards_depth_proc() RCU walk` 注释 | 修复在 4-22 / 5-06 发布，8-05 构建在其后 → **已修** |
| 43499 | `rtmutex: Use waiter::task instead of current in remove_waiter()` | **6.12.86** | `kernel/locking/rtmutex.c` 的 `remove_waiter()` 已用 `waiter_task = waiter->task`，并带 `waiter::task != current` 注释 | 修复在 6-12.86，8-05 构建在其后 → **已修** |
| 64560 | `posix-cpu-timers: Prevent UAF caused by non-leader exec() race` | **6.12.100**（7-30） | `kernel/time/posix-cpu-timers.c` **没有** `timer_lock_sighand`（grep 0），仍是 `pid_task → lock_task_sighand` 旧形态 | 修复在 7-30 / 十月公告；r59 是 7-28 → **大概率仍存活** |

证据来源：CVE Services / NVD（标题与 fixed 版本）；GKI 2026-12 发布页
（r59 日期）；AOSP ACK 分支 `refs/tags/android16-6.12-2025-12_r59` 的
`eventpoll.c` / `rtmutex.c` / `posix-cpu-timers.c` 原文（`?format=TEXT` 拉取）。

### 9.2 对旧结论的修正

- §5 的 `RUNTIME_BLOCKED`（PD2564 8.8 先例）**不再适用**：本机 0/256 的
  43074 MCAST race 与 43499 race，和"修复已进构建"的解释一致；继续调
  CPU 对/延迟/轮数**不会**把根因调出来（PITFALLS 的停止规则）。
- `fd_graph` 与 carrier page-cache 路线**同时**依赖 43074，因此一并关闭。
- **唯一存活**：64560。其修复（6.12.100 / 十月公告）晚于 r59，也晚于设备
  8-05 私有 respin 的构建日；除非 vivo 在该私有分支提前 cherry-pick，否则
  设备仍带漏洞。
- **残余不确定性**：私有 respin 是否完整继承了 r59 的修复。设备侧可用
  `/sys/kernel/tracing/available_filter_functions` 查 `timer_lock_sighand`
  / `ep_free` 是否存在（`tools/pd2545_preflight.sh` 已包含该只读检查）；
  拿不到 tracefs 时，只能以下一节的设备 Image/BTF 为准。

## 10. r59 公开符号/BTF 与 PD2545D 64560 端口（2026-10-17）

### 10.1 符号来源

- 公开 GKI `android16-6.12-2025-12_r59` 的 **certified boot** 与
  **debug boot**（`dl.google.com/android/gki/gki-*-boot-android16-6.12-2025-12_r59.zip`）。
- **符号来源陷阱（本会话踩到并已修正）**：`boot-6.12-allsyms.img`
  （debug，`image_size=0x36a0000`）与 `boot-6.12.img`（certified 量产，
  `0x2820000`）是**不同布局的两次构建**；all-syms 的 151,293 个地址
  （连 `posix_cpu_timer_del` 都是 `0xffffffc0805cfbcc` vs certified
  `0xffffffc080519334`）**不能用于设备**。本表与 PD2545D 端口全部改用
  **certified Image**（`boot-6.12.img.kernel`，sha256 `fe3ef364…`，
  **128,886 符号**），静态基址 `0xffffffc080000000`。
- certified Image 的 BTF 在文件偏移 `0x19ea3e4`（类型 164,804），给出
  6.12 的 `task_struct`、`cred`、`signal_struct`、`pipe_buffer`、
  `file_operations`、`miscdevice`、`k_itimer`、`configfs_buffer` 布局。
- 6.12 关键偏移（与一加 15T 6.12.69 移植逐项一致）：
  - `task_struct`: `tasks=0x638`、`pid=0x708`、`tgid=0x70c`、
    `group_leader=0x748`、`real_cred=0x8f8`、`cred=0x900`、`comm=0x910`、
    `thread_node=0x7b8`；`signal_struct.thread_head=0x10`。
  - `cred` 仍是 `0xb8`（Pixel 的 `DP_CRED_*` 全部不变）。
  - `file_operations`: `read_iter=0x28`、`write_iter=0x30`、
    `unlocked_ioctl=0x50`、`open=0x68`、`release=0x78`；`pipe_buffer=0x28`。
  - `kmalloc` CG8K 槽位 `0x68`；`configfs_buffer` 的 `page=0x10`、
    `needs_read_fill=0x50`、`bin_buffer=0x58`、`bin_buffer_size=0x60`。
- `init_task.thread_node` 与 `init_signals.thread_head` 正是 64560 里的
  `LINK_CYCLE_C/D` 双节点环：r59 分别为 `0xffffffc08253e278` /
  `0xffffffc08253ef10`（`init_task+0x7b8`、`init_signals+0x10`）。

### 10.2 6.12 后桥：Rust ashmem 已不可用，改 uhid + configfs

一加 15T（PLZ110 / 6.12.69）设备验证移植确认：
`drivers/staging/android/ashmem.rs` 把 C ashmem 换成了 Rust；Rust 版
`ASHMEM_SET_NAME` 只把 `(ptr,len,cap)` 写进对象，用户串落在另开的堆缓冲，
原 PoC 的"把受控字节写进 `file->private_data + 11`"在 6.12 上**没有 ioctl
能做**。该移植把桥整条换成 **uhid + configfs**（`bridge-6.12`）：

- 写通道：`open=uhid_char_open`、`write=uhid_char_write`（把用户数据拷到
  `file->private_data + 0x50`）、`write_iter=configfs_bin_write_iter`；
- 读通道：`open` 留空（`private_data` = 伪造记录）、
  `read_iter=configfs_read_iter`，每次读前用写通道摆好 `count/page`。

- **vivo vr.ko 两层中和已内置**：PreSpawn 将
  `__tracepoint_sys_exit.funcs`（certified `0xffffffc0826d8de0`，`funcs` 偏移
  `0x48`，来自 `__traceiter_sys_exit` 反汇编）置 0，拆掉 sys_exit 击杀探针；
  PostSpawn 先清 root child 的 `task+0x0` flags 字（tag A + 0x400），再清
  `task+0x2c` tag B。顺序与上游一致。

本仓库的 PD2545D 端口（`pd2545-64560/`）以该 6.12 载荷为基底，替换为 r59
符号与上面的 6.12 偏移；保留了 Apache-2.0 归属（见该目录 `NOTICE`）。

### 10.3 端口状态与安全闸

- 产物：`pd2545-64560/build/cve-2026-64560-pd2545`，
  **517,560 B**，静态 AArch64，无 `PT_INTERP`；
  sha256 `7347dbaa1343be250d7f8f43259d62c7a8853b56ee652c423090efb62e3f9f4e`。
- profile 识别：`uname -r` 含 `dd6fd5c98918` → `pd2545-dd6fd5c98918`；
  指纹 `vivo/PD2545/PD2545:17/CP2A.260605.016_V000L1/261012214913:user/release-keys`。
- `--preflight` **只读**：只做身份/limits/slab 基线的检查，不启动 race。
- **r59 漏洞存在性（独立判据）**：`repro/fgroot.py vuln-check` 在 certified
  r59 Image（sha256 `fe3ef364…`）上输出 **`status=VULNERABLE`**：
  `timer_lock_sighand` 不存在；`posix_cpu_timer_del/set/rearm` 都是
  `pid_task → __lock_task_sighand` 且无回到 `pid_task` 的回边。
  证据：`pd2545-64560/evidence/r59-vuln-check.json`。设备私有 respin 仍需
  tracefs/`timer_lock_sighand` 或精确 Image 才能同判。
- **直接 root 尝试**：`tools/pd2545_run.sh` 先跑只读 `--preflight`，再以
  `FG_ROOT_EXEC` 执行 root proof 脚本；vr.ko 两层已内置，**不要求 boot.img**。
  剩余风险是 r59 certified 与设备私有 respin 的符号/PHYS_DELTA 差——失败可能
  panic/重启（不写分区，可恢复）。boot.img/全量包是失败后精确化的**兜底**，
  不是前置。
  - PHYS_DELTA 仍按 `target.h` 的 `TARGET_KERNEL_PHYS_LOAD_DELTA=0` 计算
    （`direct.bootidParent=0xffffff800265e198`、
    `direct.cycleC=0xffffff800253e278`）；真实值在 `/proc/iomem`（root-only）。
  - 私有 respin `dd6fd5c98918` 与 r59 的符号差由 `LINK_INIT_CRED`/`init_task`/
    `VR_GUARD_*` 等 gate 在运行时暴露。

### 10.4 下一步

1. 设备连上后先跑 `tools/pd2545_preflight.sh`（只读；`--preflight` +
   tracefs/iomem/uhid/limits 采集）。
2. **不要更新系统**（10/23-29 的 OTA 会换内核，64560 窗口可能关闭）。
3. 精确符号来源优先级：**当前构建的全量包/boot.img** > 设备
   `/proc/iomem` 一次性 root 只读 > r59 基线 + gate 验证。
4. PHYS_DELTA/符号确认后，才考虑 `--run`；在此之前只跑 preflight。

## 11. 2026-10-17 22:25 `timer_goal=55962` 实机运行：attempt 1 喷洒后设备掉线

### 11.1 本轮改了什么

只把 PD2545 profile 的 `TIMER_GOAL` 从 **55478** 改为 **55962**
（`floor(Max pending signals 57842 × 0.9675)`），CPU/delete mask 与其他几何不动。
产物 `aa13e1ea…`，运行模式仍是 **`--dump-kallsyms`（只读桥）**，未进入
stage8 的 cred/SELinux/root 阶段，未写用户分区。

### 11.2 实际输出

attempt 1 走到 `LANE_DONE`（`timers=55962 deleted=55962 errors=0`，
`exec_overlap=1`），随后：

```text
SLAB_DRAIN_STRICT_PASS attempt=1 active=745 total=864 baseline=777/896 wait_ms=10273
STAGE0_DIRTY_ARM attempt=1
SPRAY_DONE attempt=1 valid=1 pairs=6000 fragments=48000 errors=0
SPRAY_SLAB attempt=1 posix_timers active=745 total=864
```

之后没有任何 `STAGE0_RAW_DIFF`，`adb shell` 以 **255** 结束；设备从
`adb devices`、`fastboot devices` 和 Windows USB 枚举中**同时消失**，
mDNS 无 adb 服务，持续 5 分钟以上未回来。运行前的 `boot_id`
（`4e7993b5…`）与上一轮相同，说明这轮开始前设备没有重启；现在无法确认
是 panic 重启、内核卡死，还是物理断电/拔线。

### 11.3 与上一轮的关键差异

- 上一轮（55478）连续 25 次 attempt 都走到 `STAGE0_RAW_DIFF … frags_with_diff=0`，
  drain 走 **LOOSE** 分支、等待约 20 s；设备全程在线。
- 本轮启动时 `SLAB_BASELINE=777/896`（上一轮 25 次后残留的内核 timer
  对象把基线抬高），strict 判据因此只等 **10.27 s** 就放行喷洒。strict
  判据本身仍要求 `active <= baseline+64`，但基线被抬高后它比真实的
  “干净基线 256/256”宽松，不能单独证明 RCU/回收已经干净。
- 本轮唯一新变量是 `timer_goal=55962`；掉线发生在 attempt 1 的喷洒完成后、
  读回 fragment 之前。

### 11.4 两种解释（尚未定论）

1. **真实命中**：55962 让 race 第一次命中，freed `k_itimer` 被 48000 个
   fragment 之一回收；内核在处理伪造 timerqueue 节点时崩溃，用户态没来得及
   打印 `STAGE0_RAW_DIFF`。若成立，这是目前最强的“原语开始生效”信号，
   但崩溃点说明 PD2545 私有 respin 的收尾/几何仍有不匹配。
2. **非命中**：LMKD/OOM 杀掉 shell/adbd，或设备被物理断电/拔线。单纯
   adbd 被杀通常会重连；整机在 adb/fastboot/USB 三层同时消失更像内核级
   停止或断电。

### 11.5 已排除/已核对

- r59 BTF：`struct k_itimer` **size=0x100**，`it` 联合在 **0x78**，
  `cpu_timer.node` 在 **0x78**，`timerqueue_node.expires` 在 **+0x18**。
  载荷的 `FAKE_CPU_TIMER_FIRST_OFF=0x78`、stride `0x100`、
  `EXPIRES_OFF=0x18` 与 r59 完全一致。
- **设备自身 slabinfo 已给出相同几何**（10-17 20:21 只读 preflight）：
  `posix_timers_cache 256 256 256 32 2` ⇒ objsize **256 (0x100)**、
  **32 objs/slab**、**2 pages/slab (order-1)**。与 8 KB fragment、
  0x100 stride 完全吻合，fake 节点不存在尺寸/步长错位。
- 新运行 `SLAB_BASELINE=777/896` 对应 **28 个 slab**，而干净态是
  `256/256`（8 个 slab）；上一轮 25 次 attempt 留下了约 521 个未归还的
  timer 对象，把 baseline 抬高，才让 strict 判据在 10.27 s 提前放行。
  这是一条独立于 race 的污染证据，下一次应先重启到干净基线。
- 设备 config：`KASAN_HW_TAGS=y`，`KASAN_GENERIC/SW_TAGS` 均未开，
  `SLUB`/`SLAB_BUCKETS`/`FREELIST_HARDENED` 不改变 `k_itimer` 的字段偏移；
  `DEBUG_SPINLOCK`/`LOCKDEP` 未见开启。
- `PHYS_DELTA=0` 与上游 PD2502 golden route 的 `target.h`
  （`TARGET_KERNEL_PHYS_LOAD_DELTA=0`、`DIRECT_MAP_ALIAS=base+image_offset`）
  同型；PD2545 的 `direct.cycleC` 也正好等于 `base + (LINK_CYCLE_C-image_base)`。
  因此“直接映射整体错 0x40000000”不是首要解释。

### 11.6 设备回来后先做的只读检查

1. `boot_id`/`uptime`/`ro.boot.bootreason`/`sys.boot.reason`：判断是否重启。
2. `/sys/fs/pstore`、`/proc/last_kmsg`（能读则读）：找 panic/oops 的
   调用栈或 fault 地址；若为空，说明更像卡死/断电而非可记录的 panic。
3. `/sys/kernel/slab/posix_timers/{object_size,objs_per_slab,order,slab_size}`
   与 `/proc/slabinfo`：确认设备实际对象尺寸/每 slab 对象数是否仍是
   `0x100 × 32`（order-1）。若不是，先重算 fake 节点 stride，禁止重跑 race。
4. 若以上都正常，重跑时强制 **20 s drain**（不因抬高后的 baseline 提前
   strict 放行），并在主机侧 1 s 轮询 `adb devices`、同时抓 logcat/panic
   标记，先把“掉线发生在喷洒前/中/后”钉死；喷洒量先减半，避免 OOM 混淆。

