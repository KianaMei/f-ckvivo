# NukeApp-OSS 0.1.2 分析（dump 自本机 vivo X Fold6）

> 采集时间：2026-11-16 19:07（本机时间）
> 目标包：`io.github.nuke.oss`（应用名 `NukeApp-OSS`）
> APK sha256：`873a32807cf808deb4125c357e783c37c4607b7a7aceac8eb16bf89f7b31188c`（6,799,320 B）

## 0. 一句话结论

**给抖音（`com.ss.android.ugc.aweme`）用的 Xposed/Zygisk 模块**：同一个 APK 同时是
① 管理界面 App（Compose）、② 传统 Xposed（API 82）模块、③ 现代 libxposed（API 102）模块、
④ Zygisk + LSPlant 模块（可直接改名 `.zip` 刷进 KernelSU/Magisk）。
进去以后在抖音设置页多出「NukeApp-OSS」一行，里面是 100 多项开关；此外还有
**JS 脚本插件（QuickJS）**、**MCP 服务器（把抖音账号能力暴露给 LLM Agent）**、
**注入环境自检（Frida/Xposed/root/SELinux…）** 三块非常规能力。

上游开源：<https://github.com/dartcv/NukeApp-OSS>（MIT，`dev` 分支，272 star）。
本 APK 的 git 版本为 `93539571b51af11c4ed042e1e10750fb1c6061c5`，正是上游 `v0.1.2`
release 的提交（AGP 9.1.1，GitHub Actions 构建：native 里留有
`/home/runner/work/NukeApp-OSS/NukeApp-OSS/...` 路径）。

## 1. 现场状态（只读采集，未做任何设备变更）

| 项 | 值 |
|---|---|
| 设备 | vivo X Fold6，Android 17 / OriginOS 7（本项目主线设备） |
| 模块 APK | 已安装，firstInstall `2026-11-16 17:26:33`，lastUpdate `19:07:48`（v0.1.2 / code 6） |
| 抖音 | 已安装 `com.ss.android.ugc.aweme` 41.2.0（code 410200） |
| KernelSU 模块目录 | `/data/adb/modules` 只有 `zygisk_shamiko` / `zygisk_vector` / `zygisksu` → **Zygisk 形态未安装** |
| Vector（LSPosed） | `modules_config.db` 的 `modules` 表**没有** `io.github.nuke.oss` → **未在框架里启用/注册** |
| 模块数据目录 | `/data/user/0/io.github.nuke.oss/` 只有 `cache`/`code_cache` → 设置界面基本没被使用时写入过 |
| 抖音侧运行痕迹 | `/data/user/0/com.ss.android.ugc.aweme/no_backup/nuke` 不存在 → **从未在抖音进程里跑起来过** |
| 测试宿主 | `io.github.nuke.oss.fixture` 未安装 |

结论：**这是一个干净的待分析样本，设备上没有任何执行痕迹**。

## 2. APK 内部结构（三合一 + 原生）

```
AndroidManifest.xml          xposedmodule/xposedminversion=82/xposedscope
assets/xposed_init           io.github.nuke.oss.loader.xposed82.Xposed82Entry
module.prop                  id=nukeapp-oss / version 0.1.2 / zygisk+LSPlant 说明
customize.sh                 KernelSU/Magisk 安装脚本（SKIPUNZIP=1，仅 arm64）
classes.dex                  4.2 MB（2566 个类，自己的代码只有 ~21 个类，其余是 Compose/KotlinX）
lib/arm64-v8a/libnuke_zygisk.so        582 KB  Zygisk 入口 + LSPlant + Dobby + DexBuilder
lib/arm64-v8a/libnuke_protocol_core.so 1.1 MB  Rust：IM 协议、MCP HTTP 服务、签名/风控
lib/arm64-v8a/libquickjs.so            790 KB  QuickJS（脚本引擎）
lib/arm64-v8a/libsilk_codec.so         347 KB  Silk 语音编解码（语音消息转发用）
lib/arm64-v8a/libdexkit.so             395 KB  DexKit（运行时 dex 定位）
third-party/*.txt            LSPlant/Dobby/DexBuilder/parallel-hashmap/quickjs-kt/Silk/xDL/Zygisk 许可
```

`customize.sh` 的行为值得注意：它把 APK 里的 `classes*.dex` 解到
`$MODPATH/payload/`、`libnuke_zygisk.so` 解到 `$MODPATH/zygisk/arm64.so`，
其余 native（protocol core / quickjs / silk / dexkit）解到 `$MODPATH/natives/`，
**宿主进程的类由 payload dex 在内存里加载，不需要另装模块 APK**。

签名：自签证书 `CN=C`，证书 SHA-256 = `72b63dc6…b5b32a`。**这个摘要被硬编码在
`libnuke_protocol_core.so` 里**（见 §8），即"未改包/未重签"才被承认。

## 3. 注入链：三个入口 + 一个仲裁

```
Xposed(82)  ──> Xposed82Entry.handleLoadPackage ─┐
libxposed102──> Libxposed102Entry.onPackageReady ─┼─> RuntimeBootstrap(do2).a(...)
Zygisk      ──> libnuke_zygisk.so ─ payload dex ──┤     ├─ host adapter 选择（按包名）
                (InMemoryDexClassLoader)          │     ├─ scope 校验
                ZygiskEntry.bootstrap() ──────────┘     ├─ 模块签名校验
                                                        └─ 单例：已有运行时就拒绝
```

* **进程内 token 仲裁**：`do2.a()` 里 `co2` 单例 + `claim`（`qg1(eg2)`）保证同一进程里
  "先到者生效"，后到的入口直接 `xposed82_skipped entry already claimed` / `detach()`。
* **作用域**（`defpackage.v11`）：只认两个包名——
  `io.github.nuke.oss.fixture`（测试宿主）与 `com.ss.android.ugc.aweme`（抖音）；
  抖音侧只接**主进程**和**非 `sandboxed_process` 的子进程**，且子进程名要匹配
  `mini(?:app|game)(?:[0-9]+|X)`（小程序/小游戏/热更新），即 `u11.i`(主) / `u11.j` / `u11.k`。
  其余进程 `run` 直接被忽略（`The process is outside the host scope`）。
* **子进程不重复建配置**：主进程把开关快照写到
  `…/no_backup/nuke/process-mirror.json`（含 `safe_mode`、被 hold 的 feature 列表），
  子进程读它决定装哪些 hook（`child_ready/child_skipped … no_mirror`）。

### Zygisk 侧（`libnuke_zygisk.so`，C++）

* `zygisk_module_entry` → 专用化列表里含 `com.ss.android.ugc.aweme`、`io.github.nuke.oss.fixture`；
* 读模块目录下 `payload/*.dex` → `InMemoryDexClassLoader` → 找
  `io.github.nuke.oss.loader.zygisk.ZygiskEntry.bootstrap(...)` → 反射调用；
* 同时 `RegisterNatives` 注册 `io.github.nuke.oss.backend.lsplant.JniLsplantDriver`
  的 `nativeInitialize/nativeHook/nativeUnhook/nativeInvokeConstructor`（LSPlant 真正的落地）；
* 进程内状态写到 **`/cache/nukeapp-zygisk.txt`**（README 里也提示读这个文件判断
  `bootstrap=returned|failed`），失败分支写得很细（DEX 未分配、构造失败、RegisterNatives 失败…）；
* 还有一个 Dobby 的 inline hook（字符串 `Failed to hook EncodeGenericId, attaching
  debugger may crash the process`），属于反调试/环境相关的旁路处理。

## 4. Java 层运行时架构

```
loader/{xposed82,libxposed102,zygisk}   ← 三个入口（薄）
backend/{xposed82,libxposed102,lsplant} ← hook 后端抽象 f01，统一 attach/support/unhook
do2 (RuntimeBootstrap, fo2)             ← 唯一运行时创建者，含全部守卫
host adapter b11:  id3 = tiktok 宿主 / tp0 = fixture 宿主
feature   go2/kn0/vj3                   ← 107 个注册项（hv0.a 列表）
settings  feature  cy2 = "SettingsEntry" ← 往抖音设置页插一行入口
```

* 后端能力面 `g01` 有差异（LSPlant 只开放"便携方法回调"，xposed/libxposed 更多）；
  运行时会 `support()` 检查，不支持就不装。
* `id3`（抖音适配器）是核心：拿 `dataDir`/`sourceDir` → 读/建设置存储 →
  `DexKit` 定位关键类 → 构造每个 feature 的 hook → 装上；**定位不到就"hold"该功能**
  （日志 `features_held dex_unlocated=N`），避免抖音更新后乱炸。
* 设置存储：`/data/data/com.ss.android.ugc.aweme/no_backup/nuke/`
  ├─ `<version>/…`（按 APK 长度+修改时间分桶，避免串版本）
  ├─ `process-mirror.json`（主进程 → 子进程开关镜像）
  ├─ `streak/`（自动续火的状态，发送前落盘，避免重复发）
  └─ `feature-files/`（功能自己落盘的数据）
* UI 主题项也在这套 KV 里（`ui.theme` / `ui.language` / `ui.accent` / 模糊/透明度…）。

## 5. 功能面（107 个注册项 / 114 条带说明的开关）

从 `hv0.a` 逐个解出的 feature ID（`logs/features_map.txt`、`logs/feature_ids_unique.txt`）
和设置页文案（`logs/feature_catalog.tsv`）看，覆盖面大致是：

| 分类 | 代表功能 |
|---|---|
| 视频/播放 | `SetVideoQuality`、`ForceHighVideoQuality`、`CustomPlaybackSpeed`、`KeepAutoplayOn`、`LongPressSpeed`、`PauseOnPlaybackCompleted`、`BlockForegroundPlaybackResume`、`PortraitVideoSize`、`OfflineCacheCount`、`AlwaysShowProgress`、`ProgressTime` |
| 广告/推荐 | `SkipSplashAd`、`SkipChapterAds`、`HideRelatedRecommendations`、`HideTeenModeDialog`、`DisableUpdateChecks`、`DisableHotUpdates`、`SkipMiniApp{InterstitialAd,RewardAd,RealName}`、`SkipMinorCheck` |
| 界面精简 | `CleanMode`、`HideFeedComponents`、`HideVideoControls`、`HideStatusBar`、`Trim{Share,Comment,LongPress}Panel`、`TrimProfileTabs`、`HideCommentTabs`、`HideAvatarRings`、`TransparentNavigationBar`、`CustomDpi`、`ControlOpacity` |
| 评论 | `CommentDetails`、`CommentPreciseTime`、`CustomCommentStyle`、`FilterComments`、`BlockEmptyCommentInput`、`ShowCommentGender`、`ShowPreciseCounts`、`CleanCommentCopy`、`CheckSentComments`、`CommentPanelLook` |
| 聊天/消息 | `BlockReadStatusUpload`（不上报已读，支持黑/白名单）、`PreventMessageRecall`/`…2`、`RepeatMessage`（+1 复读）、`CustomChatBubble`、`MessageSendTime`、`MessageTabMenu`、`AllowAnyForward`、`VoiceForward`、`ShareFriendGroups`、`DisableChatEffects`、`KeepViewOnceMessages`、`HideMessageTopFriends` |
| 自动化 | `AutoStreak`（自动续火）、`AutoSparkClaim`（定时领火星，可只选好友）、`AutoPetFeed`（自动喂宠物）、`OpenRedPackets`（抢红包）、`CustomStreakDays`、`TimedClose` |
| 抓取/导出 | `AllowFeedDownloads`、`DownloadOriginalImages`、`AutoOriginalMedia`、`CoverInfo`、`VideoBottomInfo`、`AddAuthorInfoRow`、`LongPressCopy` |
| 隐私/环境 | `RandomLocationGradient`（IP 属地随机渐变）、`OpenExternalLinks`、`BypassListenFilter`、`AllowAlbumQrScan`、`BlockNotificationPrompts` |
| 特殊 | `McpServer`（§6）、`CustomProtocolSender`（裸 IM 协议）、`SettingsEntry`、`Init` |

设计上比较克制的地方（也是可信度的来源）：
* 大部分功能是"改本地判断/改 UI"，**发消息类功能一律先落盘再发，`unknown` 状态绝不重发**；
* 默认只开一项（`DisableDoubleTapDigg`），其余默认关；
* 开关改动要求"强停抖音再开"，不做热重载（`onHotReloading` 返回 false）。

## 6. MCP 服务器（最"重"的一块）

Feature `McpServer`（`lp1`）→ JNI `NativeMcp` → **`libnuke_protocol_core.so` 里一个
手写的 HTTP/1.1 + JSON-RPC 2.0 服务器**（MCP 协议版本 `2025-06-18`，serverInfo `nuke-douyin`）。

配置（`vo1` → `open(port, lan, json)`）：
```
{ "port": 1024..65535, "lan": bool, "tokens": [ {
    id, name, secretHash, enabled, account,
    permissions: [READ_CONTACTS|SEND_TEXT|SEND_IMAGE|CLAIM_REWARDS|MARK_READ|SEND_PROTOCOL],
    allowAll: bool, conversations: [conversationId…] (≤2000), limit: 1..600/min, createdAt } ] }
```
`issue(entropy, …)` 生成 token（返回 `{id, secretHash, token}`，明文 token 只给一次），
HTTP 侧 `Authorization: Bearer`。

工具（tool 名与语义都写在 `.so` 的提示词里，`logs/native_mcp_tools.txt` 是原始字符串）：

| tool | 作用 | 关键约束 |
|---|---|---|
| `douyin_list_contacts` | 列出可触达的好友/群 | limit 1..200，offset ≤100000 |
| `douyin_find_contact` | 按昵称/备注/群名模糊找 | 1..20 条，重名要跟用户确认 |
| `douyin_send_text` | 发文本 | 正文 1..5000 字；`sent` 只在服务器确认后返回 |
| `douyin_send_image` | 发一张 JPEG/PNG | base64，≤5 MB，可选不压缩 |
| `douyin_claim_sparks` | 领"火花"奖励 | 只领已挣到的；分页 `next/skip`；`unknown` 绝不重复提交 |
| `douyin_claim_closeness` | 领"亲密值" | 同上；遇到 unknown 直接停并告知 |
| `douyin_mark_all_read` | 一键全读（含陌生人信箱） | 需"全聊天"权限；无单聊版本、无撤销 |
| `douyin_send_protocol` | 发**裸 IM protobuf** | 需全聊天权限；4 MiB 上限；不校验内容；超时不许重试 |

协议侧防护（从错误码字符串提取）：`UNAUTHORIZED`、`ORIGIN_NOT_ALLOWED`、
`RATE_LIMITED`（`Retry-After: 60`）、`TOKEN_REVOKED`、`PERMISSION_DENIED`、
`ALL_CHATS_REQUIRED`、`CONTACT_NOT_FOUND`、`CONVERSATION_NOT_ALLOWED`、
`RESPONSE_TOO_LARGE`；HTTP 解析器是自己写的（`INVALID_CONTENT_LENGTH`、
`TRANSFER_ENCODING_NOT_SUPPORTED`、`CRLF_REQUIRED` 等），也自带 ZIP 读取（脚本包）。

线程模型：`NativeMcp.serve(handle, host)` 在阻塞线程里跑服务器，
每个请求回调 Java 的 `McpHost.request(...)`（`ng0` 实现），由它驱动抖音内的 IM 逻辑；
`configure(handle, json)` 可热更新 token 表，`snapshot(handle)` 取状态。

> 风险提示：`SEND_PROTOCOL` + `allowAll` 的 token ≈ **账号全权**；
> `lan: true` 会把端口开到局域网。这两点属于本项目里最该看清的开关。

## 7. 原生组件与 JNI 面

`NativeMcp`/`NativeProtocol`/`NativeRisk`/`NativeSignature`/`NativeScriptPackage`
全部只做 JNI 转发，实现在 `libnuke_protocol_core.so`（Rust：`jni` crate、`zip` crate、
自带 protobuf 编解码与 HTTP）。完整符号表见 `logs/native_jni.txt`，共 17 个：

* `NativeMcp`: `open/close/configure/issue/snapshot/serve/entropyBytes`
* `NativeProtocol`: `abiVersion`(=1)、`encode/decode`、`wrapPayload`、`correlate`、`validateRequest`
  （把 IM `RequestBody` protobuf 打包/拆包，并按 `cmd`/`seq` 关联回包；
  字符串里有 `douyin_send_text`、`no tracer field in the status` 等调试提示）
* `NativeSignature`: `signerDigest`、`verify`
* `NativeRisk`: `scan`
* `NativeScriptPackage`: `install`

## 8. 完整性 / 反检测（`NativeRisk`）

`NativeRisk.scan()` 在宿主进程里跑，产出一份"设备信任"报告，管理界面有对应面板
（`device_trust_*` 文案）。**29 个 flag，分 4 类**（源码 `rust/src/riskctl/flags.rs`，
见 `logs/native_risk_flags.txt`）：

| 类别 | flag |
|---|---|
| `Hard`（单独即可判定） | `DEBUGGER`、`GDB_PTRACE`、`FRIDA`、`LINKER_ENV`（LD_PRELOAD/LD_AUDIT）、`FD_SUSPICIOUS`、`EXEC_INTEGRITY`（模块库/libc 与磁盘文件不一致） |
| `Weak`（需伴随 Hard 才算） | `SIGNATURE_HOOK`、`PROC_MAPS`、`HOOK_SURFACE`、`MEMFD_DELETED`、`WX_MAPPING`、`NATIVE_PATH`、`MULTI_MAPPING`、`DUMP_SURFACE`、`OTHER_HOOK`、`NETWORK_SURFACE` |
| `Env`（只在报告里说明） | `XPOSED`、`ZYGISK`、`MAGISK`、`ROOT_TOOL`、`SU_BINARY`、`BOOT_UNLOCKED`、`SELINUX_PERMISSIVE`、`KERNEL_TAMPER`、`DEBUGGABLE_BUILD` |
| `Note`（仅供参考） | `NATIVE_BRIDGE`、`ADBD_RUNNING`、`TEST_KEYS`、`PROXY` |

**tripwire 逻辑**（源码注释写得很明白，和"旧版 native 引擎"一致）：
`Hard` 单独触发；`Weak` 与 `KERNEL_TAMPER` **只有在一个 `Hard` 同时存在时才计入**——
"一台 root 过的手机或 JIT 的可写可执行内存本身不算数，只有这些东西挨着调试器/Frida 时才算"。
所以本机常态（Zygisk + KernelSU + 解锁/verity、SELinux 打补丁）只是 `Env` 项，
面板会逐条列出状态，但**不会**因为 root 本身报"红"。等级字符串：`ok/info/warn/alert/unknown`。

签名固定（自毁逻辑）：
* `MainActivity.onCreate` 先 `NativeSignature.verify(sourceDir)`，不通过就
  `Log.e("NukeApp-OSS/Signature", …)` → `finishAndRemoveTask()` → `killProcess()`；
* 宿主内再校验**模块 APK** 的 v2/v3 签名（日志 `module_signature_refused …`，
  错误文案 `The module APK is not signed with the release certificate (signer …)`）；
* 内嵌摘要 `72b63dc62e5da6697bd82454537e997b2741be47788a0a944dcad6a58db5b32a`
  与 APK 实际签名证书 SHA-256 完全一致 → **这份样本是官方签名构建，能跑；被重签会直接被杀**；
* 但 pin **不是**硬编码在源码里的：`rust/src/signer/verify.rs` 用
  `option_env!("NUKE_MODULE_SIGNER_SHA256")`，值由 `apps/module/build.gradle.kts`
  从 `local.properties` 里那把发布 key 的证书摘要算出来注入（CI 用 secrets 里的 release
  key）。没有这把 key 时 `Verdict::Unconfigured`，`ModuleSignature.refusal()` 返回
  `null`（只记 `module_signature unconfigured`）→ **自己从源码构建的 APK 不会被拦**。
  pin 的用途是"防别人重签官方包冒充官方"。

  > 2026-11-17 备注：`nuke/src/local.properties` 已入库并指向 `signing/debug.keystore`
  > （见仓库根 `signing/README.md`），所以现在从 `nuke/src` 构建时 pin **是 configured**：
  > native 里嵌的是 debug A 的证书摘要，用同一把 key 自签的包自然通过。删掉
  > `local.properties` 里的 `signing.*` 四行才会退回 `Unconfigured`。

## 9. 脚本插件系统（QuickJS）

* 包格式 `.nks`（zip）：`manifest.json` + `main.js` + 可选 `README.md`/`icon.png`/`configSchema.json`；
* 校验很严：包 ≤16 MiB、单文件 ≤8 MiB、条目数上限、路径穿越/符号链接/加密条目全拒、
  大小写冲突拒、`manifestVersion/apiVersion/id(两段小写域名式)/version/versionCode/hosts` 必填；
* 权限模型：`permissions.network`（`origin` 白名单，或 `true`）、`permissions.filesystem`；
  `configSchema.fields[]`（string/text/number/integer/boolean/select，1..64 选项）；
* 运行在主进程（`pr2` 管理生命周期），SDK 见上游 `sdk/script-sdk`。

## 10. 与设备现状的对照 / 下一步

想在本机跑起来，有三条路（都属设备变更，按仓库红线需明确授权）：

1. **Xposed**：Vector 里启用模块 + scope 勾 `com.ss.android.ugc.aweme` → 强停抖音再开。
   （当前 `modules_config.db` 里没有它，等于完全没启用。）
2. **Zygisk**：把已安装的**同一个** APK 改 `.zip` 刷进 KernelSU → `arm64-v8a` + Zygisk 已开 +
   抖音不在 DenyList → **需要重启手机**（红线：硬重启会丢临时 root，不建议）。
3. **免 root 修补**：上游的 NukePatcher（另一个 APK `io.github.nuke.oss.patcher`）把模块嵌进抖音。

注意点：
* 本模块**不声明 INTERNET 权限**——它自己的进程不联网；MCP 服务器跑在抖音进程里（用抖音的权限）。
* 抖音 41.2.0 是否与 0.1.2 的功能锚点匹配未知；跑起来后看
  `features_held dex_unlocated=N` 就知道有多少功能没定位到。
* 分析顺序建议：先在飞书上**只启用 `SettingsEntry`**（能进设置页即可），再逐项开；
  开 `McpServer` 时先只用 `READ_CONTACTS` 权限的 token，确认无误再加 `SEND_TEXT`。

## 11. 证据索引

| 文件 | 内容 |
|---|---|
| `apk/nuke-base.apk` | 原始 APK（sha256 见文件头） |
| `extract/` | APK 解包（dex / lib / assets / module.prop / customize.sh / third-party） |
| `jadx/` | jadx 反编译（默认模式，`defpackage.id3` 未展开） |
| `jadx2/` | jadx `--show-bad-code`（关键类 `id3`/`ng0`/`pr2`/`v90`/`lp1` 可读） |
| `logs/features_map.txt` | `hv0.a` 107 个注册项 → feature ID 映射 |
| `logs/feature_catalog.tsv` | 设置页 120 条文案（标题 + 说明，英文默认值） |
| `logs/strings_en.txt` | 全部 1454 条 string 资源的默认语言值 |
| `logs/resources.txt` | `aapt2 dump resources` 原始输出 |
| `logs/native_jni.txt` | `libnuke_protocol_core.so` 的 JNI 符号表 |
| `logs/native_mcp_tools.txt` | MCP 工具/提示词/错误码原始字符串 |
| `logs/native_risk_flags.txt` | 风控 flag 与检查项清单 |
| `logs/modules_config.db` | 采集时的 Vector 配置快照（证明模块未注册） |
| `logs/upstream-README.md` | 上游 README（安装/三种入口/许可证说明） |
| `COMPARE-vs-douai.md` | 与本仓库 `douyin/reference/DouAi`（我们的 41.2 融合分支）的对照 |
| `logs/upstream-tree.json` | 上游 `dev`@`9353957` 全仓文件树（720 blob，用于核对"源码是否完整"） |
| `logs/upstream-signer-verify.rs`、`logs/upstream-ModuleSignature.kt`、`logs/upstream-module-build.gradle.kts` | 签名 pin 的实现与注入方式 |
| `logs/upstream-risk-flags.rs`、`logs/upstream-mcp-tools.rs` | 风控 flag 与 MCP 工具的上游源码（与 .so 字符串互证） |
| `logs/upstream-ci.yml`、`logs/upstream-release.py`、`logs/upstream-prepare_lspatch.py` | 构建/签名/LSPatch 获取流程 |
| `logs/jadx*.log` | 反编译日志（32 个方法降级，属正常 R8 产物） |

## 12. 开源真实性核查（对上游 `dev`@`9353957`）

结论：**是真的完全开源**——APK 里的 revision 就是上游 `dev` HEAD（也是 `v0.1.2` 发版提交），
仓库里能拿到同一份源码，且**没有任何预编译二进制入库**。

| 核查项 | 结果 |
|---|---|
| 仓库 / 许可 | `dartcv/NukeApp-OSS`，根 `LICENSE` = **MIT**；`apps/patcher/LICENSE` = GPL-3.0（因为内嵌 LSPatch） |
| 代码版本对得上 | APK 的 `META-INF/version-control-info.textproto` = `93539571b51af…`；GitHub `dev` HEAD 同 sha；`v0.1.2` 是 annotated tag `be1ef2e` |
| 源码规模 | 720 个 blob：`apps/module/src` 512 个（Kotlin，含 `feature/*.kt` **115 个**）+ Rust 37 个（`mcp/`、`protocol/`、`riskctl/`、`script/`、`signer/`）+ C++ `module.cpp` + `core/{runtime,loader,script,designsystem,hook-ksp}` + `sdk/script-sdk`（JS/TS）+ `fixtures/` + `tools/*.py` + 完整的 Kotlin/Rust 测试 |
| 提交的二进制 | 只有 `gradle/wrapper/gradle-wrapper.jar` 和测试用 `sdk/script-sdk/test/fixtures/packages/hello.nks`；**没有** `.so`/`.jar`/`.aar` 预编译产物 |
| 第三方 native | `cpp/dependencies.lock.json` 按 commit 锁定，`tools/build_native.py` 现场构建：LSPlant `d8b5d1d`、Dobby `cfb3469`、xDL `1e0b625`、zygisk API4 `7bb941a`、DexBuilder `ac7fb22`、parallel-hashmap `0cd57d2` |
| 二进制↔源码互证 | `.so` 里的 8 个 `douyin_*` 工具名、29 个风控 flag 名/分类、MCP 工具提示词，与 `rust/src/mcp/tools.rs`、`rust/src/riskctl/flags.rs` 逐字一致（`logs/upstream-mcp-tools.rs`、`logs/upstream-risk-flags.rs`） |
| 不在仓库里的 3 类东西 | ① **release keystore 与密码**（CI `secrets.SIGN_*`，`NUKE_MODULE_SIGNER_SHA256` 由 keystore 推出）；② **LSPatch jar**（patcher 用，`tools/prepare_lspatch.py` 按 sha256 锁定 URL 下载，`artifacts/` 不入库）；③ Maven 依赖（quickjs-kt、Silk-Codec-Android、AndroidHiddenApiBypass、Compose…） |
| 能否自己编译 | 能。`tools/build_rust.py` / `build_native.py` + Gradle；本地无发布 key 时签名校验为 `Unconfigured`（配了 `nuke/src/local.properties` 则嵌我们自己那把 debug key 的摘要），模块照常加载。CI 里 PR 构建的是 **unsigned** Release，只有 push/tag 才用 secrets 签名 |

唯一"非公开"的东西是发版私钥，这不影响"源码完整"。另外仓库自带 `AGENTS.md` / `CLAUDE.md`
（作者自己用它跑 AI 协作），从侧面也能看出是活的开源项目（272★/26 fork，2025-11 建，最近 push 2026-11-16）。

## 13. 一句话风险评级

* 能力面：**很高**（Hook + 自动化 + 账号级 MCP + 脚本引擎 + 反检测），本质是"抖音增强 + RPA"。
* 代码质量：**高**（上游开源、功能门控、状态落盘、失败不重发、签名固定、三入口仲裁做得比同类细）。
* 使用时最该盯的：`McpServer` 的 token 权限/`lan` 开关、自动化发送的 `unknown` 语义；
  "设备信任"面板只是如实报告（root/Zygisk 属 `Env` 项，不单独触发告警）。
