#!/bin/sh
# 酷安运行时类导出模块构建（复用 shared/build-lib.sh 与 shared/stubs/xposed-legacy）
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$project_dir/../../.." && pwd)
. "$repo_root/shared/build-lib.sh"

init_repo_env "$repo_root"
detect_toolchain 1

build_dir="$project_dir/build"
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}
version_code=${VERSION_CODE:-1}
version_name=${VERSION_NAME:-1.0.0}

keystore=${SIGNING_KEYSTORE:-"$SIGNING_DIR/coolapk-inspect.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-coolapkinspect}
key_alias=${SIGNING_KEY_ALIAS:-coolapkinspect}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}

rm -rf "$build_dir"
mkdir -p "$build_dir/assets" "$outputs_dir"

compile_legacy_stubs "$build_dir/stubs"
compile_java_dir "$project_dir/src" "$android_jar_w;$(w "$build_dir/stubs")" "$build_dir/classes"
dex_classes "$build_dir/classes" "$(w "$build_dir/stubs")" "$build_dir/dex" 30

"$build_tools/aapt2.exe" compile --dir "$(w "$project_dir/res")" -o "$(w "$build_dir/resources.zip")"
"$build_tools/aapt2.exe" link \
  -I "$android_jar_w" \
  --manifest "$(w "$project_dir/AndroidManifest.xml")" \
  --min-sdk-version 30 --target-sdk-version 35 \
  --version-code "$version_code" --version-name "$version_name" \
  -o "$(w "$build_dir/unsigned.apk")" "$(w "$build_dir/resources.zip")"

cp "$build_dir/dex/classes.dex" "$build_dir/classes.dex"
cp "$project_dir/assets/xposed_init" "$build_dir/assets/xposed_init"
extra_assets="assets/xposed_init"
for f in tpl-classes.txt z26-classes.txt; do
  if [ -f "$project_dir/assets/$f" ]; then
    cp "$project_dir/assets/$f" "$build_dir/assets/$f"
    extra_assets="$extra_assets assets/$f"
  fi
done
( cd "$build_dir" && zip_add unsigned.apk classes.dex $extra_assets )

ensure_keystore "$keystore" "$key_alias" "$ks_pass" "$key_pass" \
  "CN=CoolapkInspect, OU=Local, O=Local, L=Local, ST=Local, C=CN"

signed_apk="$outputs_dir/coolapk-inspect-$version_name.apk"
align_and_sign "$build_dir/unsigned.apk" "$build_dir/aligned.apk" "$signed_apk" \
  "$keystore" "$key_alias" "$ks_pass" "$key_pass" true

echo "OK -> $signed_apk"
