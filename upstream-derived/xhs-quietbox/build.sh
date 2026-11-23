#!/bin/sh
# 离线重打包旧版 QuietBox APK（按 profiles/*.json 替换 anchors.json 并重签；日常推荐用 build-src.sh）
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$project_dir/../.." && pwd)
. "$repo_root/shared/build-lib.sh"

init_repo_env "$repo_root"
detect_toolchain 0

python_bin=${PYTHON:-python}
profile=${ANCHOR_PROFILE:-"$project_dir/profiles/anchors-9.54.0.json"}
base_apk=${BASE_APK:-"$LAB_CACHE/bases/xhs/quietbox-0.9.9.apk"}

keystore=${SIGNING_KEYSTORE:-"$SIGNING_DIR/quietbox-port.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-quietboxport}
key_alias=${SIGNING_KEY_ALIAS:-quietboxport}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}

[ -f "$profile" ] || { printf '%s\n' "Missing anchor profile: $profile" >&2; exit 1; }
meta() { "$python_bin" -I "$project_dir/tools/profile_info.py" "$profile" "$1"; }
target_version=$(meta --app-version)
target_code=$(meta --version-code)
source_version=$(meta --source-version)
module_version=${MODULE_VERSION:-0.9.9}

xhs_apk=${XHS_APK:-"$LAB_CACHE/hosts/device-xhs-$target_version-$target_code/base.apk"}
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}
output_apk=${OUTPUT_APK:-"$outputs_dir/quietbox-$module_version-xhs$target_version.apk"}
build_dir="$project_dir/build/quietbox-$target_version"

if [ "${PATCH_OLD+x}" = "x" ]; then patch_old=$PATCH_OLD; else patch_old=$source_version; fi
patch_new=${PATCH_NEW:-$target_version}

if [ -e "$build_dir" ] || [ -e "$output_apk" ]; then
  [ "${FORCE:-0}" = "1" ] || { printf '%s\n' "Build output exists. Rerun with FORCE=1." >&2; exit 1; }
  rm -rf "$build_dir" "$output_apk"
fi
mkdir -p "$build_dir/payload" "$outputs_dir"

[ -f "$base_apk" ] || { printf '%s\n' "Missing base APK: $base_apk" >&2; exit 1; }
[ -f "$xhs_apk" ] || { printf '%s\n' "Missing XHS audit input: $xhs_apk" >&2; exit 1; }

"$python_bin" -I "$project_dir/tools/probe_anchors.py" \
  "$xhs_apk" "$profile" --json "$build_dir/anchor-audit.json" > "$build_dir/anchor-audit.txt"

unzip -p "$base_apk" classes.dex > "$build_dir/original-classes.dex"
do_patch=0
if [ -n "$patch_old" ] && [ "$patch_old" != "$patch_new" ]; then
  "$python_bin" -I "$project_dir/tools/patch_dex_version.py" \
    "$build_dir/original-classes.dex" "$build_dir/payload/classes.dex" \
    --old "$patch_old" --new "$patch_new"
  do_patch=1
else
  cp "$build_dir/original-classes.dex" "$build_dir/payload/classes.dex"
fi
cp "$profile" "$build_dir/payload/anchors.json"

cp "$base_apk" "$build_dir/unsigned.apk"
(
  cd "$build_dir"
  zip_del unsigned.apk \
    "META-INF/MANIFEST.MF" "META-INF/*.SF" "META-INF/*.RSA" "META-INF/*.DSA" "META-INF/*.EC" \
    classes.dex anchors.json assets/dexopt/baseline.prof assets/dexopt/baseline.profm
)
( cd "$build_dir/payload" && zip_add_best "$build_dir/unsigned.apk" classes.dex anchors.json )

ensure_keystore "$keystore" "$key_alias" "$ks_pass" "$key_pass" \
  "CN=QuietBox Port, OU=Local, O=ganvivo, L=Unknown, ST=Unknown, C=CN"

align_and_sign "$build_dir/unsigned.apk" "$build_dir/aligned.apk" "$output_apk" \
  "$keystore" "$key_alias" "$ks_pass" "$key_pass" true

if [ "$do_patch" = "1" ]; then
  "$python_bin" -I "$project_dir/tools/verify_apk.py" \
    "$base_apk" "$output_apk" "$profile" --old-version "$patch_old" --new-version "$patch_new"
else
  "$python_bin" -I "$project_dir/tools/verify_apk.py" \
    "$base_apk" "$output_apk" "$profile" --skip-version-patch
fi
"$build_tools/zipalign.exe" -c -p 4 "$(w "$output_apk")"
printf '%s\n' "OK -> $output_apk"
