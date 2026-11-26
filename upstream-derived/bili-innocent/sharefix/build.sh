#!/bin/sh
# Bilibili-Innocent 分享净化补丁：保留底座入口 + 动态追加下一个 classes{N+1}.dex 与 ShareFixEntry
# 用法：BASE_APK=/path/to/base.apk ./build.sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$project_dir/../../.." && pwd)
. "$repo_root/shared/build-lib.sh"

init_repo_env "$repo_root"
detect_toolchain 1

outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}
base_apk=${BASE_APK:-"$LAB_CACHE/bases/innocent/innocent-1.2.2-base.apk"}
entry_list=META-INF/xposed/java_init.list
merged_entry=local.codex.innocent.sharefix.ShareFixEntry
python_bin=${PYTHON:-python}
version_name=${VERSION_NAME:-1.2.2-sharefix-rebuild}
signed_apk="$outputs_dir/BilibiliInnocent-$version_name.apk"

keystore=${SIGNING_KEYSTORE:-"$SIGNING_DIR/innocent-sharefix.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-innocent}
key_alias=${SIGNING_KEY_ALIAS:-innocent}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}
api_jar="$SHARED_LIBS/libxposed-api-102.jar"

[ -f "$base_apk" ] || { printf '缺少底座 APK: %s（可用 BASE_APK=... 指定）\n' "$base_apk" >&2; exit 1; }
[ -f "$keystore" ] || { printf '缺少现有签名文件: %s\n' "$keystore" >&2; exit 1; }
[ -f "$api_jar" ] || { printf '缺少共享库: %s\n' "$api_jar" >&2; exit 1; }
[ ! -e "$signed_apk" ] || { printf '产物已存在，请另选 VERSION_NAME: %s\n' "$signed_apk" >&2; exit 1; }
command -v "$python_bin" >/dev/null || { printf '找不到 Python 3；请设置 PYTHON\n' >&2; exit 1; }
[ -x "$build_tools/dexdump.exe" ] || { printf 'SDK build-tools 缺少 dexdump\n' >&2; exit 1; }

# 识别底座 APK 现有连续 DEX（classes.dex .. classesN.dex），避免多 DEX 底座被 classes2.dex 覆盖
next_dex_raw=$("$python_bin" -I - "$(w "$base_apk")" <<'PY'
import re
import sys
from zipfile import ZipFile

DEX_NAME = re.compile(r"classes([0-9]+)?\.dex")

with ZipFile(sys.argv[1]) as apk:
    names = [name for name in apk.namelist() if DEX_NAME.fullmatch(name)]

if "classes.dex" not in names or len(set(names)) != len(names):
    raise SystemExit(f"底座 APK 缺少 classes.dex 或存在重复 DEX 条目: {names}")

indices = []
for name in names:
    if name == "classes.dex":
        indices.append(1)
    else:
        suffix = DEX_NAME.fullmatch(name).group(1)
        if suffix.startswith("0") or int(suffix) < 2:
            raise SystemExit(f"底座 APK 存在非法 DEX 命名: {name}")
        indices.append(int(suffix))

if sorted(indices) != list(range(1, len(indices) + 1)):
    raise SystemExit(f"底座 APK DEX 编号不连续: {sorted(names)}")

print(f"classes{len(indices) + 1}.dex")
PY
)
next_dex=$(printf '%s' "$next_dex_raw" | tr -d '\r\n')
case "$next_dex" in
  classes[0-9]*.dex) ;;
  *) printf '无法确定下一个 DEX 文件名: %s\n' "$next_dex" >&2; exit 1 ;;
esac

mkdir -p "$project_dir/build" "$outputs_dir"
build_dir=$(mktemp -d "$project_dir/build/merge.XXXXXX")
trap 'rm -rf -- "$build_dir"' EXIT
mkdir -p "$build_dir/entries/META-INF/xposed"

compile_java_dir "$project_dir/src" "$android_jar_w;$(w "$api_jar")" "$build_dir/classes"
dex_classes "$build_dir/classes" "$(w "$api_jar")" "$build_dir/dex" 30
[ -f "$build_dir/dex/classes.dex" ] && [ ! -e "$build_dir/dex/classes2.dex" ] || {
  printf '分享补丁超出单 DEX 限制或未产出 classes.dex\n' >&2
  exit 1
}
cp "$build_dir/dex/classes.dex" "$build_dir/entries/$next_dex"

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
( cd "$build_dir/entries" && zip_add_best "$build_dir/merged.apk" "$next_dex" "$entry_list" )

"$build_tools/zipalign.exe" -p -f 4 "$(w "$build_dir/merged.apk")" "$(w "$build_dir/aligned.apk")"
"$python_bin" -I "$(w "$project_dir/verify_apk.py")" \
  "$(w "$base_apk")" "$(w "$build_dir/aligned.apk")" "$merged_entry" "$(w "$build_tools/dexdump.exe")"

# vivo 系统安装器（手动侧载）会校验 JAR 签名，只有 v2/v3 的 APK 会报「安装失败：系统原因」；
# 因此这里必须同时打开 v1。（adb install 不校 v1，所以只走 adb 时不容易暴露这个问题。）
# vivo 系统安装器（手动侧载）会校验 JAR 签名：只有 v2/v3 的 APK 在真机上手动装会报「安装失败：系统原因」，
# 而 adb install 不校 v1、所以只走 adb 时根本不会暴露。两条坑已实测：
#   1) 必须显式 --max-sdk-version，apksigner 0.9 在不传 max 时不会生成 v1（即使 --v1-signing-enabled true）；
#   2) --min-sdk-version 要 <24，否则 v1 不会被纳入签名方案（这里取 21，不影响 APK 清单里的 minSdkVersion）。
"$build_tools/apksigner.bat" sign \
  --ks "$(w "$keystore")" --ks-key-alias "$key_alias" \
  --ks-pass "pass:$ks_pass" --key-pass "pass:$key_pass" \
  --min-sdk-version 21 --max-sdk-version 2147483647 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$(w "$build_dir/signed.apk")" "$(w "$build_dir/aligned.apk")"

"$build_tools/apksigner.bat" verify --verbose "$(w "$build_dir/signed.apk")"
# v1 只在 <24 的验证区间里被检查，单独再验一次，避免「看着没 v1 其实有」的误判
"$build_tools/apksigner.bat" verify --min-sdk-version 21 --max-sdk-version 23 --verbose "$(w "$build_dir/signed.apk")" | head -4
mv -- "$build_dir/signed.apk" "$signed_apk"
printf '\nOK -> %s\n' "$signed_apk"
