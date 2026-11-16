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
| `CHANGES.md` | `src/` 相对上游基准的**完整 diff**（8 改 + 1 新增） | ✅ |
| `src/` | **我们维护的 NukeApp 源码**（直接改，ganvivo 跟踪；基准版本与改动清单见 `src/UPSTREAM.md`） | ✅ |
| `apk/nuke-base.apk` | 从设备拉取的原始 APK（6.8 MB） | ❌ gitignore |
| `extract/` | APK 解包：dex、lib、assets、`module.prop`、`customize.sh` | ❌ |
| `jadx/` `jadx2/` | jadx 反编译（`jadx2` 带 `--show-bad-code`，关键类可读） | ❌ |
| `logs/` | 证据：feature 映射、设置页文案、JNI 表、MCP 工具串、风控 flag、Vector DB 快照；`upstream-*` 为上游源码核对件（tree / signer / flags / tools / ci） | ✅ |

## 复现（只读，Git Bash）

```sh
ADB="C:/Users/jaqenze/AppData/Local/AndroidSdk/platform-tools/adb.exe"
export MSYS2_ARG_CONV_EXCL='/data'          # 否则 /data 被改写成 C:/Program Files/Git/data

# 1) 定位并拉 APK
P=$($ADB shell su -c 'pm path io.github.nuke.oss' | sed 's/^package://' | tr -d '\r')
$ADB shell su -c "cp '$P' /data/local/tmp/nuke_base.apk"
$ADB pull /data/local/tmp/nuke_base.apk apk/nuke-base.apk

# 2) 解包
unzip -o -q apk/nuke-base.apk -d extract

BT="C:/Users/jaqenze/AppData/Local/AndroidSdk/build-tools/35.0.0"

# 3) 反编译（关键类 defpackage.id3 需要 --show-bad-code）
../_tools/jadx/bin/jadx.bat --no-res -d jadx  apk/nuke-base.apk
../_tools/jadx/bin/jadx.bat --show-bad-code --no-res -d jadx2 apk/nuke-base.apk

# 4) 设置页文案（1454 条 string 资源）
"$BT/aapt2.exe" dump resources apk/nuke-base.apk > logs/resources.txt

# 5) 签名；证书 SHA-256 应与 .so 内嵌摘要 72b63dc6…b5b32a 一致
"$BT/apksigner.bat" verify --print-certs apk/nuke-base.apk
```

## 设备现状（采集时）

* 已安装 APK（v0.1.2 / code 6），**未**在 Vector 里注册/启用；
* KernelSU `/data/adb/modules` 里**没有** `nukeapp-oss` → Zygisk 形态未安装；
* 抖音 `no_backup/nuke` 不存在 → 从未在抖音进程内运行。

启用方式（属设备变更，需用户明确授权；**禁止**为它重启手机——本机 root 是临时 LKM）：
Vector 启用 + scope 勾 `com.ss.android.ugc.aweme` → 强停抖音再打开。
