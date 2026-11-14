#!/usr/bin/env python3
"""把价目表回填进 pi 的 models.json（补 cost 字段）。

背景: pi 按 models.json 里每个模型的 cost 计算美元金额; 自定义 provider
(如 myaxon / mamba-out 中转) 没有价目, 写入的 usage.cost 恒为 0。

做法: 用 PandaCode 抓好的官方价目 (%APPDATA%/ai.pandacode.desktop/usage-prices.json,
每 token 美元) 匹配 pi 的模型 id, 换算成 models.json 需要的
USD / 1M tokens 写回 cost{input,output,cacheRead,cacheWrite[,tiers]}。

匹配规则(依次):
  1. 模型 id 原样          cn:deepseek-v4.1-flash -> ?
  2. 去掉 cn:/us:/eu: 前缀  deepseek-v4.1-flash
  3. 版本号点换 p          deepseek-v4p1-flash
匹配不到就原样保留, 不猜价格。

用法:
  python tools/pi_prices.py --dry-run   # 只看要写什么, 不动文件
  python tools/pi_prices.py             # 备份后写入
  python tools/pi_prices.py --prune     # 额外删掉成本为全 0 的 cost 字段
"""

import argparse
import json
import os
import re
import shutil
import sys
from datetime import datetime

PI_MODELS = os.path.expanduser("~/.pi/agent/models.json")
PRICES = os.path.join(os.environ.get("APPDATA", ""), "ai.pandacode.desktop", "usage-prices.json")
PREFIX = re.compile(r"^(cn|us|eu):")


def load_prices():
    with open(PRICES, encoding="utf-8") as f:
        return json.load(f).get("prices", {})


def candidates(model_id):
    base = PREFIX.sub("", model_id)
    seen, out = set(), []
    for c in (model_id, base, base.replace(".", "p")):
        if c and c not in seen:
            seen.add(c)
            out.append(c)
    return out


def per_million(p, key, fallback=None):
    v = p.get(key)
    if v is None:
        v = p.get(fallback) if fallback else None
    return round((v or 0) * 1_000_000, 9)


def to_cost(p):
    """价目条目(每 token) -> models.json 的 cost(每 1M tokens)。"""
    cost = {
        "input": per_million(p, "input"),
        "output": per_million(p, "output"),
        "cacheRead": per_million(p, "cache_read", "input"),
        # 上游不单收 cache 写时按 input 价计, 避免凭空造一个折扣/溢价
        "cacheWrite": per_million(p, "cache_write", "input"),
    }
    if p.get("threshold") and p.get("input_high") is not None:
        cost["tiers"] = [{
            "inputTokensAbove": p["threshold"],
            "input": per_million(p, "input_high"),
            "output": per_million(p, "output_high"),
            "cacheRead": per_million(p, "cache_read_high", "input_high"),
            "cacheWrite": per_million(p, "cache_write_high", "input_high"),
        }]
    return cost


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="把官方价目回填进 pi models.json")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--prune", action="store_true", help="同时移除全 0 的 cost")
    args = ap.parse_args()

    prices = load_prices()
    with open(PI_MODELS, encoding="utf-8") as f:
        cfg = json.load(f)

    filled, missed, zeroed = [], [], []
    for pid, prov in cfg.get("providers", {}).items():
        for m in prov.get("models", []):
            hit = next((c for c in candidates(m["id"]) if c in prices), None)
            if not hit:
                missed.append(f"{pid}/{m['id']}")
                continue
            cost = to_cost(prices[hit])
            if not any(cost[k] for k in ("input", "output", "cacheRead", "cacheWrite")):
                zeroed.append(f"{pid}/{m['id']}")
                continue
            old = m.get("cost") or {}
            changed = any(old.get(k) != cost[k] for k in ("input", "output", "cacheRead", "cacheWrite"))
            m["cost"] = cost
            filled.append((f"{pid}/{m['id']}", hit, cost, changed))

    for pid, prov in cfg.get("providers", {}).items():
        for m in prov.get("models", []):
            if args.prune and m.get("cost") and not any(m["cost"].get(k) for k in ("input", "output", "cacheRead", "cacheWrite")):
                del m["cost"]

    print(f"价目表: {PRICES}")
    print(f"模型表: {PI_MODELS}\n")
    for name, hit, cost, changed in filled:
        flag = "更新" if changed else "无变化"
        tier = f" + {cost['tiers'][0]['inputTokensAbove']:,} tokens 分层" if cost.get("tiers") else ""
        print(f"  {name:34} <- {hit:26} ${cost['input']:g}/${cost['output']:g} per M{tier}  [{flag}]")
    if zeroed:
        print("\n价目为 0(上游免费), 未写入: " + ", ".join(zeroed))
    if missed:
        print("\n没有对应价目(保持原样): " + ", ".join(missed))

    if args.dry_run:
        print("\n--dry-run: 未写文件")
        return

    if not any(c[3] for c in filled):
        print("\n没有需要写入的变化")
        return
    backup = f"{PI_MODELS}.bak-{datetime.now():%Y%m%d-%H%M%S}-before-cost"
    shutil.copy2(PI_MODELS, backup)
    tmp = PI_MODELS + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp, PI_MODELS)
    print(f"\n已写入, 备份: {backup}")


if __name__ == "__main__":
    main()
