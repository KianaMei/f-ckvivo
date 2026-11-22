#!/usr/bin/env python3
"""Replica of com.bbk.updater's real getVgcAndPatch request with device-read values."""
import json, sys
from urllib.parse import quote
from vivo_ota_tracker import send_encrypted_request

IMEI = "000000000000000"

def enc(k, v):
    from urllib.parse import quote as q
    return f"{k}={q(str(v), safe='')}"

def build(base_fullver, base_swver):
    # order roughly mirrors getBaseCommonUrl -> getBaseUrl -> getUpdateRequestParams
    p = []
    p.append(enc("model", "PD2545"))
    p.append(enc("imei", IMEI))
    p.append(enc("s_n", ""))
    p.append(enc("dType", "phone"))
    p.append(enc("version", f"PD2545_N_PD2545MA_{base_swver}"))
    p.append(enc("public_model", "V2545A"))
    p.append(enc("cy", "CN-ZH"))
    p.append(enc("cu", "N"))
    p.append(enc("vgcSwVer", "17.1.1"))
    p.append(enc("vgcCu", "V000"))
    p.append(enc("hasVgc", 1))
    p.append(enc("elapsedtime", 152730))
    p.append(enc("st1", 100000))
    p.append(enc("st2", 0))
    p.append(enc("emmcid", ""))
    p.append(enc("fullVer", base_fullver))
    p.append(enc("si", "cn,cn"))
    p.append(enc("ne", "cn,cn"))
    p.append(enc("ch", "N"))
    p.append(enc("sf", 1))
    p.append(enc("sn1", "null"))
    p.append(enc("sn2", "null"))
    p.append(enc("nt", "WIFI"))
    p.append(enc("sm1", "null"))
    p.append(enc("sm2", "null"))
    p.append(enc("srm1", 0))
    p.append(enc("srm2", 0))
    p.append(enc("gn", 0))
    p.append(enc("newActiveVer", ""))
    p.append(enc("newPassiveVer", ""))
    p.append(enc("vgcNewActiveVer", ""))
    p.append(enc("vgcNewPassiveVer", ""))
    p.append(enc("hwVer", "PD2545MA"))
    p.append(enc("swVer", base_swver))
    p.append(enc("language", "zh_CN"))
    p.append(enc("ms", 0))
    p.append(enc("mtype", "FULL_SC"))
    p.append(enc("radiotype", "L"))
    p.append(enc("isMan", 1))
    p.append(enc("isFull", 1))
    p.append(enc("protocalversion", "1.0"))
    p.append(enc("checkTrige", "MANUL"))
    p.append(enc("isstlifeover", "false"))
    p.append(enc("hwFingerprint", ""))
    p.append(enc("logVersionNegotiation", "5360"))
    p.append(enc("ram", 16384))
    p.append(enc("rom", 1048576))
    return "&".join(p)

def run(label, fullver, swver):
    body = build(fullver, swver)
    resp = send_encrypted_request(body)
    no_update = "210" in resp.split("retcode")[-1][:20]
    print(f"[{label}] {'无更新(210)' if no_update else 'GOT RESPONSE!'}")
    if not no_update:
        print(resp[:4000])
    return resp

if __name__ == "__main__":
    # 1) current version -> expect 17.1.12.10 (what the phone sees)
    run("current 17.1.12.5", "PD2545D_A_17.1.12.5.W10.V000L1", "17.1.12.5.W10.V000L1")
    # 2) factory official -> hope for the 17.1.12.5 full package
    run("factory 16.0.14.15", "PD2545D_A_16.0.14.15.W10.V000L1", "16.0.14.15.W10.V000L1")
