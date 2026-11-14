# X Fold3 Pro (PD2337 / V2337A) · 6.1.145 · 载荷已构建（未开火）

2026-10-20。目标是 **vivo X Fold3 Pro**（V2337A / PD2337，OriginOS 6，
构建 `compiler260909164005`，2026-09-09），固件 **16.2.18.6**，安全补丁 **2026-08-01**，
内核 `6.1.145-android14-11-maybe-dirty`。

## 结论

1. **临时 root 的完整载荷已经构建完成**：`payload-vivo.so`（175776 B，
   KSuRoot 4.1.2 的 `libbaseline_6_1.so` + 本机 kallsyms 偏移 + vivo vr.ko 绕过）。
2. **本机内核确认未修复 CVE-2026-43499（GhostLock）**：`remove_waiter()` 仍用
   `current`，不是 `waiter->task`（证据见下）。
3. KSuRoot App 里那句「安全补丁已合并幽灵锁修复」是**只按补丁月份猜的**
   （`PatchLevel.kt`，2026-07 及以上就红叹号），对本机是假警报。
4. **尚未执行任何提权动作**。按用户要求，开火前停下确认。

## 一、全量包与内核产物

- 全量包直链（匿名可下，HTTP Range 正常）：
  `https://sysuptxdl.vivo.com.cn/upgrade/oem/files/202609092046225f10203f190341854f969673bc247160.zip`
  - 9,514,062,330 B（8.9 GiB），`payload.bin` 以 stored 方式存放。
- **zstd 坑**：该 payload 的所有分区都是 `REPLACE_ZSTD`（install_operation type **14**）。
  KSuRoot App 的「解析完整包链接」不支持 14，会报
  `分区 'boot' 里有不支持的操作类型 14`。`extract_partitions.py` 补上了 14
  （用 Python 3.14 标准库 `compression.zstd`），在本机直接就地读取 zip 内的 payload。
- 产物：
  - `boot.img`：100,663,296 B，sha256 `75b1c78936f1952762abbb0e4e560d7836605f256ce39ce9d860a3aed051ad20`
  - `vendor_boot.img`：100,663,296 B，sha256 `8862ca7afae2141f2a0107bf74ab8b45d019f7469f0901f5217a0b39cd40764f`
  - `kernel_image`（boot.img 内裸 Image）：37,837,312 B

## 二、vr.ko 标记（vivo 绕过）

vendor_boot 是 `VNDRBOOT v4`，vendor_ramdisk 为 **LZ4 legacy**（首字节 `02 21 4c 18`），
解压后是 cpio newc（129,641,984 B）。里面有两份 vr.ko：

| 路径 | 大小 | 说明 |
|---|---|---|
| `lib/modules/vr.ko` | 188,016 B | **实际加载**（modprobe 先找 `/lib/modules/<uname -r>/`，不存在则回退平铺） |
| `lib/modules/6.1-gki/vr.ko` | 183,320 B | GKI 变体，非本机加载的那份 |

平铺那份 vermagic 带 `vivo`；按 KSuRoot `VrKoProbe` 的判据扫描得到：

```
tag A = 0x04    tag B = 0x2c
```

与 6.1 族基线（`libbaseline_6_1.so` 编译期 `VR_TAG_A_OFF=4`）一致，无需改写
（构建日志：`changed=false old=0x04 new=0x04 sites=0 anchors=3`）。

## 三、kallsyms 与载荷构建

- KSuRoot 的 `KallsymsFinder`（纯 JVM）在 `kernel_image` 上解析出
  **113,870 个符号**，基址 `0xffffffc008000000`；`_stext=0xffffffc008010000`。
- 关键偏移（与 2026-10-19 自研解析一致，互为佐证）：

```
INIT_TASK_OFF        0x21dfc00
SELINUX_BLOB_SIZES   0x16ef938
SELINUX_ENFORCING    0x2558f40
ASHMEM_MISC_FOPS     0x23930c0  (kallsyms: ashmem_miscs +0x10)
```

- 构建方式：把 `KSuRoot/app/src/main/java/com/kernelpack`（无 Android 依赖）
  用 Kotlin **2.4.0** 编译成 JVM CLI，调用与 App 完全相同的
  `KernelPack.pack(PackRequest(bootImage, baseLibrary=libbaseline_6_1.so,
  baseline=BaselineRegistry.BASELINE_6_1, scheme=VIVO))`。
  入口见 `builder/Main.kt`。
- 结果：偏移 **28/28 全部解析**；**13 项替换成功（39 处）**；12 项该 .so 未引用；
  0 失败。产物 `payload-vivo.so` = **175,776 B**。
- vivo 方案闸门（`VrKoPayloadCheck`）：
  `hasBypass=true`，`vr detag` 日志串在，`add #0x2c` 3 处，`AND ~0x400` 1 处，
  可执行节 57,920 B → **PASS**。

## 四、内核是否修了 GhostLock：**没有**

主线修复 commit `3bfdc63936dd4773109b7b8c280c0f3b5ae7d349`
（*rtmutex: Use waiter::task instead of current in remove_waiter()*），
6.1.y 回移 `d8cce4773c2b`，首个修复版本 **6.1.175**。本机是 **6.1.145**。

反汇编本机 `remove_waiter`（`0xffffffc0090c60a4`，完整见
`remove_waiter_disasm.txt`）：

```asm
mrs      x20, sp_el0            ; x20 = current          ← 修复版应为 ldr x20, [waiter,#task]
add      x22, x20, #0x924       ; &current->pi_lock      (F6_1: task_pi_lock=0x924)
...
str      xzr, [x20, #0x950]     ; current->pi_blocked_on = NULL   (0x950)
...
mov      x5, x20                ; rt_mutex_adjust_prio_chain(..., current)
```

修复版会把 `current` 换成 `waiter->task`。这里三处全是 `current` →
**漏洞原样存在，补丁级别 2026-08 并未包含这个回移**。

所以 App 的红叹号是月份启发式误报，不代表本机构建不可用。

## 五、开火方式（**尚未执行**）

KSuRoot 4.1.2 的 App 流程是（`InstallViewModel.executeExploit`）：

```
helper = libcve43499root.so
helper --run-payload <payload> <helper> <logfile>
env: EXPLOIT_ATTEMPTS=24
     P0_ATTEMPT_TIMEOUT_SEC=45
     EXPLOIT_ATTEMPT_TIMEOUT_SEC=120
成功后：helper --late-load   （装载 KernelSU 驱动）
```

对应到 adb shell 的最小复现（**等用户确认后才跑**）：

```sh
adb push payload-vivo.so /data/local/tmp/payload.so
adb push libcve43499root.so /data/local/tmp/helper
adb shell chmod 755 /data/local/tmp/helper /data/local/tmp/payload.so
adb shell 'EXPLOIT_ATTEMPTS=24 P0_ATTEMPT_TIMEOUT_SEC=45 EXPLOIT_ATTEMPT_TIMEOUT_SEC=120 \
  /data/local/tmp/helper --run-payload /data/local/tmp/payload.so \
  /data/local/tmp/helper /data/local/tmp/exploit.log'
adb shell /data/local/tmp/helper --late-load
```

风险与边界：临时 root 到重启为止；载荷会写内核内存（pid/fops 等），
设计上不影响用户数据；但任何内核利用都存在 panic/重启可能。

## 文件

- `payload-vivo.so` — 本机构建产物（勿用于别的内核串）
- `offsets.json` / `target.h` — 28 项偏移与来源
- `kallsyms.txt.gz` — 113,870 个符号（地址/类型/名）
- `remove_waiter_disasm.txt` — 未修复证据
- `extract_partitions.py` — 支持 REPLACE_ZSTD 的 payload 提取器
- `builder/Main.kt` — 离线构建入口（Kotlin 2.4.0 + KSuRoot kernelpack）
