#!/bin/sh
# DmSilencer (modern libxposed API 102) 构建脚本
# 复用 shared/build-lib.sh 与 shared/libs/libxposed-api-102.jar
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$project_dir/../.." && pwd)
. "$repo_root/shared/build-lib.sh"

init_repo_env "$repo_root"
detect_toolchain 1

build_dir="$project_dir/build"
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}
version_code=${VERSION_CODE:-2}
version_name=${VERSION_NAME:-0.2.0}

keystore=${SIGNING_KEYSTORE:-"$SIGNING_DIR/dmsilencer.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-dmsilencer}
key_alias=${SIGNING_KEY_ALIAS:-dmsilencer}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}

api_jar="$SHARED_LIBS/libxposed-api-102.jar"
[ -f "$api_jar" ] || { echo "缺少共享库: $api_jar" >&2; exit 1; }

rm -rf "$build_dir"
mkdir -p "$build_dir/stubs" "$build_dir/META-INF/xposed" "$outputs_dir"

unzip -q -o "$api_jar" -d "$build_dir/stubs"
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
cp "$project_dir/module-meta/module.prop" "$build_dir/META-INF/xposed/module.prop"
cp "$project_dir/module-meta/java_init.list" "$build_dir/META-INF/xposed/java_init.list"
cp "$project_dir/module-meta/scope.list" "$build_dir/META-INF/xposed/scope.list"
(
  cd "$build_dir"
  zip_add unsigned.apk classes.dex \
    META-INF/xposed/module.prop \
    META-INF/xposed/java_init.list \
    META-INF/xposed/scope.list
)

ensure_keystore "$keystore" "$key_alias" "$ks_pass" "$key_pass" \
  "CN=DmSilencer, OU=Local, O=Local, L=Local, ST=Local, C=CN"

signed_apk="$outputs_dir/DmSilencer-$version_name.apk"
align_and_sign "$build_dir/unsigned.apk" "$build_dir/aligned.apk" "$signed_apk" \
  "$keystore" "$key_alias" "$ks_pass" "$key_pass" false

echo "OK -> $signed_apk"
