# FINDINGS —— X Fold6 (PD2545D/V2545A, MT6993) 研究全记录

日期：2026-10-12 ~ 2026-10-16。设备当前系统：`PD2545D_A_17.1.12.10.W10`（OriginOS 7 / Android 17）。

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
| CVE-2026-64560（POSIX timer UAF，NebuSec） | 晚于 2026-09 | 大概率在漏洞区间 | 一加15T @ 6.12.69 已 device-verified 移植 |

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

- 探针编译：`aarch64-linux-android35-clang -O2 -static -pthread ...`（见 xf6-exploit/，NDK r29）
- 运行：`adb push exploit_xf6_v3 /data/local/tmp/ && adb shell /data/local/tmp/exploit_xf6_v3 --info|--check|--probe-epoll|--probe-pipe`
- OTA 查询：`python tools/live_replica.py`（正式推送开放后应可直接出全量包直链）
- 设备侧探针：`probes/*.java` → d8 编译 → `app_process` 运行（见各文件头部注释）

## 7. 更新日志

- 2026-10-16：carrier 自研推导成功（0x9220）；kernelsnitch 实机验证；race 校准进行中

## 8. fd_graph 路线（2026-10-16 深夜新增）

- 发现 [alfzki/ghostlock-app](https://github.com/alfzki/ghostlock-app)：**vivo X300 Pro（MT6993 / 6.12.58 / android16-6）**
  的 fd_graph（RouteKind=4）完整设备战役仓库；官方 app 无此路线。
- 其 device-gates 记录：**5 PASS / 28 FAIL**；其中 `PROFILE-61258-05-20261004-w1-owner-reorder-PASS`
  = **W1（SELinux 写）实机通过**；所有 FAIL 均安全（无变砖）。最终 bin 打包于 2026-10-11。
- profile.bin 格式（wire v2）已由官方 `NativeProfile.kt` 规范确认并实现解析/重序列化
  （`tools/bin_tool.py`，对原件往返字节级一致）。
- 已生成 `fdgraph/xf6_fdgraph.bin`：他们的全部数据（fd_graph 几何 / X300 Pro 符号偏移 /
  `vr_guard` vivo 反 root 防护块）+ release 串换为本机构建。
- CLI 行为实测：release 不符 → **干净拒绝（零内核接触）**；换串后通过该门即进入完整攻击链。
- **停放点（机主安全闸）**：错配构建攻击（他机构建偏移），最坏可恢复重启；
  **等待机主 GO 后执行**（见 `fdgraph/FDGRAPH-ROUTE.md` 运行命令）。
