#!/bin/sh
# Build QuietBox from upstream source with our local overlay, for the current host version.
#
# What "our modifications" means here (keep this list true):
#   1. Sign with .signing/quietbox-port.keystore  -> adb install -r over the installed port.
#   2. release-trust.properties pinned to OUR cert SHA-256 -> no "unofficial signature" banner.
#   3. Apply the maintained local hook and host anchor patch in patches/.
#
# Usage:  sh xhs/build-src.sh [--audit]
#   --audit   also run probe_anchors.py against work/device-xhs-<ver>-<code>/base.apk if present.
# Re-run after upstream updates: clones are pulled --ff-only first.
#
# Machine prerequisites (see NOTES.md): SDK platforms/android-37 must be a REAL directory whose
# source.properties says AndroidVersion.ApiLevel=37 (copy of android-37.0 with the level patched);
# a junction or fractional level makes AGP 8.13 fail target resolution.
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
src="$project_dir/work/upstream-quietbox"
core="$project_dir/work/upstream-xposed-core"
cert_sha256=dae30a0a98b6ed9e915b712e6cd1ed4cec0e2f0dd06c57e0fc0e6b49ffe5f97f

clone_or_pull() {
  repo=$1; dir=$2
  if [ ! -d "$dir/.git" ]; then
    git -c http.sslBackend=openssl clone --depth 50 "$repo" "$dir"
  else
    git -C "$dir" fetch origin && git -C "$dir" pull --ff-only origin "$(git -C "$dir" rev-parse --abbrev-ref HEAD)"
  fi
}

clone_or_pull https://codefloe.com/quiet-tools/quietbox.git "$src"
clone_or_pull https://codefloe.com/quiet-tools/xposed-core.git "$core"

# --- Overlay 1: sign with our key (storeFile is resolved by rootProject.file, relative to $src) ---
cat > "$src/keystore.properties" <<EOF
storeFile=../../.signing/quietbox-port.keystore
storePassword=quietboxport
keyAlias=quietboxport
keyPassword=quietboxport
EOF

# --- Overlay 2: signature self-check trusts OUR cert, not upstream's ---
cat > "$src/release-trust.properties" <<EOF
# 本地覆盖（xhs 战线）：改为自有密钥 quietbox-port 的证书，避免自编译包弹"非官方签名"警告。
certificateSha256=$cert_sha256
EOF

# --- Overlay 3: SDK location for both composite-build roots ---
sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/AppData/Local/Android/Sdk"}}
win_sdk=$(cygpath -w "$sdk_dir")
printf 'sdk.dir=%s\n' "$(printf '%s' "$win_sdk" | sed 's/\\/\\\\/g')" > "$src/local.properties"
cp "$src/local.properties" "$core/local.properties"

# --- Overlay 4: apply the local QuietBox patch ---
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

# --- Build ---
cd "$src"
XPOSED_CORE_PATH="$core" ./gradlew :app:assembleRelease --no-daemon
apk="$src/app/build/outputs/apk/release/quietbox-$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1).apk"
[ -f "$apk" ] || { echo "release apk not found: $apk" >&2; exit 1; }

# --- Acceptance: cert, alignment, packaged anchors == source ---
bt=$(ls -d "$sdk_dir"/build-tools/* | sort -V | tail -n1)
"$bt/apksigner.bat" verify --print-certs "$apk" 2>/dev/null | grep -qi "dae30a0a98b6ed9e915b712e6cd1ed4cec0e2f0dd06c57e0fc0e6b49ffe5f97f" \
  || { echo "FAIL: not signed with quietbox-port key" >&2; exit 1; }
"$bt/zipalign.exe" -c -p 4 "$apk"
unzip -p "$apk" anchors.json > "$src/packaged-anchors.json"
cmp "$src/app/src/main/resources/anchors.json" "$src/packaged-anchors.json" \
  || { echo "FAIL: packaged anchors.json differs from source" >&2; exit 1; }
rm -f "$src/packaged-anchors.json"

target=$(python -I "$project_dir/tools/profile_info.py" "$src/app/src/main/resources/anchors.json" --app-version)
mkdir -p "$project_dir/dist"
out="$project_dir/dist/quietbox-$(basename "$apk" .apk | sed 's/^quietbox-//')-xhs${target}.apk"
cp "$apk" "$out"
sha256sum "$out"

# --- Optional: anchor audit against a pulled host APK ---
if [ "${1:-}" = "--audit" ]; then
  code=$(python -I "$project_dir/tools/profile_info.py" "$src/app/src/main/resources/anchors.json" --version-code)
  host="$project_dir/work/device-xhs-$target-$code/base.apk"
  [ -f "$host" ] || { echo "host apk missing, skip audit: $host" >&2; exit 0; }
  python -I "$project_dir/tools/probe_anchors.py" "$host" "$src/app/src/main/resources/anchors.json" \
    --json "$project_dir/work/anchor-audit-$target-upstream.json" | tail -5
fi

printf '%s\n' "OK -> $out"
printf '%s\n' "Install (needs user consent + unlocked screen): adb install -r $out"
