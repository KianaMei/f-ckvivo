#!/bin/sh
# Windows / Git Bash 构建脚本：编译 -> dex -> aapt2 打包 -> 对齐 -> 签名
# 依赖：JDK 17+、Android SDK（自动探测；可用 ANDROID_SDK_ROOT / JAVA_HOME 覆盖）
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
keystore=${SIGNING_KEYSTORE:-"$signing_dir/fq-fold-pad.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-fqfoldpad}
key_alias=${SIGNING_KEY_ALIAS:-fqfoldpad}
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
(
  cd "$build_dir"
  zip_add unsigned.apk classes.dex assets/xposed_init
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
    -dname "CN=FqFoldPad, OU=Local, O=Local, L=Local, ST=Local, C=CN"
fi

signed_apk="$outputs_dir/fq-fold-pad-$version_name.apk"
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
