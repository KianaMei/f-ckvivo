# 内存与后台保留分析：vivo X Fold6（PD2545D / V2545A）

> 采集时间 2026-11-21 21:08–22:15，**全程只读**：没有改设置、没有写设备节点、没有 force-stop、没有重启。
> 现场：OriginOS 7 / Android 17 / `PD2545D_A_17.1.13.20.W10` / MT6993 / 16GB，
> `uptime ≈ 6 天`，KernelSU + Vector 3080，275 个用户态进程。
> 原始采样见 [`logs/`](logs/)，复现脚本 [`tools/memdump.sh`](tools/memdump.sh)。

## 0. 结论

1. **不是"内存不够"，是"占得太满 + 策略互相打架"。** 物理内存 15.0 GiB 的去向能对账到 14.95 GiB，
   没有神秘泄漏；最大的单项是 **zram 自己（约 4.0–4.4 GiB）**。
2. **24GB zram 是 vivo 的公式算出来的**，不是配置错误：`RAM×2 − 磁盘 swap，封顶 24GB`
   （旧版本公式是 `RAM×1`）。这台 16GB → 24 GiB。
3. **写回（zram writeback）开着，但基本没用上**：3GB 后端文件里实际只占 123 MB，
   历史峰值 225 MB，当前 `wb_limit = 0`。
4. **后台被杀的主因是 `single-cleaner`，不是 LMK，也不是任何用户设置。**
   它由 `com.vivo.sps` 发起，读自己的一套 filter 名单，"允许后台高耗电"那张表它不读。
   小红书在采集期间（21:44:43）又被一次性 force-stop 了 3 个进程。
5. **代码里存在天然豁免**：前台、小窗/分屏（`isProcInVivoMultiTask`）、多窗口活动根
   （`isTopProcessInActiveRoot`）命中的进程，`forceStopPackageByRms` 直接跳过。
6. `swappiness`、`mem_limit`、Doze/AppOps 白名单、cached_apps_freezer、phantom procs
   **全部不是这里的问题**，逐条证据见 §6。

## 1. 物理内存预算（能对账）

`MemTotal 15,717,812 kB = 14.99 GiB`。逐项拆到 14.95 GiB（数据见 [`logs/memory.txt`](logs/memory.txt)）：

| 项 | GiB | 来源 |
|---|---|---|
| **zram 压缩池** | **4.02–4.19** | `/sys/block/zram0/mm_stat` 第 3 字段 `mem_used_total` |
| 匿名页 | 2.36 | `/proc/meminfo` AnonPages |
| 文件页 Cached | 2.36 | `/proc/meminfo` Cached |
| Slab | 1.50 | Slab 1,577,328 kB（SUnreclaim 1,293,344） |
| **GPU private** | **1.11** | `dumpsys meminfo` GPU 行的 private 段 |
| DMA-BUF 未映射 | ≈0.75 | DMA-BUF 总 − mapped |
| VmallocUsed | 0.60 | `/proc/meminfo` |
| PageTables | 0.32 | 对应约 55.6 万个 `vm_area_struct` |
| CMA | 0.19 | |
| KernelStack | 0.14 | 275 进程 |
| Free | 0.63 | |
| **合计** | **≈14.9** | vs 14.99 ✅ |

**注意 `dumpsys meminfo` 的两个口径陷阱**（源码在 `/system/framework/framework.jar`
的 `com.android.internal.util.MemInfoReader`）：

```java
public long getKernelUsedSizeKb() {
    long size = mInfos[4] + mInfos[7] + mInfos[12] + mInfos[13] + mInfos[27] + mInfos[28];
    if (!Debug.isVmapStack()) size += mInfos[14];
    long kernelCma = Debug.getKernelCmaUsageKb();
    long kernelGpu = getGpuKernelUsedSizeKb();      // = Debug.getGpuPrivateMemoryKb()
    if (kernelGpu > 0) return size + kernelGpu;     // ← GPU 私有内存被算进 "kernel"
}
```

- 所以 "kernel 4.7GB" **不是内核占用**，里面含 GPU private（本次 1.11–1.24 GiB）。
  真实内核侧按 `/proc/meminfo` 是 Slab + 栈 + 页表 + vmalloc ≈ 1.8–2.4 GiB。
- `dumpsys meminfo` 的 **PSS 合计把 swap 也算进去了**（微信那行 `TOTAL 1.91 GB` 里
  `SwapPss 1.81 GB`），所以它不能当物理占用读。

## 2. zram 24GB 是怎么来的

`/vendor/bin/zramsize_reconfig.sh` 里的 `zwb_size_v5()`：

```sh
zRamSizeMBShow=`expr $RamSizeGB \* 2048`     # 16 × 2048 = 32768 MB
zRamSizeMB=$zRamSizeMBShow
[ -n "$hSwapFileMB" ] && zRamSizeMB=`expr $zRamSizeMB - $hSwapFileMB`   # -2560
[ -n "$zSwapFileMB" ] && zRamSizeMB=`expr $zRamSizeMB - $zSwapFileMB`   # -1536
if [ $zRamSizeMB -gt 24576 ]; then let zRamSizeMB=24576; fi            # 封顶 24GB
```

实测对账：

```text
MemTotal          15,717,812 kB     (16 GB 机型)
zram disksize     25,769,803,776 B  = 24 GiB      ✅
persist.vendor.vivo.zramwb.size = 32768 (MB)      ✅ 扣减前的值
```

**版本差异**（同一脚本）：

| 版本 | zram 大小 | 16GB 机器的结果 |
|---|---|---|
| `zwb_size_v4_1` | `min(RAM×1, 16GB)` | 16 GB，后端 `RAM/4 = 4GB` |
| `zwb_size_v5`（本机） | `RAM×2 − 磁盘swap，封顶 24GB` | **24 GB**，后端 3 GB |

这就是"同样 16G、不同机型体感不同"最硬的结构差异候选。

## 3. 写回：3GB 后端只用了 220MB

厂商自己的控制面在 `/sys/block/zram0/zram_wb/`（不是主线那套 `writeback`），
统计见 [`logs/zram.txt`](logs/zram.txt)：

```text
disksize             25,769,803,776 B   6,291,456 page   (24 GiB)
orig_stored          11,114,586,112 B   2,713,522 page   (10.35 GiB 被换出)
eram_size             3,221,225,472 B      49,152 extent  (3 GiB 后端)
eram_used               128,974,848 B       1,968 extent  (123 MB，4%)
orig_in_backing_dev     231,043,072 B      56,407 page    (220 MB 真在磁盘上)
orig_in_ram          10,883,543,040 B   2,657,115 page   (10.1 GiB 仍在 RAM)
bd_max_used(clean)      235,565,056 B      57,511 page    (225 MB，历史峰值)
wb_limit_enable: 1     wb_limit: 0 page
bd_stat(w/r): 0 / 1104 page
```

结论：**3GB 是上限不是配额，96% 从没用过**。但它是 `/data` 上的文件（分区 930GB，剩 395GB），
**不占内存**，所以它对体感零影响。真正的问题是写回额度为 0、10.1 GiB 冷数据只能压在 RAM 里。

主线文档说明（`Documentation/admin-guide/blockdev/zram.rst`）：

> 一旦设置了 writeback_limit_enable，在管理员通过 writeback_limit 设置预算之前，zram 不允许任何写回。
> 如果闪存设备有大量的写入 IO，它可能会产生闪存磨损问题……

UFS 寿命 `life_time_a/b = 0x01`（JEDEC 最好档），脚本里"寿命 > 0x05 就关 writeback"的分支没触发，
所以这不是磨损保护导致的关闭。

## 4. 谁在吃内存

进程 PSS 榜见 [`logs/top-pss.txt`](logs/top-pss.txt)。按"PSS 含 swap"读会严重高估，正确读法举例：

**微信 `com.tencent.mm`**（pid 16094，采集时）：

```text
VmPeak/VmSize  175 GiB      ← 64 位地址预留，零成本
VmData          8.5 GiB     ← 私有映射，绝大部分从未触碰
VmRSS           396 MB      ← 真正驻留
VmSwap          1.76 GiB    ← 换出到 zram
Threads         382
```

1.48 GB 来自 scudo 分配器且全部换出：

```text
[anon:scudo:secondary]  1,049 MB / 724 段 / 驻留 1 MB
[anon:scudo:primary]      435 MB /  66 段 / 驻留 8 MB
```

→ 微信的真实物理代价 ≈ 105MB 驻留 + 1.76GB/2.6 ≈ **780 MB**，不是 2.4 GB。

线程数对比值得单独记一笔：**小红书 476、微信 382、QQ 363**。

## 5. 后台被杀的链路：single-cleaner

`exit-info` 证据（[`logs/exit-info.txt`](logs/exit-info.txt)）：

```text
X        11:33:10 / 12:00:13 / 13:58:58   stop ... due to single-cleaner
小红书    21:44:43                        3 个进程同一秒被 force-stop
B站      11-20 22:25:04 / 11-21 00:15:51  三个进程同一秒
抖音      18:03:17                        三个进程同一秒
```

代码链路（反编译产物，**不在本仓库**，机器上可复现，见 §9）：

```java
// /system/framework/vivo-services.jar → com.android.server.am.VivoAmsImpl
public void forceStopPackageByRms(String pkg, int userId, String reason, boolean secure) {
    if (applicationInfo != null && isForegroundApp(...))                     z2 = true;  // 跳过
    if (applicationInfo != null && ("cleaner".equals(reason) || "single-cleaner".equals(reason))) {
        if (process.isProcInVivoMultiTask())                                  z2 = true;  // 小窗/分屏
    }
    if (("cleaner".equals(reason) || "single-cleaner".equals(reason))
            && mAms.mWindowManager...isTopProcessInActiveRoot(pkg, i))        z2 = true;  // 多窗口
    if (z2) return;
    mAms.forceStopPackage(pkg, userId, 0, reason, true, null, 1);
}
```

发起方在 `com.vivo.sps`（`/system/app/SuperProcessSystem`，`android.uid.system` + `PERSISTENT`）：

```java
// com.sp.sdk.ams.SpKillPackageHelper
doKillPackage() → SpServiceBinderMgr.getInstance().getAms().killPackage(caller, pkg, reason, ...)
                  // reason 就是字符串 "single-cleaner"
```

它读的 filter 表在 `w/l.java`（`DefaultFilters`），跟"允许后台高耗电"无关：

```java
a(10, "not force stop")            a(11, "not kill")
a(11, "accessibility services")    a(15, "single protect process")
a(20, "single only remove task apps")   a(21, "single only notify apps")
a(100, "static protect task")      a(100, "protect voip state task")
```

filter 数据来自 `CleanerConfig`（`w/j.java`）：`process_list.not_clean_apps` /
`not_kill_process` / `task_list.single_protect_process` 等，由 NRMS 配置下发，
本地只有解析器，没有可写的配置文件。

**用户设置实际写进了哪**（只读可查）：

```text
content://com.vivo.abe.highpower.provider/malicelist   → 后台高耗电名单（需 com.vivo.abe.permission.hp.read）
content://com.vivo.permissionmanager.provider.permission/bg_start_up_apps → 自启动名单
```

实测 X 在名单里：`userKill=0 isUserChoice=1 level=4 maxPower=8.0`，
但 12:00 / 13:58 照样被 single-cleaner 杀。**两张表不互通。**

## 6. 已排除的假设（别重复踩）

| 假设 | 实测 | 结论 |
|---|---|---|
| Doze/AppOps 白名单不够 | 4 个 App 的 standby bucket 全是 `5 = EXEMPTED`；X 的 appops 是 `Default mode: allow` | 已满级，不是问题 |
| 缺 `deviceidle whitelist` | `user` 名单已含 X/小红书/B站/微信/QQ/抖音/Shizuku/Scene/GKD | 已做完 |
| cached app freezer 冻结 | `settings get global cached_apps_freezer` = `device_default` | 不是冻结问题 |
| phantom process killer | `settings_enable_monitor_phantom_procs` = `null`（默认） | 未触发 |
| `swappiness` 太高 | 100；内核文档：默认 60，**对 zram 建议可 >100**（"2x faster → 133"） | **降 60 无依据，已撤回** |
| `mem_limit` 能回收内存 | 文档：超限后写入返回 `-ENOMEM`，**不清理已有页** | 只会更早 OOM，已撤回 |
| 内核真吃了 4.7GB | `getKernelUsedSizeKb()` 把 GPU private 算进去了 | 显示口径问题 |
| 写回 3GB 白给在内存 | `eram_used` 123 MB，占的是 `/data` 磁盘 | 不影响内存 |
| 有人做过 sps 拦截 | `"com.vivo.sps" "single-cleaner"` 搜索 **0 条**；`SuperProcessSystem` 仅 15 条通用问答；AppRetentionHook / ProcessKeepAlive 都不适配 vivo | 无先例 |

## 7. 与 X Fold3 Pro 的差异（待验证）

用户体感：同样 16GB、同样用法，X Fold3 Pro 保后台明显更好。目前只有假设，未实测：

| 项 | X Fold6（本机 / OS7） | X Fold3 Pro（待测） |
|---|---|---|
| zram 策略 | v5：`RAM×2` 封顶 24GB | 若 OS 较老则是 v4：`RAM×1` = 16GB |
| SoC | MT6993 | 高通（型号待记） |
| 清理器 | `com.vivo.sps` + single-cleaner | 待查 |
| 模块栈 | Vector 3080 + Zygisk + Shamiko + GKD + Scene + clash + Shizuku | 大概率原厂 |

**必须用同一套只读采样**，比这 6 个量：`disksize`（判断 v4/v5）、`zram_wb` 的
`wb_limit`/`orig_in_backing_dev`、`swappiness`、`page-cluster`、`mem_used_total`、
`exit-info` 里 single-cleaner 的次数。

## 8. 计划

- **P1｜两台对比**：Fold3 Pro 插上后跑 `tools/memdump.sh`，产出并排表，确认差异来自
  zram 公式、清理策略还是模块栈。这是唯一能把体感变成证据的做法。
- **P2｜探针（未授权，先不做）**：若 P1 确认 OS7 是主因，再考虑在 `com.vivo.sps` 内挂
  **只打日志**的探针，验证 `SpKillPackageHelper.doKillPackage` 在 X 被杀那一刻确实被调用。
  注意：`com.vivo.sps` 是 `android.uid.system` + `PERSISTENT`，崩了会重启但会连累清理/加速功能；
  全网无先例，风险自担。
- **P3｜不做的事**：hook AMS/system_server（红线）、改 zram 大小/`mem_limit`（需重启且丢 root）、
  反复补 `writeback_limit`（拿 UFS 寿命换 RAM，且本机 3GB 后端只用了 4%）。

## 9. 复现

```sh
# 只读采样（本目录）
./tools/memdump.sh

# 关键节点
adb shell 'dumpsys meminfo | tail -9'
adb shell "su -c 'cat /sys/block/zram0/zram_wb/stat_readable'"
adb shell "su -c 'cat /proc/swaps'"
adb shell "su -c 'cat /sys/ufs/life_time_a'"
adb shell 'dumpsys activity exit-info com.twitter.android | grep -B8 single-cleaner'

# zram 策略脚本
adb shell "su -c 'cat /system/bin/zramsize_reconfig.sh'"

# 反编译 vivo-services.jar 看 single-cleaner 链路（本机临时目录，不入库）
adb pull /system/framework/vivo-services.jar
jadx --single-class com.android.server.am.VivoAmsImpl vivo-services.jar
```

## 10. 弯路记录

- 先用 `dumpsys meminfo` 的 "kernel 4.7GB" 判断内核异常 → 实际是 `MemInfoReader`
  把 GPU private 加进来了。
- 用 "PSS 合计 15.2 GiB" 判断进程占满物理内存 → PSS 含 swap，不能这么读。
- 建议"降 swappiness / 设 mem_limit 回收 1GB" → 两条都被内核文档否定。
- 把 zram writeback 说成"vivo 配了却关着、白给 3GB" → 实际是上限文件、占磁盘，
  只用了 4%，且当前额度 0。
- 判断"内存不够" → 实际是 275 进程 + 24GB zram + 图形 2GB 的组合，不是容量问题。

**教训：`dumpsys` 的数字先确认口径，再下结论；内核行为和厂商策略要分开看。**
