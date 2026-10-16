#!/usr/bin/env python3
"""Replica of the app's /beta/queryBetaOrTaste.do request (trial/beta channel)."""
from urllib.parse import quote
from vivo_ota_tracker import encrypt_to_jvq, decrypt_response, http_post, BASE_URL

IMEI = "000000000000000"
ENDPOINT = "/beta/queryBetaOrTaste.do"

def enc(k, v):
    return f"{k}={quote(str(v), safe='')}"

def build(is_full):
    p = []
    # --- getBaseCommonUrl (device-real values) ---
    p += [enc("model", "PD2545"), enc("imei", IMEI), enc("s_n", ""), enc("dType", "phone"),
          enc("version", "PD2545_N_PD2545MA_17.1.12.5.W10.V000L1"),
          enc("public_model", "V2545A"), enc("cy", "CN-ZH"), enc("cu", "N"),
          enc("vgcSwVer", "17.1.1"), enc("vgcCu", "V000"), enc("hasVgc", 1),
          enc("elapsedtime", 152730), enc("st1", 100000), enc("st2", 0),
          enc("emmcid", ""), enc("fullVer", "PD2545D_A_17.1.12.5.W10.V000L1"),
          enc("si", "cn,cn"), enc("ne", "cn,cn"), enc("ch", "N"), enc("sf", 1),
          enc("sn1", "null"), enc("sn2", "null"), enc("nt", "WIFI"),
          enc("sm1", "null"), enc("sm2", "null"), enc("srm1", 0), enc("srm2", 0),
          enc("gn", 0), enc("newActiveVer", ""), enc("newPassiveVer", ""),
          enc("vgcNewActiveVer", ""), enc("vgcNewPassiveVer", ""),
          # --- getBaseUrl tail ---
          enc("hwVer", "PD2545MA"), enc("swVer", "17.1.12.5.W10.V000L1"),
          enc("language", "zh_CN"), enc("ms", 0), enc("mtype", "FULL_SC"), enc("radiotype", "L"),
          # --- PublicBetaOrTrialCheckTask extras ---
          enc("trigger", "ACTIVITY_START"), enc("hwFingerprint", ""),
          enc("manual", 1), enc("push", 0), enc("logVersionNegotiation", 5360),
          enc("ram", 16384), enc("rom", 1048576), enc("romVer", "OriginOS 7"),
          enc("action", "all"), enc("isFull", is_full),
          enc("isSupportVgcTaste", 1), enc("isSupportShowNote", 1), enc("isCdma", 0)]
    return "&".join(p)

def run(is_full):
    body = "jvq_param=" + encrypt_to_jvq(build(is_full))
    raw = http_post(BASE_URL + ENDPOINT, body)
    if raw.startswith(("ACw", "ACo")):
        dec = decrypt_response(raw)
        print(f"\n===== isFull={is_full} =====")
        print(dec[:4000])
        return dec
    print(f"\n===== isFull={is_full} ===== [non-encrypted reply] {raw[:500]}")
    return raw

if __name__ == "__main__":
    run(1)
    run(0)
