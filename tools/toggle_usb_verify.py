import subprocess, re, time, os, sys

ADB = os.path.expandvars(r"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe")
def sh(*args):
    return subprocess.run([ADB] + list(args), capture_output=True, text=True, timeout=60).stdout

sh("shell", "am", "start", "-a", "com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
time.sleep(3)
sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
xml = sh("exec-out", "cat", "/sdcard/ui.xml")

# Find scroll to the target if needed; first try direct
TARGET = "验证通过 USB 安装的应用"
def find_row(xml, target):
    idx = xml.find(target)
    if idx < 0: return None
    pre = xml[:idx]
    nodes = list(re.finditer(r'<node[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?>', pre))
    # walk backwards: find a clickable node or a big row node containing
    for m in reversed(nodes):
        tag = m.group(0)
        if 'clickable="true"' in tag or 'checkable="true"' in tag:
            x1,y1,x2,y2 = map(int, m.groups())
            return (x1+x2)//2, (y1+y2)//2, tag
    return None

row = find_row(xml, TARGET)
print("row:", row[:2] if row else None)
if row:
    x, y, tag = row
    # check current checked state within the row's subtree area: search 3KB after idx for checked=
    idx = xml.find(TARGET)
    after = xml[idx:idx+3000]
    checked = re.search(r'checked="(true|false)"', after)
    print("current checked:", checked.group(1) if checked else "?")
    sh("shell", "input", "tap", str(x), str(y))
    time.sleep(1.5)
    sh("shell", "uiautomator", "dump", "/sdcard/ui2.xml")
    xml2 = sh("exec-out", "cat", "/sdcard/ui2.xml")
    idx2 = xml2.find(TARGET)
    if idx2 >= 0:
        after2 = xml2[idx2:idx2+3000]
        checked2 = re.search(r'checked="(true|false)"', after2)
        print("after tap checked:", checked2.group(1) if checked2 else "?")
    else:
        print("row not found after tap (page changed?)")
else:
    # Maybe need scroll. Dump the visible texts for debugging
    texts = [t for t in re.findall(r'text="([^"]{2,40})"', xml) if t.strip()]
    print("visible:", texts[:25])
