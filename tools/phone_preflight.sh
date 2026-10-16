#!/bin/bash
# X Fold6 preflight: kernel + carrier identity (read-only, no root)
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
echo "== kernel release (must be 6.12.58-android16-6-g0a092cc0037a-abogki521987229-4k) =="
"$ADB" shell uname -r
echo "== kernel version =="
"$ADB" shell uname -v
echo "== cmdline product.version token =="
"$ADB" shell cat /proc/cmdline | tr ' ' '\n' | grep product.version
echo "== carrier dumpstate lib =="
export MSYS_NO_PATHCONV=1
"$ADB" shell stat -c %s /system/lib64/libdumpstateaidl.so
"$ADB" shell 'dd if=/system/lib64/libdumpstateaidl.so bs=1 skip=37904 count=16 2>/dev/null' | xxd
echo "(expect: fd 7b bd a9 f5 0b 00 f9 f4 4f 02 a9 fd 03 00 91)"
