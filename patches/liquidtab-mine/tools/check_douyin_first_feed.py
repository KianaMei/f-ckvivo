import argparse
import re
import sys
from pathlib import Path


LOG_LINE = re.compile(r"^\s*\S+\s+\d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+I\s+(LiquidTab|NukeApp-OSS)\s*:\s*(.*)$")
PAGE = re.compile(r"page=\d+x\d+ h=-?\d+ intro=(-?\d+) bar=(-?\d+)")
TRACE = "feature=KeepFeedOnReturn trace_gated event="


def check(lines, pid):
    first_samples = []
    first_batch = first_hit = load_more = landing_page = other_page = None
    for index, line in enumerate(lines):
        entry = LOG_LINE.match(line)
        if not entry or entry[1] != str(pid):
            continue
        tag, message = entry.group(2, 3)
        if tag == "LiquidTab":
            if message.startswith("coldstart_probe t=0ms "):
                first_samples.append((index, message.removeprefix("coldstart_probe t=0ms ").strip()))
            elif message.strip() == "first_feed_binding native_defer=true action=immediate" and first_hit is None:
                first_hit = index
        elif TRACE in message:
            event = message.split(TRACE, 1)[1].strip()
            if event == "feed_results H0 items=1" and first_batch is None:
                first_batch = index
            elif re.fullmatch(r"feed_results aE items=[1-9]\d*", event) and load_more is None:
                load_more = index
            elif re.match(r"pager_settle item=0\b", event) and landing_page is None:
                landing_page = index
            elif re.match(r"pager_settle item=[1-9]\d*\b", event) and other_page is None:
                other_page = index

    if len(first_samples) != 1:
        return 2, "INCONCLUSIVE: expected one t=0ms sample from the selected PID"
    sample_index, page_text = first_samples[0]
    page = PAGE.fullmatch(page_text)
    if not page or first_batch is None or first_batch >= sample_index:
        return 2, "INCONCLUSIVE: need an isolated landing page following H0 items=1"
    if landing_page is None or landing_page >= sample_index:
        return 2, "INCONCLUSIVE: no preceding pager confirmation for item zero"
    if other_page is not None and other_page < sample_index:
        return 2, "INCONCLUSIVE: the pager had already changed pages"
    intro, bar = map(int, page.groups())
    if intro <= 0 or bar <= 0:
        return 1, f"FAIL: first landing-page sample has intro={intro} bar={bar}"
    if first_hit is None or first_hit >= sample_index:
        return 2, "INCONCLUSIVE: visible controls without a preceding immediate-binding hit"
    if load_more is None or load_more <= sample_index:
        return 2, "INCONCLUSIVE: need a later load-more callback to establish ordering"
    return 0, f"PASS: first page has intro={intro} bar={bar} before load-more and before paging"


def main():
    parser = argparse.ArgumentParser(description="Check a single Douyin 41.2.0 cold start in threadtime logcat order.")
    parser.add_argument("--pid", type=int, required=True, help="Douyin main process ID for this run")
    parser.add_argument("log", nargs="?", type=Path, help="UTF-8 log file (default: standard input)")
    args = parser.parse_args()
    if args.log:
        with args.log.open(encoding="utf-8-sig", errors="replace") as stream:
            code, message = check(stream, args.pid)
    else:
        code, message = check(sys.stdin, args.pid)
    print(message)
    return code


if __name__ == "__main__":
    sys.exit(main())
