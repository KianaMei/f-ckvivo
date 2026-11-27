#!/system/bin/sh
# Minimal kprobe-fetch kernel read primitive for PD2337 / 6.1.172.
# Usage: sh rk.sh <hex-address> [hex-address ...]
#
# Each address is exposed as an argument to a temporary capable() kprobe.
# The trace line is then parsed by the caller; it looks like:
#   ... kx: (...) a0=0x... a1=0x...
T=/sys/kernel/tracing
[ -d "$T" ] || T=/sys/kernel/debug/tracing
[ -d "$T" ] || { echo "TRACEFS_MISSING"; exit 9; }

if [ "$#" -lt 1 ] || [ "$#" -gt 8 ]; then
  echo "usage: sh rk.sh <hex-address> [max 8]" >&2
  exit 2
fi

N=0
FETCH=""
for A in "$@"; do
  case "$A" in
    0x*) ;;
    *) echo "BAD_ADDR=$A" >&2; exit 2 ;;
  esac
  FETCH="$FETCH a$N=@$A:u64"
  N=$((N + 1))
done

cd "$T" || exit 9
echo 0 > events/enable 2>/dev/null
echo > kprobe_events 2>/dev/null
printf "p:kx capable%s\n" "$FETCH" >> kprobe_events || exit 9
echo 1 > events/kprobes/kx/enable 2>/dev/null || echo 1 > events/kprobes/enable 2>/dev/null
echo 1 > tracing_on 2>/dev/null
echo > trace 2>/dev/null
/data/local/tmp/probe >/dev/null 2>&1
echo 0 > tracing_on 2>/dev/null
grep 'kx:' trace | tail -1
echo > kprobe_events 2>/dev/null
