#!/usr/bin/env python3
"""通过 kprobe 读取内核内存，遍历 kprobe_table 找指定地址上的 pre_handler"""
import subprocess, os, sys

ADB=os.environ.get("ADB","/usr/lib/android-sdk/platform-tools/adb")
DEV=os.environ.get("DEV","<DEVICE_IP>:33481")
HOME=os.environ.get("HOME","/workspace/.adbh")
ENV=dict(os.environ, HOME=HOME)

def rootcmd(cmd, timeout=60):
    r=subprocess.run([ADB,"-s",DEV,"shell",'/data/local/tmp/libroot.so su -c "%s"'%cmd.replace('"','\\"')],
                     capture_output=True,text=True,timeout=timeout,env=ENV)
    return r.stdout

def read_mem(addrs):
    """一次最多 8 个地址"""
    out={}
    for i in range(0,len(addrs),8):
        chunk=addrs[i:i+8]
        args=" ".join(hex(a) for a in chunk)
        for attempt in range(3):
            txt=rootcmd("sh /data/local/tmp/rk.sh "+args)
            line=None
            for ln in txt.splitlines():
                if "rk" in ln and "=" in ln: line=ln
            if not line: continue
            import re
            vals={}
            for m in re.finditer(r"\b([a-h])=(0x[0-9a-fA-F]+)", line):
                vals[m.group(1)]=int(m.group(2),16)
            if len(vals)>=len(chunk):
                for k,a in enumerate(chunk):
                    out[a]=vals[chr(ord('a')+k)]
                break
        else:
            for a in chunk: out.setdefault(a,None)
    return out

def sread(addr, n=16):
    """读字符串"""
    words=[addr+8*i for i in range((n+7)//8)]
    m=read_mem(words)
    b=b""
    for w in words:
        v=m.get(w) or 0
        b+=v.to_bytes(8,'little')
    return b.split(b"\0")[0].decode('utf-8','replace')

def main():
    SLIDE=int(os.environ.get("SLIDE","0x2b81c00000"),16)
    table=0xffffffc00a47d938+SLIDE
    print("kprobe_table @ %#x"%table)
    heads=[table+8*i for i in range(64)]
    m=read_mem(heads)
    live=[(i,m[h]) for i,h in enumerate(heads) if m.get(h)]
    print("非空 bucket:",[(i,hex(p)) for i,p in live])
    for b,node in live:
        seen=set()
        while node and node not in seen and len(seen)<40:
            seen.add(node)
            mm=read_mem([node, node+0x28, node+0x30, node+0x40])
            nxt=mm.get(node); addr=mm.get(node+0x28); namep=mm.get(node+0x30); pre=mm.get(node+0x40)
            nm=""
            if namep: nm=sread(namep,24)
            print("  b=%-2d node=%#x next=%s addr=%s name=%s pre=%s"%(
                b,node,hex(nxt) if nxt else "0",hex(addr) if addr else "0",nm or "?",hex(pre) if pre else "0"))
            node=nxt or 0
if __name__=="__main__":
    main()
