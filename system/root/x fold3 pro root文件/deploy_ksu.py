#!/usr/bin/env python3
"""一键复现：临时 root -> 清 dfd 门禁 -> 用本机 kallsyms 补丁 KO -> insmod -> ksud install
用法: DEV=ip:port python3 deploy_ksu.py
前提: 手机上已有 /data/local/tmp/{libroot.so,attempt_fix.sh,cve98260/profile.framed,
      ksud_new,ksu-final.ko,ksu_enable 相关脚本,rk.sh}（由 kit 推送）
"""
import subprocess, os, re, sys, struct, time

ADB="/usr/lib/android-sdk/platform-tools/adb"
ENV=dict(os.environ, HOME="/workspace/.adbh")
DEV=os.environ["DEV"]

def adb(*a, timeout=120):
    try:
        r=subprocess.run([ADB]+list(a),capture_output=True,text=True,timeout=timeout,env=ENV)
        return (r.stdout or "")+(r.stderr or "")
    except subprocess.TimeoutExpired: return "TIMEOUT"

def sh(cmd, timeout=120):
    return adb("-s",DEV,"shell",cmd,timeout=timeout)

def root(cmd, timeout=180):
    return adb("-s",DEV,"shell",'/data/local/tmp/libroot.so su -c "%s"'%cmd.replace('"','\\"'),timeout=timeout)

def wait_root(budget=240):
    t0=time.time()
    while time.time()-t0<budget:
        out=sh("cd /data/local/tmp && ./libroot.so su -c 'id -u' 2>/dev/null")
        if out.strip().splitlines()[-1:] == ["0"]:
            return True
        time.sleep(5)
    return False

def main():
    # 0) 已有 root？
    if not wait_root(20):
        print("[*] 启动 exploit")
        sh("cd /data/local/tmp; nohup sh attempt_fix.sh > att_bg.txt 2>&1 &")
        if not wait_root(240):
            print("[-] 没拿到 root"); return 2
    print("[+] root ok")

    # 1) 清 dfd 加载门禁（kprobe pre_handler / notifier / sys_exit）
    print("[*] 清门禁")
    root("sh /data/local/tmp/zs_gates.sh")

    # 2) 导出本机 kallsyms（此时可读）
    sh('cd /data/local/tmp; ./libroot.so su -c "cat /proc/kallsyms > /data/local/tmp/ksym_real.txt; chmod 644 /data/local/tmp/ksym_real.txt"')
    adb("-s",DEV,"pull","/data/local/tmp/ksym_real.txt","/workspace/ksym_real.txt")
    kmap={}
    for line in open('/workspace/ksym_real.txt',encoding='utf-8',errors='replace'):
        p=line.split()
        if len(p)>=3 and p[2] not in kmap: kmap[p[2]]=int(p[0],16)

    # 3) 补丁 KO：未定义符号 -> ABS 运行时地址 + vivo_module_filter
    d=bytearray(open('/workspace/neo10/assets/ksu-final.ko','rb').read())
    shoff,=struct.unpack_from('<Q',d,0x28); se,sc,si=struct.unpack_from('<HHH',d,0x3a)
    S=[]
    for i in range(sc):
        o=shoff+i*se
        nm,typ,fl,addr,off,size,link,inf,al,en=struct.unpack_from('<IIQQQQIIQQ',d,o)
        S.append(dict(nm=nm,off=off,size=size,link=link))
    st=S[si]['off']
    for s in S:
        e=d.index(b'\0',st+s['nm']); s['name']=d[st+s['nm']:e].decode()
    sym=[s for s in S if s['name']=='.symtab'][0]; strt=S[sym['link']]
    def g(n):
        o=strt['off']+n; e=d.index(b'\0',o); return d[o:e].decode('utf-8','replace')
    n_patched=0
    for i in range(sym['size']//24):
        o=sym['off']+i*24
        n,info,other,shndx,val,sz=struct.unpack_from('<IBBHQQ',d,o)
        if shndx!=0: continue
        nm=g(n)
        if nm in kmap:
            struct.pack_into('<IBBHQQ',d,o,n,info,other,0xfff1,kmap[nm],sz); n_patched+=1
    old=b'scmversion=g0c0fdb960178\x00'; new=b'vivo_module_filter=auto\x00\x00'
    i=d.find(old)
    if i>0 and len(old)==len(new): d[i:i+len(old)]=new
    open('/workspace/ksu_v.ko','wb').write(d)
    print("[+] 补丁完成 %d 个符号"%n_patched)

    # 4) insmod
    adb("-s",DEV,"push","/workspace/ksu_v.ko","/data/local/tmp/ksu_v.ko")
    out=root('cd /data/local/tmp; insmod ksu_v.ko allow_shell=1; echo rc=$?; grep -i kernelsu /proc/modules')
    print(out.strip()[-300:])

    # 5) ksud install + 恢复 Enforcing + 验证
    root("/data/adb/ksud install 2>&1 | tail -3")
    root("setenforce 1")
    print(sh('su -c "id; getenforce" 2>&1 | head -3'))
    print(sh('su -c "/data/adb/ksud debug info" 2>&1 | head -6'))
    return 0

if __name__=="__main__":
    sys.exit(main())
