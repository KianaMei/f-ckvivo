#!/usr/bin/env python3
"""Replay OTA query with the user's real IMEI for PD2545D / V2545A."""
import sys, time
from vivo_ota_tracker import (
    DeviceConfig, build_request_params, join_params,
    send_encrypted_request, parse_update_response, print_update_info,
    generate_imei,
)

MODEL, DEVICE = "PD2545D", "V2545A"
REAL_IMEI = "000000000000000"

# Documented/likely pre-upgrade bases, then a tight targeted sweep.
PRIORITY = [
    "16.0.14.15.W10",   # documented PD2545 official base
    "17.1.12.5.W10",    # current version (would reveal any newer build)
]
SWEEP = []
for minor in range(16, 9, -1):          # 16.0.16.x .. 16.0.10.x
    for patch in range(15, 0, -1):
        SWEEP.append(f"16.0.{minor}.{patch}.W10")
for minor in range(10, -1, -1):         # 16.1.10.x .. 16.1.0.x
    for patch in range(15, 0, -1):
        SWEEP.append(f"16.1.{minor}.{patch}.W10")

def query(base):
    cfg = DeviceConfig(device_type="phone", model_sw_ver=MODEL, device_model=DEVICE,
                       sw_version=base, android_ver=17 if base.startswith("17") else 16,
                       is_full=True)
    params = build_request_params(cfg)
    params["imei"] = REAL_IMEI
    return send_encrypted_request(join_params(params)), cfg

def run(base, label):
    resp, cfg = query(base)
    if "210" in resp.split("retcode")[-1][:20]:
        print(f"[{label}] {base} -> 无更新", flush=True)
        return None
    print(f"\n[{label}] {base} -> HIT!", flush=True)
    info = parse_update_response(resp, cfg)
    print_update_info(info)
    print("--- RAW (first 3000 chars) ---")
    print(resp[:3000])
    return info

t0 = time.time()
for base in PRIORITY:
    if run(base, "priority"):
        sys.exit(0)

print(f"\npriority misses at {time.time()-t0:.0f}s, starting sweep of {len(SWEEP)}...", flush=True)
for i, base in enumerate(SWEEP):
    resp, cfg = query(base)
    if "210" in resp.split("retcode")[-1][:20]:
        continue
    print(f"\n[sweep] {base} -> HIT (try {i+1}, {time.time()-t0:.0f}s)!", flush=True)
    info = parse_update_response(resp, cfg)
    print_update_info(info)
    print("--- RAW (first 3000 chars) ---")
    print(resp[:3000])
    sys.exit(0)
    if i % 50 == 0:
        print(f"  ... {i} tried, {time.time()-t0:.0f}s", flush=True)

print(f"\nno hit ({len(SWEEP)} swept, {time.time()-t0:.0f}s)")
sys.exit(1)
