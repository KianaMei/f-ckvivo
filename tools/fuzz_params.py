#!/usr/bin/env python3
"""Fuzz beta/rollout-related OTA params for PD2545D from the last official base."""
import itertools
import json
from vivo_ota_tracker import (
    DeviceConfig, build_request_params, join_params, send_encrypted_request,
)

MODEL, DEVICE = "PD2545D", "V2545A"
BASE = "16.0.14.15.W10"   # last OriginOS 6 official per android-hilfe.de

# Param overrides to try: name -> candidate values
OVERRIDES = {
    "ch": ["Y", "A", "C", "T"],          # channel (尝鲜?)
    "cu": ["Y", "1"],                     # customer update?
    "checkTrige": ["SILENCE", "AUTO", "MANUL_BETA"],
    "isMan": [0],
    "ms": [1, 2],
    "gn": [1],
    "hasVgc": [0],
    "radiotype": ["A"],
    "nt": ["0", "4G"],
    "protocalversion": ["2.0"],
}

cfg = DeviceConfig(device_type="phone", model_sw_ver=MODEL, device_model=DEVICE,
                   sw_version=BASE, android_ver=16, is_full=True)

def query(overrides):
    params = build_request_params(cfg)
    params.update({k: v for k, v in overrides.items()})
    resp = send_encrypted_request(join_params(params))
    ok = "210" not in resp.split("retcode")[-1][:20] and not resp.startswith("[Error]")
    return ok, resp

# Pass 1: each override alone
found = []
for key, values in OVERRIDES.items():
    for val in values:
        ok, resp = query({key: val})
        tag = f"{key}={val}"
        if ok:
            print(f"[HIT] {tag}: {resp[:400]}")
            found.append(tag)
        else:
            print(f"[-] {tag}")

# Pass 2: promising pairs
if not found:
    pairs = [
        {"ch": "Y", "cu": "Y"}, {"ch": "Y", "ms": 1}, {"ch": "Y", "checkTrige": "SILENCE"},
        {"cu": "Y", "ms": 1}, {"ch": "A", "cu": "Y"}, {"ms": 1, "gn": 1},
    ]
    for p in pairs:
        ok, resp = query(p)
        tag = json.dumps(p)
        if ok:
            print(f"[HIT] {tag}: {resp[:400]}")
            found.append(tag)
        else:
            print(f"[-] {tag}")

print("\nresult:", found or "no combination unlocked the rollout")
