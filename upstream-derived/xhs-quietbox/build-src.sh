#!/bin/sh
# 从 forks/quietbox 上游源码叠加 upstream-derived/xhs-quietbox/ 的本地签名、自校验与补丁，构建静盒（QuietBox）
# 用法：sh upstream-derived/xhs-quietbox/build-src.sh [--audit]
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$project_dir/../.." && pwd)
. "$repo_root/shared/build-lib.sh"

init_repo_env "$repo_root"
detect_toolchain 0

src="$FORKS_DIR/quietbox"
core="$FORKS_DIR/xposed-core"
cert_sha256=dae30a0a98b6ed9e915b712e6cd1ed4cec0e2f0dd06c57e0fc0e6b49ffe5f97f
keystore=${SIGNING_KEYSTORE:-"$SIGNING_DIR/quietbox-port.keystore"}
[ -f "$keystore" ] || { echo "缺少签名 keystore: $keystore" >&2; exit 1; }

clone_or_pull() {
  repo=$1; dir=$2
  if [ ! -d "$dir/.git" ]; then
    mkdir -p "$(dirname "$dir")"
    git -c http.sslBackend=openssl clone --depth 50 "$repo" "$dir"
  else
    git -C "$dir" fetch origin && git -C "$dir" pull --ff-only origin "$(git -C "$dir" rev-parse --abbrev-ref HEAD)"
  fi
}

clone_or_pull https://codefloe.com/quiet-tools/quietbox.git "$src"
clone_or_pull https://codefloe.com/quiet-tools/xposed-core.git "$core"

# Overlay 1: 统一使用 shared/signing/quietbox-port.keystore（正斜杠绝对路径，兼容任意 FORKS_DIR）
ks_prop=$(w "$keystore" | sed 's/\\/\//g')
cat > "$src/keystore.properties" <<EOF
storeFile=$ks_prop
storePassword=quietboxport
keyAlias=quietboxport
keyPassword=quietboxport
EOF

# Overlay 2: 证书自校验绑定自有 cert
cat > "$src/release-trust.properties" <<EOF
# 本地覆盖（xhs-quietbox）：改为自有密钥 quietbox-port 的证书，避免自编译包弹"非官方签名"警告。
certificateSha256=$cert_sha256
EOF

# Overlay 3: SDK 路径同步到复合构建两端
win_sdk=$(w "$android_sdk" | sed 's/\\/\\\\/g')
printf 'sdk.dir=%s\n' "$win_sdk" > "$src/local.properties"
cp "$src/local.properties" "$core/local.properties"

# Overlay 4: 幂等应用本地补丁
patch_file="$project_dir/patches/ui78-coldstart-restore.patch"
if [ -f "$patch_file" ]; then
  cd "$src"
  if git apply --check --ignore-whitespace "$patch_file" 2>/dev/null; then
    git apply --ignore-whitespace "$patch_file"
    echo "Applied local QuietBox patch"
  elif git apply --reverse --check --ignore-whitespace "$patch_file" 2>/dev/null; then
    echo "Local QuietBox patch already applied"
  else
    echo "FAIL: local QuietBox patch conflicts with the source at $src" >&2
    exit 1
  fi
fi

# 构建
cd "$src"
XPOSED_CORE_PATH="$core" ./gradlew :app:assembleRelease --no-daemon
apk="$src/app/build/outputs/apk/release/quietbox-$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1).apk"
[ -f "$apk" ] || { echo "release apk not found: $apk" >&2; exit 1; }

# 验收：签名指纹、4K 对齐、打包 anchors.json 与源码一致
"$build_tools/apksigner.bat" verify --print-certs "$(w "$apk")" 2>/dev/null | grep -qi "$cert_sha256" \
  || { echo "FAIL: not signed with quietbox-port key" >&2; exit 1; }
"$build_tools/zipalign.exe" -c -p 4 "$(w "$apk")"
unzip -p "$apk" anchors.json > "$src/packaged-anchors.json"
cmp "$src/app/src/main/resources/anchors.json" "$src/packaged-anchors.json" \
  || { echo "FAIL: packaged anchors.json differs from source" >&2; exit 1; }
rm -f "$src/packaged-anchors.json"

target=$(python -I "$project_dir/tools/profile_info.py" "$src/app/src/main/resources/anchors.json" --app-version)
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}
mkdir -p "$outputs_dir"
out="$outputs_dir/quietbox-$(basename "$apk" .apk | sed 's/^quietbox-//')-xhs${target}.apk"
cp "$apk" "$out"
sha256sum "$out"

if [ "${1:-}" = "--audit" ]; then
  code=$(python -I "$project_dir/tools/profile_info.py" "$src/app/src/main/resources/anchors.json" --version-code)
  host="$LAB_CACHE/hosts/device-xhs-$target-$code/base.apk"
  [ -f "$host" ] || { echo "host apk missing, skip audit: $host" >&2; exit 0; }
  python -I "$project_dir/tools/probe_anchors.py" "$host" "$src/app/src/main/resources/anchors.json" \
    --json "$LAB_CACHE/scratch/xhs-anchor-audit-$target.json" | tail -5
fi

printf '%s\n' "OK -> $out"
printf '%s\n' "Install (needs user consent + unlocked screen): adb install -r $out"
