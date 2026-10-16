#!/usr/bin/env python3
"""Final-fidelity replica of com.bbk.updater requests with ALL live device values (17.1.12.10)."""
import sys, time
from urllib.parse import quote
from vivo_ota_tracker import encrypt_to_jvq, decrypt_response, http_post, BASE_URL

IMEI = "000000000000000"

# Live values from adb (2026-10-16, 17.1.12.10)
LIVE = dict(
    model="PD2545", hw="PD2545MA", soft="17.1.12.10.W10.V000L1",
    version="PD2545_N_PD2545MA_17.1.12.10.W10.V000L1",
    fullver="PD2545D_A_17.1.12.10.W10.V000L1",
    s_n="", dType="phone", public_model="V2545A", cy="CN-ZH", cu="N",
    vgcSwVer="17.1.1", vgcCu="V000", hasVgc=1,
    elapsedtime=25751170, st1=25318463, st2=25318434,
    si="cn,cn", ne="cn,cn", ch="N", sf=2, sn1="46002", sn2="46007",
    sm1="null", sm2="null", srm1=0, srm2=0, gn=0,
    nt="WIFI", ms=0, mtype="FULL_SC", radiotype="L", language="zh_CN",
)

def enc(k, v):
    return f"{k}={quote(str(v), safe='')}"

def base_params(base_fullver, base_swver):
    L = dict(LIVE)
    p = []
    p += [enc("model", L["model"]), enc("imei", IMEI), enc("s_n", L["s_n"]), enc("dType", L["dType"]),
          enc("version", L["version"] if base_swver == L["soft"] else f"PD2545_N_PD2545MA_{base_swver}"),
          enc("public_model", L["public_model"]), enc("cy", L["cy"]), enc("cu", L["cu"]),
          enc("vgcSwVer", L["vgcSwVer"]), enc("vgcCu", L["vgcCu"]), enc("hasVgc", L["hasVgc"]),
          enc("elapsedtime", L["elapsedtime"]), enc("st1", L["st1"]), enc("st2", L["st2"]),
          enc("emmcid", ""), enc("fullVer", base_fullver if base_fullver else L["fullver"]),
          enc("si", L["si"]), enc("ne", L["ne"]), enc("ch", L["ch"]), enc("sf", L["sf"]),
          enc("sn1", L["sn1"]), enc("sn2", L["sn2"]), enc("nt", L["nt"]),
          enc("sm1", L["sm1"]), enc("sm2", L["sm2"]), enc("srm1", L["srm1"]), enc("srm2", L["srm2"]),
          enc("gn", L["gn"]), enc("newActiveVer", ""), enc("newPassiveVer", ""),
          enc("vgcNewActiveVer", ""), enc("vgcNewPassiveVer", ""),
          enc("hwVer", L["hw"]), enc("swVer", base_swver if base_swver else L["soft"]),
          enc("language", L["language"]), enc("ms", L["ms"]), enc("mtype", L["mtype"]), enc("radiotype", L["radiotype"])]
    return p

def reg_request(base_fullver, base_swver, is_full):
    p = base_params(base_fullver, base_swver)
    p += [enc("isMan", 1), enc("isFull", is_full), enc("protocalversion", "1.0"),
          enc("checkTrige", "MANUL"), enc("isstlifeover", "false"), enc("hwFingerprint", ""),
          enc("logVersionNegotiation", "5360"), enc("ram", 16), enc("rom", 930)]
    return "&".join(p)

def beta_request(base_fullver, base_swver, trigger):
    p = base_params(base_fullver, base_swver)
    p += [enc("trigger", trigger), enc("hwFingerprint", ""),
          enc("manual", 1), enc("push", 0), enc("logVersionNegotiation", 5360),
          enc("ram", 16), enc("rom", 930), enc("romVer", "OriginOS 7"),
          enc("action", "all"), enc("isFull", 0),
          enc("isSupportVgcTaste", 1), enc("isSupportShowNote", 1), enc("isCdma", 0)]
    return "&".join(p)

def fire(endpoint, body, label):
    try:
        raw = http_post(BASE_URL + endpoint, "jvq_param=" + encrypt_to_jvq(body))
    except Exception as e:
        print(f"[{label}] NET-ERR {e}")
        return None
    if raw.startswith(("ACw", "ACo")):
        dec = decrypt_response(raw)
        interesting = dec != '{"message":"无更新","retcode":210}' and '"210"' not in dec[:60]
        tag = "*** DATA ***" if interesting else "210/empty"
        print(f"[{label}] {tag}: {dec[:500]}")
        return dec
    print(f"[{label}] NON-ENC: {raw[:200]}")
    return None

if __name__ == "__main__":
    BASES = [
        ("17.1.12.5", "PD2545D_A_17.1.12.5.W10.V000L1", "17.1.12.5.W10.V000L1"),
        ("16.0.14.19", "PD2545D_A_16.0.14.19.W10.V000L1", "16.0.14.19.W10.V000L1"),
        ("16.0.15.6", "PD2545D_A_16.0.15.6.W10.V000L1", "16.0.15.6.W10.V000L1"),
    ]
    for name, fv, sv in BASES:
        fire("/vgc/v2/getVgcAndPatch.do?", reg_request(fv, sv, 1), f"reg full base={name}")
        fire("/beta/queryBetaOrTaste.do", beta_request(fv, sv, ""), f"beta base={name}")
    # self base (current) — nothing newer expected
    fire("/vgc/v2/getVgcAndPatch.do?", reg_request(LIVE["fullver"], LIVE["soft"], 1), "reg full base=self")
