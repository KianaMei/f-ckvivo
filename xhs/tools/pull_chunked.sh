#!/bin/bash
# Pull a large device file over a flaky adb link: 4MiB dd chunks via exec-out, per-chunk
# size check, up to 12 retries, local concat + md5. Args: remote_path local_out total_bytes
# Uses su; caller cleans up the remote staging file.
ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
remote=$1; out=$2; total=$3
BS=4194304
N=$(( (total + BS - 1) / BS ))
mkdir -p "$(dirname "$out")"
rm -f "$out".chunk*
fail=0
for i in $(seq 0 $((N-1))); do
  want=$BS; [ $i -eq $((N-1)) ] && want=$((total - BS*(N-1)))
  c="$out.chunk$(printf %03d $i)"
  for try in $(seq 1 12); do
    MSYS_NO_PATHCONV=1 "$ADB" exec-out su -c "dd if=$remote bs=$BS skip=$i count=1 2>/dev/null" > "$c" 2>/dev/null
    got=$(stat -c %s "$c" 2>/dev/null || echo 0)
    [ "$got" -eq "$want" ] && break
    sleep 1
  done
  got=$(stat -c %s "$c" 2>/dev/null || echo 0)
  if [ "$got" -ne "$want" ]; then echo "CHUNK $i FAILED (got $got want $want)"; fail=1; break; fi
  echo "chunk $i/$((N-1)) ok"
done
[ $fail -eq 0 ] || exit 1
cat "$out".chunk* > "$out"
rm -f "$out".chunk*
echo "assembled: $(stat -c %s "$out") bytes (expect $total)"
md5sum "$out"
