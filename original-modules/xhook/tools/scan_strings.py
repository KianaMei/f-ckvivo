"""Extract strings of interest from an APK (classes*.dex + all text-ish entries)."""

import io
import os
import re
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PATH = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else None

PRINTABLE = re.compile(rb"[\x20-\x7e]{4,}")
UTF16 = re.compile(rb"(?:[\x20-\x7e]\x00){4,}")

PATTERNS = [
    r"https?://[^\s\"'<> )]+",
    r"\b[A-Za-z0-9.-]+\.(?:com|net|org|io|top|xyz|cn|me|dev|app|cc|tv|ru|tk)\b",
    r"\b(?:\d{1,3}\.){3}\d{1,3}\b",
    r"Runtime\.getRuntime|ProcessBuilder|/system/(?:x)?bin/(?:su|sh)|dex2oat|DexClassLoader|InMemoryDexClassLoader|PathClassLoader",
    r"LSPosed|libxposed|xposed|de\.robv\.android\.xposed|XposedBridge",
    r"getExternalStorage|MediaStore|ContentResolver|ContactsContract|SmsManager|ClipboardManager|AccessibilityService",
    r"REQUEST_INSTALL_PACKAGES|PackageInstaller|ACTION_INSTALL|setComponentEnabledSetting",
    r"api\.github|github\.com|t\.me|telegram|cdn\.|update",
]

out = []
stats = {"entries": 0, "dex": 0, "strings": 0}
with zipfile.ZipFile(PATH) as z:
    for info in z.infolist():
        if info.is_dir():
            continue
        name = info.filename
        if not re.search(r"\.(dex|so|txt|json|xml|html|js|properties|version|bin|dat)$", name, re.I) and name != "resources.arsc":
            continue
        try:
            data = z.read(info)
        except Exception:
            continue
        stats["entries"] += 1
        if name.endswith(".dex"):
            stats["dex"] += 1
        found = set()
        for m in PRINTABLE.finditer(data):
            found.add(m.group().decode("ascii", "replace"))
        if name.endswith(".dex") or name.endswith(".so"):
            for m in UTF16.finditer(data):
                found.add(m.group().decode("utf-16-le", "replace"))
        stats["strings"] += len(found)
        for s in found:
            for pat in PATTERNS:
                if re.search(pat, s, re.I):
                    found_flag = True
                    break
            else:
                found_flag = False
            if found_flag:
                out.append((name, s))

seen = set()
lines = []
for name, s in out:
    key = (name, s)
    if key in seen:
        continue
    seen.add(key)
    lines.append("%s\t%s" % (name, s))

text = "\n".join(lines)
if OUT:
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)
    print("stats:", stats, "interesting:", len(lines), "->", OUT)
else:
    print(text)
