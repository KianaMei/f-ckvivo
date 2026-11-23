import glob
import os
import re
import sys
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = r"D:\D-code\ganvivo\xhook\unpack\rex-src\sources"
PATTERN = re.compile(r'vi2\.a\("((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"')
GENERIC = {
    "默认", "取消", "关闭", "开启", "确定", "返回", "重试", "复制", "全选", "分享",
    "隐藏", "深色", "浅色", "自动", "语言", "主题", "重试", "知道了", "输入",
}

by_file = defaultdict(list)
all_pairs = Counter()
for path in glob.glob(os.path.join(ROOT, "**", "*.java"), recursive=True):
    with open(path, encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    if "vi2.a(" not in text:
        continue
    for zh, en in PATTERN.findall(text):
        zh = zh.strip()
        en = en.strip()
        if not zh or zh in GENERIC or len(zh) > 70:
            continue
        key = (zh, en)
        all_pairs[key] += 1
        by_file[path].append(key)

print("### files with most labels")
for path, pairs in sorted(by_file.items(), key=lambda item: len(item[1]), reverse=True)[:25]:
    print(len(pairs), os.path.relpath(path, ROOT))

print("\n### labels grouped by top file")
for path, pairs in sorted(by_file.items(), key=lambda item: len(item[1]), reverse=True)[:12]:
    print("\n##", os.path.relpath(path, ROOT))
    seen = set()
    for zh, en in pairs:
        if zh in seen:
            continue
        seen.add(zh)
        print(" -", zh, "|", en)

print("\n### all unique pairs:", len(all_pairs))
