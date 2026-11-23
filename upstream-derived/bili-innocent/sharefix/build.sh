#!/bin/sh
# Bilibili-Innocent 分享净化补丁：保留底座入口 + 追加 classes2.dex 与 ShareFixEntry
# 用法：BASE_APK=/path/to/base.apk ./build.sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$project_dir/../../.." && pwd)
. "$repo_root/shared/build-lib.sh"

init_repo_env "$repo_root"
detect_toolchain 1

outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}
base_apk=${BASE_APK:-"$LAB_CACHE/bases/innocent/innocent-1.2.2-base.apk"}
entry_list=META-INF/xposed/java_init.list
merged_entry=local.codex.innocent.sharefix.ShareFixEntry
python_bin=${PYTHON:-python}
version_name=${VERSION_NAME:-1.2.2-sharefix-rebuild}
signed_apk="$outputs_dir/BilibiliInnocent-$version_name.apk"

keystore=${SIGNING_KEYSTORE:-"$SIGNING_DIR/innocent-sharefix.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-innocent}
key_alias=${SIGNING_KEY_ALIAS:-innocent}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}
api_jar="$SHARED_LIBS/libxposed-api-102.jar"

[ -f "$base_apk" ] || { printf '缺少底座 APK: %s（可用 BASE_APK=... 指定）\n' "$base_apk" >&2; exit 1; }
[ -f "$keystore" ] || { printf '缺少现有签名文件: %s\n' "$keystore" >&2; exit 1; }
[ -f "$api_jar" ] || { printf '缺少共享库: %s\n' "$api_jar" >&2; exit 1; }
[ ! -e "$signed_apk" ] || { printf '产物已存在，请另选 VERSION_NAME: %s\n' "$signed_apk" >&2; exit 1; }
command -v "$python_bin" >/dev/null || { printf '找不到 Python 3；请设置 PYTHON\n' >&2; exit 1; }
[ -x "$build_tools/dexdump.exe" ] || { printf 'SDK build-tools 缺少 dexdump\n' >&2; exit 1; }

mkdir -p "$project_dir/build" "$outputs_dir"
build_dir=$(mktemp -d "$project_dir/build/merge.XXXXXX")
trap 'rm -rf -- "$build_dir"' EXIT
mkdir -p "$build_dir/entries/META-INF/xposed"

compile_java_dir "$project_dir/src" "$android_jar_w;$(w "$api_jar")" "$build_dir/classes"
dex_classes "$build_dir/classes" "$(w "$api_jar")" "$build_dir/dex" 30
cp "$build_dir/dex/classes.dex" "$build_dir/entries/classes2.dex"

entry_file="$build_dir/entries/$entry_list"
unzip -p "$base_apk" "$entry_list" > "$entry_file"
last_byte=$(tail -c 1 "$entry_file")
[ -z "$last_byte" ] || printf '\n' >> "$entry_file"
printf '%s\n' "$merged_entry" >> "$entry_file"

cp "$base_apk" "$build_dir/merged.apk"
(
  cd "$build_dir"
  zip_del merged.apk \
    "META-INF/MANIFEST.MF" "META-INF/*.SF" "META-INF/*.RSA" "META-INF/*.DSA" \
    "$entry_list"
)
( cd "$build_dir/entries" && zip_add_best "$build_dir/merged.apk" classes2.dex "$entry_list" )

"$build_tools/zipalign.exe" -p -f 4 "$(w "$build_dir/merged.apk")" "$(w "$build_dir/aligned.apk")"
"$python_bin" -I "$(w "$project_dir/verify_apk.py")" \
  "$(w "$base_apk")" "$(w "$build_dir/aligned.apk")" "$merged_entry" "$(w "$build_tools/dexdump.exe")"

"$build_tools/apksigner.bat" sign \
  --ks "$(w "$keystore")" --ks-key-alias "$key_alias" \
  --ks-pass "pass:$ks_pass" --key-pass "pass:$key_pass" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$(w "$build_dir/signed.apk")" "$(w "$build_dir/aligned.apk")"

"$build_tools/apksigner.bat" verify --verbose --print-certs "$(w "$build_dir/signed.apk")"
mv -- "$build_dir/signed.apk" "$signed_apk"
printf '\nOK -> %s\n' "$signed_apk"
