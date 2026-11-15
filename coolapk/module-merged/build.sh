#!/bin/sh
# 「酷安净化」合并模块：净化本体 + 回复赞助过滤，一个 APK 两个入口。
#
# 做法：以已打好 16.7.3 profile 的净化 APK 为底座，追加 classes2.dex（本目录的 hook），
# 并在 META-INF/xposed/java_init.list 里追加第二个入口类（Vector 的 API 102 支持多入口）。
# 底座必须用与手机上已装版本相同的 keystore 签名，才能 `adb install -r` 覆盖安装。
#
# 用法：
#   ./build.sh                      # 用 ../work/purifier-now.apk
#   BASE_APK=/path/to/base.apk ./build.sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
coolapk_dir=$(CDPATH= cd -- "$project_dir/.." && pwd)
build_dir="$project_dir/build"
outputs_dir=${OUTPUT_DIR:-"$project_dir/dist"}

base_apk=${BASE_APK:-"$coolapk_dir/work/purifier-now.apk"}
base_entry=${BASE_ENTRY:-io.github.yylsping.coolapkpurifier.y}
merged_entry=${MERGED_ENTRY:-local.codex.coolapk.MergedHook}

android_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"C:/Users/$USERNAME/AppData/Local/AndroidSdk"}}
android_jar="$android_sdk/platforms/android-35/android.jar"
build_tools=${ANDROID_BUILD_TOOLS:-"$android_sdk/build-tools/35.0.0"}
java_home=${JAVA_HOME:-"C:/Program Files/jdk-21.0.8+9"}

# 必须与已装净化模块同签名（CN=CoolapkInspect），否则不能覆盖安装
signing_dir=${SIGNING_DIR:-"$coolapk_dir/module-inspect/.signing"}
keystore=${SIGNING_KEYSTORE:-"$signing_dir/coolapk-inspect.keystore"}
ks_pass=${SIGNING_STORE_PASSWORD:-coolapkinspect}
key_alias=${SIGNING_KEY_ALIAS:-coolapkinspect}
key_pass=${SIGNING_KEY_PASSWORD:-$ks_pass}

version_name=${VERSION_NAME:-2.8.0-merged}

w() { cygpath -w "$1"; }

java_home=$(w "$java_home")
android_jar=$(w "$android_jar")
build_tools=$(w "$build_tools")

[ -f "$base_apk" ] || { echo "缺少底座 APK: $base_apk" >&2; exit 1; }
[ -f "$keystore" ] || { echo "缺少签名 keystore: $keystore" >&2; exit 1; }

rm -rf "$build_dir"
mkdir -p "$build_dir/stubs" "$build_dir/classes" "$build_dir/dex" "$build_dir/entries" "$outputs_dir"

# 1. libxposed API 编译桩
find "$project_dir/stubs" -name '*.java' -print0 |
  xargs -0 "$java_home/bin/javac.exe" -nowarn -source 8 -target 8 \
    -cp "$android_jar" \
    -d "$(w "$build_dir/stubs")"

# 2. 合并模块代码
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
cp "$build_dir/dex/classes.dex" "$build_dir/entries/classes2.dex"

# 4. 底座 APK 去掉旧签名 + 重写 java_init.list（保留原入口，追加合并入口）
cp "$base_apk" "$build_dir/merged.apk"
(
  cd "$build_dir"
  zip -q -d merged.apk \
    "META-INF/MANIFEST.MF" "META-INF/*.SF" "META-INF/*.RSA" "META-INF/*.DSA" \
    "META-INF/xposed/java_init.list"
)
mkdir -p "$build_dir/entries/META-INF/xposed"
printf '%s\n%s\n' "$base_entry" "$merged_entry" > "$build_dir/entries/META-INF/xposed/java_init.list"

(
  cd "$build_dir/entries"
  zip -q -X -9 "$(w "$build_dir/merged.apk")" \
    classes2.dex META-INF/xposed/java_init.list
)

# 5. 对齐（lib/*.so 与 resources.arsc 保持 Stored + 4K 页对齐，否则 Android 14+ 拒装）
"$build_tools/zipalign.exe" -p -f 4 "$(w "$build_dir/merged.apk")" "$(w "$build_dir/aligned.apk")"

# 6. 签名（与已装版本同 key）
signed_apk="$outputs_dir/coolapk-purifier-merged-$version_name.apk"
"$build_tools/apksigner.bat" sign \
  --ks "$(w "$keystore")" \
  --ks-key-alias "$key_alias" \
  --ks-pass "pass:$ks_pass" \
  --key-pass "pass:$key_pass" \
  --out "$(w "$signed_apk")" \
  "$(w "$build_dir/aligned.apk")"

"$build_tools/apksigner.bat" verify --verbose "$(w "$signed_apk")" | sed -n '1,8p'

echo
echo "java_init.list:"
unzip -p "$signed_apk" META-INF/xposed/java_init.list | sed 's/^/  /'
echo "dex:"
unzip -l "$signed_apk" | awk '$4 ~ /^classes[0-9]*\.dex$/ {printf "  %s %s\n", $1, $4}'
echo
echo "OK -> $signed_apk"
