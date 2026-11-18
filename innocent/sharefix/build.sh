#!/bin/sh
# Bilibili-Innocent 分享净化补丁：保留所选底座 APK 的 Xposed 入口，
# 再追加 classes2.dex 和分享净化入口。用仓库自有 key 签名，不操作设备。
#
# 用法： ./build.sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}

base_apk=${BASE_APK:-"$project_dir/base/innocent-1.2.2-base.apk"}
entry_list=META-INF/xposed/java_init.list
merged_entry=local.codex.innocent.sharefix.ShareFixEntry
python_bin=${PYTHON:-python}

android_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$android_sdk" ]; then
  for d in "$HOME/AppData/Local/Android/Sdk" "$HOME/AppData/Local/AndroidSdk" "$HOME/Android/Sdk" /c/Android/Sdk; do
    [ -d "$d/platforms" ] && { android_sdk="$d"; break; }
  done
fi
java_home=${JAVA_HOME:-}
if [ -z "$java_home" ] && command -v javac >/dev/null 2>&1; then
  java_home=$(CDPATH= cd -- "$(dirname -- "$(command -v javac)")/.." && pwd)
fi
if [ -z "$java_home" ]; then
  for d in /c/jdk-* "/c/Program Files/jdk-"* "/c/Program Files/Eclipse Adoptium/"jdk*; do
    [ -x "$d/bin/javac.exe" ] && { java_home="$d"; break; }
  done
fi
[ -d "${android_sdk:-}" ] || { echo "找不到 Android SDK；请设置 ANDROID_SDK_ROOT" >&2; exit 1; }
android_jar=$(ls -d "$android_sdk"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n1)
build_tools=${ANDROID_BUILD_TOOLS:-$(ls -d "$android_sdk"/build-tools/* 2>/dev/null | sort -V | tail -n1)}
[ -n "$android_jar" ] && [ -n "$build_tools" ] || { echo "SDK 缺少 platforms/build-tools" >&2; exit 1; }
[ -x "${java_home:-}/bin/javac.exe" ] || { echo "找不到 JDK；请设置 JAVA_HOME" >&2; exit 1; }

zip_add() {
  apk=$1; shift
  if command -v zip >/dev/null 2>&1; then
    zip -q -X -9 "$apk" "$@"
  else
    "$build_tools/aapt.exe" add "$(w "$apk")" "$@" >/dev/null
  fi
}
zip_del() {
  apk=$1; shift
  if command -v zip >/dev/null 2>&1; then
    zip -q -d "$apk" "$@"
  else
    entries=$("$build_tools/aapt.exe" list "$(w "$apk")")
    entries=$(printf '%s' "$entries" | tr -d '\r')
    for pattern in "$@"; do
      printf "%s\n" "$entries" | while IFS= read -r entry; do
        case "$entry" in
          $pattern) "$build_tools/aapt.exe" remove "$(w "$apk")" "$entry" >/dev/null ;;
        esac
      done
    done
  fi
}

signing_dir=${SIGNING_DIR:-"$project_dir/.signing"}
keystore=${SIGNING_KEYSTORE:-"$signing_dir/innocent-sharefix.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-innocent}
key_alias=${SIGNING_KEY_ALIAS:-innocent}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}

version_name=${VERSION_NAME:-1.2.2-sharefix-rebuild}
signed_apk="$outputs_dir/BilibiliInnocent-$version_name.apk"

w() { cygpath -w "$1"; }

java_home=$(w "$java_home")
android_jar=$(w "$android_jar")
build_tools=$(w "$build_tools")

[ -f "$base_apk" ] || { printf '缺少底座 APK: %s\n' "$base_apk" >&2; exit 1; }
[ -f "$keystore" ] || { printf '缺少现有签名文件: %s\n' "$keystore" >&2; exit 1; }
[ ! -e "$signed_apk" ] || { printf '产物已存在，请另选 VERSION_NAME: %s\n' "$signed_apk" >&2; exit 1; }
command -v "$python_bin" >/dev/null || { printf '找不到 Python 3；请设置 PYTHON\n' >&2; exit 1; }
[ -x "$build_tools/dexdump.exe" ] || { printf 'SDK build-tools 缺少 dexdump\n' >&2; exit 1; }

mkdir -p "$project_dir/build" "$outputs_dir"
build_dir=$(mktemp -d "$project_dir/build/merge.XXXXXX")
trap 'rm -rf -- "$build_dir"' EXIT
mkdir -p "$build_dir/classes" "$build_dir/dex" "$build_dir/entries/META-INF/xposed"

# 1. libxposed API 102（compile-only，仅编译期 classpath，不打进 dex）
api_jar="$project_dir/libs/libxposed-api-102.jar"
[ -f "$api_jar" ] || { echo "缺少 $api_jar" >&2; exit 1; }

# 2. 补丁代码
find "$project_dir/src" -name '*.java' -print0 |
  xargs -0 "$java_home/bin/javac.exe" -nowarn -source 8 -target 8 \
    -cp "$android_jar;$(w "$api_jar")" \
    -d "$(w "$build_dir/classes")"

# 3. dex（单独成 classes2.dex，不碰底座 classes.dex）
find "$build_dir/classes" -name '*.class' -print0 |
  xargs -0 "$build_tools/d8.bat" \
    --lib "$android_jar" \
    --classpath "$(w "$api_jar")" \
    --min-api 30 \
    --output "$(w "$build_dir/dex")"
cp "$build_dir/dex/classes.dex" "$build_dir/entries/classes2.dex"

# 4. 原入口名随 R8 构建变化，逐行保留当前底座列表，仅追加分享补丁入口。
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
(
  cd "$build_dir/entries"
  zip_add "$build_dir/merged.apk" classes2.dex "$entry_list"
)

# 5. 先确认对齐后的 APK 保留原入口和原 DEX，再签名。
"$build_tools/zipalign.exe" -p -f 4 "$(w "$build_dir/merged.apk")" "$(w "$build_dir/aligned.apk")"
"$python_bin" -I "$(w "$project_dir/verify_apk.py")" \
  "$(w "$base_apk")" "$(w "$build_dir/aligned.apk")" "$merged_entry" "$build_tools/dexdump.exe"

# 6. 使用现有签名；验签成功才输出到 dist。
"$build_tools/apksigner.bat" sign \
  --ks "$(w "$keystore")" \
  --ks-key-alias "$key_alias" \
  --ks-pass "pass:$ks_pass" \
  --key-pass "pass:$key_pass" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$(w "$build_dir/signed.apk")" \
  "$(w "$build_dir/aligned.apk")"

"$build_tools/apksigner.bat" verify --verbose --print-certs "$(w "$build_dir/signed.apk")"
mv -- "$build_dir/signed.apk" "$signed_apk"
printf '\nOK -> %s\n' "$signed_apk"
