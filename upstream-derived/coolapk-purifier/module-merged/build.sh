#!/bin/sh
# 「酷安净化」合并模块：净化本体 + 回复赞助过滤（双入口合包）
# 用法：./build.sh  或  BASE_APK=/path/to/base.apk ./build.sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$project_dir/../../.." && pwd)
. "$repo_root/shared/build-lib.sh"

init_repo_env "$repo_root"
detect_toolchain 1

build_dir="$project_dir/build"
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}
base_apk=${BASE_APK:-"$LAB_CACHE/bases/coolapk/coolapk-purifier-v2.9.0.apk"}
base_entry=${BASE_ENTRY:-}
merged_entry=${MERGED_ENTRY:-local.codex.coolapk.MergedHook}
version_name=${VERSION_NAME:-2.9.0-merged}

keystore=${SIGNING_KEYSTORE:-"$SIGNING_DIR/coolapk-inspect.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-coolapkinspect}
key_alias=${SIGNING_KEY_ALIAS:-coolapkinspect}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}

[ -f "$base_apk" ] || { echo "缺少底座 APK: $base_apk（可用 BASE_APK=... 指定）" >&2; exit 1; }
[ -f "$keystore" ] || { echo "缺少签名 keystore: $keystore" >&2; exit 1; }

if [ -z "$base_entry" ]; then
  base_entry=$(unzip -p "$base_apk" META-INF/xposed/java_init.list | tr -d '\r' | sed '/^$/d' | head -n1)
fi
[ -n "$base_entry" ] || { echo "无法从底座 APK 读取 META-INF/xposed/java_init.list" >&2; exit 1; }

rm -rf "$build_dir"
mkdir -p "$build_dir/entries/META-INF/xposed" "$outputs_dir"

compile_java_dir "$project_dir/stubs" "$android_jar_w" "$build_dir/stubs"
compile_java_dir "$project_dir/src" "$android_jar_w;$(w "$build_dir/stubs")" "$build_dir/classes"
dex_classes "$build_dir/classes" "$(w "$build_dir/stubs")" "$build_dir/dex" 30
cp "$build_dir/dex/classes.dex" "$build_dir/entries/classes2.dex"

cp "$base_apk" "$build_dir/merged.apk"
(
  cd "$build_dir"
  zip_del merged.apk \
    "META-INF/MANIFEST.MF" "META-INF/*.SF" "META-INF/*.RSA" "META-INF/*.DSA" \
    "META-INF/xposed/java_init.list"
)
printf '%s\n%s\n' "$base_entry" "$merged_entry" > "$build_dir/entries/META-INF/xposed/java_init.list"
( cd "$build_dir/entries" && zip_add_best "$build_dir/merged.apk" classes2.dex META-INF/xposed/java_init.list )

signed_apk="$outputs_dir/coolapk-purifier-merged-$version_name.apk"
align_and_sign "$build_dir/merged.apk" "$build_dir/aligned.apk" "$signed_apk" \
  "$keystore" "$key_alias" "$ks_pass" "$key_pass" true

echo "java_init.list:"
unzip -p "$signed_apk" META-INF/xposed/java_init.list | sed 's/^/  /'
echo "OK -> $signed_apk"
