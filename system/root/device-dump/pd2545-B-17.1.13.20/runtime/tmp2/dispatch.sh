#!/system/bin/sh
# 假 root 命令通道。
#
# 三个退出条件，确保它永远不会在后台空转（"核心满载发烫"的唯一来源）：
#   1. $CH/.done 存在 —— 流程已成功、已拿到真 root，通道不再需要。
#      用「持久标记」而不是一次性 .stop：.stop 会被第一个 dispatch 消费掉，
#      之后再被拉起的新实例看不到它，就会永久空转。.done 一直存在，晚起的也能退。
#   2. $CH/.stop 存在 —— 本次收尾请求。
#   3. 连续 3 次 sleep 失败 —— 说明 fork 已枯竭，此时 while 循环会变成纯忙等，
#      必须退出而不是继续转（这是实测到 100% CPU 的直接成因）。
CH=/data/local/tmp/chan
PATH=/system/bin:/system/xbin:/sbin:/vendor/bin
export PATH
/system/bin/mkdir -p $CH 2>/dev/null
/system/bin/chmod 777 $CH 2>/dev/null
/system/bin/echo "[dispatch] ready pid=$$ uid=$(/system/bin/id -u)" >> $CH/out
/system/bin/chmod 666 $CH/out 2>/dev/null

fails=0
while true; do
  if [ -f $CH/.done ]; then
    /system/bin/echo "[dispatch] done marker, bye" >> $CH/out
    exit 0
  fi
  if [ -f $CH/.stop ]; then
    /system/bin/rm -f $CH/.stop $CH/.up
    /system/bin/echo "[dispatch] stop requested, bye" >> $CH/out
    exit 0
  fi
  if [ -f $CH/run ]; then
    /system/bin/mv -f $CH/run $CH/busy 2>/dev/null
    if [ -f $CH/busy ]; then
      /system/bin/echo "__BEGIN__" >> $CH/out
      /system/bin/sh $CH/busy >> $CH/out 2>&1
      /system/bin/echo "__END__" >> $CH/out
      /system/bin/chmod 666 $CH/out 2>/dev/null
      /system/bin/rm -f $CH/busy
    fi
    continue
  fi
  if /system/bin/toybox sleep 1 2>/dev/null || /system/bin/sleep 1 2>/dev/null; then
    fails=0
  else
    fails=$((fails + 1))
    if [ $fails -ge 3 ]; then
      /system/bin/echo "[dispatch] sleep failed x$fails, exit to avoid busy loop" >> $CH/out
      exit 0
    fi
  fi
done
