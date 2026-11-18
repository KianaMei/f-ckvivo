#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_dir="$project_dir/build"
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}

android_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$android_sdk" ]; then
  for d in "$HOME/AppData/Local/Android/Sdk" "$HOME/AppData/Local/AndroidSdk" "$HOME/Android/Sdk" /c/Android/Sdk; do
    [ -d "$d/platforms" ] && { android_sdk=$d; break; }
  done
fi
java_home=${JAVA_HOME:-}
if [ -z "$java_home" ] && command -v javac >/dev/null 2>&1; then
  java_home=$(CDPATH= cd -- "$(dirname -- "$(command -v javac)")/.." && pwd)
fi
if [ -z "$java_home" ]; then
  for d in /c/jdk-* "/c/Program Files/jdk-"* "/c/Program Files/Eclipse Adoptium/"jdk*; do
    [ -x "$d/bin/javac.exe" ] && { java_home=$d; break; }
  done
fi
[ -d "${android_sdk:-}" ] || { echo "找不到 Android SDK；请设置 ANDROID_SDK_ROOT" >&2; exit 1; }
android_jar=$(ls -d "$android_sdk"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n1)
build_tools=${ANDROID_BUILD_TOOLS:-$(ls -d "$android_sdk"/build-tools/* 2>/dev/null | sort -V | tail -n1)}
[ -n "$android_jar" ] && [ -n "$build_tools" ] || { echo "SDK 缺少 platforms/build-tools：$android_sdk" >&2; exit 1; }
[ -x "${java_home:-}/bin/javac.exe" ] || { echo "找不到 JDK；请设置 JAVA_HOME" >&2; exit 1; }

w() { cygpath -w "$1"; }

java_home=$(w "$java_home")
android_jar=$(w "$android_jar")
build_tools=$(w "$build_tools")
version_code=${VERSION_CODE:-2}
version_name=${VERSION_NAME:-0.1.1}

rm -rf "$build_dir/classes" "$build_dir/stubs" "$build_dir/dex"
mkdir -p "$build_dir/classes" "$build_dir/stubs" "$build_dir/dex" "$build_dir/assets" "$outputs_dir"

find "$project_dir/stubs" -name '*.java' -print0 |
  xargs -0 "$java_home/bin/javac.exe" -nowarn -source 8 -target 8 \
    -cp "$android_jar" -d "$(w "$build_dir/stubs")"
find "$project_dir/src" -name '*.java' -print0 |
  xargs -0 "$java_home/bin/javac.exe" -nowarn -source 8 -target 8 \
    -cp "$android_jar;$(w "$build_dir/stubs")" -d "$(w "$build_dir/classes")"
find "$build_dir/classes" -name '*.class' -print0 |
  xargs -0 "$build_tools/d8.bat" --lib "$android_jar" \
    --classpath "$(w "$build_dir/stubs")" --min-api 30 --output "$(w "$build_dir/dex")"

"$build_tools/aapt2.exe" compile --dir "$(w "$project_dir/res")" -o "$(w "$build_dir/resources.zip")"
"$build_tools/aapt2.exe" link -I "$android_jar" \
  --manifest "$(w "$project_dir/AndroidManifest.xml")" \
  --min-sdk-version 30 --target-sdk-version 36 \
  --version-code "$version_code" --version-name "$version_name" \
  -o "$(w "$build_dir/unsigned.apk")" "$(w "$build_dir/resources.zip")"

cp "$build_dir/dex/classes.dex" "$build_dir/classes.dex"
cp "$project_dir/assets/xposed_init" "$build_dir/assets/xposed_init"
(
  cd "$build_dir"
  if command -v zip >/dev/null 2>&1; then
    zip -q -X unsigned.apk classes.dex assets/xposed_init
  else
    "$build_tools/aapt.exe" add "$(w "$build_dir/unsigned.apk")" classes.dex assets/xposed_init >/dev/null
  fi
)
"$build_tools/zipalign.exe" -p -f 4 "$(w "$build_dir/unsigned.apk")" "$(w "$build_dir/aligned.apk")"

signing_dir=${SIGNING_DIR:-"$project_dir/.signing"}
keystore=${SIGNING_KEYSTORE:-"$signing_dir/vivoportal.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-vivoportal}
key_alias=${SIGNING_KEY_ALIAS:-vivoportal}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}
mkdir -p "$signing_dir"
if [ ! -f "$keystore" ]; then
  "$java_home/bin/keytool.exe" -genkeypair -keystore "$(w "$keystore")" \
    -storepass "$ks_pass" -keypass "$key_pass" -alias "$key_alias" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=VPortalPlus, OU=Local, O=Local, L=Local, ST=Local, C=CN"
fi
signed_apk="$outputs_dir/vivoportal-$version_name.apk"
"$build_tools/apksigner.bat" sign --ks "$(w "$keystore")" --ks-key-alias "$key_alias" \
  --ks-pass "pass:$ks_pass" --key-pass "pass:$key_pass" \
  --out "$(w "$signed_apk")" "$(w "$build_dir/aligned.apk")"
"$build_tools/apksigner.bat" verify --verbose "$(w "$signed_apk")"
echo "OK -> $signed_apk"
