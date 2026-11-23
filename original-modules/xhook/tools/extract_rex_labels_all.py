import glob
import os
import re
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = r"D:\D-code\ganvivo\xhook\unpack\rex-src\sources"
PATTERN = re.compile(r'vi2\.a\("((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"')

by_file = defaultdict(list)
for path in glob.glob(os.path.join(ROOT, "**", "*.java"), recursive=True):
    with open(path, encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    if "vi2.a(" not in text:
        continue
    for zh, en in PATTERN.findall(text):
        zh, en = zh.strip(), en.strip()
        if zh and len(zh) <= 80:
            by_file[path].append((zh, en))

for path, pairs in sorted(by_file.items(), key=lambda item: len(item[1]), reverse=True):
    if len(pairs) < 4:
        continue
    print("\n##", os.path.relpath(path, ROOT), len(pairs))
    seen = set()
    for zh, en in pairs:
        if zh in seen:
            continue
        seen.add(zh)
        print(" -", zh, "|", en)
