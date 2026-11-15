#!/bin/sh
# Windows / Git Bash 构建脚本：编译 -> dex -> aapt2 打包 -> 对齐 -> 签名
# 依赖：JDK 17+、Android SDK（platforms/android-35 + build-tools/35.0.0）
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_dir="$project_dir/build"
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}

android_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"C:/Users/$USERNAME/AppData/Local/AndroidSdk"}}
android_jar="$android_sdk/platforms/android-35/android.jar"
build_tools=${ANDROID_BUILD_TOOLS:-"$android_sdk/build-tools/35.0.0"}
java_home=${JAVA_HOME:-"C:/Program Files/jdk-21.0.8+9"}

signing_dir=${SIGNING_DIR:-"$project_dir/.signing"}
keystore=${SIGNING_KEYSTORE:-"$signing_dir/coolapk-inspect.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-coolapkinspect}
key_alias=${SIGNING_KEY_ALIAS:-coolapkinspect}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}

version_code=${VERSION_CODE:-1}
version_name=${VERSION_NAME:-1.0.0}

w() { cygpath -w "$1"; }

java_home=$(w "$java_home")
android_jar=$(w "$android_jar")
build_tools=$(w "$build_tools")

mkdir -p "$build_dir/stubs" "$build_dir/classes" "$build_dir/dex" "$build_dir/assets" "$outputs_dir" "$signing_dir"
rm -rf "$build_dir/stubs" "$build_dir/classes" "$build_dir/dex" "$build_dir/assets"
mkdir -p "$build_dir/stubs" "$build_dir/classes" "$build_dir/dex" "$build_dir/assets"

# 1. Xposed API 桩
find "$project_dir/stubs" -name '*.java' -print0 |
  xargs -0 "$java_home/bin/javac.exe" -nowarn -source 8 -target 8 \
    -cp "$android_jar" \
    -d "$(w "$build_dir/stubs")"

# 2. 模块代码
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

# 4. 资源 + 清单
"$build_tools/aapt2.exe" compile --dir "$(w "$project_dir/res")" -o "$(w "$build_dir/resources.zip")"
"$build_tools/aapt2.exe" link \
  -I "$android_jar" \
  --manifest "$(w "$project_dir/AndroidManifest.xml")" \
  --min-sdk-version 30 \
  --target-sdk-version 35 \
  --version-code "$version_code" \
  --version-name "$version_name" \
  -o "$(w "$build_dir/unsigned.apk")" \
  "$(w "$build_dir/resources.zip")"

# 5. 塞入 dex 与 xposed_init
cp "$build_dir/dex/classes.dex" "$build_dir/classes.dex"
cp "$project_dir/assets/xposed_init" "$build_dir/assets/xposed_init"
if [ -f "$project_dir/assets/tpl-classes.txt" ]; then
  cp "$project_dir/assets/tpl-classes.txt" "$build_dir/assets/tpl-classes.txt"
fi
if [ -f "$project_dir/assets/z26-classes.txt" ]; then
  cp "$project_dir/assets/z26-classes.txt" "$build_dir/assets/z26-classes.txt"
fi
(
  cd "$build_dir"
  zip -q -X unsigned.apk classes.dex assets/xposed_init assets/tpl-classes.txt assets/z26-classes.txt
)

# 6. 对齐 + 签名
"$build_tools/zipalign.exe" -p -f 4 "$(w "$build_dir/unsigned.apk")" "$(w "$build_dir/aligned.apk")"

if [ ! -f "$keystore" ]; then
  "$java_home/bin/keytool.exe" -genkeypair \
    -keystore "$(w "$keystore")" \
    -storepass "$ks_pass" \
    -keypass "$key_pass" \
    -alias "$key_alias" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=CoolapkInspect, OU=Local, O=Local, L=Local, ST=Local, C=CN"
fi

signed_apk="$outputs_dir/coolapk-inspect-$version_name.apk"
"$build_tools/apksigner.bat" sign \
  --ks "$(w "$keystore")" \
  --ks-key-alias "$key_alias" \
  --ks-pass "pass:$ks_pass" \
  --key-pass "pass:$key_pass" \
  --out "$(w "$signed_apk")" \
  "$(w "$build_dir/aligned.apk")"

"$build_tools/apksigner.bat" verify --verbose "$(w "$signed_apk")"

echo
echo "OK -> $signed_apk"
