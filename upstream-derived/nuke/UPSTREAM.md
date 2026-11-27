# 这份源码是怎么来的

## 基准

* 上游：<https://github.com/dartcv/NukeApp-OSS>（MIT）
* 基准提交：`22d272d5a5f25e23b4afa4a6e30832ebbac54ba2`
  （`fix(playback): hook the check that hides the progress bar`，2026-11-16 的 `dev`）
* 基线版本：`0.1.2 / 6`

本目录就是**我们维护的源码**，直接由 ganvivo 仓库跟踪，**不依赖任何上游 git 历史，也不向上游提交任何东西**。
「基准提交」只用于将来对照上游更新：`git diff 22d272d..` 之类可以在临时克隆里做，不会影响这里。

## 我们相对基准改了什么

| 文件 | 改动 | 目的 |
|---|---|---|
| `feature/chat/streak/SelfSpeech.kt`（新） | 每账号 `speech.dat`：`本地日 → 会话ID`，留 7 天 | 记住“今天我在这个会话说过话” |
| `feature/chat/streak/StreakEngine.kt` | `tick` / `nextWakeAt` 增加 `skip` | 已发言的好友不写 claim、不发送；不算 pending（否则每秒空转） |
| `feature/chat/streak/AutoStreak.kt` | 观察聊天行、uid↔会话映射、跳过日志 | 用已有 `ChatAdapterEvents` 记录我方发言 |
| `test/.../StreakEngineTest.kt` | 新增 2 条用例 | 跳过后不写 claim；全跳过时 `nextWakeAt` 落次日 |
| `feature/feed/DownloadOriginals.kt` | `DownloadOriginalVideo` 增加 `source` 选项 | auto / play / h264 / h265 / download；只解析所选那一路 |
| `query/DownloadQueries.kt` | 新增 3 个字段查询 | `play_addr` / `play_addr_h264` / `play_addr_265` |
| `ui/hostsettings/HostFeatureLabels.kt` | 选项标签 + 新功能标签 | 设置页显示 |
| `res/values*/host_settings_strings.xml` | 新增文案（中/英） | 同上 |
| `feature/analytics/AnalyticsSink.kt`（新） | 接收端接口与 `AnalyticsVisit` | 与存储解耦，可单测；没有消费方的 `action()` 已删 |
| `feature/analytics/AnalyticsRecorder.kt`（新） | 观看会话的开启/关闭/可见时长/哈希 | 纯逻辑，可单测；暂停时切页不再计时（`playing` 状态） |
| `feature/analytics/SqliteAnalyticsSink.kt`（新） | SQLite 表：visits / daily / hours / ranks / collection | 本地账本，失败静默；未使用的 `actions` 表已删 |
| `feature/analytics/LocalAnalytics.kt`（新） | `@HookEntry` 功能，默认关闭；接 `FeedEvents` 的视频切换与播放/暂停 | 采集入口 |
| `feature/analytics/AnalyticsReport.kt`（新） | 只读查询：日汇总、时段 Top3、作者数 | 供设置页报告使用 |
| `ui/hostsettings/AnalyticsDialog.kt`（新）、`HostSettingsScreen.kt` | 报告对话框 + `DIALOG_FEATURES` 接入 | 点功能行打开统计 |
| `test/.../AnalyticsRecorderTest.kt`（新） | 6 条用例 | 时长、暂停不计、暂停中切页不计、首次播放前按播放中、无 id 不记、哈希稳定 |
| `settings/ModuleConfig.kt` | `preview()` + `ConfigImportMode`（REPLACE/MERGE）；解析与校验抽成 `read()` | 导入前可预览；默认仍是替换，旧调用不变 |
| `ui/hostsettings/HostSettingsViewModel.kt` | `PendingConfig` 状态；`importConfig` 只预览，`applyConfig(mode)` 才写 | 先预览后选择 |
| `ui/hostsettings/HostSettingsScreen.kt`、`HostSettingsActivity.kt`、`ModuleDebug.kt` | `ConfigPreviewDialog`（取消/合并/替换）+ 参数接线 | 同上 |
| `res/values*/home_strings.xml` | 预览文案（中/英） | 同上 |
| `test/.../ModuleConfigTest.kt` | 新增 3 条用例 | 合并保留未涉及的键；预览计数不写入；预览同样拒绝别的宿主 |
| `app/shared/integrity/HostApk.kt`（新） | 流式算宿主 APK 整包 SHA-256 + `HostVerdict`；`KnownHostApks.DIGESTS` 默认空 | 同版本号的改包能被识别（默认不启用、不读文件） |
| `app/tiktok/TikTokHostAdapter.kt` | 主进程启动时在 `diagnosticsWorker` 上校验并把结果写日志 | `host_identity verdict=… digest=… version=…` |
| `test/.../HostApkTest.kt`（新） | 6 条用例 | 摘要值、四种 verdict、大小写不敏感、缺文件、清单为空 |
| `app/shared/media/MediaSaved.kt`（新） | 保存完成广播；监听方要在 `save` 返回前取走字节 | 给上传之类的后续动作一个挂点，宿主的下载器不经过这里 |
| `app/tiktok/injector/AndroidMediaFiles.kt` | 保存成功后 `MediaSaved.publish(output, type)` | 文件在 finally 里删除，所以通知放在删除前 |
| `app/shared/net/WebDavClient.kt`（新） | Basic 认证、MKCOL（裸 socket，`HttpURLConnection` 不许该方法）、PUT、307/308 跟随且跨源不带凭据 | 纯 JDK API，可单测 |
| `feature/general/WebDavUpload.kt`（新） | `@HookEntry` 功能：地址/用户/密码/目录；保存后复制到缓存并单线程上传；32 MiB 上限；日志只写状态 | 把模块保存的文件自动传上 WebDAV |
| `ui/hostsettings/HostFeatureLabels.kt`、`res/values*/host_settings_strings.xml` | 功能与 4 个选项的中英文案 | 设置页可见可配 |
| `app/shared/media/DownloadQueue.kt`（新） | 2 线程 FIFO 队列、进度、取消、`TaskStage` 失败阶段、保留最近 20 条；`DownloadConsoles` 把队列发布给设置页 | D-4 的任务管理核心 |
| `app/tiktok/sdk/media/MediaFiles.kt` | `save(...)` 增加 `progress` 与 `cancelled` 两个默认参数 | 不改变既有调用方，新增的下载器可以上报/取消 |
| `app/tiktok/injector/AndroidMediaFiles.kt` | 下载循环里分块上报进度、按 `cancelled()` 抛 `DownloadCancelled` | 同上 |
| `app/tiktok/feature/feed/DownloadVideoWithName.kt` | 接管后改为入队（`DownloadQueue`），实现 `DownloadConsole` 并注册/注销 | 进度与取消对设置页可见 |
| `ui/hostsettings/DownloadTasksDialog.kt`（新）、`HostSettingsScreen.kt` | 任务列表（排队/进度/失败阶段/取消/清空），500ms 轮询快照 | 设置页点该功能即打开 |
| `res/values*/home_strings.xml` | 下载任务文案（中/英） | 同上 |
| `app/shared/media/Mp3Encoder.kt`（新） | jump3r `LameEncoder` 包装：分块喂 PCM、按需 flush；没有输入时 flush 直接返回 | MP3 编码不需要原生库 |
| `app/shared/media/VideoAudio.kt`（新） | `MediaExtractor` + `MediaCodec` 解码第一条音轨 → MP3 文件，返回时长或失败原因 | 视频原声导出 |
| `feature/general/SaveVideoAudio.kt`（新） | 监听 `MediaSaved` 的 video，复制后转 MP3，再 `saveFile` 到 `Music/douyin/audio` | D-5 |
| `MediaFiles.kt`、`AndroidMediaFiles.kt` | `save` 增加 `tree`（SAF 目录）参数；新增 `saveFile`；图片帖入口用 `ImageFolder.IMAGE` | D-4b |
| `feature/feed/DownloadVideoWithName.kt` | 同一入口接管图片帖（逐张 + 序号）、读 `tree` 选项 | D-4b |
| `ui/hostsettings/DownloadTasksDialog.kt` | 目录选择（`OpenDocumentTree` + 持久授权）、改回相册目录 | D-4b |
| `test/.../Mp3EncoderTest.kt`（新）、`DownloadVideoWithNameTest.kt` | 4 条编码用例；图片帖 2 条 | D-5 / D-4b |
| `feature/analytics/AnalyticsReport.kt` | `AnalyticsSummary.dayRows()`：日期升序 | 导出按日历阅读 |
| `ui/hostsettings/AnalyticsImageExport.kt`（新） | 画布渲染 PNG（标题 + 汇总 + 每日一行）写 `Downloads/Nuke` | S-4 |
| `ui/hostsettings/AnalyticsDialog.kt`、`values*/host_settings_strings.xml` | “导出图片”动作与结果提示 | S-4 |
| `test/.../AnalyticsSummaryTest.kt`（新） | 2 条用例（升序、空摘要） | S-4 |
| `app/shared/media/MotionPhoto.kt`（新） | JPEG + MP4 封装成 Motion Photo（XMP APP1，`MicroVideoOffset`）；`videoOffset()` 回读 | D-6 |
| `MediaFiles.kt`、`AndroidMediaFiles.kt` | `saveMotionPhoto`：下载两个地址、封装、按图片保存（支持 SAF 目录） | D-6 |
| `feature/feed/DownloadVideoWithName.kt` | 图片带 `video` 时走 Motion Photo；`Picture` 载体 | D-6 |
| `test/.../MotionPhotoTest.kt`（新）、`DownloadVideoWithNameTest.kt` | 5 条封装用例；实况照片 1 条 | D-6 |
| `feature/feed/DownloadVideoWithName.kt` | 分享面板加一行“按模板下载”（`SharePanelEntry`）；解析与入队抽成 `enqueue()` | D-4c |
| `values*/host_settings_strings.xml` | 分享面板行文案 | D-4c |
| `test/.../DownloadVideoWithNameTest.kt` | 分享面板入口 1 条 | D-4c |
| `feature/feed/FeedGestureZones.kt`（新） | 双击落在九宫格某个区域时执行动作（下一条/上一条/不做）；只挂 `DOUBLE_TAP` | A10a |
| `sdk/feed/FeedEvents.kt` | `FeedPager.previous()`，`next()` 共用 `move(delta)` | A10a |
| `ui/hostsettings/HostFeatureLabels.kt`、`values*/host_settings_strings.xml` | 分区与动作的中英文案（含九个区域、三个动作） | A10a |
| `test/.../FeedGestureZonesTest.kt`（新） | 3 条分区映射用例 | A10a |
| `feature/feed/FeedGestureZones.kt` | 新增 `menu` 动作：双击区域弹出「下一条/上一条」，文案取模块资源 | A10b |
| `app/shared/media/ChatMedia.kt`（新） | 从聊天 content JSON 提媒体地址（去重/过滤缩略图/扩展名/目录名）+ HTTP 下载器 | D-7 |
| `feature/chat/CacheChatMedia.kt`（新） | 挂 `INCOMING_MESSAGE`，后台把消息媒体存到 `featureFiles/CacheChatMedia/<会话>/` | D-7 |
| `values*/host_settings_strings.xml`、`HostFeatureLabels.kt` | 功能文案（中/英） | D-7 |
| `test/.../ChatMediaUrlsTest.kt`、`CacheChatMediaTest.kt`（新） | 6 + 3 条用例 | D-7 |
| `feature/feed/AutoLike.kt`（新） | 播放到设定秒数后点宿主自己的 like 按钮；已赞/本次赞过/上限都跳过；`Rules` 单独可测 | A16a |
| `ui/hostsettings/HostFeatureLabels.kt`、`values*/host_settings_strings.xml` | 功能与两个选项的中英文案 | A16a |
| `test/.../AutoLikeTest.kt`（新） | 4 条规则用例 | A16a |
| `feature/feed/AutoFollow.kt`（新） | 首次手动关注时记住 receiver，播放到设定秒数后复用 follow 调用；`Rules` 可测 | A16b |
| `ui/hostsettings/HostFeatureLabels.kt`、`values*/host_settings_strings.xml` | 功能与两个选项的中英文案 | A16b |
| `test/.../AutoFollowTest.kt`（新） | 4 条规则用例 | A16b |
| `test/.../DownloadQueueTest.kt`（新） | 8 条用例 | 完成/失败阶段/排队取消/运行取消/顺序/清理/上限 |
| `test/.../WebDavClientTest.kt`（新） | 8 条用例（JDK 自带 HttpServer） | 成功、建目录、401/403、转义、跨源重定向不带凭据、非法地址、文件缺失 |
| `test/.../MediaFeaturesTest.kt` | 适配 `DownloadOriginalVideo` 新构造参数 | 保持既有用例可编译 |
| `app/shared/media/MediaNaming.kt`（新） | 下载命名模板：10 个标记（#author#/#desc#/#aid#/#date#/#time#/#datetime#/#timestamp#/#index#/#type#/#ext#）、按 UTF-8 字节截断、非法字符替换、空结果回退下载时间；`folder()` 只放行纯字母数字 | 命名与目录不再依赖宿主的保存路径 |
| `feature/feed/DownloadVideoWithName.kt`（新） | `@HookEntry` 功能（默认关闭）：接管长按菜单的保存项，读面板持有的 Aweme → 播放地址 → 自有下载，模板与文件夹两个 `TextOption`；没有视频/可播地址时不接管 | 自有下载器与 D-2/D-3 的落地 |
| `query/DownloadQueries.kt` | 新增 `PANEL_DOWNLOAD_CLICK`（`LppDownloadModule` 点击）与 `PANEL_AWEME`（`LongPressPanelAbsModule` 的 Aweme getter） | 长按面板入口 |
| `sdk/media/MediaFiles.kt` | `save(..., name)` 增加可选文件名；`ImageFolder.VIDEO` | 给复用保存路径的功能传名字 |
| `injector/AndroidMediaFiles.kt` | 视频写 `MediaStore.Video` + `Movies/douyin/<folder>`；有名字时用名字（扩展名以实际保存的文件为准），旧版保存重名时递增后缀 | 同上 |
| `ui/hostsettings/HostFeatureLabels.kt`、`res/values*/host_settings_strings.xml` | 新功能与两个选项的中英文案 | 设置页可见可配 |
| `test/.../MediaNamingTest.kt`（新） | 11 条用例 | 展开、清洗、索引、时区、字节截断、回退、文件夹校验 |
| `test/.../DownloadVideoWithNameTest.kt`（新） | 5 条用例 | 接管与命名、文件夹、无视频/无地址回退宿主、base64 地址 |
| `test/.../FakeHostUi.kt` | `RecordingMediaFiles` 记录 `name` | 断言命名 |
| `query/ChatQueries.kt` | `ALBUM_SCAN` 改为单串 `qr_code_scan_rejected`，升 `version = 2` | 41.2 已无 `scan_to_login_block_path_list`；缓存按查询版本失效 |
| `query/FeedQueries.kt` | `DANMAKU_STYLE` 改为单串 `DDanmakuConfig(alpha=`，升 `version = 3` | 41.2 的 `toString()` 不再打印 `textColor`（全 dex 仅 1 处命中） |
| `feature/playback/ColorfulDanmaku.kt` | 打印法找不到颜色字段时，用构造器第二个 int 参数匹配唯一相等的 int 字段 | 41.2 上仍能取到 textColor 字段 |
| `app/shared/diagnostics/FeatureDiagnostics.kt` | `FailureStore.clearAll()`；`FailureRecorder.forgetAll()` 改走它 | 连本版本解不出的旧格式记录一起删 |
| `app/tiktok/TikTokHostAdapter.kt` | `setup.build_version` 变化时清空失败记录并写回新版本；`DiagnosticsStore` 增 `buildVersion/setBuildVersion` | 升级即重试一次被锁的功能（日志 `features_unlocked`） |
| `feature/debug/HostProbe.kt`（新） | 只读探针：候选类签名写 `memory.HostProbe.*`；hook `ProfileSideBarServiceImpl.LJI` 与 `SearchUserProfileFragment.onCreate` | A9/A12 取证；默认关闭 |
| `ui/hostsettings/HostFeatureLabels.kt`、`values*/host_settings_strings.xml` | 探针的中英文案 | 设置页可见 |
| `build.gradle.kts`、`apps/module/build.gradle.kts` | 版本 0.1.9 / versionCode 13 | 与装机包一致 |

## 约定

* 改动直接改这里的文件，按功能说明写在提交信息里；这里没有 `patches/` 之类的东西。
* 完整 diff 与验证记录见上层 [`../CHANGES.md`](../CHANGES.md)（改完这一层后要重新生成）。
* 本目录**未提交**到 ganvivo 仓库时，改动只存在于工作区（`git status` 可见），不要 `git clean -xdf`。
* 上游更新（可选）：把上游新版本单独克隆到临时目录，与本目录 `diff` 后手工并回来；**不要**把上游 `.git` 引入本目录。
* 本机构建与单测命令（Gradle 9.8.2 + SDK `platforms/android-37.0`，不需要 Rust/NDK）：

```sh
cd nuke/src
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:compileDebugKotlin
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:testDebugUnitTest -x checkProtocolRust --tests "<要跑的测试类>"
```

  整包单测在本机会卡在 `PacketExecutorTest.cancellationPropagatesAndClosesWaiter`，别直接跑全量。

## 2026-11-27 合并上游 dev（`22d272d` → `c53c444`，20 个提交）

做法：在临时克隆里建 `local/merge` 分支（`22d272d` + 本目录当前状态），`git merge origin/dev`；三处冲突手工解决后回灌本目录。临时克隆在 `cache/lsp-update-check/nuke-merge/`，不入库。

| 冲突文件 | 解决方式 |
|---|---|
| `apps/module/src/main/res/values{,-zh}/host_settings_strings.xml` | 两边都保留（我们的本地分析文案 + 上游的图文复制文案），只是插在同一位置 |
| `gradle/libs.versions.toml` | 上游 `markdownRenderer` 升 0.46.0，保留我们的 `jump3r = 1.0.5` |

上游这 20 个提交里 16 个是依赖升级（markdownRenderer 0.46.0、KSP 2.3.14、Kotlin 2.4.21、Rust crate 若干、upload-artifact v7），实质改动 4 个：

| 提交 | 内容 | 对我们的影响 |
|---|---|---|
| `3baa01692` + `69cf07905` | 新功能 `AllowCaptionCopy`「解除图文复制限制」：作者关掉复制的图文，长按选中正文仍可复制，复制按钮不再变灰 | 已合并，设置页在「分享与转发」分类 |
| `401b66b37` | patcher 模式伪造原签名，让修补版抖音能登录 | 只影响无 root 的 patcher 通道，我们用 LSPosed/Zygisk 不走它 |
| `e08310dbf` | 破坏性：hook 作用域去掉 `io.github.nuke.oss.fixture` 测试宿主，`fixtures/host` 整目录删除 | 已合并，本目录删除 `fixtures/host/`、`FixtureHostAdapter.kt`、`FixturePatchIntegrationTest.kt`，新增 `PatchRoundTripTest.kt` |

版本：`0.1.22` / `versionCode 26`（原 0.1.21 / 25）。升码是为了让 `TikTokHostAdapter` 的「跨版本清空失败记录」逻辑跑一次，之前被 hold 的功能会重试一次。

构建（WSL，本机 Windows 编不了：`tools/*.py` 用 `fcntl`，NDK 预编译目录是 `linux-x86_64`）：

```sh
cd /mnt/c/mycode/somethingsmall/fuckmyphone/upstream-derived/nuke
. ~/.cargo/env
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
java -Dorg.gradle.appname=gradlew -jar gradle/wrapper/gradle-wrapper.jar \
  -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:assembleDebug --no-daemon
```

- 环境：WSL Ubuntu 24.04 + `/opt/android-sdk`（NDK `29.0.13113456`、cmake `4.1.2`）+ Rust `1.90.0`（`aarch64-linux-android`）+ JDK 21。Rust 这次是新装的（`~/.cargo`、`~/.rustup`）。
- `local.properties` 需要四个 `signing.*` 键指向 `../../shared/signing/debug.keystore`（debug A），否则会退回 Android 默认 debug key，装机就得先卸载。
- `:apps:module:compileDebugKotlin` 16 分钟（冷）、`assembleDebug` 13 分钟（含 Rust + NDK CMake）、改版本号后增量 4 分钟。
- 产物 `apps/module/build/outputs/apk/debug/module-debug.apk`，25,764,353 B，SHA-256 `b9cdf246dd6f1504cc8b9e24c3850f131bef314b3b49f49a72a97af5bea57936`（含 `module.prop` 升版后重打），签名 `1990df77…7212fb`（与在装 0.1.21 同证书，可 `adb install -r`）。
- 定向单测通过：`ModuleConfigTest` 11、`FeatureDiagnosticsTest` 10、`TikTokHostAdapterTest` 20，共 41 条 0 失败。
- 差异提醒：新构建的 `libnuke_zygisk.so` 是 582,480 B，在装的 0.1.21 里是 12,962,496 B（strip 与否的差别），`libnuke_protocol_core.so` 1,178,736 vs 1,177,440。功能面一致，体积差异来自上次构建没跑 strip 断言。

## 构建范围：只走 root（LSPosed），不构建也不安装 patcher

本仓库**只构建 `:apps:module`**。上游的免 root 路线（`apps/patcher`，独立 App `io.github.nuke.oss.patcher`）我们不用，验证依据：

- 我们的 APK（`apps/module/build/outputs/apk/debug/module-debug.apk`，170 个条目）里**没有任何 patcher/lspatch 内容**：
  文件名无 `lspatch`/`patcher` 命中，`classes*.dex` 与 xml/json/sh 里搜 `lspatch` / `LSPatch` / `NPatch` / `io.github.nuke.oss.patcher` 全部为 false。
- `apps/patcher/build/` 不存在 → 这个模块从未被构建过。
- APK 里与 root 路线有关的是三样：`assets/xposed_init`（libxposed 102 入口）、`module.prop` + `customize.sh`（刷进 KernelSU/Magisk 的 Zygisk 形态）、`lib/arm64-v8a/libnuke_zygisk.so`。这三样都是 root 用的，**保留**。
- 官方包 `io.github.nuke.oss`（签名 `72b63d…`）与我们 `io.github.nuke.oss.fork` 并存；LSPosed 里只启用我们这一个，作用域 `com.ss.android.ugc.aweme`。

两处副作用要知道：

1. `apps/module/src/main/resources/module.prop` 是**静态文件**，上游写死 `0.1.2 / 6`，不随 `build.gradle.kts` 变。本轮已手工改成 `0.1.22 / 26` —— **以后每次改版本号必须同步这个文件**，否则刷成 Zygisk 模块时模块管理器显示的版本会是旧值。
2. `tools/check_apks.py` 把 `versionCode` 钉死成 `6`（第 415、428 行），而且它需要 patcher APK 一起校验。所以我们这棵树**跑不了上游的 `verify` / `checkDebugApk`**；验收改用「`compileDebugKotlin` + 定向单测 + 装机后对账（版本 / 设备侧 SHA-256 / 三个 PID 不变）」，见 AGENTS.md §2.1。
