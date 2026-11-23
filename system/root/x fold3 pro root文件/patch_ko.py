#!/usr/bin/env python3
"""
patch_ko.py —— 让外部 KO 能在 vivo 内核上加载的两个必要补丁

用法:
  python3 patch_ko.py <base.ko> <kallsyms.txt> <out.ko> [--filter]

  1) base.ko      : 原始内核模块（.ko，含 .symtab）
  2) kallsyms.txt : 目标设备上导出的 /proc/kallsyms（需 root 且 kptr_restrict=0）
  3) out.ko       : 输出
  4) --filter     : 额外把 modinfo 里的 scmversion= 原位置换成
                    vivo_module_filter=auto（长度相同，vivo 缺少该字段会直接 EPERM）

背景:
  * vivo 内核把模块加载门的"未导出符号"无法被 loader 解析，需要预先把这些符号
    改成 SHN_ABS 并填入运行时地址（等价于 syms 预解析）。
  * vivo 的模块过滤要求 .modinfo 中存在 vivo_module_filter=auto，否则
    init_module 直接返回 EPERM 且不打任何日志。
"""
import struct, sys, os

def main():
    if len(sys.argv) < 4:
        print(__doc__); return 1
    base, ksym, out = sys.argv[1], sys.argv[2], sys.argv[3]
    do_filter = "--filter" in sys.argv[4:]

    kmap = {}
    with open(ksym, encoding="utf-8", errors="replace") as f:
        for line in f:
            p = line.split()
            if len(p) >= 3 and p[2] not in kmap:
                try: kmap[p[2]] = int(p[0], 16)
                except ValueError: pass
    if not any(kmap.values()):
        print("[!] kallsyms 里地址全为 0，请先让 kptr_restrict=0"); return 2

    d = bytearray(open(base, "rb").read())
    shoff, = struct.unpack_from("<Q", d, 0x28)
    se, sc, si = struct.unpack_from("<HHH", d, 0x3A)
    S = []
    for i in range(sc):
        o = shoff + i * se
        nm, typ, fl, addr, off, size, link, inf, al, en = struct.unpack_from("<IIQQQQIIQQ", d, o)
        S.append(dict(nm=nm, off=off, size=size, link=link))
    st = S[si]["off"]
    for s in S:
        e = d.index(b"\0", st + s["nm"]); s["name"] = d[st + s["nm"]:e].decode()
    sym = next(s for s in S if s["name"] == ".symtab")
    strt = S[sym["link"]]

    def g(n):
        o = strt["off"] + n; e = d.index(b"\0", o); return d[o:e].decode("utf-8", "replace")

    patched = 0; missing = []
    for i in range(sym["size"] // 24):
        o = sym["off"] + i * 24
        n, info, other, shndx, val, sz = struct.unpack_from("<IBBHQQ", d, o)
        if shndx != 0: continue
        name = g(n)
        if not name: continue
        if name in kmap:
            struct.pack_into("<IBBHQQ", d, o, n, info, other, 0xFFF1, kmap[name], sz)
            patched += 1
        else:
            missing.append(name)
    print("[+] 已解析符号: %d, 缺失: %d" % (patched, len(missing)))
    if missing: print("    缺失示例:", missing[:8])

    if do_filter:
        old = b"scmversion=g0c0fdb960178\x00"
        new = b"vivo_module_filter=auto\x00\x00"
        i = d.find(old)
        if i > 0 and len(old) == len(new):
            d[i:i + len(old)] = new
            print("[+] 已写入 vivo_module_filter=auto")
        else:
            print("[!] 没找到长度合适的位置，需手工处理 modinfo")

    open(out, "wb").write(d)
    print("[+] 输出:", out, len(d), "字节")
    return 0

if __name__ == "__main__":
    sys.exit(main())
