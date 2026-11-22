#!/usr/bin/env python3
"""Try older base versions until the OTA server returns the 17.1.12.5 full package."""
import sys
from vivo_ota_tracker import (
    DeviceConfig, build_request_params, join_params,
    send_encrypted_request, parse_update_response, print_update_info,
)

MODEL = "PD2545D"
DEVICE = "V2545A"
TARGET_PREFIX = "17.1.12"

# Candidate older base versions, newest-first so the first hit is the closest predecessor.
CANDIDATES = []
for minor in range(12, -1, -1):          # 17.1.12.x down to 17.1.0.x
    for patch in range(15, 0, -1):
        CANDIDATES.append(f"17.1.{minor}.{patch}.W10")
CANDIDATES.append("1.0.0.0.W10")          # bogus ancient version as fallback

for ver in CANDIDATES:
    cfg = DeviceConfig(
        device_type="phone", model_sw_ver=MODEL, device_model=DEVICE,
        sw_version=ver, android_ver=17, is_full=True,
    )
    try:
        resp = send_encrypted_request(join_params(build_request_params(cfg)))
    except Exception as e:
        print(f"[{ver}] request error: {e}", flush=True)
        continue
    if resp.startswith("[Error]") or '"210"' in resp or "210" in resp.split("retcode")[-1][:20]:
        print(f"[{ver}] no update", flush=True)
        continue
    print(f"\n[{ver}] GOT RESPONSE:", flush=True)
    info = parse_update_response(resp, cfg)
    print_update_info(info)
    print("\n--- RAW ---")
    print(resp)
    sys.exit(0)

print("\nAll candidates exhausted, no package returned.")
sys.exit(1)
