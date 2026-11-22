export PATH=/system/bin:/vendor/bin
insmod /data/local/tmp/ksu_cur.ko allow_shell=1 2>&1
echo "irc=$?"
