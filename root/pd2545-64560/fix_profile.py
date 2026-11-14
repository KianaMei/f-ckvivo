#!/usr/bin/env python3
"""Fix PD2545D profile selection and make the preflight gate truthful.

Problems fixed:
  * detect_standalone_profile() hit the PLZ110 uname fast path (empty
    fingerprint) even on this device; the PD2545 fingerprint/device check now
    runs first.
  * PREFLIGHT_PASS printed a hardcoded pd2545 profile string, so the preflight
    gate could pass while the configured profile was PLZ110. It now prints the
    actual active profile.
  * tools/pd2545_preflight.sh and tools/pd2545_run.sh gate on
    TARGET_PROFILE_GATE_PASS profile=pd2545-dd6fd5c98918, not on PREFLIGHT_PASS.
"""
import pathlib
import sys

SRC = 'cve-2026-64560-pd2545.c'
p = pathlib.Path(SRC)
s = p.read_text(encoding='utf-8')

if 'PD2545_PROFILE_PRIORITY' in s:
    print('already patched')
    sys.exit(0)

# 1) fingerprint/device-first profile selection
old = '''    char qemu[PROP_VALUE_MAX] = {0};

    {
        struct utsname kver;'''
new = '''    char qemu[PROP_VALUE_MAX] = {0};

    /* PD2545_PROFILE_PRIORITY: select by the exact fingerprint/device before
     * any uname fast path.  The device runs dd6fd5c98918, but the PLZ110 uname
     * fast path previously won and configured PLZ110 direct addresses. */
    if (__system_property_get("ro.build.fingerprint", fingerprint) > 0 &&
        __system_property_get("ro.product.device", device) > 0 &&
        !strcmp(fingerprint, pd2545) && !strcmp(device, "PD2545"))
        return STANDALONE_PROFILE_PD2545;

    {
        struct utsname kver;'''
if s.count(old) != 1:
    sys.exit('detect anchor not unique')
s = s.replace(old, new)

# 2) active profile name for the truthful preflight marker
old = 'static void configure_standalone(char **argv) {'
new = 'static const char *active_profile_name = "unknown";\n\nstatic void configure_standalone(char **argv) {'
if s.count(old) != 1:
    sys.exit('configure anchor not unique')
s = s.replace(old, new)

old = '    for (size_t index = 0; index < ARRAY_SIZE(exec_environment); index++) {'
new = '''    active_profile_name = profile_name;
    for (size_t index = 0; index < ARRAY_SIZE(exec_environment); index++) {'''
if s.count(old) != 1:
    sys.exit('setenv loop anchor not unique')
s = s.replace(old, new)

old = '''        printf("PREFLIGHT_PASS profile=pd2545-dd6fd5c98918 race_not_started=1 "
               "kernel_writes=0\\n");'''
new = '''        printf("PREFLIGHT_PASS profile=%s race_not_started=1 "
               "kernel_writes=0\\n", active_profile_name);'''
if s.count(old) != 1:
    sys.exit('PREFLIGHT_PASS anchor not unique')
s = s.replace(old, new)

p.write_text(s, encoding='utf-8')
print('source patched; bytes', len(s))

# 3) scripts: gate on the runtime TARGET_PROFILE_GATE_PASS line
for fn in ['../tools/pd2545_preflight.sh', '../tools/pd2545_run.sh']:
    q = pathlib.Path(fn)
    t = q.read_text(encoding='utf-8')
    old = "grep -q 'PREFLIGHT_PASS profile=pd2545-dd6fd5c98918'"
    new = "grep -q 'TARGET_PROFILE_GATE_PASS profile=pd2545-dd6fd5c98918'"
    if old not in t:
        sys.exit(f'{fn}: gate string not found')
    q.write_text(t.replace(old, new), encoding='utf-8')
    print(fn, 'gate now uses TARGET_PROFILE_GATE_PASS')
print('done')
