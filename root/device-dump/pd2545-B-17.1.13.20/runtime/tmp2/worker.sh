#!/system/bin/sh
# exploit 每完成一次 plant 都会 fork 一个本脚本并 waitpid 等它退出。
# 若这里 exec 一个永不退出的 dispatch.sh，就会「每轮泄漏一个常驻 root 进程」，
# 越堆越多 -> fork 枯竭 -> dispatch 的 sleep 失败 -> 纯忙等空转 -> 核心满载发烫。
# 因此必须尽早 return，只在「真的需要通道」时启动它。
CH=/data/local/tmp/chan

# 闸门 0：流程已成功收尾（.done 标记）—— 通道不再需要，直接退出。
# 这是最可靠的一道：不依赖读 /proc/modules，也不怕 .up 里的旧 pid 被复用。
[ -f $CH/.done ] && exit 0

# 闸门 1：KernelSU 模块已加载 —— 已有真 root，不再需要假 root 通道
/system/bin/grep -q '^kernelsu ' /proc/modules 2>/dev/null && exit 0

# 闸门 2：通道已在运行 —— 必须确认那个 pid 真的还是 dispatch.sh。
# 只用 /proc/$pid 存在判断会误判：/data/local/tmp 跨重启残留的旧 pid
# 可能被无关进程复用，导致所有 worker 都直接退出、通道永远起不来。
if [ -f $CH/.up ]; then
  old=$(/system/bin/cat $CH/.up 2>/dev/null)
  if [ -n "$old" ] && /system/bin/grep -qa 'dispatch.sh' /proc/$old/cmdline 2>/dev/null; then
    exit 0
  fi
fi

/system/bin/mkdir -p $CH 2>/dev/null
/system/bin/chmod 777 $CH 2>/dev/null
/system/bin/echo $$ > $CH/.up 2>/dev/null
/system/bin/chmod 666 $CH/.up 2>/dev/null
/system/bin/echo "[worker] pid=$$ uid=$(/system/bin/id -u) ctx=$(/system/bin/cat /proc/self/attr/current 2>/dev/null)" >> /data/local/tmp/root.log
exec /system/bin/sh $CH/dispatch.sh
