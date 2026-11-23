#!/usr/bin/env python3
"""Emit the PD2337 6.1.172 hook-clear constants from the old kallsyms snapshot."""
import argparse
from pathlib import Path

NAMES = (
    "kprobe_table",
    "load_module",
    "kallsyms_open",
    "proc_dointvec_minmax_sysadmin",
    "kptr_restrict",
    "sysctl_perf_event_paranoid",
    "__tracepoint_sys_exit",
)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ksyms")
    ap.add_argument("--old-slide", default="0x1c7d000000")
    args = ap.parse_args()
    old_slide = int(args.old_slide, 0)

    link: dict[str, int] = {}
    for line in Path(args.ksyms).read_text(encoding="utf-8", errors="replace").splitlines():
        fields = line.split()
        if len(fields) >= 3 and fields[2] not in link:
            try:
                runtime = int(fields[0], 16)
            except ValueError:
                continue
            if runtime >= old_slide:
                link[fields[2]] = runtime - old_slide

    for name in NAMES:
        if name not in link:
            raise SystemExit(f"missing symbol: {name}")
        print(f"{name.upper()}={link[name]:#x}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
