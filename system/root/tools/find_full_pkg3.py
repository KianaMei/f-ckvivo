#!/usr/bin/env python3
"""Sweep 16.1.x and missed 16.0.x ranges as base for PD2545D full package."""
import sys, time
from vivo_ota_tracker import (
    DeviceConfig, build_request_params, join_params,
    send_encrypted_request, parse_update_response, print_update_info,
)

MODEL, DEVICE = "PD2545D", "V2545A"

CANDIDATES = []
# 16.1.x line (OriginOS 6 mid-life, matches July 2026 launch window)
for minor in range(25, -1, -1):
    for patch in range(20, 0, -1):
        CANDIDATES.append(f"16.1.{minor}.{patch}.W10")
# missed 16.0.x corners: minor 0-7 and 21-25, patches 1-20
for minor in list(range(7, -1, -1)) + list(range(25, 20, -1)):
    for patch in range(20, 0, -1):
        CANDIDATES.append(f"16.0.{minor}.{patch}.W10")

t0 = time.time()
hits = 0
for i, ver in enumerate(CANDIDATES):
    cfg = DeviceConfig(device_type="phone", model_sw_ver=MODEL, device_model=DEVICE,
                       sw_version=ver, android_ver=16, is_full=True)
    try:
        resp = send_encrypted_request(join_params(build_request_params(cfg)))
    except Exception as e:
        print(f"[{ver}] error: {e}", flush=True)
        continue
    if "210" in resp.split("retcode")[-1][:20]:
        continue
    print(f"\n[{ver}] HIT (try {i+1}, {time.time()-t0:.0f}s):", flush=True)
    info = parse_update_response(resp, cfg)
    print_update_info(info)
    print("--- RAW ---")
    print(resp[:2000])
    hits += 1
    break

if not hits:
    print(f"\nNo hit in {len(CANDIDATES)} candidates ({time.time()-t0:.0f}s).")
    sys.exit(1)
