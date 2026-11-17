#!/bin/sh
# Windows / Git Bash build: libxposed API -> module -> dex -> aapt2 -> align -> sign
# Requires JDK 17+ and Android SDK (auto-detected; override via ANDROID_SDK_ROOT / JAVA_HOME).
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_dir="$project_dir/build"
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}

# 工具链：环境变量优先，未设置则按本机常见位置自动探测（多机路径不固定，不要写死）
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

# 精简版 Git Bash 可能没有 zip；回退到 build-tools 自带的 aapt（多机环境不依赖系统 zip）
zip_add() {
  apk=$1; shift
  if command -v zip >/dev/null 2>&1; then
    zip -q -X "$apk" "$@"
  else
    "$build_tools/aapt.exe" add "$(w "$apk")" "$@" >/dev/null
  fi
}
zip_del() {
  apk=$1; shift
  if command -v zip >/dev/null 2>&1; then
    zip -q -d "$apk" "$@"
  else
    entries=$("$build_tools/aapt.exe" list "$(w "$apk")" | tr -d '\r')
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
keystore=${SIGNING_KEYSTORE:-"$signing_dir/origintweaks.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-origintweaks}
key_alias=${SIGNING_KEY_ALIAS:-origintweaks}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}

version_code=${VERSION_CODE:-6}
version_name=${VERSION_NAME:-0.1.6}

w() { cygpath -w "$1"; }

java_home=$(w "$java_home")
android_jar=$(w "$android_jar")
build_tools=$(w "$build_tools")

rm -rf "$build_dir"
mkdir -p "$build_dir/stubs" "$build_dir/classes" "$build_dir/dex" \
         "$build_dir/assets" "$outputs_dir" "$signing_dir"

# 1. libxposed API 102 (compile-only; never packaged).
#    NOTE: d8 is a .bat, so a "a;b" classpath gets split by cmd.exe and the
#    second entry is treated as PROGRAM input. Keep d8's classpath a single dir.
unzip -q -o "$project_dir/libs/libxposed-api-102.jar" -d "$build_dir/stubs"

# 2. module sources (libxposed API resolved from build/stubs, not packaged)
find "$project_dir/src" -name '*.java' -print0 |
  xargs -0 "$java_home/bin/javac.exe" -nowarn -source 8 -target 8 \
    -cp "$android_jar;$(w "$build_dir/stubs")" \
    -d "$(w "$build_dir/classes")"

# 3. dex
find "$build_dir/classes" -name '*.class' -print0 |
  xargs -0 "$build_tools/d8.bat" \
    --lib "$android_jar" \
    --classpath "$(w "$build_dir/stubs")" \
    --min-api 30 \
    --output "$(w "$build_dir/dex")"

# 4. resources + manifest
"$build_tools/aapt2.exe" compile --dir "$(w "$project_dir/res")" \
  -o "$(w "$build_dir/resources.zip")"
"$build_tools/aapt2.exe" link \
  -I "$android_jar" \
  --manifest "$(w "$project_dir/AndroidManifest.xml")" \
  --min-sdk-version 30 \
  --target-sdk-version 35 \
  --version-code "$version_code" \
  --version-name "$version_name" \
  -o "$(w "$build_dir/unsigned.apk")" \
  "$(w "$build_dir/resources.zip")"

# 5. dex + xposed entry
#    Modern libxposed packaging. Vector 2.2's loader decides by
#    targetApiVersion first: targetApi >= 101 -> MODERN (reads
#    META-INF/xposed/java_init.list); otherwise assets/xposed_init ->
#    LEGACY; targetApi == 100 -> UNSUPPORTED; neither -> Unusable.
#    A package carrying both entries is still MODERN when targetApi >= 101;
#    assets/xposed_init is simply ignored. Do not ship it: it makes the
#    packaging ambiguous for maintainers and tools.
cp "$build_dir/dex/classes.dex" "$build_dir/classes.dex"
mkdir -p "$build_dir/META-INF/xposed"
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

# 6. align + sign
"$build_tools/zipalign.exe" -p -f 4 \
  "$(w "$build_dir/unsigned.apk")" \
  "$(w "$build_dir/aligned.apk")"

if [ ! -f "$keystore" ]; then
  "$java_home/bin/keytool.exe" -genkeypair \
    -keystore "$(w "$keystore")" \
    -storepass "$ks_pass" \
    -keypass "$key_pass" \
    -alias "$key_alias" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=OriginTweaks, OU=Local, O=Local, L=Local, ST=Local, C=CN"
fi

signed_apk="$outputs_dir/OriginTweaks-$version_name.apk"
"$build_tools/apksigner.bat" sign \
  --ks "$(w "$keystore")" \
  --ks-key-alias "$key_alias" \
  --ks-pass "pass:$ks_pass" \
  --key-pass "pass:$key_pass" \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$(w "$signed_apk")" \
  "$(w "$build_dir/aligned.apk")"

"$build_tools/apksigner.bat" verify --verbose "$(w "$signed_apk")"

echo
echo "OK -> $signed_apk"
