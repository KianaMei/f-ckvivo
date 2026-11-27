#!/usr/bin/env python3
"""Sweep 17.x bases with the real IMEI (covers the beta-lineage theory)."""
import sys, time
from vivo_ota_tracker import (
    DeviceConfig, build_request_params, join_params,
    send_encrypted_request, parse_update_response, print_update_info,
)

MODEL, DEVICE = "PD2545D", "V2545A"
REAL_IMEI = "000000000000000"

CANDIDATES = []
for minor in range(12, -1, -1):
    for patch in range(15, 0, -1):
        if minor == 12 and patch >= 5:
            continue  # 17.1.12.5+ is current; skip self and above
        CANDIDATES.append(f"17.1.{minor}.{patch}.W10")
for minor in range(12, -1, -1):
    for patch in range(15, 0, -1):
        CANDIDATES.append(f"17.0.{minor}.{patch}.W10")

t0 = time.time()
for i, ver in enumerate(CANDIDATES):
    cfg = DeviceConfig(device_type="phone", model_sw_ver=MODEL, device_model=DEVICE,
                       sw_version=ver, android_ver=17, is_full=True)
    params = build_request_params(cfg)
    params["imei"] = REAL_IMEI
    try:
        resp = send_encrypted_request(join_params(params))
    except Exception as e:
        print(f"[{ver}] error: {e}", flush=True)
        continue
    if "210" in resp.split("retcode")[-1][:20]:
        continue
    print(f"\n[{ver}] HIT (try {i+1}, {time.time()-t0:.0f}s)!", flush=True)
    info = parse_update_response(resp, cfg)
    print_update_info(info)
    print("--- RAW (first 3000 chars) ---")
    print(resp[:3000])
    sys.exit(0)
    if i % 60 == 0:
        print(f"  ... {i} tried, {time.time()-t0:.0f}s", flush=True)

print(f"\nno hit in {len(CANDIDATES)} candidates ({time.time()-t0:.0f}s)")
sys.exit(1)
