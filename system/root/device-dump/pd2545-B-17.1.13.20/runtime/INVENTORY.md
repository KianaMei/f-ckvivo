# 运行时快照（2026-11-15 12:33，同一开机，uptime 221,762 s）

手机重新连回后补采。全部只读（`su -c 'cat …'` / `adb exec-out`），
没有写入、没有 remount、没有 reboot。

## 1. 物理布局 —— PHYS_DELTA 的答案就在这里

`/proc/iomem`（root 可读，`runtime/iomem.txt`）：

```
80000000-8807ffff : System RAM
  80000000-8209ffff : Kernel code        ← 物理起点 0x80000000
  820a0000-8251ffff : reserved
  82520000-8281ffff : Kernel data
```

- 内核代码物理基址 = **0x80000000**，虚拟基址 = `0xffffffc080000000`
- 这正是 GKI 的假设值 → `TARGET_KERNEL_PHYS_LOAD_DELTA = 0` **基本坐实**
  （最终仍应由 payload 自己的 `PHYS_LAYOUT_PASS` gate 确认）
- `Kernel data` 物理 0x82520000 ↔ 链接期 `_sdata = 0xffffffc082520000`，
  两者尾数完全对齐，进一步佐证线性映射关系

## 2. `posix_timers` slab 几何 —— 64560 喷洒直接可用

```
posix_timers_cache  672  672  256  32  2  : slabdata 21 21 0
                    ↑active ↑total ↑objsize ↑obj/slab ↑pages/slab
```

| 项 | 值 |
|---|---|
| 对象大小 | 256 B（`struct posix_cpu_timer` ≈ 0x100） |
| 每 slab 对象数 | 32 |
| slab 大小 | 2 pages = 8 KiB（order-1） |
| 当前 | 672/672 活跃，21 个 slab |
| `sighand_cache` | objsize 2112，15/slab，order-3，92 slabs |

这组数字以前只能靠推，现在是实测基线，可直接喂给 64560 的 `SLAB_BASELINE`/喷洒参数。

## 3. kallsyms 三方对照（全过）

| 来源 | 结果 |
|---|---|
| 镜像离线抽取 | 128,943 条目 / **124,315 唯一名** |
| `/proc/kallsyms` 内置部分 | **124,315 唯一名，差集 0 / 0** |
| exploit `chan/out` 的 kread | `load_module` 运行时 `0xffffffec7f102688` − 离线 `0xffffffc080502688` = `0x2bfec00000` = `x300run.log` 记录的 slide |

> `/proc/kallsyms` 共 546,485 行 = 128,943 内置 + 417,542 模块符号；
> 且 `kptr_restrict=2`，**地址全被抹成 0**（vivo 硬化），所以只能取名字、拿不到 slide。

## 4. root 现场清点

| 项 | 值 |
|---|---|
| 内核模块 | `kernelsu 221184 1`（隐藏基址）、`kiro 8192 0` |
| 用户态 | `/data/adb/ksu/bin/{su,busybox,bootctl,mkbootfs}` + `libadbroot.so`；`ksud → /data/adb/ksud` |
| su | 6,402,736 B，`sha256 049491e2…fbfb1b`（与 kiroot.apk `assets/ksud` 同哈希） |
| 模块 | Shamiko v1.2.5(414)、Vector v2.2(3080)、Zygisk Next 1.5.3(880) |
| allowlist | 3,144 B（`shell` 免弹窗由 `allow_shell=1` 提供） |
| 通道残留 | `/data/local/tmp/chan/{out,busy,run}`，`.done` 已置位；**无 t28k / dispatch 存活** |

## 5. 补采清单

已拉回本地（`runtime/`）：

- `iomem.txt`、`kallsyms-runtime.txt`（26 MB，地址被抹零，仅留符号名）、
  `runtime-slabinfo.txt`、`runtime-meminfo.txt`
- `tmp2/`：32 个脚本与诊断文件（`x300root.sh`、`ksu_stage.sh`、`worker.sh`、`dispatch.sh`、
  `cleanroot_bakasu.sh`、`fyl-diag.sh`、`ghostlock-fix.sh`、`root.log`、`t28k.log`、
  `child_log.txt`、`cseq.log`、`chan/out` 片段、`s1/s2/stat1/stat2` 等）
- `tmp/`：`ksu_cur.ko`（515,952，BakaSU 模块）、`kiro-hello.ko`、
  以及大件 `ksu.apk` / `poc_tmp_root` / `ksud*` / `probe`（只记哈希，不入 git）
- `adb/`：`allowlist`、`modules_info.txt`、`bin/{su,busybox,bootctl,mkbootfs,libadbroot.so}`

大件哈希（不重复入库，均可从 `kiroot.apk` 或管理器复现）：

```
ksu.apk      e27ad21204e8f58a4268c41725de97341c62a2f4acca7fa4dda256e9d7863d01
poc_tmp_root 369d2f78cf7d4692c165b0e888e3759d577de4ec831800e5705d997c727ea602  (= APK asset)
ksud (tmp)   f94491240b597a19a7be0bd83ae28f19617cb00924708ad5d5ceee04238c41eb
ksud-next    f4359553a597955956b97e86277e08bc426f644de0dba5ff13da562fba29c55d
ksud-rs410   cb8273c56282313ee0347d6044b47c5a59ddd9f6031269ec5597b11e71777333
probe        1b7da65023ee4a5015ae120624696d98fc9c962f6bae1cbc9a5294657e6fb40d
adb/ksud     70bef0366ba13008619b8969569bb26069f5b4d47afc0ac8488c0819e44ba17b
```
