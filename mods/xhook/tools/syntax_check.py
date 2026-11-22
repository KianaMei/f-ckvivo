import glob
import os
import sys

import javalang

ROOT = r"D:\D-code\ganvivo\xhook\ours\app\src\main\java"
ok = True
for path in glob.glob(os.path.join(ROOT, "**", "*.java"), recursive=True):
    with open(path, encoding="utf-8") as handle:
        source = handle.read()
    try:
        javalang.parse.parse(source)
        print("OK  ", os.path.relpath(path, ROOT))
    except Exception as error:
        ok = False
        print("FAIL", path, error)
print("ALL OK" if ok else "HAS ERRORS")
sys.exit(0 if ok else 1)
