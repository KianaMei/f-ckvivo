"""Precisely namespace known private host files; public saved media is unaffected."""
from pathlib import Path
import re
import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--tests", action="store_true", help="Update private-store fixtures without changing their assertions")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1] / ("repos/DouAi/app/src/test/java/com/douai/module" if args.tests else "repos/DouAi/app/src/main/java/com/douai/module")
helper = "com.douai.module.config.HostStorageNamespace.name"
changes = []
for file in root.rglob("*.java"):
    if file.name in {"HostStorageNamespace.java", "SettingsRepository.java", "LocalAnalytics.java", "PreviewSettingsIsolationTest.java", "HostStorageNamespaceTest.java"}:
        continue
    original = file.read_text(encoding="utf-8")
    value, prefs = re.subn(r'getSharedPreferences\(\s*"(douai[_-][A-Za-z0-9_-]+)"\s*,',
                          lambda m: 'getSharedPreferences(' + helper + '("' + m[1] + '"),', original)
    value, constants = re.subn(r'(static final String (?:FILE|COMMENT_FILE|REPLY_FILE|PREFERENCES) = )"(douai[_-][A-Za-z0-9_-]+)";',
                              lambda m: m[1] + helper + '("' + m[2] + '");', value)
    paths = 0
    for name in ("douai_debug_logs", "douai-voice-source", "douai-analytics", "douai_test_chat", "douai_message_chat", "douai-comment-audio", "douai-private-voice", "douai-tts"):
        pattern = r'(get(?:Files|NoBackupFiles|Cache)Dir\(\), )"' + re.escape(name) + r'"'
        value, count = re.subn(pattern, lambda m: m[1] + helper + '("' + name + '")', value)
        paths += count
    if args.tests:
        # Tests may construct an isolated temporary parent; replace only named private children.
        known = "douai_debug_logs|douai-voice-source|douai-analytics|douai_test_chat|douai_message_chat|douai-comment-audio|douai-private-voice|douai-tts"
        value, count = re.subn(r'(new File\([^,\n]+, )"(' + known + r')"',
                              lambda m: m[1] + helper + '("' + m[2] + '")', value)
        paths += count
    if file.name == "DouAiModule.java":
        value = value.replace('"no_backup/douai/host-scan-v2.cache"', '"no_backup/" + ' + helper + '("douai") + "/host-scan-v2.cache"')
        value = value.replace('getNoBackupFilesDir(), "douai"', 'getNoBackupFilesDir(), ' + helper + '("douai")')
    if value != original:
        file.write_text(value, encoding="utf-8")
        changes.append({"file": str(file.relative_to(root)), "preferences": prefs, "constants": constants, "paths": paths})
import json
print(json.dumps(changes, indent=2))
