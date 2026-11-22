#!/bin/sh
# 跨战线共享 Android/Xposed 构建函数库（Windows Git Bash + POSIX 通用）
# 用法：在各模块 build.sh 开头 source "$repo_root/shared/build-lib.sh"

w() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s' "$1"
  fi
}

# 初始化仓库与统一缓存/Fork目录路径（默认位于仓库内 cache/ 与 forks/，均在 .gitignore 屏蔽；支持环境变量覆盖）
init_repo_env() {
  # 调用方需提前设置 repo_root，或传入参数 $1
  if [ -n "${1:-}" ]; then
    repo_root=$(CDPATH= cd -- "$1" && pwd)
  fi
  [ -n "${repo_root:-}" ] || { echo "build-lib.sh: 必须先设置 repo_root" >&2; exit 1; }

  LAB_CACHE=${LAB_CACHE:-"$repo_root/cache"}
  FORKS_DIR=${FORKS_DIR:-"$repo_root/forks"}
  SIGNING_DIR=${SIGNING_DIR:-"$repo_root/shared/signing"}
  SHARED_LIBS="$repo_root/shared/libs"
  SHARED_LEGACY_STUBS="$repo_root/shared/stubs/xposed-legacy"
}

# 自动探测 Android SDK 与 JDK（不写死单机路径）
detect_toolchain() {
  need_jar=${1:-1}

  android_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
  if [ -z "$android_sdk" ]; then
    for d in \
      "$HOME/AppData/Local/Android/Sdk" \
      "$HOME/AppData/Local/AndroidSdk" \
      "$HOME/Android/Sdk" \
      /c/Android/Sdk \
      /d/D-code/tools/android/sdk; do
      if [ -d "$d/build-tools" ]; then
        android_sdk=$d
        break
      fi
    done
  fi
  [ -d "${android_sdk:-}" ] || { echo "找不到 Android SDK；请设置 ANDROID_SDK_ROOT" >&2; exit 1; }

  build_tools=${ANDROID_BUILD_TOOLS:-$(ls -d "$android_sdk"/build-tools/* 2>/dev/null | sort -V | tail -n1)}
  [ -n "$build_tools" ] || { echo "SDK 缺少 build-tools：$android_sdk" >&2; exit 1; }

  if [ "$need_jar" = "1" ]; then
    android_jar=$(ls -d "$android_sdk"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n1)
    [ -n "$android_jar" ] || { echo "SDK 缺少 platforms/android-*/android.jar：$android_sdk" >&2; exit 1; }
  else
    android_jar=""
  fi

  java_home=${JAVA_HOME:-}
  if [ -z "$java_home" ] && command -v javac >/dev/null 2>&1; then
    java_home=$(CDPATH= cd -- "$(dirname -- "$(command -v javac)")/.." && pwd)
  fi
  if [ -z "$java_home" ]; then
    for d in \
      /c/jdk-* \
      "/c/Program Files/jdk-"* \
      "/c/Program Files/Eclipse Adoptium/"jdk* \
      "/c/Program Files/Java/"jdk*; do
      if [ -x "$d/bin/javac.exe" ] || [ -x "$d/bin/javac" ]; then
        java_home=$d
        break
      fi
    done
  fi
  if [ -x "${java_home:-}/bin/javac.exe" ]; then
    javac_bin="$java_home/bin/javac.exe"
    keytool_bin="$java_home/bin/keytool.exe"
  elif [ -x "${java_home:-}/bin/javac" ]; then
    javac_bin="$java_home/bin/javac"
    keytool_bin="$java_home/bin/keytool"
  else
    echo "找不到 JDK；请设置 JAVA_HOME" >&2
    exit 1
  fi

  java_home_w=$(w "$java_home")
  build_tools_w=$(w "$build_tools")
  if [ -n "$android_jar" ]; then
    android_jar_w=$(w "$android_jar")
  else
    android_jar_w=""
  fi
}

# 精简版 Git Bash 可能没有 zip；自动回退到 build-tools 的 aapt
zip_add() {
  apk=$1; shift
  if command -v zip >/dev/null 2>&1; then
    zip -q -X "$apk" "$@"
  else
    "$build_tools/aapt.exe" add "$(w "$apk")" "$@" >/dev/null
  fi
}

zip_add_best() {
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

# 编译共享 Legacy Xposed 桩
compile_legacy_stubs() {
  out_dir=$1
  mkdir -p "$out_dir"
  find "$SHARED_LEGACY_STUBS" -name '*.java' -print0 |
    xargs -0 "$javac_bin" -nowarn -source 8 -target 8 \
      -cp "$android_jar_w" -d "$(w "$out_dir")"
}

# 编译指定目录 Java 源码
compile_java_dir() {
  src_dir=$1
  cp_win=$2
  out_dir=$3
  mkdir -p "$out_dir"
  find "$src_dir" -name '*.java' -print0 |
    xargs -0 "$javac_bin" -nowarn -source 8 -target 8 \
      -cp "$cp_win" -d "$(w "$out_dir")"
}

# d8 打包为 dex（注意：d8.bat 会把含分号的 classpath 劈开，classpath 必须是单个目录或单个 jar）
dex_classes() {
  classes_dir=$1
  cp_win=$2
  out_dir=$3
  min_api=${4:-30}
  mkdir -p "$out_dir"
  find "$classes_dir" -name '*.class' -print0 |
    xargs -0 "$build_tools/d8.bat" \
      --lib "$android_jar_w" \
      --classpath "$cp_win" \
      --min-api "$min_api" \
      --output "$(w "$out_dir")"
}

# 确保签名 keystore 存在（仅在调用方允许自动生成时使用）
ensure_keystore() {
  ks=$1; alias=$2; spass=$3; kpass=$4; dname=$5
  if [ ! -f "$ks" ]; then
    mkdir -p "$(dirname "$ks")"
    "$keytool_bin" -genkeypair \
      -keystore "$(w "$ks")" \
      -storepass "$spass" \
      -keypass "$kpass" \
      -alias "$alias" \
      -keyalg RSA -keysize 4096 -validity 10000 \
      -dname "$dname"
  fi
}

# 4K 对齐 + apksigner 签名 + 验签
align_and_sign() {
  unsigned_apk=$1
  aligned_apk=$2
  signed_apk=$3
  ks=$4; alias=$5; spass=$6; kpass=$7
  v1_flag=${8:-true}

  "$build_tools/zipalign.exe" -p -f 4 "$(w "$unsigned_apk")" "$(w "$aligned_apk")"
  mkdir -p "$(dirname "$signed_apk")"
  "$build_tools/apksigner.bat" sign \
    --ks "$(w "$ks")" \
    --ks-key-alias "$alias" \
    --ks-pass "pass:$spass" \
    --key-pass "pass:$kpass" \
    --v1-signing-enabled "$v1_flag" \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$(w "$signed_apk")" \
    "$(w "$aligned_apk")"
  "$build_tools/apksigner.bat" verify --verbose "$(w "$signed_apk")" | sed -n '1,8p'
}
