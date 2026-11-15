#!/bin/sh
# FuckDrag build: libxposed API 102 -> module -> dex -> aapt2 -> align -> sign
# Requires JDK 17+ and Android SDK (platforms/android-35 + build-tools/35.0.0).
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_dir="$project_dir/build"
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}

android_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"C:/Users/$USERNAME/AppData/Local/AndroidSdk"}}
android_jar="$android_sdk/platforms/android-35/android.jar"
build_tools=${ANDROID_BUILD_TOOLS:-"$android_sdk/build-tools/35.0.0"}
java_home=${JAVA_HOME:-"C:/Program Files/jdk-21.0.8+9"}

signing_dir=${SIGNING_DIR:-"$project_dir/.signing"}
keystore=${SIGNING_KEYSTORE:-"$signing_dir/fuckdrag.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-fuckdrag}
key_alias=${SIGNING_KEY_ALIAS:-fuckdrag}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}

version_code=${VERSION_CODE:-2}
version_name=${VERSION_NAME:-0.2.0}

w() { cygpath -w "$1"; }

java_home=$(w "$java_home")
android_jar=$(w "$android_jar")
build_tools=$(w "$build_tools")

rm -rf "$build_dir"
mkdir -p "$build_dir/stubs" "$build_dir/classes" "$build_dir/dex" \
         "$build_dir/assets" "$outputs_dir" "$signing_dir"

# 1. libxposed API 102 (compile-only; never packaged).
unzip -q -o "$project_dir/libs/libxposed-api-102.jar" -d "$build_dir/stubs"

# 2. module sources.
find "$project_dir/src" -name '*.java' -print0 |
  xargs -0 "$java_home/bin/javac.exe" -nowarn -encoding UTF-8 -source 8 -target 8 \
    -cp "$android_jar;$(w "$build_dir/stubs")" \
    -d "$(w "$build_dir/classes")"

# 3. dex.
find "$build_dir/classes" -name '*.class' -print0 |
  xargs -0 "$build_tools/d8.bat" \
    --lib "$android_jar" \
    --classpath "$(w "$build_dir/stubs")" \
    --min-api 30 \
    --output "$(w "$build_dir/dex")"

# 4. resources + manifest.
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

# 5. modern libxposed packaging.
#    targetApiVersion=102 -> Vector reads META-INF/xposed/java_init.list.
#    Do not ship assets/xposed_init; it would only confuse maintainers.
cp "$build_dir/dex/classes.dex" "$build_dir/classes.dex"
mkdir -p "$build_dir/META-INF/xposed"
cp "$project_dir/module-meta/module.prop" "$build_dir/META-INF/xposed/module.prop"
cp "$project_dir/module-meta/java_init.list" "$build_dir/META-INF/xposed/java_init.list"
cp "$project_dir/module-meta/scope.list" "$build_dir/META-INF/xposed/scope.list"
(
  cd "$build_dir"
  zip -q -X unsigned.apk classes.dex \
    META-INF/xposed/module.prop \
    META-INF/xposed/java_init.list \
    META-INF/xposed/scope.list
)

# 6. align + sign.
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
    -dname "CN=FuckDrag, OU=Local, O=Local, L=Local, ST=Local, C=CN"
fi

signed_apk="$outputs_dir/FuckDrag-$version_name.apk"
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
