# PD2545D (vivo X Fold6 / MT6993) — CVE-2026-64560 port

This directory is a **research port** of the 6.12 `CVE-2026-64560` temporary-root
payload. It is derived from the device-verified OnePlus 15T (PLZ110 / 6.12.69)
port, which replaced the original Pixel 6.6 `ashmem` bridge with a
`uhid + configfs` bridge. That replacement is what makes a 6.12 build possible:
the Rust ashmem rewrite removed the C `ashmem_ioctl/open/release` primitive the
original payload depended on.

## Provenance / license

- Upstream template: `feiguang-ai/plz110-6.12.69-cve-2026-64560-ksu`
  (Apache-2.0; see `NOTICE`).
- Bridge: `bridge-6.12` from that repository (`repro/lib/fgroot_render.py`).
- Original vulnerability/PoC: NebuSec `CVE-2026-64560` for Pixel 10 Pro
  (`blazer`, 6.6.118).
- Symbols below are from the **certified production** GKI
  `android16-6.12-2025-12_r59` Image (`boot-6.12.img`, image_size
  `0x2820000`, sha256 `fe3ef364…`, 128,886 symbols), not from the device's
  private image and **not** from the debug `boot-6.12-allsyms.img`
  (`0x36a0000`). The debug all-syms build has a different layout: even
  `posix_cpu_timer_del` differs (`0x805cfbcc` vs `0x80519334`), so its
  151,293 addresses were discarded.

## Why this route

The device kernel is a **GKI 2025-12 (6.12.58)** private respin built
2026-08-05 (`dd6fd5c98918-abogki543627742-4k`, SPL 2026-09-01).

- **CVE-2026-43074** (eventpoll/page-cache): fixed by
  `eventpoll: defer struct eventpoll free to RCU grace period`, present in
  stable **6.12.83** and already in the public r59 tag (2026-07-28). The
  device build is after r59. The old 0/256 reclaim result is therefore
  **root-cause absent**, not a geometry/RUNTIME_BLOCKED problem.
- **CVE-2026-43499** (rtmutex remove_waiter): fixed in **6.12.86**; r59's
  `remove_waiter()` already uses `waiter->task`, so the same conclusion holds.
- **CVE-2026-64560**: fixed in **6.12.100** (2026-07-30) / Android October
  bulletin. r59 (2026-07-28) **does not contain** `timer_lock_sighand`; the
  2026-08-05 device build is therefore expected to remain vulnerable. This is
  the only live public kernel route.

## Symbols used (r59, static base `0xffffffc080000000`)

| LINK | r59 value |
|---|---|
| `LINK_CYCLE_C` (`init_task.thread_node`) | `0xffffffc08253e278` |
| `LINK_CYCLE_D` (`init_signals.thread_head`) | `0xffffffc08253ef10` |
| `LINK_MISC_LIST` | `0xffffffc08265e238` |
| `LINK_SYSCTL_BOOTID` | `0xffffffc0827e8d88` |
| `LINK_UHID_MISC` / `LINK_UHID_FOPS` | `0xffffffc082699a18` / `0xffffffc08145f8d0` |
| `LINK_UHID_CHAR_OPEN/WRITE/RELEASE` | `0xffffffc080e96d5c` / `0xffffffc080e965d8` / `0xffffffc080e96e80` |
| `LINK_CONFIGFS_READ_ITER` / `BIN_WRITE_ITER` | `0xffffffc08073a0ac` / `0xffffffc08073a658` |
| `LINK_MODULE_DIRECT_BASE/PLT_BASE` | `0xffffffc081926090` / `0xffffffc081926098` |
| `LINK_MEMSTART_ADDR` / `KIMAGE_VOFFSET` | `0xffffffc081926078` / `0xffffffc081927000` |
| `LINK_KMALLOC_CACHES` (slot `0x68`) | `0xffffffc08192e4c0` |
| `LINK_ANON_PIPE_BUF_OPS` | `0xffffffc0812eb608` |
| `LINK_INIT_TASK` / `INIT_CRED` | `0xffffffc08253dac0` / `0xffffffc082553840` |
| `LINK_SELINUX_BLOB_SIZES` / `SELINUX_STATE` | `0xffffffc081938868` / `0xffffffc0827c69a0` |
| `LINK_SDATA` / `LINK_END` | `0xffffffc082520000` / `0xffffffc082820000` |

`direct.bootidParent = 0xffffff800265e198` (`random_table + 0xe0` direct alias),
`direct.cycleC = 0xffffff800253e278`, both with **PHYS_DELTA assumed 0**
(`target.h TARGET_KERNEL_PHYS_LOAD_DELTA`).

## 6.12 layouts (from the r59 BTF, cross-checked against the OnePlus port)

`task_struct`: `tasks=0x638`, `pid=0x708`, `tgid=0x70c`, `group_leader=0x748`,
`real_cred=0x8f8`, `cred=0x900`, `comm=0x910`, `thread_node=0x7b8`;
`signal_struct.thread_head=0x10`; `cred` size `0xb8` (unchanged);
`file_operations`: `read_iter=0x28`, `write_iter=0x30`,
`unlocked_ioctl=0x50`, `open=0x68`, `release=0x78`; `pipe_buffer` size `0x28`;
`configfs_buffer` offsets `page=0x10`, `needs_read_fill=0x50`,
`bin_buffer=0x58`, `bin_buffer_size=0x60`, `cb_max_size=0x64`.

## Build

```sh
# 需要 NDK r29；ANDROID_NDK_ROOT 指向本机 NDK 根目录
NDK=${ANDROID_NDK_ROOT:?先 export ANDROID_NDK_ROOT=/path/to/android-ndk-r29}
CC=$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android35-clang
"$CC" -O2 -g0 -D_GNU_SOURCE -I. \
  -o build/cve-2026-64560-pd2545 cve-2026-64560-pd2545.c \
  -static -pthread -Wl,--strip-all
```

The produced binary is a static AArch64 ELF (no `PT_INTERP`). `bionic_compat.h`
only supplies the `__system_property_get` declaration and `PROP_VALUE_MAX`;
bionic's static libc provides the real symbol (the separate
`bionic_compat.c` is for the musl build and is intentionally not linked).

Built artifact (certified-symbol port):
`build/cve-2026-64560-pd2545` — **518,712 B**, sha256
`aa13e1eae51b194185feafde156621bef226cfe9150b9655f22052bda8eeb972`.
It contains `--dump-kallsyms` (read-only stage0 → uhid/configfs bridge →
physical read window) in addition to `--preflight` / `--run`.

### 2026-10-17 22:25 device result

With `TIMER_GOAL=55962`, attempt 1 printed `LANE_DONE ... timers=55962
deleted=55962 errors=0`, `SLAB_DRAIN_STRICT_PASS ... wait_ms=10273`,
`SPRAY_DONE valid=1 pairs=6000 fragments=48000`, then the device vanished
from adb/fastboot/Windows USB before `STAGE0_RAW_DIFF`. It had not returned
at the time of writing. No stage8/cred/SELinux path ran and no partition or
userdata write was performed. See `../FINDINGS.md` §11 for the full record
and the next read-only checks (`posix_timers` slab geometry, bootreason,
pstore) before any rerun.

## r59 vulnerability verdict

`repro/fgroot.py vuln-check` (from the OnePlus port's tooling) was run against
the certified r59 Image (`sha256 fe3ef364…`). Result:
**`status=VULNERABLE`** — `timer_lock_sighand` is absent and
`posix_cpu_timer_del/set/rearm` still call `pid_task → __lock_task_sighand`
without a retry edge. Evidence: `evidence/r59-vuln-check.json`.

This is the public r59 baseline, not the device's private
`dd6fd5c98918` respin; the device-side tracefs check or its exact Image is
still required for the same verdict.

## Device validation

## vr.ko neutralization (built in)

This device has vivo `vr.ko`; the OnePlus port does not. The payload now carries
the upstream two-layer guard:

- **PreSpawn**: zero `__tracepoint_sys_exit.funcs` at
  `LINK_SYS_EXIT_TP + kernel_slide + 0x48`, disarming the sys_exit tracepoint
  probe that kills tasks reaching euid 0. `__tracepoint_sys_exit =
  0xffffffc0826d8de0` in the certified r59 Image; the `funcs` offset `0x48`
  comes from `__traceiter_sys_exit` (`ldr x21,[x8,#0xe28]`, page base
  `0xffffffc0826d8000`).
- **PostSpawn**: clear the root child's `thread_info` flags word at
  `task+0x0` (tag A + the 0x400 syscall-tracepoint bit) first, then tag B at
  `task+0x2c`. Order is execution order, as upstream requires.

Success markers: `VR_GUARD_DISARM_PASS`, `VR_GUARD_TAGS_PASS`,
`STAGE8_PERMISSIVE_PASS`, `ROOT_CHILD_HOLD_PASS`.

## Direct temporary-root attempt

```sh
cd ..                 # 回到 root/
./tools/pd2545_run.sh
```

The runner performs the read-only `--preflight` gate, then `--run` with
`FG_ROOT_EXEC` pointing at a root proof script (`id`, LSM context, capability
set, `/proc/version`). **No boot.img is required for the attempt.**

The binary is a **PD2545D-only** port: `detect_standalone_profile()` is forced
to `STANDALONE_PROFILE_PD2545`, and the `configure_standalone()` PLZ110 branch
no longer matches PD2545. The preflight gate checks the runtime
`TARGET_PROFILE_GATE_PASS profile=pd2545-dd6fd5c98918` line (not the old
hardcoded `PREFLIGHT_PASS` string). On 2026-10-17 the read-only preflight
printed that line with `direct_parent=0xffffff800265e198` and
`kernel_writes=0`.

The r59 certified symbols are the baseline. A private-respin symbol or
`PHYS_DELTA` mismatch can make the run fail or panic/reboot; the payload does
not write partitions and the device recovers by rebooting. The current-build
boot.img/full OTA is the **fallback** for exact symbols and physical layout
after a failure, not a prerequisite.
