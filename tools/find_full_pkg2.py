#!/usr/bin/env python3
"""Probe documented + swept older base versions for the PD2545D full package."""
import sys
import time
from vivo_ota_tracker import (
    DeviceConfig, build_request_params, join_params,
    send_encrypted_request, parse_update_response, print_update_info,
)

MODEL = "PD2545D"
DEVICE = "V2545A"

# Documented real versions for the PD2545 family first, then sweeps.
CANDIDATES = [
    "16.0.14.15.W10",
    "17.0.8.14.W10",
    "17.0.8.14.W30",
    "16.0.14.15.W30",
]
for minor in range(12, -1, -1):
    for patch in range(15, 0, -1):
        CANDIDATES.append(f"17.0.{minor}.{patch}.W10")
for minor in range(20, 7, -1):
    for patch in range(15, 0, -1):
        CANDIDATES.append(f"16.0.{minor}.{patch}.W10")

t0 = time.time()
for i, ver in enumerate(CANDIDATES):
    cfg = DeviceConfig(
        device_type="phone", model_sw_ver=MODEL, device_model=DEVICE,
        sw_version=ver, android_ver=17, is_full=True,
    )
    try:
        resp = send_encrypted_request(join_params(build_request_params(cfg)))
    except Exception as e:
        print(f"[{ver}] request error: {e}", flush=True)
        continue
    if "210" in resp.split("retcode")[-1][:20]:
        continue  # silent skip for no-update, keep output short
    print(f"\n[{ver}] GOT RESPONSE (after {i+1} tries, {time.time()-t0:.0f}s):", flush=True)
    info = parse_update_response(resp, cfg)
    print_update_info(info)
    print("\n--- RAW ---")
    print(resp)
    sys.exit(0)

print(f"\nAll {len(CANDIDATES)} candidates exhausted ({time.time()-t0:.0f}s), no package.")
sys.exit(1)
