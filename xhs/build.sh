#!/bin/sh
# Build a minimal QuietBox port for the target XHS version described by a profile.
# No device command is run here. Re-run with FORCE=1 to replace prior outputs.
#
# Common overrides:
#   ANCHOR_PROFILE=profiles/anchors-9.55.0.json
#   XHS_APK=work/device-xhs-9.55.0-9550805/base.apk
#   PATCH_OLD=9.54.0                 # module DEX target string to replace
#   PATCH_OLD=                       # explicitly disable DEX version patching
#   MODULE_VERSION=0.9.9
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
profile=${ANCHOR_PROFILE:-"$project_dir/profiles/anchors-9.54.0.json"}
base_apk=${BASE_APK:-"$project_dir/apk/quietbox-0.9.9.apk"}

android_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"C:/Users/$USERNAME/AppData/Local/AndroidSdk"}}
build_tools=${ANDROID_BUILD_TOOLS:-"$android_sdk/build-tools/35.0.0"}
java_home=${JAVA_HOME:-"C:/Program Files/jdk-21.0.8+9"}
python_bin=${PYTHON:-python}

signing_dir=${SIGNING_DIR:-"$project_dir/.signing"}
keystore=${SIGNING_KEYSTORE:-"$signing_dir/quietbox-port.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-quietboxport}
key_alias=${SIGNING_KEY_ALIAS:-quietboxport}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}

[ -f "$profile" ] || { printf '%s\n' "Missing anchor profile: $profile" >&2; exit 1; }
meta() {
  "$python_bin" -I "$project_dir/tools/profile_info.py" "$profile" "$1"
}
target_version=$(meta --app-version)
target_code=$(meta --version-code)
source_version=$(meta --source-version)
module_version=${MODULE_VERSION:-0.9.9}

xhs_apk=${XHS_APK:-"$project_dir/work/device-xhs-$target_version-$target_code/base.apk"}
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}
output_apk=${OUTPUT_APK:-"$outputs_dir/quietbox-$module_version-xhs$target_version.apk"}
build_dir="$project_dir/build/quietbox-$target_version"

if [ "${PATCH_OLD+x}" = "x" ]; then
  patch_old=$PATCH_OLD
else
  patch_old=$source_version
fi
patch_new=${PATCH_NEW:-$target_version}

if [ -e "$build_dir" ] || [ -e "$output_apk" ]; then
  if [ "${FORCE:-0}" != "1" ]; then
    printf '%s\n' "Build output exists. Inspect it first, then rerun with FORCE=1." >&2
    exit 1
  fi
  rm -rf "$build_dir"
  rm -f "$output_apk"
fi
mkdir -p "$build_dir/payload" "$outputs_dir"

w() { cygpath -w "$1"; }
build_tools=$(w "$build_tools")
java_home=$(w "$java_home")

[ -f "$base_apk" ] || { printf '%s\n' "Missing base APK: $base_apk" >&2; exit 1; }
[ -f "$xhs_apk" ] || { printf '%s\n' "Missing XHS audit input: $xhs_apk" >&2; exit 1; }

# 1. Validate all anchors and auxiliary classes against the target host APK.
"$python_bin" -I "$project_dir/tools/probe_anchors.py" \
  "$xhs_apk" "$profile" --json "$build_dir/anchor-audit.json" \
  > "$build_dir/anchor-audit.txt"

# 2. Optionally patch the module's shared target-version string.
unzip -p "$base_apk" classes.dex > "$build_dir/original-classes.dex"
do_patch=0
if [ -n "$patch_old" ] && [ "$patch_old" != "$patch_new" ]; then
  "$python_bin" -I "$project_dir/tools/patch_dex_version.py" \
    "$build_dir/original-classes.dex" "$build_dir/payload/classes.dex" \
    --old "$patch_old" --new "$patch_new"
  do_patch=1
else
  cp "$build_dir/original-classes.dex" "$build_dir/payload/classes.dex"
  if [ -z "$patch_old" ]; then
    printf '%s\n' "No source version in profile; skipping DEX version patch. Set PATCH_OLD to enable it." >&2
  fi
fi
cp "$profile" "$build_dir/payload/anchors.json"

# 3. Preserve other payload bytes, removing signatures and stale DEX profiles.
# baseline.prof(m) binds the original classes.dex checksum and blocks reinstall.
cp "$base_apk" "$build_dir/unsigned.apk"
(
  cd "$build_dir"
  zip -q -d unsigned.apk \
    "META-INF/MANIFEST.MF" "META-INF/*.SF" "META-INF/*.RSA" \
    "META-INF/*.DSA" "META-INF/*.EC" \
    classes.dex anchors.json \
    assets/dexopt/baseline.prof assets/dexopt/baseline.profm
)
(
  cd "$build_dir/payload"
  zip -q -X -9 "$(w "$build_dir/unsigned.apk")" classes.dex anchors.json
)

# 4. Align and sign with a stable project-local key for future in-place updates.
if [ ! -f "$keystore" ]; then
  mkdir -p "$signing_dir"
  "$java_home/bin/keytool.exe" -genkeypair \
    -keystore "$(w "$keystore")" -storepass "$ks_pass" \
    -alias "$key_alias" -keypass "$key_pass" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=QuietBox Port, OU=Local, O=ganvivo, L=Unknown, ST=Unknown, C=CN"
fi
"$build_tools/zipalign.exe" -p -f 4 \
  "$(w "$build_dir/unsigned.apk")" "$(w "$build_dir/aligned.apk")"
"$build_tools/apksigner.bat" sign \
  --ks "$(w "$keystore")" --ks-key-alias "$key_alias" \
  --ks-pass "pass:$ks_pass" --key-pass "pass:$key_pass" \
  --out "$(w "$output_apk")" "$(w "$build_dir/aligned.apk")"

# 5. Offline acceptance: archive, payload allowlist, DEX, signature and alignment.
if [ "$do_patch" = "1" ]; then
  "$python_bin" -I "$project_dir/tools/verify_apk.py" \
    "$base_apk" "$output_apk" "$profile" \
    --old-version "$patch_old" --new-version "$patch_new"
else
  "$python_bin" -I "$project_dir/tools/verify_apk.py" \
    "$base_apk" "$output_apk" "$profile" --skip-version-patch
fi
"$build_tools/apksigner.bat" verify --verbose --print-certs "$(w "$output_apk")"
"$build_tools/zipalign.exe" -c -p 4 "$(w "$output_apk")"
sha256sum "$base_apk" "$output_apk"
printf '%s\n' "Target: XHS $target_version / $target_code"
printf '%s\n' "OK -> $output_apk"
printf '%s\n' "Signer key: $keystore (keep it for future in-place QuietBox updates)"
