#!/usr/bin/env python3
"""pi / PandaCode 会话用量统计。

数据源: ~/.pi/agent/sessions/<project>/*.jsonl
  - 每条 assistant 消息的 usage 字段(input/output/cacheRead/cacheWrite/reasoning/total/cost)
  - type="usage" 的独立用量记录(如 cache_warm)
  - 压缩/分支摘要条目里附带的 usage

用法:
  python tools/usage.py                      # 全部会话, 按模型分组
  python tools/usage.py --by day             # 按天
  python tools/usage.py --by session         # 按会话
  python tools/usage.py --project ganvivo    # 只统计某个目录下的会话
  python tools/usage.py --since 2026-11-01   # 起始日期(本地时间)
  python tools/usage.py --session current    # 只看当前会话($PI_SESSION_FILE)
  python tools/usage.py --json               # 机器可读输出

成本: pi 写入的 usage.cost 依赖 models.json 里的价目, 自定义 provider(如 myaxon)
没有价目时恒为 0, 因此本脚本回落到价目表自行估算:
  1) PandaCode 的 usage-prices.json(%APPDATA%/ai.pandacode.desktop, 每 token, 官方抓取)
  2) 可选的 ~/.pi/agent/prices.json(每 1M tokens, 手填覆盖)
  3) 都查不到则该组成本显示 "-"
以 "~" 开头的数字表示估算值。model id 会自动去掉 cn:/us:/eu: 前缀再查表。
"""

import argparse
import glob
import json
import os
import sys
from collections import defaultdict
from datetime import datetime

TOKEN_FIELDS = ("input", "output", "cacheRead", "cacheWrite", "reasoning", "totalTokens")
SESSION_GLOB = os.path.expanduser("~/.pi/agent/sessions/*/*.jsonl")
# 自己写的单价表(每 1M tokens, 美元), 键为 "provider/model" 或裸 model
PRICES_PATH = os.path.expanduser("~/.pi/agent/prices.json")
# PandaCode 自己维护的官方价目表(每 token, 美元), 直接复用, 无需手填
PANDACODE_PRICES = os.path.join(os.environ.get("APPDATA", ""), "ai.pandacode.desktop", "usage-prices.json")
MODEL_PREFIXES = ("cn:", "us:", "eu:")


def load_prices():
    """返回 (per_model_1m, per_token)。"""
    per_1m, per_token = {}, {}
    try:
        with open(PRICES_PATH, encoding="utf-8") as f:
            per_1m = json.load(f)
    except (OSError, ValueError):
        pass
    try:
        with open(PANDACODE_PRICES, encoding="utf-8") as f:
            per_token = json.load(f).get("prices", {})
    except (OSError, ValueError):
        pass
    return per_1m, per_token


def price_for(provider, model, prices):
    """先查自带价目(每 token), 再查手填表(每 1M), 命中后统一换算成每 token。"""
    per_1m, per_token = prices
    if not model:
        return None
    names = [model] + [model[len(p):] for p in MODEL_PREFIXES if model.startswith(p)]
    for name in names:
        if name in per_token:
            p = per_token[name]
            return {"input": p.get("input") or 0, "output": p.get("output") or 0,
                    "cacheRead": p.get("cache_read") or 0, "cacheWrite": p.get("cache_write") or 0}
    for key in (f"{provider}/{model}", model, *names):
        if key in per_1m:
            p = per_1m[key]
            return {k: (p.get(k) or 0) / 1_000_000 for k in ("input", "output", "cacheRead", "cacheWrite")}
    return None


def new_bucket():
    b = {k: 0 for k in TOKEN_FIELDS}
    b["cost"] = 0.0
    b["calls"] = 0
    return b


def add_usage(bucket, usage):
    if not isinstance(usage, dict):
        return
    bucket["calls"] += 1
    for k in TOKEN_FIELDS:
        bucket[k] += usage.get(k) or 0
    cost = usage.get("cost")
    if isinstance(cost, dict):
        bucket["cost"] += cost.get("total") or 0


def iter_sessions(paths):
    """产出 (session_meta, events)。events = [(timestamp, provider, model, usage)]"""
    for path in paths:
        meta = {"file": path, "id": os.path.basename(path)[:-6], "cwd": None, "project": None}
        events = []
        try:
            fh = open(path, encoding="utf-8")
        except OSError:
            continue
        with fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                etype = entry.get("type")
                ts = entry.get("timestamp")
                if etype == "session":
                    meta["id"] = entry.get("id", meta["id"])
                    meta["cwd"] = entry.get("cwd")
                elif etype == "message":
                    msg = entry.get("message") or {}
                    if msg.get("role") == "assistant" and msg.get("usage"):
                        events.append((ts, msg.get("provider"), msg.get("model"), msg["usage"]))
                elif etype == "usage":
                    events.append((ts, entry.get("provider"), entry.get("model"), entry.get("usage")))
                elif entry.get("usage") and etype in ("compaction", "branch_summary"):
                    events.append((ts, entry.get("provider"), entry.get("model"), entry["usage"]))
        if meta["cwd"]:
            meta["project"] = os.path.basename(meta["cwd"].replace("\\", "/").rstrip("/")) or meta["cwd"]
        else:
            meta["project"] = os.path.dirname(os.path.basename(os.path.dirname(path)))
        yield meta, events


def parse_day(ts):
    if not ts:
        return "?"
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone().strftime("%Y-%m-%d")
    except ValueError:
        return ts[:10]


def estimate_cost(b, price):
    """按单价(每 token, 美元)估算。"""
    if not price:
        return None
    return sum(b[k] * price.get(k, 0) for k in ("input", "output", "cacheRead", "cacheWrite"))


def fmt_int(n):
    return f"{n:,}"


def _w(v):
    return sum(2 if ord(ch) > 0x2E80 else 1 for ch in str(v))


def _pad(cell, width, left=False):
    gap = max(width - _w(cell), 0)
    return str(cell) + " " * gap if left else " " * gap + str(cell)


def print_table(title, rows, total):
    head = ("分组", "调用", "input", "output", "reasoning", "cacheRead", "cacheWrite", "total", "成本$")
    data = [head] + rows + [total]
    widths = [max(_w(r[i]) for r in data) for i in range(len(head))]
    line = "  ".join("─" * w for w in widths)
    print(f"\n{title}")
    for idx, row in enumerate(data):
        print("  ".join(_pad(c, widths[i], left=(i == 0)) for i, c in enumerate(row)))
        if idx == 0:
            print(line)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="pi/PandaCode 会话用量统计")
    ap.add_argument("--by", choices=("model", "day", "session", "project"), default="model")
    ap.add_argument("--project", help="项目名或路径片段, 如 ganvivo")
    ap.add_argument("--since", help="起始日期 YYYY-MM-DD(本地时间, 含当天)")
    ap.add_argument("--session", default="all", help="all | current | 会话 id 片段")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    args = ap.parse_args()

    paths = sorted(glob.glob(SESSION_GLOB))
    if args.session == "current":
        current = os.environ.get("PI_SESSION_FILE")
        if not current:
            sys.exit("PI_SESSION_FILE 未设置: 不在 pi 会话内运行, 请用 --session <id> 或 all")
        paths = [current]
    elif args.session != "all":
        paths = [p for p in paths if args.session in p]

    prices = load_prices()
    groups = defaultdict(new_bucket)
    totals = new_bucket()
    days = set()

    for meta, events in iter_sessions(paths):
        if args.project and args.project.lower() not in (meta["cwd"] or meta["file"]).lower():
            continue
        for ts, provider, model, usage in events:
            day = parse_day(ts)
            if args.since and day < args.since:
                continue
            days.add(day)
            add_usage(totals, usage)
            key = {
                "model": f"{provider}/{model}",
                "day": day,
                "session": f"{meta['project']} / {meta['id'][:8]}",
                "project": meta["project"],
            }[args.by]
            add_usage(groups[key], usage)
            groups[key].setdefault("_price", price_for(provider, model, prices))

    if not totals["calls"]:
        sys.exit("没有匹配的用量记录")

    if args.json:
        out = {"total": totals, "since": args.since, "by": args.by,
               "groups": {k: {kk: vv for kk, vv in v.items() if not kk.startswith("_")}
                          for k, v in groups.items()}}
        print(json.dumps(out, ensure_ascii=False, indent=2))
        return

    est_sum = 0.0
    rows = []
    for key in sorted(groups, key=lambda k: -groups[k]["totalTokens"]):
        b = groups[key]
        est = estimate_cost(b, b.get("_price"))
        est_sum += est or 0
        cost = f"{b['cost']:.4f}" if b["cost"] else (f"~{est:.4f}" if est else "-")
        rows.append((key, fmt_int(b["calls"]), fmt_int(b["input"]), fmt_int(b["output"]),
                     fmt_int(b["reasoning"]), fmt_int(b["cacheRead"]), fmt_int(b["cacheWrite"]),
                     fmt_int(b["totalTokens"]), cost))

    spent = totals["cost"] or est_sum
    total_cost = f"{spent:.4f}" if spent else "-"
    total = ("合计", fmt_int(totals["calls"]), fmt_int(totals["input"]), fmt_int(totals["output"]),
             fmt_int(totals["reasoning"]), fmt_int(totals["cacheRead"]),
             fmt_int(totals["cacheWrite"]), fmt_int(totals["totalTokens"]), total_cost)

    scope = f"项目≈{args.project}" if args.project else "全部会话"
    if args.session not in ("all",):
        scope += f" 会话={args.session}"
    if args.since:
        scope += f" 起 {args.since}"
    print(f"{scope} | 活跃天数 {len(days)} | 会话文件 {len(paths)} | 按{args.by}")
    print_table("用量", rows, total)


if __name__ == "__main__":
    main()
