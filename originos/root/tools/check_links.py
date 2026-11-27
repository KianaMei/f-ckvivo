#!/usr/bin/env python3
"""检查仓库内 markdown 的相对链接是否还能打开（整理目录后用）。"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(ROOT))  # root/tools -> repo
SKIP = ('root/.upstream', 'douyin/work', 'douyin/apk', '.git')

LINK = re.compile(r'\]\(([^)\s]+)\)')


def main():
    broken = []
    total = 0
    for base, dirs, files in os.walk(ROOT):
        rel_base = os.path.relpath(base, ROOT).replace('\\', '/')
        if any(rel_base.startswith(s) for s in SKIP):
            dirs[:] = []
            continue
        dirs[:] = [d for d in dirs if d not in ('.git', '__pycache__')]
        for name in files:
            if not name.endswith('.md'):
                continue
            path = os.path.join(base, name)
            text = io.open(path, encoding='utf-8', errors='replace').read()
            for target in LINK.findall(text):
                if target.startswith(('http://', 'https://', 'mailto:', '#', 'data:')):
                    continue
                clean = target.split('#')[0].split('?')[0]
                if not clean:
                    continue
                resolved = os.path.normpath(os.path.join(base, clean))
                total += 1
                if not os.path.exists(resolved):
                    broken.append((os.path.relpath(path, ROOT), target))
    print("checked %d relative links" % total)
    for src, target in broken:
        print("BROKEN  %s  ->  %s" % (src, target))
    return 1 if broken else 0


if __name__ == '__main__':
    sys.exit(main())
