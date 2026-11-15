T=/sys/kernel/tracing
D=/sys/kernel/debug/tracing

echo '===DEVICE==='
printf 'model   = '; getprop ro.product.model
printf 'android = '; getprop ro.build.version.release
printf 'sdk     = '; getprop ro.build.version.sdk
printf 'patch   = '; getprop ro.build.version.security_patch
printf 'kernel  = '; uname -r

echo '===SELF==='
id
printf 'selinux = '; getenforce 2>&1
printf 'ctx     = '; cat /proc/self/attr/current 2>&1
grep -E '^Cap(Eff|Prm|Bnd):' /proc/self/status 2>&1
grep -E '^Seccomp' /proc/self/status 2>&1

echo '===TRACE-READ==='
for f in $T/tracing_on $T/current_tracer $T/available_tracers $T/available_events $T/buffer_size_kb $D/tracing_on; do
  printf '%-56s => ' "$f"
  head -c 36 "$f" 2>&1 | tr '\n' ' '
  echo ''
done
echo '--- per_cpu raw ---'
for c in $T/per_cpu/cpu*/trace_pipe_raw; do
  printf '%-56s => ' "$c"
  if [ -r "$c" ]; then
    sz=$(stat -c %s "$c" 2>/dev/null)
    echo "readable size=$sz"
  else
    echo 'NOT readable'
  fi
done

echo '===EVENT-ID==='
for e in mm_page_alloc mm_page_free kmem_cache_alloc kmem_cache_free; do
  printf '%-44s => ' "kmem/$e/id"
  cat $T/events/kmem/$e/id 2>&1 | head -n 1
done

echo '===TRACE-WRITE==='
o_trace=$(cat $T/tracing_on 2>/dev/null)
o_alloc=$(cat $T/events/kmem/mm_page_alloc/enable 2>/dev/null)
o_cache=$(cat $T/events/kmem/kmem_cache_alloc/enable 2>/dev/null)
o_free=$(cat $T/events/kmem/mm_page_free/enable 2>/dev/null)
echo "orig: tracing_on=$o_trace alloc=$o_alloc cache=$o_cache free=$o_free"
tryw() {
  if printf '%s' "$2" > "$1" 2>/dev/null; then r=OK; else r=DENIED; fi
  printf 'WRITE-%-7s %-52s (was %s)\n' "$r" "$1" "$3"
}
tryw $T/tracing_on 1 "$o_trace"
tryw $T/events/kmem/mm_page_alloc/enable 1 "$o_alloc"
tryw $T/events/kmem/kmem_cache_alloc/enable 1 "$o_cache"
tryw $T/events/kmem/mm_page_free/enable 1 "$o_free"
[ -n "$o_trace" ] && printf '%s' "$o_trace" > $T/tracing_on 2>/dev/null
[ -n "$o_alloc" ] && printf '%s' "$o_alloc" > $T/events/kmem/mm_page_alloc/enable 2>/dev/null
[ -n "$o_cache" ] && printf '%s' "$o_cache" > $T/events/kmem/kmem_cache_alloc/enable 2>/dev/null
[ -n "$o_free" ] && printf '%s' "$o_free" > $T/events/kmem/mm_page_free/enable 2>/dev/null
printf 'after restore tracing_on='; cat $T/tracing_on 2>/dev/null

echo '===KALLSYMS==='
printf 'head  => '; head -n 2 /proc/kallsyms 2>&1 | tr '\n' ' '; echo ''
printf 'lines => '; grep -c '' /proc/kallsyms 2>/dev/null || echo n/a
printf 'kptr  => '; cat /proc/sys/kernel/kptr_restrict 2>&1

echo '===KSU==='
for p in /data/adb/ksu /data/adb/ksu/bin/ksud /data/adb/modules /data/local/tmp/ghostlock-fix.sh; do
  printf '%-36s => ' "$p"
  ls -ld "$p" 2>&1 | head -n 1
done

echo '===MODULES==='
printf 'count => '; grep -c '' /proc/modules 2>/dev/null || echo n/a
head -n 2 /proc/modules 2>&1

echo '===MISC==='
printf 'slabinfo      => '; head -c 36 /proc/slabinfo 2>&1 | tr '\n' ' '; echo ''
printf 'perf_paranoid => '; cat /proc/sys/kernel/perf_event_paranoid 2>&1
printf 'cpu_count     => '; ls -d $T/per_cpu/cpu* 2>/dev/null | wc -l

echo '===DONE==='
