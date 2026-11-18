# nuke/ — NukeApp-OSS 分析工作区

对 `io.github.nuke.oss`（NukeApp-OSS）的一次只读 dump + 逆向。
**结论先看 [`ANALYSIS.md`](ANALYSIS.md)**；与自家 DouAi-41.2 的逐项对比见 [`COMPARE-vs-douai.md`](COMPARE-vs-douai.md)。
这里只是目录索引与复现命令。

## 目录

| 路径 | 内容 | 入库 |
|---|---|---|
| `ANALYSIS.md` | 分析正文（注入链 / 架构 / 功能面 / MCP / 风控 / 签名固定 / **开源核查**） | ✅ |
| `COMPARE-vs-douai.md` | 与 `douyin/reference/DouAi`（我们的 41.2 融合分支）的功能与实现对照 | ✅ |
| `GAP-ours-vs-nuke.md` | **我们有、NukeApp 没有**的能力清单（含移植成本） | ✅ |
| `PLAN-nuke-upgrade.md` | **迁移到 NukeApp 的改造清单**（P0~P3 + 已完成项 + 验证方法） | ✅ |
| `CHANGES.md` | `src/` 相对上游基准的**完整 diff**（24 改 + 28 新增，含验证记录） | ✅ |
| `src/` | **我们维护的 NukeApp 源码**（直接改，ganvivo 跟踪；基准版本与改动清单见 `src/UPSTREAM.md`） | ✅ |
| `apk/nuke-base.apk` | 从设备拉取的原始 APK（6.8 MB） | ❌ gitignore（换机需按上面命令重新拉取） |
| `extract/` | APK 解包：dex、lib、assets、`module.prop`、`customize.sh` | ❌ |
| `jadx/` `jadx2/` | jadx 反编译（`jadx2` 带 `--show-bad-code`，关键类可读） | ❌ |
| `logs/` | 证据：feature 映射、设置页文案、JNI 表、MCP 工具串、风控 flag、Vector DB 快照；`upstream-*` 为上游源码核对件（tree / signer / flags / tools / ci） | ✅ |

## 复现（只读，Git Bash）

```sh
# adb 不在 PATH 就 export ADB=/全路径/adb；SDK 用 ANDROID_SDK_ROOT 或 ANDROID_HOME 指定
ADB=${ADB:-adb}
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n1)
export MSYS2_ARG_CONV_EXCL='/data'          # 否则 /data 被改写成 C:/Program Files/Git/data

# 1) 定位并拉 APK
P=$($ADB shell su -c 'pm path io.github.nuke.oss' | sed 's/^package://' | tr -d '\r')
$ADB shell su -c "cp '$P' /data/local/tmp/nuke_base.apk"
$ADB pull /data/local/tmp/nuke_base.apk apk/nuke-base.apk

# 2) 解包
unzip -o -q apk/nuke-base.apk -d extract

# 3) 反编译（关键类 defpackage.id3 需要 --show-bad-code）
# jadx 按机器自备（仓库里没有副本；把 jadx.bat 换成你本机的路径）
jadx.bat --no-res -d jadx  apk/nuke-base.apk
jadx.bat --show-bad-code --no-res -d jadx2 apk/nuke-base.apk

# 4) 设置页文案（1454 条 string 资源）
"$BT/aapt2.exe" dump resources apk/nuke-base.apk > logs/resources.txt

# 5) 签名；证书 SHA-256 应与 .so 内嵌摘要 72b63dc6…b5b32a 一致
"$BT/apksigner.bat" verify --print-certs apk/nuke-base.apk
```

## 构建与验证 `src/`（2026-11-17 实测）

```sh
cd nuke/src
export ANDROID_HOME=<SDK>        # 需要 platforms/android-37* 与 build-tools（本机 37.0.0）

# 编译（不需要 Rust/NDK；compileSdk 37 与目录名 android-37.0 的差异用下面的开关绕过）
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:compileDebugKotlin

# 单测：一定用 --tests 定向，整包会卡在 PacketExecutorTest（缺原生库时它不返回）
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:testDebugUnitTest -x checkProtocolRust \
  --tests "io.github.nuke.oss.app.tiktok.AnalyticsRecorderTest" \
  --tests "io.github.nuke.oss.app.shared.settings.ModuleConfigTest" \
  --tests "io.github.nuke.oss.app.shared.integrity.HostApkTest"
```

* 完整 APK（`assembleDebug`）会连带跑 `buildZygiskNative` / `buildProtocolNative`，
  需要 `python3`（任务里写死）+ Rust 工具链 + NDK 29；本机三者都没有。
* 整包单测的失败集合与上游基线一致（47 条，均缺 Rust 原生库），可作为“没引入新失败”的对照。
* 签名：`nuke/src/local.properties` → `signing/debug.keystore`（相对路径），自建包与官方包**不能** `install -r`。

## 设备现状（采集时，2026-11-17 只读复核仍一致）

* 已安装 APK（v0.1.2 / code 6），**未**在 Vector 里注册/启用；
* KernelSU `/data/adb/modules` 里**没有** `nukeapp-oss` → Zygisk 形态未安装；
* 抖音 `no_backup/nuke` 不存在 → 从未在抖音进程内运行。

启用方式（属设备变更，需用户明确授权；**禁止**为它重启手机——本机 root 是临时 LKM）：
Vector 启用 + scope 勾 `com.ss.android.ugc.aweme` → 强停抖音再打开。
