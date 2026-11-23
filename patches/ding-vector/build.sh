#!/bin/sh
# Patch upstream Ding for Vector, then align and sign with the repository key.
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$project_dir/../.." && pwd)
. "$repo_root/shared/build-lib.sh"

init_repo_env "$repo_root"
detect_toolchain 0

python_bin=${PYTHON:-python}
metadata="$project_dir/upstream.json"
meta() { "$python_bin" -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))[sys.argv[2]])' "$metadata" "$1"; }
package_name=$(meta package)
module_version=$(meta versionName)
version_code=$(meta versionCode)
base_apk=${BASE_APK:-"$LAB_CACHE/bases/ding/Ding-$module_version.apk"}
keystore=${SIGNING_KEYSTORE:-"$SIGNING_DIR/ding-vector.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-dingvector}
key_alias=${SIGNING_KEY_ALIAS:-dingvector}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}
output_apk=${OUTPUT_APK:-"$project_dir/dist/Ding-$module_version-vector.apk"}
build_dir="$project_dir/build/ding-$module_version-vector"

[ -f "$base_apk" ] || { printf '%s\n' "Missing upstream APK: $base_apk" >&2; exit 1; }
if [ -e "$build_dir" ] || [ -e "$output_apk" ]; then
  [ "${FORCE:-0}" = "1" ] || { printf '%s\n' "Build output exists. Rerun with FORCE=1." >&2; exit 1; }
  rm -rf "$build_dir" "$output_apk"
fi
mkdir -p "$build_dir" "$(dirname "$output_apk")"

"$python_bin" - "$base_apk" "$metadata" <<'PY'
import hashlib, json, sys
from pathlib import Path
apk, meta = map(Path, sys.argv[1:])
expected = json.loads(meta.read_text(encoding="utf-8"))["sha256"]
actual = hashlib.sha256(apk.read_bytes()).hexdigest()
if actual != expected:
    raise SystemExit("Upstream SHA-256 mismatch: expected %s, got %s" % (expected, actual))
print("Upstream SHA-256: " + actual)
PY

"$python_bin" -I "$project_dir/tools/dex_framework_patch.py" \
  "$base_apk" "$build_dir/unsigned.apk" --report "$build_dir/patch-report.json"

ensure_keystore "$keystore" "$key_alias" "$ks_pass" "$key_pass" \
  "CN=DingVector, OU=Local, O=ganvivo, L=Unknown, ST=Unknown, C=CN"

align_and_sign "$build_dir/unsigned.apk" "$build_dir/aligned.apk" "$output_apk" \
  "$keystore" "$key_alias" "$ks_pass" "$key_pass" true

"$python_bin" "$project_dir/tools/verify_apk.py" \
  "$base_apk" "$output_apk" --report "$build_dir/verify-report.json"
"$build_tools/zipalign.exe" -c -p 4 "$(w "$output_apk")"
"$build_tools/apksigner.bat" verify --verbose --print-certs "$(w "$output_apk")" \
  > "$build_dir/signature-report.txt"

package_line=$("$build_tools/aapt.exe" dump badging "$(w "$output_apk")" | head -n 1)
printf '%s\n' "$package_line" > "$build_dir/badging.txt"
case "$package_line" in
  *"name='$package_name'"*"versionCode='$version_code'"*"versionName='$module_version'"*) ;;
  *) printf '%s\n' "Unexpected package/version: $package_line" >&2; exit 1 ;;
esac
printf '%s\n' "$package_line"
printf '%s\n' "OK -> $output_apk"
