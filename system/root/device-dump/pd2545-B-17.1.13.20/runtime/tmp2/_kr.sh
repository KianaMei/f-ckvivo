T=/sys/kernel/tracing
cd $T || exit 9
echo 0 > events/enable
echo > kprobe_events
echo 'p:kx capable  a0=@0xffffffec811e4cc0 a1=@0xffffffec811e4cc8 a2=@0xffffffec811e4cd0' >> kprobe_events
echo 1 > events/kprobes/enable
echo 1 > tracing_on
echo > trace
/data/local/tmp/probe >/dev/null 2>&1
echo 0 > tracing_on
grep 'kx:' trace | tail -1
echo > kprobe_events
