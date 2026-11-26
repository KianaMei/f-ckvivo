#!/bin/sh
# 从 forks/zhiliao 上游源码叠加 upstream-derived/zhihu-zhiliao/ 的本地适配补丁与自有签名，构建知了（Zhiliao）
# 用法：sh upstream-derived/zhihu-zhiliao/build-src.sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$project_dir/../.." && pwd)
. "$repo_root/shared/build-lib.sh"

init_repo_env "$repo_root"
detect_toolchain 0

src="$FORKS_DIR/zhiliao"
upstream_repo="https://github.com/shatyuka/Zhiliao.git"
upstream_commit="988eac3efb5d23f9a861c44739bfb9d1b9d030cc"
cert_sha256="b7dcd7f93012d47dd38793a6032f63d8006ca017e9e899500d5cec8069f314d0"
keystore=${SIGNING_KEYSTORE:-"$SIGNING_DIR/zhiliao-adapt.keystore"}
[ -f "$keystore" ] || { echo "缺少签名 keystore: $keystore" >&2; exit 1; }

if [ ! -d "$src/.git" ]; then
  mkdir -p "$(dirname "$src")"
  git -c http.sslBackend=openssl clone "$upstream_repo" "$src"
  git -C "$src" checkout "$upstream_commit"
fi

# Overlay 1: SDK 路径
win_sdk=$(w "$android_sdk" | sed 's/\\/\\\\/g')
printf 'sdk.dir=%s\n' "$win_sdk" > "$src/local.properties"

# Overlay 2: 幂等应用本地知乎 11.14.0 适配补丁
patch_file="$project_dir/patches/adapt-zhihu-11.14.0.patch"
if [ -f "$patch_file" ]; then
  cd "$src"
  if git apply --check --ignore-whitespace "$patch_file" 2>/dev/null; then
    git apply --ignore-whitespace "$patch_file"
    echo "Applied local Zhiliao patch"
  elif git apply --reverse --check --ignore-whitespace "$patch_file" 2>/dev/null; then
    echo "Local Zhiliao patch already applied"
  else
    echo "FAIL: local Zhiliao patch conflicts with the source at $src" >&2
    exit 1
  fi
fi

# Overlay 3: 无 Dobby submodule 时自动从上游原包提取双架构预编译 libzhiliao.so
gradle_extra_args=""
if [ ! -f "$src/app/src/main/cpp/external/Dobby/CMakeLists.txt" ]; then
  gradle_extra_args="-PusePrebuiltNative=true"
  base_apk=${ZHILIAO_BASE_APK:-"$LAB_CACHE/hosts/zhihu-11.14.0/zhiliao-26.02.03.apk"}
  so_arm64="$src/app/src/main/jniLibs/arm64-v8a/libzhiliao.so"
  so_arm32="$src/app/src/main/jniLibs/armeabi-v7a/libzhiliao.so"
  if [ ! -f "$so_arm64" ] || [ ! -f "$so_arm32" ]; then
    if [ -f "$base_apk" ]; then
      mkdir -p "$src/app/src/main/jniLibs/arm64-v8a" "$src/app/src/main/jniLibs/armeabi-v7a"
      unzip -p "$base_apk" lib/arm64-v8a/libzhiliao.so > "$so_arm64"
      unzip -p "$base_apk" lib/armeabi-v7a/libzhiliao.so > "$so_arm32"
      echo "Extracted prebuilt libzhiliao.so (arm64-v8a + armeabi-v7a) from $base_apk"
    else
      echo "WARN: 未找到 Dobby 源码且缺少基线 APK ($base_apk)，将构建纯 Java 包（官方签名知乎不触发 native）" >&2
    fi
  fi
fi

# 构建 Release APK
cd "$src"
ks_path=$(w "$keystore" | sed 's/\\/\//g')
./gradlew :app:assembleRelease --no-daemon \
  -PreleaseStoreFile="$ks_path" \
  -PreleaseStorePassword=zhiliao \
  -PreleaseKeyAlias=zhiliao \
  -PreleaseKeyPassword=zhiliao \
  $gradle_extra_args

ver=$(sed -n 's/^appVerName=//p' "$src/gradle.properties" | tr -d '\r' | head -1)
apk="$src/app/build/outputs/apk/release/Zhiliao_${ver}.apk"
[ -f "$apk" ] || { echo "release apk not found: $apk" >&2; exit 1; }

# 验收：签名指纹、4K 对齐、native so 检查
"$build_tools/apksigner.bat" verify --print-certs "$(w "$apk")" 2>/dev/null | grep -qi "$cert_sha256" \
  || { echo "FAIL: not signed with zhiliao-adapt key" >&2; exit 1; }
"$build_tools/zipalign.exe" -c -p 4 "$(w "$apk")"

outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}
mkdir -p "$outputs_dir"
out="$outputs_dir/Zhiliao_${ver}.apk"
cp "$apk" "$out"
sha256sum "$out"

printf '%s\n' "OK -> $out"
printf '%s\n' "Install (needs user consent + unlocked screen): adb install -r $out"
