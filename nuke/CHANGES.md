# nuke/src 相对上游基准的全部改动

* 基准：`dartcv/NukeApp-OSS` `dev` @ `22d272d5a5f25e23b4afa4a6e30832ebbac54ba2`
* 对照方式：解开该提交的 tarball（`codeload.github.com`），与 `nuke/src` 逐文件比对
  （2026-11-18 重新生成；排除 `build/`、`.gradle/`、`local.properties`）
* 结果：**24 个文件修改 + 28 个新增**（`local.properties` 是签名配置，说明见仓库根 `signing/README.md`）

## 已实现

| 主题 | 文件 | 内容 |
|---|---|---|
| 续火跳过 | `streak/SelfSpeech.kt`(新)、`streak/StreakEngine.kt`、`streak/AutoStreak.kt`、`StreakEngineTest.kt` | 今天我方已在某会话发言 → 该好友不写 claim、不发送；`nextWakeAt` 同步排除；2 条单测 |
| 下载来源 | `feed/DownloadOriginals.kt`、`query/DownloadQueries.kt`、`hostsettings/HostFeatureLabels.kt`、`values*/host_settings_strings.xml`、`MediaFeaturesTest.kt` | `source` 选项：auto / play / h264 / h265 / download；只解析所选那一路 |
| 本地分析（采集/查看） | `analytics/*`(新)、`hostsettings/AnalyticsDialog.kt`(新)、`HostSettingsScreen.kt`、`HostSettingsViewModel.kt`、`AnalyticsRecorderTest.kt`(新) | SQLite：visits / daily / hours / ranks / collection；可见时长只算播放中（暂停切页不计）；默认关闭；6 条单测 |
| 配置导入（合并/预览） | `settings/ModuleConfig.kt`、`HostSettingsViewModel.kt`、`HostSettingsScreen.kt`、`HostSettingsActivity.kt`、`hostsettings/ModuleDebug.kt`、`values*/home_strings.xml`、`ModuleConfigTest.kt` | 先预览再选合并/替换；文件整个校验通过才写；3 条单测 |
| 宿主整包身份校验 | `integrity/HostApk.kt`(新)、`TikTokHostAdapter.kt`、`HostApkTest.kt`(新) | 整包 SHA-256 对已知清单（默认空 = 不启用）；后台线程日志；6 条单测 |
| WebDAV 上传（模块保存的文件） | `shared/media/MediaSaved.kt`(新)、`shared/net/WebDavClient.kt`(新)、`feature/general/WebDavUpload.kt`(新)、`values*/host_settings_strings.xml`、`WebDavClientTest.kt`(新) | 保存后复制并后台 `MKCOL`+`PUT`，Basic 认证只发同源；8 条单测 |
| 自有下载器（视频 + 图片帖） | `feed/DownloadVideoWithName.kt`(新)、`media/MediaNaming.kt`(新)、`DownloadQueries.kt`、`MediaFiles.kt`、`AndroidMediaFiles.kt` | 接管长按“保存”：模板命名、`Movies/douyin/<folder>`；图片帖逐张 + 序号到 `Pictures/douyin/image`；解析不出地址就仍走宿主；默认关闭 |
| 下载队列 / 进度 / 取消 / 失败阶段 / UI | `media/DownloadQueue.kt`(新)、`ui/hostsettings/DownloadTasksDialog.kt`(新)、`HostSettingsScreen.kt`、`values*/home_strings.xml`、`DownloadQueueTest.kt`(新) | 2 线程 FIFO、进度、`TaskStage`、排队与运行中都可取消、保留最近 20 条；设置页轮询展示；8 条单测 |
| SAF 自定义目录 | `AndroidMediaFiles.kt`、`DownloadTasksDialog.kt`、`DownloadVideoWithName.kt`、`values*/home_strings.xml` | `OpenDocumentTree` + 持久授权，写入走 `DocumentsContract`；可改回相册目录 |
| 视频原声 MP3 | `media/Mp3Encoder.kt`(新)、`media/VideoAudio.kt`(新)、`feature/general/SaveVideoAudio.kt`(新)、`libs.versions.toml`、`apps/module/build.gradle.kts`、`Mp3EncoderTest.kt`(新) | `MediaCodec` 解音轨 → `jump3r`（纯 Java LAME，192 kbit/s）→ `Music/douyin/audio`；4 条单测 |
| 本地分析报告导出 | `analytics/AnalyticsReport.kt`、`hostsettings/AnalyticsImageExport.kt`(新)、`AnalyticsDialog.kt`、`values*/host_settings_strings.xml`、`AnalyticsSummaryTest.kt`(新) | “导出图片”把对话框里的同一组数字绘成 PNG 写到 `Downloads/Nuke`；`dayRows()` 升序；2 条单测 |

## 验证

* `./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:compileDebugKotlin`
  通过（Gradle 9.8.2，SDK `platforms/android-37.0` + `build-tools/37.0.0`，JDK 21）。
* 本次相关单测全过：`DownloadQueueTest` 8、`DownloadVideoWithNameTest` 7、`MediaNamingTest` 11、
  `Mp3EncoderTest` 4、`WebDavClientTest` 8、`HostApkTest` 6、`ModuleConfigTest` 11、
  `AnalyticsRecorderTest` 6、`AnalyticsSummaryTest` 2、`MediaFeaturesTest` 23、`TikTokHostAdapterTest` 18、`HostSettingsViewModelTest` 19。
* 整包 `testDebugUnitTest -x checkProtocolRust` 的失败集合与上游基线一致（47 条，本机缺 Rust/QuickJS 原生库）。
* 没有产出 APK：按用户要求本仓库不构建安装包；以上功能均**未上机验证**。

## 完整 diff

```diff
diff --git a/.gitignore b/.gitignore
--- a/.gitignore
+++ b/.gitignore
@@ -3,9 +3,7 @@
 __pycache__/
 node_modules/
 **/build/
-local.properties
-*.jks
-*.keystore
+# local.properties 与签名 keystore 按用户要求入库（见仓库根 signing/README.md）
 .idea/
 *.iml
 artifacts/
diff --git a/UPSTREAM.md b/UPSTREAM.md
new file mode 100644
--- /dev/null
+++ b/UPSTREAM.md
@@ -0,0 +1,89 @@
+# 这份源码是怎么来的
+
+## 基准
+
+* 上游：<https://github.com/dartcv/NukeApp-OSS>（MIT）
+* 基准提交：`22d272d5a5f25e23b4afa4a6e30832ebbac54ba2`
+  （`fix(playback): hook the check that hides the progress bar`，2026-11-16 的 `dev`）
+* 基线版本：`0.1.2 / 6`
+
+本目录就是**我们维护的源码**，直接由 ganvivo 仓库跟踪，**不依赖任何上游 git 历史，也不向上游提交任何东西**。
+「基准提交」只用于将来对照上游更新：`git diff 22d272d..` 之类可以在临时克隆里做，不会影响这里。
+
+## 我们相对基准改了什么
+
+| 文件 | 改动 | 目的 |
+|---|---|---|
+| `feature/chat/streak/SelfSpeech.kt`（新） | 每账号 `speech.dat`：`本地日 → 会话ID`，留 7 天 | 记住“今天我在这个会话说过话” |
+| `feature/chat/streak/StreakEngine.kt` | `tick` / `nextWakeAt` 增加 `skip` | 已发言的好友不写 claim、不发送；不算 pending（否则每秒空转） |
+| `feature/chat/streak/AutoStreak.kt` | 观察聊天行、uid↔会话映射、跳过日志 | 用已有 `ChatAdapterEvents` 记录我方发言 |
+| `test/.../StreakEngineTest.kt` | 新增 2 条用例 | 跳过后不写 claim；全跳过时 `nextWakeAt` 落次日 |
+| `feature/feed/DownloadOriginals.kt` | `DownloadOriginalVideo` 增加 `source` 选项 | auto / play / h264 / h265 / download；只解析所选那一路 |
+| `query/DownloadQueries.kt` | 新增 3 个字段查询 | `play_addr` / `play_addr_h264` / `play_addr_265` |
+| `ui/hostsettings/HostFeatureLabels.kt` | 选项标签 + 新功能标签 | 设置页显示 |
+| `res/values*/host_settings_strings.xml` | 新增文案（中/英） | 同上 |
+| `feature/analytics/AnalyticsSink.kt`（新） | 接收端接口与 `AnalyticsVisit` | 与存储解耦，可单测；没有消费方的 `action()` 已删 |
+| `feature/analytics/AnalyticsRecorder.kt`（新） | 观看会话的开启/关闭/可见时长/哈希 | 纯逻辑，可单测；暂停时切页不再计时（`playing` 状态） |
+| `feature/analytics/SqliteAnalyticsSink.kt`（新） | SQLite 表：visits / daily / hours / ranks / collection | 本地账本，失败静默；未使用的 `actions` 表已删 |
+| `feature/analytics/LocalAnalytics.kt`（新） | `@HookEntry` 功能，默认关闭；接 `FeedEvents` 的视频切换与播放/暂停 | 采集入口 |
+| `feature/analytics/AnalyticsReport.kt`（新） | 只读查询：日汇总、时段 Top3、作者数 | 供设置页报告使用 |
+| `ui/hostsettings/AnalyticsDialog.kt`（新）、`HostSettingsScreen.kt` | 报告对话框 + `DIALOG_FEATURES` 接入 | 点功能行打开统计 |
+| `test/.../AnalyticsRecorderTest.kt`（新） | 6 条用例 | 时长、暂停不计、暂停中切页不计、首次播放前按播放中、无 id 不记、哈希稳定 |
+| `settings/ModuleConfig.kt` | `preview()` + `ConfigImportMode`（REPLACE/MERGE）；解析与校验抽成 `read()` | 导入前可预览；默认仍是替换，旧调用不变 |
+| `ui/hostsettings/HostSettingsViewModel.kt` | `PendingConfig` 状态；`importConfig` 只预览，`applyConfig(mode)` 才写 | 先预览后选择 |
+| `ui/hostsettings/HostSettingsScreen.kt`、`HostSettingsActivity.kt`、`ModuleDebug.kt` | `ConfigPreviewDialog`（取消/合并/替换）+ 参数接线 | 同上 |
+| `res/values*/home_strings.xml` | 预览文案（中/英） | 同上 |
+| `test/.../ModuleConfigTest.kt` | 新增 3 条用例 | 合并保留未涉及的键；预览计数不写入；预览同样拒绝别的宿主 |
+| `app/shared/integrity/HostApk.kt`（新） | 流式算宿主 APK 整包 SHA-256 + `HostVerdict`；`KnownHostApks.DIGESTS` 默认空 | 同版本号的改包能被识别（默认不启用、不读文件） |
+| `app/tiktok/TikTokHostAdapter.kt` | 主进程启动时在 `diagnosticsWorker` 上校验并把结果写日志 | `host_identity verdict=… digest=… version=…` |
+| `test/.../HostApkTest.kt`（新） | 6 条用例 | 摘要值、四种 verdict、大小写不敏感、缺文件、清单为空 |
+| `app/shared/media/MediaSaved.kt`（新） | 保存完成广播；监听方要在 `save` 返回前取走字节 | 给上传之类的后续动作一个挂点，宿主的下载器不经过这里 |
+| `app/tiktok/injector/AndroidMediaFiles.kt` | 保存成功后 `MediaSaved.publish(output, type)` | 文件在 finally 里删除，所以通知放在删除前 |
+| `app/shared/net/WebDavClient.kt`（新） | Basic 认证、MKCOL（裸 socket，`HttpURLConnection` 不许该方法）、PUT、307/308 跟随且跨源不带凭据 | 纯 JDK API，可单测 |
+| `feature/general/WebDavUpload.kt`（新） | `@HookEntry` 功能：地址/用户/密码/目录；保存后复制到缓存并单线程上传；32 MiB 上限；日志只写状态 | 把模块保存的文件自动传上 WebDAV |
+| `ui/hostsettings/HostFeatureLabels.kt`、`res/values*/host_settings_strings.xml` | 功能与 4 个选项的中英文案 | 设置页可见可配 |
+| `app/shared/media/DownloadQueue.kt`（新） | 2 线程 FIFO 队列、进度、取消、`TaskStage` 失败阶段、保留最近 20 条；`DownloadConsoles` 把队列发布给设置页 | D-4 的任务管理核心 |
+| `app/tiktok/sdk/media/MediaFiles.kt` | `save(...)` 增加 `progress` 与 `cancelled` 两个默认参数 | 不改变既有调用方，新增的下载器可以上报/取消 |
+| `app/tiktok/injector/AndroidMediaFiles.kt` | 下载循环里分块上报进度、按 `cancelled()` 抛 `DownloadCancelled` | 同上 |
+| `app/tiktok/feature/feed/DownloadVideoWithName.kt` | 接管后改为入队（`DownloadQueue`），实现 `DownloadConsole` 并注册/注销 | 进度与取消对设置页可见 |
+| `ui/hostsettings/DownloadTasksDialog.kt`（新）、`HostSettingsScreen.kt` | 任务列表（排队/进度/失败阶段/取消/清空），500ms 轮询快照 | 设置页点该功能即打开 |
+| `res/values*/home_strings.xml` | 下载任务文案（中/英） | 同上 |
+| `app/shared/media/Mp3Encoder.kt`（新） | jump3r `LameEncoder` 包装：分块喂 PCM、按需 flush；没有输入时 flush 直接返回 | MP3 编码不需要原生库 |
+| `app/shared/media/VideoAudio.kt`（新） | `MediaExtractor` + `MediaCodec` 解码第一条音轨 → MP3 文件，返回时长或失败原因 | 视频原声导出 |
+| `feature/general/SaveVideoAudio.kt`（新） | 监听 `MediaSaved` 的 video，复制后转 MP3，再 `saveFile` 到 `Music/douyin/audio` | D-5 |
+| `MediaFiles.kt`、`AndroidMediaFiles.kt` | `save` 增加 `tree`（SAF 目录）参数；新增 `saveFile`；图片帖入口用 `ImageFolder.IMAGE` | D-4b |
+| `feature/feed/DownloadVideoWithName.kt` | 同一入口接管图片帖（逐张 + 序号）、读 `tree` 选项 | D-4b |
+| `ui/hostsettings/DownloadTasksDialog.kt` | 目录选择（`OpenDocumentTree` + 持久授权）、改回相册目录 | D-4b |
+| `test/.../Mp3EncoderTest.kt`（新）、`DownloadVideoWithNameTest.kt` | 4 条编码用例；图片帖 2 条 | D-5 / D-4b |
+| `feature/analytics/AnalyticsReport.kt` | `AnalyticsSummary.dayRows()`：日期升序 | 导出按日历阅读 |
+| `ui/hostsettings/AnalyticsImageExport.kt`（新） | 画布渲染 PNG（标题 + 汇总 + 每日一行）写 `Downloads/Nuke` | S-4 |
+| `ui/hostsettings/AnalyticsDialog.kt`、`values*/host_settings_strings.xml` | “导出图片”动作与结果提示 | S-4 |
+| `test/.../AnalyticsSummaryTest.kt`（新） | 2 条用例（升序、空摘要） | S-4 |
+| `test/.../DownloadQueueTest.kt`（新） | 8 条用例 | 完成/失败阶段/排队取消/运行取消/顺序/清理/上限 |
+| `test/.../WebDavClientTest.kt`（新） | 8 条用例（JDK 自带 HttpServer） | 成功、建目录、401/403、转义、跨源重定向不带凭据、非法地址、文件缺失 |
+| `test/.../MediaFeaturesTest.kt` | 适配 `DownloadOriginalVideo` 新构造参数 | 保持既有用例可编译 |
+| `app/shared/media/MediaNaming.kt`（新） | 下载命名模板：10 个标记（#author#/#desc#/#aid#/#date#/#time#/#datetime#/#timestamp#/#index#/#type#/#ext#）、按 UTF-8 字节截断、非法字符替换、空结果回退下载时间；`folder()` 只放行纯字母数字 | 命名与目录不再依赖宿主的保存路径 |
+| `feature/feed/DownloadVideoWithName.kt`（新） | `@HookEntry` 功能（默认关闭）：接管长按菜单的保存项，读面板持有的 Aweme → 播放地址 → 自有下载，模板与文件夹两个 `TextOption`；没有视频/可播地址时不接管 | 自有下载器与 D-2/D-3 的落地 |
+| `query/DownloadQueries.kt` | 新增 `PANEL_DOWNLOAD_CLICK`（`LppDownloadModule` 点击）与 `PANEL_AWEME`（`LongPressPanelAbsModule` 的 Aweme getter） | 长按面板入口 |
+| `sdk/media/MediaFiles.kt` | `save(..., name)` 增加可选文件名；`ImageFolder.VIDEO` | 给复用保存路径的功能传名字 |
+| `injector/AndroidMediaFiles.kt` | 视频写 `MediaStore.Video` + `Movies/douyin/<folder>`；有名字时用名字（扩展名以实际保存的文件为准），旧版保存重名时递增后缀 | 同上 |
+| `ui/hostsettings/HostFeatureLabels.kt`、`res/values*/host_settings_strings.xml` | 新功能与两个选项的中英文案 | 设置页可见可配 |
+| `test/.../MediaNamingTest.kt`（新） | 11 条用例 | 展开、清洗、索引、时区、字节截断、回退、文件夹校验 |
+| `test/.../DownloadVideoWithNameTest.kt`（新） | 5 条用例 | 接管与命名、文件夹、无视频/无地址回退宿主、base64 地址 |
+| `test/.../FakeHostUi.kt` | `RecordingMediaFiles` 记录 `name` | 断言命名 |
+
+## 约定
+
+* 改动直接改这里的文件，按功能说明写在提交信息里；这里没有 `patches/` 之类的东西。
+* 完整 diff 与验证记录见上层 [`../CHANGES.md`](../CHANGES.md)（改完这一层后要重新生成）。
+* 本目录**未提交**到 ganvivo 仓库时，改动只存在于工作区（`git status` 可见），不要 `git clean -xdf`。
+* 上游更新（可选）：把上游新版本单独克隆到临时目录，与本目录 `diff` 后手工并回来；**不要**把上游 `.git` 引入本目录。
+* 本机构建与单测命令（Gradle 9.8.2 + SDK `platforms/android-37.0`，不需要 Rust/NDK）：
+
+```sh
+cd nuke/src
+./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:compileDebugKotlin
+./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:testDebugUnitTest -x checkProtocolRust --tests "<要跑的测试类>"
+```
+
+  整包单测在本机会卡在 `PacketExecutorTest.cancellationPropagatesAndClosesWaiter`，别直接跑全量。
diff --git a/apps/module/build.gradle.kts b/apps/module/build.gradle.kts
--- a/apps/module/build.gradle.kts
+++ b/apps/module/build.gradle.kts
@@ -168,6 +168,8 @@
     // Audio conversion for chosen voice files; its native library exists for arm64-v8a only.
     implementation(libs.silk.codec)
     implementation(libs.dexmaker)
+    // MP3 encoding for the audio a saved video carries: a pure-Java LAME port, like the older module used.
+    implementation(libs.jump3r)
     // Renders a script's README on the scripts page, with highlighted code blocks, as the legacy page did.
     implementation(libs.markdown.renderer.m3)
     implementation(libs.markdown.renderer.code)
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/integrity/HostApk.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/integrity/HostApk.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/integrity/HostApk.kt
@@ -0,0 +1,57 @@
+package io.github.nuke.oss.app.shared.integrity
+
+import java.io.File
+import java.io.IOException
+import java.security.MessageDigest
+
+// What the host APK on this device is, as far as the module can tell. A repackaged build keeps the version name and
+// code, so the whole file's digest is the only thing that tells it apart from the build the module was verified on.
+internal enum class HostVerdict {
+    KNOWN,
+    UNKNOWN,
+    UNREADABLE,
+    // The known-good list is empty: the host is not being checked at all.
+    UNCONFIGURED;
+
+    val label: String get() = name.lowercase()
+}
+
+// The host builds whose whole APK digest this module was verified against. Empty by default: the check reads the
+// hundred-megabyte host APK only once someone fills this in, and a filled list is meant to be updated whenever the host
+// updates. Digests are lowercase hex SHA-256 of the APK file as installed.
+internal object KnownHostApks {
+    val DIGESTS: Set<String> = emptySet()
+}
+
+internal object HostApk {
+    // Streamed in chunks, since the host APK is far too large to read into one array. Null when the file cannot be read,
+    // which is reported as its own verdict rather than as a mismatch.
+    fun digest(file: File): String? = try {
+        if (!file.isFile) null else {
+            val digest = MessageDigest.getInstance("SHA-256")
+            file.inputStream().use { input ->
+                val buffer = ByteArray(BUFFER)
+                while (true) {
+                    val count = input.read(buffer)
+                    if (count < 0) break
+                    digest.update(buffer, 0, count)
+                }
+            }
+            digest.digest().joinToString("") { "%02x".format(it) }
+        }
+    } catch (_: IOException) {
+        null
+    } catch (_: RuntimeException) {
+        // SecurityException from a file the process may not read, and anything a broken stream throws.
+        null
+    }
+
+    fun verdict(digest: String?, known: Set<String>): HostVerdict = when {
+        known.isEmpty() -> HostVerdict.UNCONFIGURED
+        digest == null -> HostVerdict.UNREADABLE
+        known.any { it.equals(digest, ignoreCase = true) } -> HostVerdict.KNOWN
+        else -> HostVerdict.UNKNOWN
+    }
+
+    private const val BUFFER = 64 * 1024
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/DownloadQueue.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/DownloadQueue.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/DownloadQueue.kt
@@ -0,0 +1,175 @@
+package io.github.nuke.oss.app.shared.media
+
+import java.util.concurrent.ExecutorService
+import java.util.concurrent.Executors
+import java.util.concurrent.atomic.AtomicLong
+
+internal enum class TaskState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
+
+// Where a task gave up: resolving the post, fetching the bytes, or writing them to the gallery.
+internal enum class TaskStage { RESOLVE, DOWNLOAD, SAVE }
+
+// One download, as the settings screen shows it. Names come from the post, so they are already the ones the file will
+// carry; no address or identifier is kept here.
+internal data class DownloadTask(
+    val id: Long,
+    val name: String,
+    val state: TaskState,
+    val bytes: Long = 0,
+    val total: Long = 0,
+    val stage: TaskStage? = null,
+    val reason: String? = null,
+) {
+    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
+}
+
+// What the settings screen reads. The queue below is the only implementation, and it lives as long as the host process.
+internal interface DownloadConsole {
+    fun snapshot(): List<DownloadTask>
+
+    // True when the task was queued or running and will not carry on.
+    fun cancel(id: Long): Boolean
+
+    // Files kept from earlier runs of this process, newest last; empty while the queue is empty.
+    fun recent(): List<DownloadTask>
+
+    // Drops the finished tasks from the screen; running ones stay.
+    fun clearFinished()
+}
+
+// What a running download reports into. Both calls come from the download thread and never block.
+internal class DownloadProgress internal constructor(
+    private val onBytes: (bytes: Long, total: Long) -> Unit,
+    private val cancelled: () -> Boolean,
+) {
+    fun update(bytes: Long, total: Long) = onBytes(bytes, total)
+
+    fun isCancelled(): Boolean = cancelled()
+
+    // Called by the downloader between chunks; a cancelled task stops by throwing this.
+    fun check() {
+        if (cancelled()) throw DownloadCancelled()
+    }
+}
+
+internal class DownloadCancelled : RuntimeException("cancelled")
+
+// A small queue: at most `threads` downloads at a time, the rest waiting in submission order. Progress, cancellation and
+// the failure stage are kept per task so the settings screen can show what happened without reading the log.
+internal class DownloadQueue(threads: Int = 2) : DownloadConsole, AutoCloseable {
+    private val ids = AtomicLong()
+    private val lock = Any()
+    private val tasks = LinkedHashMap<Long, DownloadTask>()
+    private val cancels = HashSet<Long>()
+    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { task ->
+        Thread(task, "nuke-download").apply { isDaemon = true }
+    }
+
+    fun submit(name: String, stage: TaskStage = TaskStage.DOWNLOAD, work: (DownloadProgress) -> Unit): Long {
+        val id = ids.incrementAndGet()
+        synchronized(lock) {
+            tasks[id] = DownloadTask(id, name, TaskState.QUEUED)
+            trim()
+        }
+        try {
+            pool.execute { run(id, stage, work) }
+        } catch (_: RuntimeException) {
+            // The pool is shut down: this task never runs.
+            finish(id, TaskState.CANCELLED, null, null)
+        }
+        return id
+    }
+
+    override fun snapshot(): List<DownloadTask> = synchronized(lock) { tasks.values.toList() }
+
+    override fun recent(): List<DownloadTask> = snapshot().filter { it.state != TaskState.QUEUED && it.state != TaskState.RUNNING }
+
+    override fun clearFinished() = synchronized(lock) {
+        tasks.values.filter { it.state != TaskState.QUEUED && it.state != TaskState.RUNNING }.forEach { tasks.remove(it.id) }
+    }
+
+    override fun cancel(id: Long): Boolean = synchronized(lock) {
+        val task = tasks[id] ?: return false
+        if (task.state != TaskState.QUEUED && task.state != TaskState.RUNNING) return false
+        cancels += id
+        if (task.state == TaskState.QUEUED) tasks[id] = task.copy(state = TaskState.CANCELLED)
+        true
+    }
+
+    override fun close() {
+        pool.shutdownNow()
+    }
+
+    // Tests wait on this instead of sleeping for a guessed time.
+    internal fun awaitIdle(timeoutMillis: Long = 5_000): Boolean {
+        val deadline = System.currentTimeMillis() + timeoutMillis
+        while (System.currentTimeMillis() < deadline) {
+            val running = snapshot().any { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }
+            if (!running) return true
+            Thread.sleep(10)
+        }
+        return false
+    }
+
+    private fun run(id: Long, stage: TaskStage, work: (DownloadProgress) -> Unit) {
+        synchronized(lock) {
+            val queued = tasks[id] ?: return
+            if (queued.state == TaskState.CANCELLED) return
+            if (cancels.remove(id)) {
+                tasks[id] = queued.copy(state = TaskState.CANCELLED)
+                return
+            }
+            tasks[id] = queued.copy(state = TaskState.RUNNING)
+        }
+        val progress = DownloadProgress(
+            onBytes = { bytes, total -> update(id) { it.copy(bytes = bytes, total = total) } },
+            cancelled = { synchronized(lock) { cancels.contains(id) } },
+        )
+        try {
+            work(progress)
+            finish(id, TaskState.SUCCEEDED, null, null)
+        } catch (_: DownloadCancelled) {
+            finish(id, TaskState.CANCELLED, null, null)
+        } catch (error: Exception) {
+            finish(id, TaskState.FAILED, stage, error.message?.take(160) ?: error.javaClass.simpleName)
+        }
+    }
+
+    private fun update(id: Long, change: (DownloadTask) -> DownloadTask) = synchronized(lock) {
+        tasks[id]?.let { tasks[id] = change(it) }
+    }
+
+    private fun finish(id: Long, state: TaskState, stage: TaskStage?, reason: String?) = synchronized(lock) {
+        cancels -= id
+        tasks[id]?.let { tasks[id] = it.copy(state = state, stage = stage, reason = reason) }
+        trim()
+    }
+
+    // Finished tasks are kept for the screen that opens later; the oldest go first, and the running ones never leave.
+    private fun trim() {
+        val finished = tasks.values.filter { it.state != TaskState.QUEUED && it.state != TaskState.RUNNING }
+        if (finished.size <= KEEP_FINISHED) return
+        for (task in finished.take(finished.size - KEEP_FINISHED)) tasks.remove(task.id)
+    }
+
+    private companion object {
+        const val KEEP_FINISHED = 20
+    }
+}
+
+// The settings screen opens in the host process but outside the feature that owns the queue, so the queue is published
+// here; the same process is the only place these objects exist.
+internal object DownloadConsoles {
+    @Volatile private var console: DownloadConsole? = null
+
+    fun register(value: DownloadConsole) {
+        console = value
+    }
+
+    // Clears only the queue that registered, so a replaced feature cannot erase the newer one.
+    fun clear(value: DownloadConsole) {
+        if (console === value) console = null
+    }
+
+    fun current(): DownloadConsole? = console
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/MediaNaming.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/MediaNaming.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/MediaNaming.kt
@@ -0,0 +1,91 @@
+package io.github.nuke.oss.app.shared.media
+
+import java.text.SimpleDateFormat
+import java.util.Date
+import java.util.Locale
+import java.util.TimeZone
+
+// Turns a download name template into a file name the gallery accepts. A token is #name# (or {name}) and expands to
+// one of the metadata values; a token without a value expands to nothing. The result is one path component: separators,
+// wildcards, control characters and the characters Windows refuses are replaced, runs of separators are collapsed, and
+// the name is cut to a byte budget rather than a character budget, since the filesystem counts bytes. A template that
+// expands to nothing falls back to the time of the download, so a save never ends up without a name.
+internal object MediaNaming {
+    const val DEFAULT_TEMPLATE = "#author#_#desc#"
+    const val DEFAULT_FOLDER = "video"
+    // FAT/EXFAT allow 255 bytes; the gallery appends "_1" for a name it has seen, so leave room for that.
+    const val MAX_BYTES = 150
+    private const val FALLBACK_PREFIX = "media"
+    private val TOKEN = Regex("#([A-Za-z][A-Za-z0-9]*)#|\\$?\\{([A-Za-z][A-Za-z0-9]*)\\}")
+    private val INVALID = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
+    private val SEPARATORS = Regex("[\\s_]+")
+    private val REPEATED = Regex("-{2,}|\\.{2,}")
+
+    // The name without its extension; the writer appends the extension of the file it actually saved. index counts
+    // from 1 for the second image of a set, and is appended in the host's two-digit style when the template has no
+    // index token of its own.
+    fun fileName(
+        template: String,
+        values: Map<String, String>,
+        extension: String,
+        index: Int = 0,
+        now: Long = System.currentTimeMillis(),
+        timeZone: TimeZone = TimeZone.getDefault(),
+    ): String {
+        val tokens = HashMap<String, String>(values.size + 6)
+        for ((key, value) in values) tokens[key] = value
+        tokens["ext"] = extension
+        tokens["index"] = if (index > 0) String.format(Locale.ROOT, "%02d", index) else ""
+        tokens["timestamp"] = now.toString()
+        val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { this.timeZone = timeZone }
+        tokens["date"] = date.format(Date(now))
+        val time = SimpleDateFormat("HHmmss", Locale.ROOT).apply { this.timeZone = timeZone }
+        tokens["time"] = time.format(Date(now))
+        tokens["datetime"] = "${tokens["date"]}_${tokens["time"]}"
+        var pattern = template.ifBlank { DEFAULT_TEMPLATE }
+        if (index > 0 && !pattern.contains("index")) pattern += "_${tokens["index"]}"
+        val expanded = TOKEN.replace(pattern) { match ->
+            val key = match.groupValues[1].ifEmpty { match.groupValues[2] }
+            tokens[key].orEmpty()
+        }
+        val cleaned = sanitize(expanded)
+        return if (cleaned.isEmpty()) "${FALLBACK_PREFIX}_${now}" else cleaned
+    }
+
+    // A single directory under the gallery folder of the media kind. Anything that is not plain words is refused, so a
+    // value from the settings can never climb out of the folder the feature owns.
+    fun folder(raw: String): String {
+        val value = raw.trim()
+        if (value.isEmpty() || value.length > 40) return DEFAULT_FOLDER
+        if (!value.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return DEFAULT_FOLDER
+        return if (value == "." || value == "..") DEFAULT_FOLDER else value
+    }
+
+    private fun sanitize(input: String): String {
+        val replaced = INVALID.replace(input, "_")
+        val joined = SEPARATORS.replace(replaced, "_")
+        val single = REPEATED.replace(joined) { match -> match.value.take(1) }
+        return limit(single.trim('.', '-', '_'))
+    }
+
+    // Joins code points while their UTF-8 form fits, so a long Chinese description is cut between characters.
+    private fun limit(name: String): String {
+        var bytes = 0
+        val output = StringBuilder(name.length)
+        var offset = 0
+        while (offset < name.length) {
+            val codePoint = name.codePointAt(offset)
+            val width = when {
+                codePoint < 0x80 -> 1
+                codePoint < 0x800 -> 2
+                codePoint < 0x10000 -> 3
+                else -> 4
+            }
+            if (bytes + width > MAX_BYTES) break
+            output.appendCodePoint(codePoint)
+            bytes += width
+            offset += Character.charCount(codePoint)
+        }
+        return output.toString().trimEnd('.', '-', '_')
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/MediaSaved.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/MediaSaved.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/MediaSaved.kt
@@ -0,0 +1,35 @@
+package io.github.nuke.oss.app.shared.media
+
+import io.github.nuke.oss.app.tiktok.sdk.media.MediaFileType
+import java.io.File
+import java.util.concurrent.CopyOnWriteArrayList
+
+// Every file the module itself saves, announced while the file still exists. `AndroidMediaFiles.save` deletes its
+// temporary download as soon as the save returns, so a listener that needs the bytes must copy them now; the WebDAV
+// upload does exactly that and does the network work afterwards. Host process only, like everything else here.
+internal object MediaSaved {
+    fun interface Listener {
+        // context is the host Context the save used, so a listener that writes afterwards can use it too.
+        fun saved(context: Any, file: File, type: MediaFileType)
+    }
+
+    private val listeners = CopyOnWriteArrayList<Listener>()
+
+    fun add(listener: Listener) {
+        listeners += listener
+    }
+
+    fun remove(listener: Listener) {
+        listeners -= listener
+    }
+
+    // A listener that fails costs the announcement, not the save.
+    fun publish(context: Any, file: File, type: MediaFileType) {
+        for (listener in listeners) {
+            try {
+                listener.saved(context, file, type)
+            } catch (_: RuntimeException) {
+            }
+        }
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/Mp3Encoder.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/Mp3Encoder.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/Mp3Encoder.kt
@@ -0,0 +1,45 @@
+package io.github.nuke.oss.app.shared.media
+
+import de.sciss.jump3r.lowlevel.LameEncoder
+import javax.sound.sampled.AudioFormat
+
+// MP3 encoding for audio the module already has as PCM. jump3r is a pure-Java LAME port, so this needs no native
+// library and runs wherever the module does. Callers pass 16-bit little-endian PCM, mono or stereo.
+internal class Mp3Encoder(
+    sampleRate: Int,
+    channels: Int,
+    bitrateKbps: Int = 192,
+) : AutoCloseable {
+    private val encoder = LameEncoder(
+        AudioFormat(sampleRate.toFloat(), 16, channels, true, false),
+        bitrateKbps,
+        if (channels == 1) LameEncoder.CHANNEL_MODE_MONO else LameEncoder.CHANNEL_MODE_STEREO,
+        LameEncoder.QUALITY_MIDDLE,
+        false,
+    )
+    private val frame = ByteArray(encoder.getMP3BufferSize())
+    private val chunk = ByteArray(encoder.getPCMBufferSize())
+    private var fed = 0L
+
+    // LAME fills whole frames; a decoder hands back buffers of any length, so they are split here.
+    fun encode(pcm: ByteArray, length: Int, sink: (data: ByteArray, count: Int) -> Unit) {
+        var offset = 0
+        while (offset < length) {
+            val count = minOf(length - offset, chunk.size)
+            System.arraycopy(pcm, offset, chunk, 0, count)
+            val written = encoder.encodeBuffer(chunk, 0, count, frame)
+            if (written > 0) sink(frame, written)
+            fed += count
+            offset += count
+        }
+    }
+
+    fun finish(sink: (data: ByteArray, count: Int) -> Unit) {
+        // LAME asserts while flushing a stream that never received a sample.
+        if (fed == 0L) return
+        val written = encoder.encodeFinish(frame)
+        if (written > 0) sink(frame, written)
+    }
+
+    override fun close() = encoder.close()
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/VideoAudio.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/VideoAudio.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/media/VideoAudio.kt
@@ -0,0 +1,98 @@
+package io.github.nuke.oss.app.shared.media
+
+import android.media.MediaCodec
+import android.media.MediaExtractor
+import android.media.MediaFormat
+import java.io.File
+import java.io.IOException
+
+// The result of taking a video's audio track out: the MP3 file and how long the source was, or why it could not be done.
+internal sealed interface AudioExport {
+    data class Converted(val file: File, val durationMillis: Long) : AudioExport
+
+    data class Failed(val reason: String) : AudioExport
+}
+
+// Decodes the first audio track of a video and writes it as MP3. Blocking, so callers run it off the main thread.
+internal object VideoAudio {
+    fun toMp3(video: File, output: File): AudioExport {
+        if (!video.isFile) return AudioExport.Failed("no video file")
+        var extractor: MediaExtractor? = null
+        var codec: MediaCodec? = null
+        var encoder: Mp3Encoder? = null
+        return try {
+            extractor = MediaExtractor().apply { setDataSource(video.path) }
+            val track = (0 until extractor.trackCount).firstOrNull { index ->
+                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
+            } ?: return AudioExport.Failed("no audio track")
+            val format = extractor.getTrackFormat(track)
+            val duration = (runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L).takeIf { it > 0 } ?: 0L) / 1_000
+            extractor.selectTrack(track)
+            codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
+            codec.configure(format, null, null, 0)
+            codec.start()
+            output.delete()
+            output.outputStream().use { sink ->
+                val info = MediaCodec.BufferInfo()
+                var ended = false
+                while (!ended) {
+                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_MICROS)
+                    if (inputIndex >= 0) {
+                        val buffer = checkNotNull(codec.getInputBuffer(inputIndex))
+                        val size = extractor.readSampleData(buffer, 0)
+                        if (size < 0) {
+                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
+                        } else {
+                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
+                            extractor.advance()
+                        }
+                    }
+                    when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_MICROS)) {
+                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
+                            val out = codec.outputFormat
+                            // A second change would mean a second stream, which this deliberately does not mix.
+                            if (encoder != null) return AudioExport.Failed("output format changed twice")
+                            encoder = Mp3Encoder(
+                                out.getInteger(MediaFormat.KEY_SAMPLE_RATE),
+                                out.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
+                            )
+                        }
+                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
+                        else -> if (outputIndex >= 0) {
+                            val buffer = checkNotNull(codec.getOutputBuffer(outputIndex))
+                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
+                                val pcm = ByteArray(info.size)
+                                buffer.position(info.offset)
+                                buffer.limit(info.offset + info.size)
+                                buffer.get(pcm)
+                                encoder?.encode(pcm, pcm.size) { data, count -> sink.write(data, 0, count) }
+                            }
+                            codec.releaseOutputBuffer(outputIndex, false)
+                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
+                        }
+                    }
+                }
+                encoder?.finish { data, count -> sink.write(data, 0, count) }
+            }
+            if (!output.isFile || output.length() == 0L) {
+                output.delete()
+                return AudioExport.Failed("the encoder wrote nothing")
+            }
+            AudioExport.Converted(output, duration)
+        } catch (error: IOException) {
+            output.delete()
+            AudioExport.Failed(error.javaClass.simpleName + ": " + error.message.orEmpty().take(120))
+        } catch (error: RuntimeException) {
+            output.delete()
+            AudioExport.Failed(error.javaClass.simpleName + ": " + error.message.orEmpty().take(120))
+        } finally {
+            encoder?.close()
+            codec?.let { runCatching { it.stop() } }
+            codec?.release()
+            extractor?.release()
+        }
+    }
+
+    // A codec that stops answering must not hold the worker thread for ever.
+    private const val TIMEOUT_MICROS = 10_000L
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/net/WebDavClient.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/net/WebDavClient.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/net/WebDavClient.kt
@@ -0,0 +1,143 @@
+package io.github.nuke.oss.app.shared.net
+
+import java.io.File
+import java.io.IOException
+import java.net.HttpURLConnection
+import java.net.Socket
+import java.net.URI
+import java.net.URL
+import java.net.URLEncoder
+import java.util.Base64
+import javax.net.ssl.SSLParameters
+import javax.net.ssl.SSLSocket
+import javax.net.ssl.SSLSocketFactory
+
+// The outcome of one upload. A failure keeps the status the server answered with, so the log tells a wrong password
+// (401) from a missing collection (409) without repeating the attempt.
+internal sealed interface WebDavResult {
+    data class Uploaded(val url: String, val status: Int) : WebDavResult
+
+    data class Failed(val reason: String, val status: Int = 0) : WebDavResult
+}
+
+// A small WebDAV client: it makes sure the collection exists and puts one file into it. Basic authentication only,
+// and only for the origin the settings named; a redirect to another host is followed without the credentials.
+internal class WebDavClient(
+    baseUrl: String,
+    private val user: String,
+    private val password: String,
+    private val directory: String = "",
+    private val connectTimeoutMillis: Int = 10_000,
+    private val readTimeoutMillis: Int = 60_000,
+) {
+    private val base = baseUrl.trim().trimEnd('/')
+    private val origin = originOf(base)
+
+    // Returns where the file was written, or why it was not. Never throws.
+    fun upload(file: File, name: String): WebDavResult {
+        if (base.isEmpty() || origin == null) return WebDavResult.Failed("unsupported address")
+        if (!file.isFile) return WebDavResult.Failed("no file to upload")
+        val segments = directory.split('/').filter { it.isNotBlank() }
+        val collection = segments.joinToString("/") { encode(it) }
+        val target = (segments.map(::encode) + encode(name)).joinToString("/")
+        try {
+            if (collection.isNotEmpty()) {
+                // 405 and 301 mean the collection is already there, which is what this asks for.
+                val status = createCollection("$base/$collection")
+                if (status !in 200..299 && status != 405 && status != 301) {
+                    return WebDavResult.Failed("collection not created", status)
+                }
+            }
+            val status = request("PUT", "$base/$target", file)
+            if (status !in 200..299) return WebDavResult.Failed("upload rejected", status)
+            return WebDavResult.Uploaded("$base/$target", status)
+        } catch (error: IOException) {
+            return WebDavResult.Failed(error.javaClass.simpleName + ": " + error.message.orEmpty().take(120))
+        } catch (error: RuntimeException) {
+            return WebDavResult.Failed(error.javaClass.simpleName + ": " + error.message.orEmpty().take(120))
+        }
+    }
+
+    // MKCOL over a socket of our own: HttpURLConnection refuses every method outside a short list, and MKCOL is one of
+    // them. A TLS connection verifies the host name like any other request does.
+    private fun createCollection(address: String): Int {
+        val uri = URI(address)
+        val scheme = uri.scheme.lowercase()
+        val host = uri.host ?: throw IOException("no host in $address")
+        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
+        val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?" + it } ?: "")
+        val socket = if (scheme == "https") {
+            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, port) as SSLSocket
+        } else {
+            Socket(host, port)
+        }
+        socket.use { open ->
+            if (open is SSLSocket) {
+                open.sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "HTTPS" }
+                open.startHandshake()
+            }
+            open.soTimeout = readTimeoutMillis
+            val header = StringBuilder()
+                .append("MKCOL ").append(path).append(" HTTP/1.1\r\n")
+                .append("Host: ").append(host).append("\r\n")
+            if (credentialsFor(URL(address))) header.append("Authorization: Basic ").append(basicToken()).append("\r\n")
+            header.append("Content-Length: 0\r\n").append("Connection: close\r\n\r\n")
+            open.getOutputStream().write(header.toString().toByteArray(Charsets.ISO_8859_1))
+            open.getOutputStream().flush()
+            val reader = open.getInputStream().bufferedReader(Charsets.ISO_8859_1)
+            val statusLine = reader.readLine() ?: throw IOException("no response to MKCOL")
+            return statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: $statusLine")
+        }
+    }
+
+    // One request, following the redirects that keep the method; the credentials stay on their own origin.
+    private fun request(method: String, address: String, body: File, redirects: Int = 0): Int {
+        val url = URL(address)
+        val connection = url.openConnection() as HttpURLConnection
+        try {
+            connection.requestMethod = method
+            connection.instanceFollowRedirects = false
+            connection.connectTimeout = connectTimeoutMillis
+            connection.readTimeout = readTimeoutMillis
+            connection.setRequestProperty("Accept", "*/*")
+            if (credentialsFor(url)) connection.setRequestProperty("Authorization", "Basic " + basicToken())
+            connection.doOutput = true
+            connection.setFixedLengthStreamingMode(body.length())
+            connection.outputStream.use { output -> body.inputStream().use { it.copyTo(output) } }
+            val status = connection.responseCode
+            // 307/308 keep the method and the body; 301/302/303 would turn a PUT into a GET, so they are not followed.
+            if (status in listOf(307, 308) && redirects < MAX_REDIRECTS) {
+                val location = connection.getHeaderField("Location") ?: return status
+                return request(method, URL(url, location).toString(), body, redirects + 1)
+            }
+            return status
+        } finally {
+            connection.disconnect()
+        }
+    }
+
+    private fun credentialsFor(url: URL): Boolean {
+        val given = origin ?: return false
+        return given == originOf(url.toString())
+    }
+
+    private fun basicToken(): String =
+        Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
+
+    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
+
+    private companion object {
+        const val MAX_REDIRECTS = 3
+
+        fun originOf(address: String): String? = try {
+            val uri = URI(address)
+            val scheme = uri.scheme?.lowercase() ?: return null
+            if (scheme != "http" && scheme != "https") return null
+            val host = uri.host ?: return null
+            val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
+            "$scheme://${host.lowercase()}:$port"
+        } catch (_: RuntimeException) {
+            null
+        }
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/settings/ModuleConfig.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/settings/ModuleConfig.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/settings/ModuleConfig.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/shared/settings/ModuleConfig.kt
@@ -18,6 +18,18 @@
     enum class Reason { NOT_CONFIG, OTHER_HOST, NEWER_VERSION, INVALID_SETTING }
 }
 
+// How a checked file meets what is stored: REPLACE drops every preference the file does not carry, MERGE keeps them.
+internal enum class ConfigImportMode { REPLACE, MERGE }
+
+// What importing a file would do, so replacing and merging are chosen with the numbers at hand. Keys are the store's
+// flat names, which is enough for the counts the screen shows and keeps the preview cheap.
+internal data class ConfigPreview(
+    val added: List<String>,
+    val changed: List<String>,
+    val unchanged: Int,
+    val replacedAway: List<String>,
+)
+
 // Export, import and cleaning of the module's preferences in one host. Only the user's choices travel; failure
 // records, safe mode, feature memory and the local background image stay behind. The DexKit cache lives in a store of
 // its own that is never exported; cleaning is the one thing that reaches it, through forgetDex.
@@ -45,9 +57,32 @@
         return MiniJson.write(linkedMapOf(FORMAT to FORMAT_ID, VERSION to CURRENT_VERSION, HOST to host, VALUES to exported))
     }
 
-    // The whole file is checked before anything is written, so a rejected file changes nothing. The imported
-    // preferences replace the current ones; keys that are not preferences are left as they are.
-    fun import(text: String): Int {
+    // The whole file is checked before anything is written, so a rejected file changes nothing. REPLACE replaces the
+    // current preferences; MERGE writes only the keys the file carries and leaves the rest as they are. Keys that are
+    // not preferences are left as they are either way.
+    fun import(text: String, mode: ConfigImportMode = ConfigImportMode.REPLACE): Int {
+        val imported = read(text)
+        if (mode == ConfigImportMode.REPLACE) current().keys.filter { it !in imported }.forEach(values::remove)
+        imported.forEach(values::put)
+        return imported.size
+    }
+
+    // The same checks as import, without writing anything: what the file would add, change, leave alone and, when the
+    // file replaces the store, take away.
+    fun preview(text: String): ConfigPreview {
+        val imported = read(text)
+        val current = current()
+        val added = imported.keys.filter { it !in current }
+        val changed = imported.filter { (key, value) -> key in current && current[key] != value }.keys.toList()
+        val unchanged = imported.size - added.size - changed.size
+        val replacedAway = current.keys.filter { it !in imported }
+        return ConfigPreview(added, changed, unchanged, replacedAway)
+    }
+
+    // The preference values as stored, which is all an import or a preview may touch.
+    private fun current(): Map<String, Any?> = values.all().filterKeys { kindOf(it) != null }
+
+    private fun read(text: String): Map<String, Any> {
         val document = MiniJson.obj(MiniJson.parse(text))
         if (document == null || document[FORMAT] != FORMAT_ID) throw ConfigImportRejected(ConfigImportRejected.Reason.NOT_CONFIG)
         val version = document[VERSION] as? Long ?: throw ConfigImportRejected(ConfigImportRejected.Reason.NOT_CONFIG)
@@ -55,13 +90,10 @@
         val source = document[HOST] as? String ?: throw ConfigImportRejected(ConfigImportRejected.Reason.NOT_CONFIG)
         if (source != host) throw ConfigImportRejected(ConfigImportRejected.Reason.OTHER_HOST, source)
         val entries = MiniJson.obj(document[VALUES]) ?: throw ConfigImportRejected(ConfigImportRejected.Reason.NOT_CONFIG)
-        val imported = entries.mapValues { (key, value) ->
+        return entries.mapValues { (key, value) ->
             val kind = kindOf(key) ?: throw ConfigImportRejected(ConfigImportRejected.Reason.INVALID_SETTING, key)
             decode(kind, value) ?: throw ConfigImportRejected(ConfigImportRejected.Reason.INVALID_SETTING, key)
         }
-        values.all().keys.filter { kindOf(it) != null }.forEach(values::remove)
-        imported.forEach(values::put)
-        return imported.size
     }
 
     // Every preference, record and memory of the module in this host, its background image and the DexKit cache, so the
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/TikTokHostAdapter.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/TikTokHostAdapter.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/TikTokHostAdapter.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/TikTokHostAdapter.kt
@@ -108,6 +108,8 @@
 import java.util.concurrent.Executor
 import io.github.nuke.oss.app.shared.environment.EnvironmentCollector
 import io.github.nuke.oss.app.shared.environment.ProcessFacts
+import io.github.nuke.oss.app.shared.integrity.HostApk
+import io.github.nuke.oss.app.shared.integrity.KnownHostApks
 
 // What the adapter needs from the host's private storage: Dex lookups, per-feature switches, options and memory, the
 // screen appearance, the first-launch choices and the diagnostics. The resolver's catalog is every Dex query the init analyses.
@@ -152,6 +154,7 @@
             host.diagnostics, setOf(SettingsEntry.ID, INIT_ID), { id -> manager.setEnabled(id, false) }, environment.logger, diagnosticsWorker,
         )
         manager.failureSink = recorder
+        checkHostIdentity(environment, paths, diagnosticsWorker)
         val locked = recorder.locked()
         val safeMode = host.diagnostics.safeMode()
         if (safeMode || locked.isNotEmpty()) environment.logger.log("features_held safe_mode=$safeMode locked=${locked.size}")
@@ -264,6 +267,19 @@
 
     private data class Built(val feature: RuntimeFeature, val default: Boolean, val processes: Set<HostProcess>)
 
+    // The whole host APK's digest against the builds this module was verified on, off the host's threads. With the list
+    // empty nothing is read and nothing is logged; an unreadable APK is its own verdict, not a mismatch.
+    private fun checkHostIdentity(environment: HostEnvironment, paths: HostPaths, worker: Executor) {
+        if (KnownHostApks.DIGESTS.isEmpty()) return
+        val logger = environment.logger
+        val version = environment.event.build.buildId
+        worker.execute {
+            val digest = HostApk.digest(File(paths.apkPath))
+            val verdict = HostApk.verdict(digest, KnownHostApks.DIGESTS)
+            logger.log("host_identity verdict=${verdict.label} digest=${digest ?: "none"} version=$version")
+        }
+    }
+
     // A feature that cannot work without a query the analysis did not locate is recorded as a Dex failure and held off
     // now, switched on or not, so the settings list shows it failed before anyone turns it on. Unlocking it on the debug
     // page holds it again on the next launch for as long as this host build lacks the query.
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsRecorder.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsRecorder.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsRecorder.kt
@@ -0,0 +1,82 @@
+package io.github.nuke.oss.app.tiktok.feature.analytics
+
+import java.time.Instant
+import java.time.ZoneId
+import java.security.MessageDigest
+
+// Turns the feed's events into finished visits: a video change or the feature stopping closes the one before it, and
+// only the time the video was actually playing counts as visible. Everything here runs on the host's thread, so it does
+// no I/O of its own; the sink decides where the rows go.
+internal class AnalyticsRecorder(
+    private val sink: AnalyticsSink,
+    private val clock: () -> Long = System::currentTimeMillis,
+    private val zone: () -> ZoneId = ZoneId::systemDefault,
+) {
+    private class Open(val aweme: String, val author: String, val source: String, val startedAt: Long) {
+        var visibleMs = 0L
+    }
+
+    private var open: Open? = null
+    // Null until the host reports its first play or pause, so a feed that never reports one keeps the old behaviour.
+    private var playing: Boolean? = null
+    private var playingSince: Long? = null
+
+    // The feature is on: everything from here is tracked.
+    fun start() {
+        sink.collection(true)
+    }
+
+    fun videoChanged(aweme: String?, author: String?, source: String) {
+        finish(END_CHANGED)
+        val key = aweme?.takeIf(String::isNotEmpty) ?: return
+        val now = clock()
+        open = Open(key, author.orEmpty(), source, now)
+        // Only the time a video is actually playing counts: a change while the player is paused starts at zero and
+        // waits for the play report instead of counting from the change.
+        playingSince = if (playing == false) null else now
+    }
+
+    fun playing() {
+        playing = true
+        if (open != null && playingSince == null) playingSince = clock()
+    }
+
+    fun paused() {
+        playing = false
+        playingSince?.let { since -> open?.let { it.visibleMs += (clock() - since).coerceAtLeast(0) } }
+        playingSince = null
+    }
+
+    // The feature is off or the host process ends: close the visit and stop counting.
+    fun stopped(reason: String = END_CLOSED) {
+        finish(reason)
+        sink.collection(false)
+    }
+
+    private fun finish(reason: String) {
+        val visit = open ?: return
+        val now = clock()
+        playingSince?.let { visit.visibleMs += (now - it).coerceAtLeast(0) }
+        playingSince = null
+        open = null
+        val start = Instant.ofEpochMilli(visit.startedAt).atZone(zone())
+        val startDay = start.toLocalDate().toEpochDay()
+        sink.visit(AnalyticsVisit(startDay, start.hour, visit.aweme, visit.author, visit.source, visit.visibleMs, now, reason))
+        if (visit.author.isNotEmpty()) sink.rank(startDay, KIND_AUTHOR, visit.author)
+    }
+
+    companion object {
+        const val KIND_AUTHOR = "author"
+        const val END_CHANGED = "changed"
+        const val END_CLOSED = "closed"
+
+        private val DIGEST = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
+
+        // Identifiers never reach the database as they are; a stable digest is enough to count repeats.
+        fun digest(value: String): String {
+            val digest = DIGEST.get()!!
+            digest.reset()
+            return digest.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
+        }
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsReport.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsReport.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsReport.kt
@@ -0,0 +1,57 @@
+package io.github.nuke.oss.app.tiktok.feature.analytics
+
+import android.database.sqlite.SQLiteDatabase
+import java.io.File
+import java.time.Instant
+import java.time.ZoneId
+
+internal data class AnalyticsDay(val day: Long, val views: Int, val visibleMs: Long)
+
+// What the settings dialog shows: today, the last days together, how many authors, and the busiest hours. Only counts,
+// never an identifier, since the rows behind them hold digests.
+internal data class AnalyticsSummary(
+    val todayViews: Int,
+    val todayVisibleMs: Long,
+    val days: List<AnalyticsDay>,
+    val authors: Int,
+    val hours: List<Pair<Int, Int>>,
+) {
+    val hasData: Boolean get() = days.isNotEmpty() || hours.isNotEmpty()
+
+    // Oldest first, so an exported image reads like a calendar.
+    fun dayRows(): List<AnalyticsDay> = days.sortedBy { it.day }
+    val weekViews: Int get() = days.sumOf { it.views }
+    val weekVisibleMs: Long get() = days.sumOf { it.visibleMs }
+}
+
+internal object AnalyticsReport {
+    // A read-only look at the local database; null when the feature has never run or the file no longer opens.
+    fun read(file: File, zone: ZoneId, now: Long = System.currentTimeMillis(), days: Int = 7): AnalyticsSummary? {
+        if (!file.isFile) return null
+        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
+        val from = today - (days - 1)
+        return try {
+            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
+                val rows = ArrayList<AnalyticsDay>()
+                db.rawQuery("SELECT day, views, visible_ms FROM daily WHERE day >= ? ORDER BY day", arrayOf(from.toString())).use { cursor ->
+                    while (cursor.moveToNext()) rows += AnalyticsDay(cursor.getLong(0), cursor.getInt(1), cursor.getLong(2))
+                }
+                val hours = ArrayList<Pair<Int, Int>>()
+                db.rawQuery(
+                    "SELECT hour, SUM(views) FROM hours WHERE day >= ? GROUP BY hour ORDER BY SUM(views) DESC LIMIT 3",
+                    arrayOf(from.toString()),
+                ).use { cursor ->
+                    while (cursor.moveToNext()) hours += cursor.getInt(0) to cursor.getInt(1)
+                }
+                val authors = db.rawQuery(
+                    "SELECT COUNT(DISTINCT item) FROM ranks WHERE kind = 'author' AND day >= ?",
+                    arrayOf(from.toString()),
+                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
+                val todayRow = rows.firstOrNull { it.day == today }
+                AnalyticsSummary(todayRow?.views ?: 0, todayRow?.visibleMs ?: 0L, rows, authors, hours)
+            }
+        } catch (_: RuntimeException) {
+            null
+        }
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsSink.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsSink.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsSink.kt
@@ -0,0 +1,23 @@
+package io.github.nuke.oss.app.tiktok.feature.analytics
+
+// One finished look at a video. Times are milliseconds and days are local epoch days, so the store never needs a clock
+// of its own; identifiers arrive already hashed by the caller.
+internal data class AnalyticsVisit(
+    val day: Long,
+    val hour: Int,
+    val aweme: String,
+    val author: String,
+    val source: String,
+    val visibleMs: Long,
+    val endedAt: Long,
+    val endReason: String,
+)
+
+// Where the local analysis goes. Implementations never throw: a broken store costs the statistics, not the feed.
+internal interface AnalyticsSink {
+    fun visit(visit: AnalyticsVisit)
+    fun rank(day: Long, kind: String, key: String)
+    // A switch being turned on or off, so a gap in the data can be told apart from an untracked period.
+    fun collection(started: Boolean)
+    fun close()
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/LocalAnalytics.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/LocalAnalytics.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/LocalAnalytics.kt
@@ -0,0 +1,112 @@
+package io.github.nuke.oss.app.tiktok.feature.analytics
+
+import io.github.nuke.oss.app.shared.reflect.NamedCall
+import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
+import io.github.nuke.oss.app.shared.registry.HookEntry
+import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
+import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
+import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
+import io.github.nuke.oss.runtime.FeatureScope
+import io.github.nuke.oss.runtime.RuntimeFeature
+import java.io.File
+import java.time.ZoneId
+import java.util.concurrent.Executors
+import java.util.concurrent.TimeUnit
+
+// Writes the feed's video changes and playback into a local SQLite file, so the settings screen can show how much this
+// device watched and which authors, without asking the host and without sending anything anywhere. Identifiers are
+// hashed before they reach the database. Off by default; turning the switch off closes the visit and records the
+// boundary, so the time it was off is not read as a day without videos.
+@HookEntry
+internal class LocalAnalytics(
+    private val events: FeedEvents,
+    private val directory: File,
+    private val clock: () -> Long = System::currentTimeMillis,
+    private val zone: () -> ZoneId = ZoneId::systemDefault,
+) : RuntimeFeature {
+    override val id = ID
+    private val awemeId = NamedFieldAlias("aid", "aweme_id")
+    private val author = NamedCall("getAuthor")
+    private val authorId = NamedFieldAlias("uid", "sec_uid")
+
+    override fun install(scope: FeatureScope) {
+        val sink = AsyncAnalyticsSink(SqliteAnalyticsSink(File(directory, DATABASE), clock))
+        val recorder = AnalyticsRecorder(sink, clock, zone)
+        recorder.start()
+        scope.own(AutoCloseable {
+            recorder.stopped()
+            sink.drain()
+        })
+        events.onVideoChanged(scope, ID) { aweme ->
+            recorder.videoChanged(hash(awemeId, aweme), hash(authorId, author.of(aweme)?.invoke(aweme)), SOURCE_FEED)
+        }
+        events.onPlayback(scope, ID, onPlay = recorder::playing, onPause = recorder::paused)
+    }
+
+    private fun hash(field: NamedFieldAlias, owner: Any?): String? {
+        if (owner == null) return null
+        val value = field.of(owner)?.get(owner)?.toString()?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
+        return AnalyticsRecorder.digest(value)
+    }
+
+    companion object : TikTokFeature {
+        const val ID = "LocalAnalytics"
+        private const val DATABASE = "analytics.db"
+        private const val SOURCE_FEED = "feed"
+
+        override fun create(services: TikTokFeatureServices) = with(services) {
+            listOf(LocalAnalytics(events, File(featureFiles, ID)))
+        }
+    }
+}
+
+// The host calls the recorder on its own threads, so the statements run on one analytics thread and a feed scroll never
+// waits for the database. drain writes what is still queued when the feature is turned off.
+internal class AsyncAnalyticsSink(private val delegate: AnalyticsSink) : AnalyticsSink {
+    private val executor = Executors.newSingleThreadExecutor { runnable ->
+        Thread(runnable, "nuke-analytics").apply { isDaemon = true }
+    }
+    @Volatile private var closed = false
+
+    override fun visit(visit: AnalyticsVisit) = submit { delegate.visit(visit) }
+    override fun rank(day: Long, kind: String, key: String) = submit { delegate.rank(day, kind, key) }
+    override fun collection(started: Boolean) = submit { delegate.collection(started) }
+    override fun close() = submit { delegate.close() }
+
+    fun drain() {
+        if (closed) return
+        closed = true
+        try {
+            executor.execute {
+                try {
+                    delegate.close()
+                } catch (_: RuntimeException) {
+                }
+            }
+        } catch (_: RuntimeException) {
+        }
+        executor.shutdown()
+        try {
+            executor.awaitTermination(DRAIN_MILLIS, TimeUnit.MILLISECONDS)
+        } catch (_: InterruptedException) {
+            Thread.currentThread().interrupt()
+        }
+    }
+
+    private fun submit(job: () -> Unit) {
+        if (closed) return
+        try {
+            executor.execute {
+                try {
+                    job()
+                } catch (_: RuntimeException) {
+                }
+            }
+        } catch (_: RuntimeException) {
+        }
+    }
+
+    private companion object {
+        const val DRAIN_MILLIS = 2_000L
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/SqliteAnalyticsSink.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/SqliteAnalyticsSink.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/SqliteAnalyticsSink.kt
@@ -0,0 +1,70 @@
+package io.github.nuke.oss.app.tiktok.feature.analytics
+
+import android.database.sqlite.SQLiteDatabase
+import java.io.File
+
+// The local analysis database: one file in the host's private storage, read and written only from the analytics thread.
+// Every statement is best-effort; a failure is swallowed so a damaged file can never break the feed.
+internal class SqliteAnalyticsSink(
+    private val file: File,
+    private val clock: () -> Long = System::currentTimeMillis,
+) : AnalyticsSink {
+    private var database: SQLiteDatabase? = open()
+
+    private fun open(): SQLiteDatabase? = try {
+        file.parentFile?.mkdirs()
+        SQLiteDatabase.openOrCreateDatabase(file, null).also { db ->
+            db.execSQL("CREATE TABLE IF NOT EXISTS visits(id INTEGER PRIMARY KEY AUTOINCREMENT, day INTEGER NOT NULL, hour INTEGER NOT NULL, aweme TEXT NOT NULL, author TEXT NOT NULL, source TEXT NOT NULL, visible_ms INTEGER NOT NULL, ended_at INTEGER NOT NULL, end_reason TEXT NOT NULL)")
+            db.execSQL("CREATE TABLE IF NOT EXISTS daily(day INTEGER PRIMARY KEY, views INTEGER NOT NULL, visible_ms INTEGER NOT NULL)")
+            db.execSQL("CREATE TABLE IF NOT EXISTS hours(day INTEGER NOT NULL, hour INTEGER NOT NULL, views INTEGER NOT NULL, PRIMARY KEY(day, hour))")
+            db.execSQL("CREATE TABLE IF NOT EXISTS ranks(day INTEGER NOT NULL, kind TEXT NOT NULL, item TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY(day, kind, item))")
+            db.execSQL("CREATE TABLE IF NOT EXISTS collection(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, started INTEGER NOT NULL)")
+        }
+    } catch (_: RuntimeException) {
+        null
+    } catch (_: java.io.IOException) {
+        null
+    }
+
+    override fun visit(visit: AnalyticsVisit) = write { db ->
+        db.execSQL(
+            "INSERT INTO visits(day, hour, aweme, author, source, visible_ms, ended_at, end_reason) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
+            arrayOf<Any>(visit.day, visit.hour, visit.aweme, visit.author, visit.source, visit.visibleMs, visit.endedAt, visit.endReason),
+        )
+        db.execSQL("INSERT OR IGNORE INTO daily(day, views, visible_ms) VALUES(?, 0, 0)", arrayOf<Any>(visit.day))
+        db.execSQL("UPDATE daily SET views = views + 1, visible_ms = visible_ms + ? WHERE day = ?", arrayOf<Any>(visit.visibleMs, visit.day))
+        db.execSQL("INSERT OR IGNORE INTO hours(day, hour, views) VALUES(?, ?, 0)", arrayOf<Any>(visit.day, visit.hour))
+        db.execSQL("UPDATE hours SET views = views + 1 WHERE day = ? AND hour = ?", arrayOf<Any>(visit.day, visit.hour))
+    }
+
+    override fun rank(day: Long, kind: String, key: String) = write { db ->
+        db.execSQL("INSERT OR IGNORE INTO ranks(day, kind, item, n) VALUES(?, ?, ?, 0)", arrayOf<Any>(day, kind, key))
+        db.execSQL("UPDATE ranks SET n = n + 1 WHERE day = ? AND kind = ? AND item = ?", arrayOf<Any>(day, kind, key))
+    }
+
+    override fun collection(started: Boolean) = write { db ->
+        db.execSQL("INSERT INTO collection(ts, started) VALUES(?, ?)", arrayOf<Any>(clock(), if (started) 1 else 0))
+    }
+
+    override fun close() {
+        try {
+            database?.close()
+        } catch (_: RuntimeException) {
+        }
+        database = null
+    }
+
+    private inline fun write(block: (SQLiteDatabase) -> Unit) {
+        val db = database ?: return
+        try {
+            db.beginTransaction()
+            try {
+                block(db)
+                db.setTransactionSuccessful()
+            } finally {
+                db.endTransaction()
+            }
+        } catch (_: RuntimeException) {
+        }
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/AutoStreak.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/AutoStreak.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/AutoStreak.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/AutoStreak.kt
@@ -5,6 +5,7 @@
 import io.github.nuke.oss.app.shared.dex.UsesDexQueries
 import io.github.nuke.oss.app.shared.dex.methods
 import io.github.nuke.oss.app.shared.registry.HookEntry
+import io.github.nuke.oss.app.shared.reflect.NamedCall
 import io.github.nuke.oss.app.shared.settings.ActivityRecord
 import io.github.nuke.oss.app.shared.settings.ActivityStatus
 import io.github.nuke.oss.app.shared.settings.ChoiceOption
@@ -15,7 +16,9 @@
 import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
 import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
 import io.github.nuke.oss.app.tiktok.feature.comment.CustomCommentStyle
+import io.github.nuke.oss.app.tiktok.query.ContactQueries
 import io.github.nuke.oss.app.tiktok.query.StreakQueries
+import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
 import io.github.nuke.oss.app.tiktok.sdk.chat.ChatSender
 import io.github.nuke.oss.app.tiktok.sdk.chat.SendResult
 import io.github.nuke.oss.core.Logger
@@ -23,6 +26,7 @@
 import io.github.nuke.oss.runtime.RuntimeFeature
 import java.io.File
 import java.security.MessageDigest
+import java.time.Instant
 import java.time.ZoneId
 
 // Keeps chat streaks going: once a day, from the chosen time, sends one message to each chosen friend through the
@@ -30,11 +34,14 @@
 // before every send, no retry of an unknown result) live in StreakEngine; this class only connects it to the host.
 // A worker thread sleeps until the next send time, in steps of at most a minute so a changed clock is noticed, and
 // stops with the feature. A host process the system has frozen sends nothing until it runs again, and then only
-// catches up the current day. Logs name the feature and the outcome, never a friend, an account or message text.
+// catches up the current day. A friend the user already wrote to today is skipped, since that message keeps the streak
+// just as well and a second one from here would only be a duplicate.
+// Logs name the feature and the outcome, never a friend, an account or message text.
 @HookEntry
 internal class AutoStreak(
     private val resolver: DexResolver,
     private val hostClassLoader: ClassLoader,
+    private val events: ChatAdapterEvents = ChatAdapterEvents(hostClassLoader),
     private val options: OptionValues,
     private val logger: Logger,
     private val journalDirectory: File,
@@ -46,9 +53,21 @@
     override val id = ID
     // The sender's queries, and the interactive emoji's only in that mode.
     override val dexQueries: List<DexQuery> = SENDER_QUERIES +
+        listOf(ContactQueries.SINGLE_CONVERSATION_ID) +
         (if (options.choice(MODE).equals("interactive", ignoreCase = true)) EMOJI_QUERIES else emptyList())
     @Volatile private var active = false
 
+    // A message row the host draws as the user's own, with its conversation and send time.
+    private val self = NamedCall("isSelf")
+    private val created = NamedCall("getCreatedAt")
+    private val conversation = NamedCall("getConversationId")
+
+    // One store per account, since the host can switch accounts without restarting the module.
+    private val speech = HashMap<String, SelfSpeech>()
+
+    // "uid:day" pairs already reported, so a friend skipped today is named in the log once and not every wake-up.
+    private val skipLogged = HashSet<String>()
+
     override fun install(scope: FeatureScope) {
         val mode = StreakMode.valueOf(options.choice(MODE).uppercase())
         val plan = StreakPlan(
@@ -68,8 +87,14 @@
             val (entry, configuration, convert, resources, owner, emojiContent) = resolver.methods(hostClassLoader, EMOJI_QUERIES)
             StreakEmoji(entry, configuration, convert, resources, owner, emojiContent)
         } else null
+        // Which conversation belongs to each chosen friend, so a message the user wrote in it can be recognised.
+        val singleConversation = resolver.methods(hostClassLoader, listOf(ContactQueries.SINGLE_CONVERSATION_ID)).single()
+        val conversations = plan.friends.associateWith { uid ->
+            runCatching { singleConversation.invoke(null, uid) as? String }.getOrNull()?.takeIf(String::isNotBlank)
+        }
+        observeSpeech(scope, plan)
         active = true
-        val thread = Thread { run(plan, sender, emoji) }.apply { isDaemon = true; name = "nuke-streak" }
+        val thread = Thread { run(plan, sender, emoji, conversations) }.apply { isDaemon = true; name = "nuke-streak" }
         scope.own(AutoCloseable {
             active = false
             thread.interrupt()
@@ -77,7 +102,40 @@
         thread.start()
     }
 
-    private fun run(plan: StreakPlan, sender: ChatSender, emoji: StreakEmoji?) {
+    // Every chat message row the host draws is checked for one thing: the user's own message, today, in a one-to-one
+    // chat. That is a local read of what the host already rendered, hooked only while this feature is installed.
+    private fun observeSpeech(scope: FeatureScope, plan: StreakPlan) {
+        val zoneId = ZoneId.of(plan.zoneId)
+        events.install(scope, ID, onCreated = { _, _ -> }, onBound = bound@{ _, message ->
+            // A row bind must never throw into the host: a host model that changes shape only costs this check.
+            try {
+                if (self.of(message)?.invoke(message) != true) return@bound
+                val millis = created.of(message)?.invoke(message) as? Long ?: return@bound
+                val conversationId = conversation.of(message)?.invoke(message) as? String ?: return@bound
+                val account = accountId()?.let(StreakPlan::canonical) ?: return@bound
+                val day = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate().toEpochDay()
+                speechFor(account).mark(conversationId, day)
+            } catch (_: ReflectiveOperationException) {
+            } catch (_: RuntimeException) {
+            }
+        })
+    }
+
+    private fun speechFor(account: String): SelfSpeech = synchronized(speech) {
+        speech.getOrPut(account) { SelfSpeech(speechFile(journalDirectory, account)) }
+    }
+
+    // Whether the user already wrote in that friend's chat today. Reported once per friend and day, without the uid.
+    private fun skipSpokeToday(account: String, uid: String, day: Long, conversations: Map<String, String?>): Boolean {
+        val conversationId = conversations[uid] ?: return false
+        if (!speechFor(account).spoke(conversationId, day)) return false
+        synchronized(skipLogged) {
+            if (skipLogged.add(uid + ':' + day)) logger.log("feature=$ID skip_spoke_today")
+        }
+        return true
+    }
+
+    private fun run(plan: StreakPlan, sender: ChatSender, emoji: StreakEmoji?, conversations: Map<String, String?>) {
         val engines = HashMap<String, StreakEngine>()
         try {
             while (active) {
@@ -101,8 +159,10 @@
                             sentOne = true
                             deliver(sender, emoji, uid, message, canContinue)
                         },
+                        skip = { uid, day -> skipSpokeToday(account, uid, day, conversations) },
                     )
-                    sleeper((engine.nextWakeAt(plan, clock()) - clock()).coerceIn(MIN_SLEEP_MILLIS, MAX_SLEEP_MILLIS))
+                    sleeper((engine.nextWakeAt(plan, clock()) { uid, day -> skipSpokeToday(account, uid, day, conversations) } - clock())
+                        .coerceIn(MIN_SLEEP_MILLIS, MAX_SLEEP_MILLIS))
                 } catch (error: InterruptedException) {
                     throw error
                 } catch (error: RuntimeException) {
@@ -159,7 +219,7 @@
         private const val SEND_TIMEOUT_MILLIS = 30_000L
 
         override fun create(services: TikTokFeatureServices) = with(services) {
-            listOf(AutoStreak(resolver, hostClassLoader, options(ID, OPTIONS), logger, streakDirectory, CustomCommentStyle.accountUserId(hostClassLoader, logger)))
+            listOf(AutoStreak(resolver, hostClassLoader, chatRows, options(ID, OPTIONS), logger, streakDirectory, CustomCommentStyle.accountUserId(hostClassLoader, logger)))
         }
 
         // The sends of the signed-in account, newest first, for the settings screen; empty without an account or before
@@ -186,10 +246,14 @@
         }
 
         // The account only names a directory as a hash, so the stored path carries no user ID.
-        private fun journalFile(directory: File, account: String): File {
+        private fun accountDirectory(directory: File, account: String): File {
             val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
-            return File(directory, "$hash/state.dat")
+            return File(directory, hash)
         }
+
+        private fun journalFile(directory: File, account: String): File = File(accountDirectory(directory, account), "state.dat")
+
+        private fun speechFile(directory: File, account: String): File = File(accountDirectory(directory, account), "speech.dat")
     }
 }
 
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/SelfSpeech.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/SelfSpeech.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/SelfSpeech.kt
@@ -0,0 +1,72 @@
+package io.github.nuke.oss.app.tiktok.feature.chat.streak
+
+import java.io.File
+import java.io.FileOutputStream
+import java.nio.file.Files
+import java.nio.file.StandardCopyOption
+
+// Whether the signed-in account already spoke in a one-to-one chat on a local day, so a scheduled streak message is not
+// sent on top of a message the user wrote by hand. Written from the chat list's row events (a row the host draws as the
+// user's own message), read by the streak worker just before it claims a friend. Both live in the host process, and
+// nothing here reaches the network: the file only remembers what the host already showed.
+internal class SelfSpeech(private val file: File) {
+    // day -> conversation IDs the user spoke in that day; the newest days are kept, older ones are dropped.
+    fun mark(conversationId: String, day: Long) {
+        if (conversationId.isEmpty()) return
+        // Called from a chat row hook, so nothing may reach the host: a failed write only costs the duplicate this
+        // store exists to avoid.
+        try {
+            synchronized(this) {
+                val days = read()
+                if (days[day]?.contains(conversationId) == true) return
+                write(days + (day to (days[day] ?: emptySet()) + conversationId))
+            }
+        } catch (_: java.io.IOException) {
+        } catch (_: RuntimeException) {
+        }
+    }
+
+    fun spoke(conversationId: String?, day: Long): Boolean {
+        if (conversationId.isNullOrEmpty()) return false
+        return synchronized(this) { read()[day]?.contains(conversationId) == true }
+    }
+
+    private fun read(): Map<Long, Set<String>> {
+        if (!file.exists()) return emptyMap()
+        val lines = try {
+            file.readLines()
+        } catch (_: java.io.IOException) {
+            return emptyMap()
+        }
+        val days = LinkedHashMap<Long, MutableSet<String>>()
+        lines.forEach { line ->
+            val space = line.indexOf(' ')
+            if (space <= 0) return@forEach
+            val day = line.substring(0, space).toLongOrNull() ?: return@forEach
+            val conversation = line.substring(space + 1).takeIf(String::isNotEmpty) ?: return@forEach
+            days.getOrPut(day) { LinkedHashSet() }.add(conversation)
+        }
+        return days
+    }
+
+    private fun write(days: Map<Long, Set<String>>) {
+        val directory = checkNotNull(file.parentFile)
+        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the streak speech directory" }
+        val encoded = days.entries
+            .sortedByDescending { it.key }
+            .take(MAX_DAYS)
+            .flatMap { (day, conversations) -> conversations.sorted().map { "$day $it" } }
+            .joinToString("\n", postfix = "\n")
+        val temporary = File(directory, file.name + ".new")
+        FileOutputStream(temporary).use { output ->
+            output.write(encoded.toByteArray(Charsets.UTF_8))
+            output.fd.sync()
+        }
+        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
+    }
+
+    private companion object {
+        // A plan only ever asks about today, so a handful of days is enough to survive a clock change.
+        const val MAX_DAYS = 7
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/StreakEngine.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/StreakEngine.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/StreakEngine.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/StreakEngine.kt
@@ -62,10 +62,12 @@
         if (state.revision != plan.revision) persist(state.copy(revision = plan.revision, savedAt = now, daily = null))
     }
 
-    @Synchronized fun nextWakeAt(plan: StreakPlan, now: Long): Long {
+    // skip answers whether the user already spoke in that friend's chat on that day; such a friend is not pending, or
+    // the worker would keep waking up every second for a friend it will never send to.
+    @Synchronized fun nextWakeAt(plan: StreakPlan, now: Long, skip: (uid: String, day: Long) -> Boolean = { _, _ -> false }): Long {
         val today = plan.todayAt(now)
         val day = plan.dayOf(now)
-        val pending = plan.friends.any { (state.lastAttemptDays[it] ?: Long.MIN_VALUE) < day }
+        val pending = plan.friends.any { (state.lastAttemptDays[it] ?: Long.MIN_VALUE) < day && !skip(it, day) }
         if (today >= state.savedAt && pending) return maxOf(now + 1_000, today)
         return plan.tomorrowAt(now)
     }
@@ -78,12 +80,17 @@
         allowed: () -> Boolean,
         clock: () -> Long,
         prepare: (StreakPlan) -> PreparedMessage,
+        // skip answers whether the user already spoke in that friend's chat on that day; such a friend is not claimed
+        // and the streak is kept by the user's own message.
+        skip: (uid: String, day: Long) -> Boolean = { _, _ -> false },
         send: (uid: String, text: String, canContinue: () -> Boolean) -> StreakOutcome,
     ) {
         if (state.revision != plan.revision) return
         val at = plan.todayAt(now)
         val day = plan.dayOf(now)
-        if (now < at || at < state.savedAt || !allowed() || plan.friends.none { (state.lastAttemptDays[it] ?: Long.MIN_VALUE) < day }) return
+        if (now < at || at < state.savedAt || !allowed() ||
+            plan.friends.none { (state.lastAttemptDays[it] ?: Long.MIN_VALUE) < day && !skip(it, day) }
+        ) return
         val message = state.daily?.takeIf { it.day == day && it.revision == plan.revision && plan.mode != StreakMode.INTERACTIVE }
             ?: run {
                 val prepared = prepare(plan)
@@ -101,6 +108,9 @@
             if (!allowed()) return
             val observed = clock()
             if (plan.dayOf(observed) != day || observed < at) return
+            // No claim is written for a friend the user already wrote to today: the streak is kept either way, and a
+            // second message from this module would only be a duplicate.
+            if (skip(uid, day)) continue
             val attempt = synchronized(this) {
                 if (state.revision != plan.revision || (state.lastAttemptDays[uid] ?: Long.MIN_VALUE) >= day) null
                 else StreakAttempt(UUID.randomUUID().toString(), uid, day, observed, mode = plan.mode,
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadOriginals.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadOriginals.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadOriginals.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadOriginals.kt
@@ -7,6 +7,8 @@
 import io.github.nuke.oss.app.shared.reflect.NamedCall
 import io.github.nuke.oss.app.shared.reflect.NamedField
 import io.github.nuke.oss.app.shared.registry.HookEntry
+import io.github.nuke.oss.app.shared.settings.ChoiceOption
+import io.github.nuke.oss.app.shared.settings.OptionValues
 import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
 import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
 import io.github.nuke.oss.app.tiktok.query.DownloadQueries
@@ -19,26 +21,57 @@
 import io.github.nuke.oss.runtime.RuntimeFeature
 import java.io.File
 
-// Downloads a video from its playable address instead of the download address, which carries the watermark.
+// Downloads a video from the address chosen in its options instead of the download address, which carries the
+// watermark. "auto" keeps the playable address NukeApp always used; the other choices take a specific rendition, and a
+// rendition the video does not carry falls back to the playable address rather than to the watermarked download.
 @HookEntry
-internal class DownloadOriginalVideo(private val resolver: DexResolver, private val hostClassLoader: ClassLoader) : RuntimeFeature, UsesDexQueries {
-    override val id = "DownloadOriginalVideo"
-    override val dexQueries = listOf(DownloadQueries.VIDEO_ADDRESS, DownloadQueries.VIDEO_PLAY)
+internal class DownloadOriginalVideo(
+    private val resolver: DexResolver,
+    private val hostClassLoader: ClassLoader,
+    options: OptionValues,
+) : RuntimeFeature, UsesDexQueries {
+    override val id = ID
+    private val source = options.choice(SOURCE)
     private val getVideo = NamedCall("getVideo")
+    // The rendition each explicit source reads; "auto" and "download" resolve nothing beyond the playable address.
+    private val RENDITION_QUERIES = mapOf(
+        SOURCE_PLAY to DownloadQueries.VIDEO_PLAY_ADDR,
+        SOURCE_H264 to DownloadQueries.VIDEO_PLAY_H264,
+        SOURCE_H265 to DownloadQueries.VIDEO_PLAY_H265,
+    )
+    private val addressQuery = RENDITION_QUERIES[source]
+    override val dexQueries = listOf(DownloadQueries.VIDEO_ADDRESS, DownloadQueries.VIDEO_PLAY) +
+        listOfNotNull(addressQuery)
 
     override fun install(scope: FeatureScope) {
+        // "download" is the host's own address: the hook stays out of the way entirely.
+        if (source == SOURCE_DOWNLOAD) return
         val (address, play) = resolver.methods(hostClassLoader, DownloadQueries.VIDEO_ADDRESS, DownloadQueries.VIDEO_PLAY)
         address.isAccessible = true
         play.isAccessible = true
+        val rendition = addressQuery?.let { resolver.fields(hostClassLoader, it).single().apply { isAccessible = true } }
         scope.hooks.install(address, HookSpec(id = "PlayAddress", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
             val aweme = call.argument(0) ?: return@HookSpec
             val video = getVideo.of(aweme)?.invoke(aweme) ?: return@HookSpec
-            play.invoke(video)?.let(call::returnEarly)
+            val chosen = rendition?.get(video)
+            (chosen ?: play.invoke(video))?.let(call::returnEarly)
         }))
     }
 
     companion object : TikTokFeature {
-        override fun create(services: TikTokFeatureServices) = with(services) { listOf(DownloadOriginalVideo(resolver, hostClassLoader)) }
+        const val ID = "DownloadOriginalVideo"
+        const val SOURCE = "source"
+        const val SOURCE_AUTO = "auto"
+        const val SOURCE_PLAY = "play"
+        const val SOURCE_H264 = "h264"
+        const val SOURCE_H265 = "h265"
+        const val SOURCE_DOWNLOAD = "download"
+        val SOURCES = listOf(SOURCE_AUTO, SOURCE_PLAY, SOURCE_H264, SOURCE_H265, SOURCE_DOWNLOAD)
+        val OPTIONS = listOf(ChoiceOption(SOURCE, SOURCES))
+
+        override fun create(services: TikTokFeatureServices) = with(services) {
+            listOf(DownloadOriginalVideo(resolver, hostClassLoader, options(ID, OPTIONS)))
+        }
     }
 }
 
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadVideoWithName.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadVideoWithName.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadVideoWithName.kt
@@ -0,0 +1,186 @@
+package io.github.nuke.oss.app.tiktok.feature.feed
+
+import io.github.nuke.oss.app.shared.dex.DexResolver
+import io.github.nuke.oss.app.shared.media.DownloadCancelled
+import io.github.nuke.oss.app.shared.media.DownloadConsole
+import io.github.nuke.oss.app.shared.media.DownloadConsoles
+import io.github.nuke.oss.app.shared.media.DownloadQueue
+import io.github.nuke.oss.app.shared.media.DownloadTask
+import io.github.nuke.oss.app.shared.media.TaskStage
+import io.github.nuke.oss.app.shared.dex.UsesDexQueries
+import io.github.nuke.oss.app.shared.dex.methods
+import io.github.nuke.oss.app.shared.media.MediaNaming
+import io.github.nuke.oss.app.shared.reflect.NamedCall
+import io.github.nuke.oss.app.shared.reflect.NamedField
+import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
+import io.github.nuke.oss.app.shared.reflect.callByName
+import io.github.nuke.oss.app.shared.registry.HookEntry
+import io.github.nuke.oss.app.shared.settings.OptionValues
+import io.github.nuke.oss.app.shared.settings.TextOption
+import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
+import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
+import io.github.nuke.oss.app.tiktok.query.DownloadQueries
+import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
+import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
+import io.github.nuke.oss.app.tiktok.sdk.media.mediaUrls
+import io.github.nuke.oss.core.Logger
+import io.github.nuke.oss.hook.api.HookCapability
+import io.github.nuke.oss.hook.api.HookSpec
+import io.github.nuke.oss.runtime.FeatureScope
+import io.github.nuke.oss.runtime.RuntimeFeature
+
+// Saves the post's video with the module's own downloader instead of the host's: the long-press "save" item is taken
+// over, the video is fetched from its playable address, and the file lands in Movies/douyin/<folder> under a name built
+// from the template. The host's dialog, its address choice and its own privacy/download checks are skipped, so the
+// feature is off by default; a post whose video or address does not resolve is left entirely to the host. The address
+// is the playable one, the same source the download-original feature uses, never the watermarked download address.
+@HookEntry
+internal class DownloadVideoWithName(
+    private val resolver: DexResolver,
+    private val hostClassLoader: ClassLoader,
+    private val files: () -> MediaFiles,
+    private val logger: Logger,
+    options: OptionValues,
+    private val queue: DownloadQueue = DownloadQueue(),
+) : RuntimeFeature, UsesDexQueries, DownloadConsole {
+    override val id = ID
+    override val dexQueries = listOf(
+        DownloadQueries.PANEL_DOWNLOAD_CLICK, DownloadQueries.PANEL_AWEME, DownloadQueries.VIDEO_PLAY,
+    )
+    private val template = options.text(TEMPLATE).ifBlank { MediaNaming.DEFAULT_TEMPLATE }
+    private val folder = MediaNaming.folder(options.text(FOLDER))
+    // A SAF folder the user picked; empty means the gallery folders below.
+    private val tree = options.text(TREE).takeIf { it.startsWith("content://") }
+    private val getVideo = NamedCall("getVideo")
+    private val author = NamedField("author")
+    private val nickname = NamedField("nickname")
+    private val desc = NamedField("desc")
+    private val awemeId = NamedFieldAlias("aid", "aweme_id")
+    private val urlList = NamedFieldAlias("urlList", "url_list")
+    private val images = NamedField("images")
+
+    override fun install(scope: FeatureScope) {
+        val (click, post, play) = resolver.methods(
+            hostClassLoader, DownloadQueries.PANEL_DOWNLOAD_CLICK, DownloadQueries.PANEL_AWEME, DownloadQueries.VIDEO_PLAY,
+        )
+        listOf(click, post, play).forEach { it.isAccessible = true }
+        DownloadConsoles.register(this)
+        scope.own(AutoCloseable {
+            DownloadConsoles.clear(this)
+            queue.close()
+        })
+        scope.hooks.install(click, HookSpec(id = "TakeOver", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
+            // Everything is read before the host call is cancelled: a post this feature cannot save keeps the host's
+            // own behavior instead of loading nothing at all.
+            val view = call.argument(0) ?: return@HookSpec
+            val context = runCatching { view.callByName("getContext") }.getOrNull() ?: return@HookSpec
+            val module = call.receiver ?: return@HookSpec
+            val aweme = runCatching { post.invoke(module) }.getOrNull() ?: return@HookSpec
+            val video = runCatching { getVideo.of(aweme)?.invoke(aweme) }.getOrNull()
+            val model = video?.let { runCatching { play.invoke(it) }.getOrNull() }
+            val addresses = model?.let { runCatching { urlList.of(it)?.get(it) }.getOrNull() } as? List<*>
+            val url = addresses?.let { mediaUrls(it.filterIsInstance<String>()).firstOrNull() }
+            if (url == null) {
+                // An image post has no video; its pictures are taken from the same panel entry when the host has them.
+                val pictures = pictures(aweme)
+                if (pictures.isEmpty()) return@HookSpec
+                call.returnEarly(null)
+                for ((index, address) in pictures.withIndex()) {
+                    val name = name(aweme, index + 1, "image")
+                    queue.submit(name, TaskStage.DOWNLOAD) { progress ->
+                        save(context, address, name, ImageFolder.IMAGE, progress::update, progress::isCancelled)
+                    }
+                }
+                return@HookSpec
+            }
+            val name = name(aweme, 1, "video")
+            call.returnEarly(null)
+            queue.submit(name, TaskStage.DOWNLOAD) { progress ->
+                save(context, url, name, ImageFolder.VIDEO, progress::update, progress::isCancelled)
+            }
+        }))
+    }
+
+    // The first picture of an image post, each of the rest, or the single video: the index is what keeps a set apart.
+    private fun name(aweme: Any, index: Int, kind: String): String {
+        val user = runCatching { author.of(aweme)?.get(aweme) }.getOrNull()
+        return MediaNaming.fileName(
+            template,
+            mapOf(
+                "author" to text(user) { nickname.of(it)?.get(it) },
+                "desc" to text(aweme) { desc.of(it)?.get(it) },
+                "aid" to text(aweme) { awemeId.of(it)?.get(it) },
+                "type" to kind,
+            ),
+            extension = if (kind == "video") "mp4" else "png",
+            index = if (kind == "video") 0 else index,
+        )
+    }
+
+    // Every plain picture address the post carries, in order; a post without any keeps the host's own handling.
+    private fun pictures(aweme: Any): List<String> {
+        val list = runCatching { images.of(aweme)?.get(aweme) as? List<*> }.getOrNull() ?: return emptyList()
+        val found = ArrayList<String>(list.size)
+        for (image in list) {
+            image ?: continue
+            val urls = runCatching { urlList.of(image)?.get(image) }.getOrNull() as? List<*> ?: continue
+            mediaUrls(urls.filterIsInstance<String>()).firstOrNull()?.let(found::add)
+        }
+        return found
+    }
+
+    private fun text(value: Any?, read: (Any) -> Any?): String {
+        if (value == null) return ""
+        return runCatching { read(value) as? CharSequence }.getOrNull()?.toString()?.trim().orEmpty()
+    }
+
+    private fun save(
+        context: Any,
+        address: String,
+        name: String,
+        folder: ImageFolder,
+        progress: (bytes: Long, total: Long) -> Unit,
+        cancelled: () -> Boolean,
+    ) {
+        try {
+            files().save(
+                context, listOf(address), audio = false, folder = folder, name = name,
+                progress = progress, cancelled = cancelled, tree = tree,
+            )
+            // The name comes from the post, so only its presence is logged.
+            logger.log("feature=$id saved")
+        } catch (cancelled: DownloadCancelled) {
+            logger.log("feature=$id cancelled")
+            throw cancelled
+        } catch (error: Exception) {
+            logger.log("feature=$id save_failed ${error.javaClass.simpleName}: ${error.message}")
+            throw error
+        }
+    }
+
+    // The settings screen reads the same queue; the copy it sees never changes once taken.
+    override fun snapshot(): List<DownloadTask> = queue.snapshot()
+
+    override fun recent(): List<DownloadTask> = queue.recent()
+
+    override fun cancel(id: Long): Boolean = queue.cancel(id)
+
+    override fun clearFinished() = queue.clearFinished()
+
+    companion object : TikTokFeature {
+        const val ID = "DownloadVideoWithName"
+        const val TEMPLATE = "template"
+        const val FOLDER = "folder"
+        const val TREE = "tree"
+        val OPTIONS = listOf(
+            TextOption(TEMPLATE, default = MediaNaming.DEFAULT_TEMPLATE, maxLength = 120),
+            TextOption(FOLDER, default = MediaNaming.DEFAULT_FOLDER, maxLength = 40),
+            // Only written by the folder picker in the settings screen, never typed.
+            TextOption(TREE, maxLength = 500),
+        )
+
+        override fun create(services: TikTokFeatureServices) = with(services) {
+            listOf(DownloadVideoWithName(resolver, hostClassLoader, mediaFiles, logger, options(ID, OPTIONS)))
+        }
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/general/SaveVideoAudio.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/general/SaveVideoAudio.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/general/SaveVideoAudio.kt
@@ -0,0 +1,85 @@
+package io.github.nuke.oss.app.tiktok.feature.general
+
+import io.github.nuke.oss.app.shared.media.AudioExport
+import io.github.nuke.oss.app.shared.media.MediaSaved
+import io.github.nuke.oss.app.shared.media.VideoAudio
+import io.github.nuke.oss.app.shared.registry.HookEntry
+import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
+import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
+import io.github.nuke.oss.app.tiktok.sdk.media.MediaFileType
+import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
+import io.github.nuke.oss.core.Logger
+import io.github.nuke.oss.runtime.FeatureScope
+import io.github.nuke.oss.runtime.RuntimeFeature
+import java.io.File
+import java.io.IOException
+import java.util.concurrent.Executor
+import java.util.concurrent.Executors
+
+// Keeps the sound of a video the module saved as an MP3 next to it: the audio track is decoded with MediaCodec, encoded
+// with the pure-Java LAME port, and written to Music/douyin/audio. Only files this module saved are handled; the host's
+// own downloads never pass through here. The conversion runs on its own thread, so a save is never delayed by it.
+@HookEntry
+internal class SaveVideoAudio(
+    private val files: () -> MediaFiles,
+    private val logger: Logger,
+    private val directory: File,
+) : RuntimeFeature {
+    override val id = ID
+
+    override fun install(scope: FeatureScope) {
+        directory.mkdirs()
+        val worker = Executors.newSingleThreadExecutor { task ->
+            Thread(task, "nuke-audio").apply { isDaemon = true }
+        }
+        val listener = MediaSaved.Listener { context, file, type -> enqueue(context, file, type, worker) }
+        MediaSaved.add(listener)
+        scope.own(AutoCloseable {
+            MediaSaved.remove(listener)
+            worker.shutdownNow()
+        })
+    }
+
+    // Runs on the thread that saved the video, which deletes it as soon as it returns: copy first, convert afterwards.
+    private fun enqueue(context: Any, file: File, type: MediaFileType, worker: Executor) {
+        if (!type.mime.startsWith("video/")) return
+        val size = file.length()
+        if (size <= 0) {
+            logger.log("feature=$id skipped size=$size")
+            return
+        }
+        val stamp = System.currentTimeMillis()
+        val source = File(directory, "audio-source-$stamp.${type.extension}")
+        try {
+            file.copyTo(source, overwrite = true)
+        } catch (failure: IOException) {
+            logger.log("feature=$id copy_failed ${failure.javaClass.simpleName}")
+            return
+        }
+        worker.execute {
+            val result = VideoAudio.toMp3(source, File(directory, "audio-$stamp.mp3"))
+            source.delete()
+            when (result) {
+                is AudioExport.Converted -> {
+                    try {
+                        files().saveFile(context, result.file, audio = true, name = "audio-$stamp")
+                        logger.log("feature=$id saved duration=${result.durationMillis}")
+                    } catch (failure: Exception) {
+                        logger.log("feature=$id save_failed ${failure.javaClass.simpleName}: ${failure.message}")
+                    } finally {
+                        result.file.delete()
+                    }
+                }
+                is AudioExport.Failed -> logger.log("feature=$id convert_failed ${result.reason}")
+            }
+        }
+    }
+
+    companion object : TikTokFeature {
+        const val ID = "SaveVideoAudio"
+
+        override fun create(services: TikTokFeatureServices) = with(services) {
+            listOf(SaveVideoAudio(mediaFiles, logger, File(featureFiles, ID)))
+        }
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/general/WebDavUpload.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/general/WebDavUpload.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/general/WebDavUpload.kt
@@ -0,0 +1,95 @@
+package io.github.nuke.oss.app.tiktok.feature.general
+
+import io.github.nuke.oss.app.shared.media.MediaSaved
+import io.github.nuke.oss.app.shared.net.WebDavClient
+import io.github.nuke.oss.app.shared.net.WebDavResult
+import io.github.nuke.oss.app.shared.registry.HookEntry
+import io.github.nuke.oss.app.shared.settings.OptionValues
+import io.github.nuke.oss.app.shared.settings.TextOption
+import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
+import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
+import io.github.nuke.oss.app.tiktok.sdk.media.MediaFileType
+import io.github.nuke.oss.core.Logger
+import io.github.nuke.oss.runtime.FeatureScope
+import io.github.nuke.oss.runtime.RuntimeFeature
+import java.io.File
+import java.io.IOException
+import java.util.concurrent.Executor
+import java.util.concurrent.Executors
+
+// Uploads files the module itself saves (comment images, voice messages and the like) to a WebDAV collection. The host's
+// own downloads are not included: they never pass through the module, and there is no completion callback to hook. The
+// bytes are copied while the save still has them and uploaded later, so a slow server never delays the save.
+@HookEntry
+internal class WebDavUpload(
+    private val options: OptionValues,
+    private val directory: File,
+    private val logger: Logger,
+) : RuntimeFeature {
+    override val id = ID
+
+    override fun install(scope: FeatureScope) {
+        val address = options.text(ADDRESS)
+        if (!address.startsWith("http://") && !address.startsWith("https://")) {
+            logger.log("feature=$id skipped address_not_http")
+            return
+        }
+        val client = WebDavClient(address, options.text(USER), options.text(PASSWORD), options.text(EXTRA_DIRECTORY))
+        val worker = Executors.newSingleThreadExecutor { task ->
+            Thread(task, "nuke-webdav").apply { isDaemon = true }
+        }
+        val listener = MediaSaved.Listener { _, file, type -> enqueue(file, type, worker, client) }
+        MediaSaved.add(listener)
+        scope.own(AutoCloseable {
+            MediaSaved.remove(listener)
+            worker.shutdownNow()
+        })
+    }
+
+    // Runs on the thread that saved the file, which deletes it as soon as it returns: copy first, upload afterwards.
+    private fun enqueue(file: File, type: MediaFileType, worker: Executor, client: WebDavClient) {
+        val size = file.length()
+        if (size <= 0 || size > MAX_BYTES) {
+            logger.log("feature=$id skipped size=$size")
+            return
+        }
+        directory.mkdirs()
+        val copy = File(directory, "upload-${System.currentTimeMillis()}.${type.extension}")
+        try {
+            file.copyTo(copy, overwrite = true)
+        } catch (failure: IOException) {
+            logger.log("feature=$id copy_failed ${failure.javaClass.simpleName}")
+            return
+        }
+        worker.execute {
+            val result = client.upload(copy, copy.name)
+            val outcome = when (result) {
+                is WebDavResult.Uploaded -> "uploaded status=${result.status} name=${copy.name}"
+                is WebDavResult.Failed -> "upload_failed status=${result.status} reason=${result.reason}"
+            }
+            logger.log("feature=$id $outcome")
+            copy.delete()
+        }
+    }
+
+    companion object : TikTokFeature {
+        const val ID = "WebDavUpload"
+        const val ADDRESS = "address"
+        const val USER = "user"
+        const val PASSWORD = "password"
+        const val EXTRA_DIRECTORY = "directory"
+        private const val MAX_BYTES = 32L * 1024 * 1024
+
+        val OPTIONS = listOf(
+            TextOption(ADDRESS, maxLength = 300),
+            TextOption(USER, maxLength = 100),
+            // Stored in the host's settings like every other option; the log never prints it.
+            TextOption(PASSWORD, maxLength = 200),
+            TextOption(EXTRA_DIRECTORY, maxLength = 200),
+        )
+
+        override fun create(services: TikTokFeatureServices) = with(services) {
+            listOf(WebDavUpload(options(ID, OPTIONS), File(featureFiles, ID), logger))
+        }
+    }
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/injector/AndroidMediaFiles.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/injector/AndroidMediaFiles.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/injector/AndroidMediaFiles.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/injector/AndroidMediaFiles.kt
@@ -7,13 +7,18 @@
 import android.graphics.Bitmap
 import android.media.MediaExtractor
 import android.media.MediaMetadataRetriever
+import android.net.Uri
 import android.media.MediaScannerConnection
 import android.os.Build
 import android.os.Environment
+import android.provider.DocumentsContract
 import android.provider.MediaStore
 import com.highcapable.kavaref.KavaRef.Companion.resolve
 import com.highcapable.kavaref.extension.toClass
 import com.shakster.gifkt.GifEncoder
+import io.github.nuke.oss.app.shared.media.MediaNaming
+import io.github.nuke.oss.app.shared.media.DownloadCancelled
+import io.github.nuke.oss.app.shared.media.MediaSaved
 import io.github.nuke.oss.app.shared.reflect.callByName
 import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
 import io.github.nuke.oss.app.tiktok.sdk.media.MediaFileType
@@ -42,7 +47,16 @@
     private fun hasLegacyStorage(host: Context) =
         host.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
 
-    override fun save(context: Any, urls: List<String>, audio: Boolean, folder: ImageFolder) {
+    override fun save(
+        context: Any,
+        urls: List<String>,
+        audio: Boolean,
+        folder: ImageFolder,
+        name: String?,
+        progress: (bytes: Long, total: Long) -> Unit,
+        cancelled: () -> Boolean,
+        tree: String?,
+    ) {
         val host = (context as? Context)?.applicationContext ?: error("No host Context to save with")
         val source = File.createTempFile("nuke-media-", ".media", host.cacheDir)
         var converted: File? = null
@@ -51,7 +65,7 @@
             var lastError: Exception? = null
             for (url in urls.distinct()) {
                 try {
-                    download(url, source)
+                    download(url, source, progress, cancelled)
                     downloaded = true
                     break
                 } catch (error: java.io.IOException) {
@@ -75,11 +89,13 @@
                 convertVvic(source)
                 type = MediaFileType("image/png", "png")
             }
+            // The file is deleted in the finally block below, so whoever needs the bytes takes them here.
+            MediaSaved.publish(host, output, type)
             if (Build.VERSION.SDK_INT < 29) {
                 require(hasLegacyStorage(host)) { "Storage permission is required on Android 8–9" }
-                saveLegacy(host, output, type, audio, folder)
+                saveLegacy(host, output, type, audio, folder, name)
             } else {
-                saveScoped(host, output, type, audio, folder)
+                saveScoped(host, output, type, audio, folder, name)
             }
         } finally {
             source.delete()
@@ -87,6 +103,22 @@
         }
     }
 
+    override fun saveFile(context: Any, file: File, audio: Boolean, folder: ImageFolder, name: String?) {
+        val host = (context as? Context)?.applicationContext ?: error("No host Context to save with")
+        check(file.isFile) { "No file to save" }
+        val type = file.inputStream().use { MediaFileType.detect(header(it), audio) }
+        require(if (audio) type.mime.startsWith("audio/") else type.mime.startsWith("image/") || type.mime.startsWith("video/")) {
+            "Unexpected saved MIME ${type.mime}"
+        }
+        MediaSaved.publish(host, file, type)
+        if (Build.VERSION.SDK_INT < 29) {
+            require(hasLegacyStorage(host)) { "Storage permission is required on Android 8–9" }
+            saveLegacy(host, file, type, audio, folder, name)
+        } else {
+            saveScoped(host, file, type, audio, folder, name)
+        }
+    }
+
     override fun saveText(context: Any, name: String, text: String): String {
         val host = (context as? Context)?.applicationContext ?: error("No host Context to save with")
         val filename = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.take(100).ifEmpty { "details.txt" }
@@ -120,13 +152,19 @@
         return file.absolutePath
     }
 
-    private fun saveLegacy(host: Context, output: File, type: MediaFileType, audio: Boolean, folder: ImageFolder) {
+    private fun saveLegacy(host: Context, output: File, type: MediaFileType, audio: Boolean, folder: ImageFolder, name: String?) {
         val directory = File(
-            Environment.getExternalStoragePublicDirectory(if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_PICTURES),
+            Environment.getExternalStoragePublicDirectory(
+                when {
+                    audio -> Environment.DIRECTORY_MUSIC
+                    isVideo(type, audio) -> Environment.DIRECTORY_MOVIES
+                    else -> Environment.DIRECTORY_PICTURES
+                },
+            ),
             if (audio) "douyin/audio" else "douyin/${folder.directory}",
         )
         check(directory.isDirectory || directory.mkdirs()) { "Cannot create ${directory.name}" }
-        val destination = File(directory, "${UUID.randomUUID()}.${type.extension}")
+        val destination = freeFile(directory, fileName(name, folder.directory, type.extension))
         try {
             output.inputStream().use { input -> destination.outputStream().use { target -> input.copyTo(target) } }
             MediaScannerConnection.scanFile(host, arrayOf(destination.path), arrayOf(type.mime), null)
@@ -136,14 +174,40 @@
         }
     }
 
-    private fun saveScoped(host: Context, output: File, type: MediaFileType, audio: Boolean, folder: ImageFolder) {
-        val collection = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
+    // The user picked a SAF folder: the file goes there under the template name, whatever the gallery would have done.
+    private fun saveTree(host: Context, output: File, type: MediaFileType, tree: String, name: String?) {
+        val treeUri = Uri.parse(tree)
+        val resolver = host.contentResolver
+        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
+        val created = checkNotNull(DocumentsContract.createDocument(resolver, parent, type.mime, fileName(name, "saf", type.extension))) {
+            "Unable to create the document"
+        }
+        try {
+            checkNotNull(resolver.openOutputStream(created)) { "Unable to open the document" }.use { target ->
+                output.inputStream().use { input -> input.copyTo(target) }
+            }
+        } catch (error: Exception) {
+            runCatching { DocumentsContract.deleteDocument(resolver, created) }
+            throw error
+        }
+    }
+
+    private fun saveScoped(host: Context, output: File, type: MediaFileType, audio: Boolean, folder: ImageFolder, name: String?) {
+        val collection = when {
+            audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
+            isVideo(type, audio) -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
+            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
+        }
         val values = ContentValues().apply {
-            put(MediaStore.MediaColumns.DISPLAY_NAME, "${if (audio) "audio" else folder.directory}_${UUID.randomUUID()}.${type.extension}")
+            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(name, folder.directory, type.extension))
             put(MediaStore.MediaColumns.MIME_TYPE, type.mime)
             put(
                 MediaStore.MediaColumns.RELATIVE_PATH,
-                if (audio) "${Environment.DIRECTORY_MUSIC}/douyin/audio" else "${Environment.DIRECTORY_PICTURES}/douyin/${folder.directory}",
+                when {
+                    audio -> "${Environment.DIRECTORY_MUSIC}/douyin/audio"
+                    isVideo(type, audio) -> "${Environment.DIRECTORY_MOVIES}/douyin/${folder.directory}"
+                    else -> "${Environment.DIRECTORY_PICTURES}/douyin/${folder.directory}"
+                },
             )
             put(MediaStore.MediaColumns.IS_PENDING, 1)
         }
@@ -160,7 +224,36 @@
         }
     }
 
-    private fun download(url: String, target: File) {
+    private fun isVideo(type: MediaFileType, audio: Boolean) = !audio && type.mime.startsWith("video/")
+
+    // What the gallery shows: the caller's name, or the folder and a random tail. The extension is always the one of
+    // the file that was actually saved, since a sticker becomes a GIF and a VVIC image becomes a PNG.
+    private fun fileName(name: String?, prefix: String, extension: String): String {
+        val base = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MediaNaming.MAX_BYTES) ?: "${prefix}_${UUID.randomUUID()}"
+        return if (base.endsWith(".$extension", ignoreCase = true)) base else "$base.$extension"
+    }
+
+    // A legacy save writes straight into the public folder, so a name already there must not be overwritten.
+    private fun freeFile(directory: File, name: String): File {
+        val candidate = File(directory, name)
+        if (!candidate.exists()) return candidate
+        val dot = name.lastIndexOf('.')
+        val stem = if (dot > 0) name.substring(0, dot) else name
+        val extension = if (dot > 0) name.substring(dot) else ""
+        var index = 2
+        while (true) {
+            val next = File(directory, "${stem}_$index$extension")
+            if (!next.exists()) return next
+            index++
+        }
+    }
+
+    private fun download(
+        url: String,
+        target: File,
+        progress: (bytes: Long, total: Long) -> Unit,
+        cancelled: () -> Boolean,
+    ) {
         val parsed = URL(if (url.startsWith("//")) "https:$url" else url)
         require(parsed.protocol == "https" || parsed.protocol == "http") { "Unsupported media URL" }
         val connection = parsed.openConnection() as HttpURLConnection
@@ -169,16 +262,19 @@
             connection.readTimeout = TIMEOUT
             connection.setRequestProperty("User-Agent", "Mozilla/5.0")
             require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
+            val announced = connection.contentLengthLong.coerceAtLeast(0)
             connection.inputStream.use { input ->
                 target.outputStream().use { output ->
                     val buffer = ByteArray(BUFFER)
                     var total = 0L
                     while (true) {
+                        if (cancelled()) throw DownloadCancelled()
                         val count = input.read(buffer)
                         if (count < 0) break
                         total += count
                         require(total <= MAX_BYTES) { "The media exceeds 128 MiB" }
                         output.write(buffer, 0, count)
+                        progress(total, announced)
                     }
                     require(total > 0) { "Empty download" }
                 }
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/query/DownloadQueries.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/query/DownloadQueries.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/query/DownloadQueries.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/query/DownloadQueries.kt
@@ -54,6 +54,10 @@
     }
 
     val VIDEO_DOWNLOAD = videoField("download_addr")
+    // The three playable addresses a download can be taken from, read straight from their serialized fields.
+    val VIDEO_PLAY_ADDR = videoField("play_addr")
+    val VIDEO_PLAY_H264 = videoField("play_addr_h264")
+    val VIDEO_PLAY_H265 = videoField("play_addr_265")
     val VIDEO_WATERMARK = videoField("has_watermark")
     val VIDEO_SUFFIX_WATERMARK = videoField("has_download_suffix_logo_addr")
 
@@ -97,6 +101,24 @@
         }
     }
 
+    // The long-press menu's "save" item: its click takes the View that was tapped, and the post it acts on is the one
+    // the panel module holds. Both belong to classes the host keeps unobfuscated.
+    val PANEL_DOWNLOAD_CLICK = method {
+        matcher {
+            declaredClass = "com.ss.android.ugc.aweme.feed.long_press_panel.modules.business.homepage.LppDownloadModule"
+            paramTypes("android.view.View")
+            returnType = "void"
+        }
+    }
+
+    val PANEL_AWEME = method {
+        matcher {
+            declaredClass = "com.ss.android.ugc.aweme.feed.long_press_panel.modules.LongPressPanelAbsModule"
+            paramCount = 0
+            returnType = AWEME
+        }
+    }
+
     val LOCAL_MASK = method {
         matcher { returnType = "boolean"; paramCount = 0; usingEqStrings("save_image_to_video_local_water_mask_enable") }
     }
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/sdk/media/MediaFiles.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/sdk/media/MediaFiles.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/sdk/media/MediaFiles.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/sdk/media/MediaFiles.kt
@@ -1,5 +1,6 @@
 package io.github.nuke.oss.app.tiktok.sdk.media
 
+import io.github.nuke.oss.app.shared.media.DownloadCancelled
 import java.io.File
 import java.util.Base64
 
@@ -53,8 +54,8 @@
     else -> emptyList()
 }
 
-// Where a saved image goes under Pictures/douyin.
-internal enum class ImageFolder(val directory: String) { COMMENT("comment"), AVATAR("avatar") }
+// Where a saved file goes under the gallery folder of its kind (Pictures/douyin or Movies/douyin).
+internal enum class ImageFolder(val directory: String) { COMMENT("comment"), AVATAR("avatar"), VIDEO("video"), IMAGE("image") }
 
 // Files the media features write or convert. Everything here blocks, so callers run it off the main thread.
 internal interface MediaFiles {
@@ -62,9 +63,27 @@
     // stops and the user taps again.
     fun requestStoragePermission(activity: Any): Boolean
 
-    // Downloads the first address that answers (context is any host Context) and saves it to the gallery (Pictures/douyin/<folder>) or the music
-    // folder (Music/douyin/audio). Video stickers become GIFs and VVIC images become PNGs. Throws on failure.
-    fun save(context: Any, urls: List<String>, audio: Boolean, folder: ImageFolder = ImageFolder.COMMENT)
+    // Downloads the first address that answers (context is any host Context) and saves it to the gallery (Pictures/douyin/<folder>,
+    // Movies/douyin/<folder> for a video) or the music folder (Music/douyin/audio). Video stickers become GIFs and VVIC
+    // images become PNGs. name is the file name without its extension, from MediaNaming; the writer appends the extension of
+    // what it saved, and a missing name falls back to a random one. Throws on failure.
+    // progress reports the bytes fetched so far and the size the server announced (0 when it announced none), and
+    // cancelled is asked between chunks so a queued task can be stopped; a cancelled save throws DownloadCancelled.
+    // tree is a SAF folder the user picked: the file is written there instead of the gallery.
+    fun save(
+        context: Any,
+        urls: List<String>,
+        audio: Boolean,
+        folder: ImageFolder = ImageFolder.COMMENT,
+        name: String? = null,
+        progress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
+        cancelled: () -> Boolean = { false },
+        tree: String? = null,
+    )
+
+    // Writes a file the module produced itself (an extracted MP3) to the gallery or the music folder, under the same
+    // naming rules as save. The file is left in place; the caller owns it.
+    fun saveFile(context: Any, file: File, audio: Boolean, folder: ImageFolder = ImageFolder.COMMENT, name: String? = null)
 
     // Writes the text to Downloads/Nuke (or the app's own documents folder before Android 10) and returns where it went.
     fun saveText(context: Any, name: String, text: String): String
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/AnalyticsDialog.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/AnalyticsDialog.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/AnalyticsDialog.kt
@@ -0,0 +1,117 @@
+package io.github.nuke.oss.ui.hostsettings
+
+import androidx.compose.foundation.layout.Arrangement
+import androidx.compose.foundation.layout.Column
+import androidx.compose.foundation.layout.fillMaxWidth
+import androidx.compose.runtime.Composable
+import androidx.compose.runtime.LaunchedEffect
+import androidx.compose.runtime.getValue
+import androidx.compose.runtime.mutableStateOf
+import androidx.compose.runtime.remember
+import androidx.compose.runtime.rememberCoroutineScope
+import androidx.compose.runtime.setValue
+import androidx.compose.ui.Modifier
+import androidx.compose.ui.platform.LocalContext
+import androidx.compose.ui.res.stringResource
+import androidx.compose.ui.unit.dp
+import io.github.nuke.oss.R
+import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsReport
+import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsSummary
+import io.github.nuke.oss.designsystem.NukeDialog
+import io.github.nuke.oss.designsystem.NukeDialogTextButton
+import java.io.File
+import java.time.Instant
+import java.time.ZoneId
+import java.time.format.DateTimeFormatter
+import kotlinx.coroutines.Dispatchers
+import kotlinx.coroutines.launch
+import kotlinx.coroutines.withContext
+
+internal const val LOCAL_ANALYTICS = "LocalAnalytics"
+
+// Reads what the local analysis database already holds. Nothing here depends on the host: the numbers come from the
+// file the feature writes in the host's private storage, so the dialog works while the feature is off.
+@Composable
+internal fun AnalyticsDialog(directory: File?, title: String, onDismiss: () -> Unit) {
+    var summary by remember { mutableStateOf<AnalyticsSummary?>(null) }
+    var loaded by remember { mutableStateOf(false) }
+    var exported by remember { mutableStateOf<String?>(null) }
+    val context = LocalContext.current
+    val scope = rememberCoroutineScope()
+    LaunchedEffect(directory) {
+        summary = withContext(Dispatchers.IO) {
+            directory?.let { AnalyticsReport.read(File(it, DATABASE), ZoneId.systemDefault()) }
+        }
+        loaded = true
+    }
+    // Built during composition, where resources are available; the export action only reads them.
+    val exportLines = summary?.let { reportLines(it) }.orEmpty()
+    NukeDialog(
+        visible = true,
+        onDismissRequest = onDismiss,
+        title = title,
+        actions = {
+            NukeDialogTextButton(
+                stringResource(R.string.host_analytics_export),
+                {
+                    if (exportLines.isEmpty()) return@NukeDialogTextButton
+                    scope.launch {
+                        val name = "analytics-" + System.currentTimeMillis()
+                        exported = withContext(Dispatchers.IO) {
+                            AnalyticsImageExport.export(context, title, exportLines, name)
+                        } ?: ""
+                    }
+                },
+                Modifier.weight(1f),
+            )
+            NukeDialogTextButton(stringResource(R.string.host_analytics_close), onDismiss, Modifier.weight(1f))
+        },
+    ) {
+        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
+            val report = summary
+            when {
+                !loaded -> OptionsBodyText(stringResource(R.string.host_analytics_loading))
+                report == null || !report.hasData -> OptionsBodyText(stringResource(R.string.host_analytics_empty))
+                else -> {
+                    OptionsBodyText(stringResource(R.string.host_analytics_today, report.todayViews, duration(report.todayVisibleMs)))
+                    OptionsBodyText(stringResource(R.string.host_analytics_week, report.weekViews, duration(report.weekVisibleMs)))
+                    OptionsBodyText(stringResource(R.string.host_analytics_authors, report.authors))
+                    if (report.hours.isNotEmpty()) {
+                        OptionsBodyText(stringResource(R.string.host_analytics_hours))
+                        OptionsBodyText(report.hours.joinToString("   ") { (hour, views) -> "%02d:00  ×%d".format(hour, views) })
+                    }
+                    exported?.let { path ->
+                        OptionsBodyText(
+                            if (path.isEmpty()) stringResource(R.string.host_analytics_export_failed)
+                            else stringResource(R.string.host_analytics_exported, path),
+                        )
+                    }
+                }
+            }
+        }
+    }
+}
+
+// The same numbers the dialog shows, as plain lines for the image.
+@Composable
+private fun reportLines(report: AnalyticsSummary): List<String> = buildList {
+    add(stringResource(R.string.host_analytics_today, report.todayViews, duration(report.todayVisibleMs)))
+    add(stringResource(R.string.host_analytics_week, report.weekViews, duration(report.weekVisibleMs)))
+    add(stringResource(R.string.host_analytics_authors, report.authors))
+    if (report.hours.isNotEmpty()) {
+        add(stringResource(R.string.host_analytics_hours))
+        add(report.hours.joinToString("   ") { (hour, views) -> "%02d:00  x%d".format(hour, views) })
+    }
+    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd")
+    for (day in report.dayRows()) {
+        add(format.format(Instant.ofEpochMilli(day.day * 86_400_000L).atZone(ZoneId.of("UTC")).toLocalDate()) + "   " + duration(day.visibleMs))
+    }
+}
+
+// h:mm, so a long day stays readable without a resource per unit.
+private fun duration(millis: Long): String {
+    val minutes = (millis / 60_000).coerceAtLeast(0)
+    return "%d:%02d".format(minutes / 60, minutes % 60)
+}
+
+private const val DATABASE = "analytics.db"
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/AnalyticsImageExport.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/AnalyticsImageExport.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/AnalyticsImageExport.kt
@@ -0,0 +1,91 @@
+package io.github.nuke.oss.ui.hostsettings
+
+import android.content.ContentValues
+import android.content.Context
+import android.graphics.Bitmap
+import android.graphics.Canvas
+import android.graphics.Color
+import android.graphics.Paint
+import android.graphics.Typeface
+import android.os.Build
+import android.os.Environment
+import android.provider.MediaStore
+import java.io.File
+import java.io.IOException
+
+// Exports the local analysis as one PNG in Downloads/Nuke: a title, the summary lines and a row per day. The image is
+// drawn here rather than through a document library, so the module gains no dependency for it.
+internal object AnalyticsImageExport {
+    // Returns where the file went, or null when it could not be written. Blocking; callers run it off the main thread.
+    fun export(context: Context, title: String, lines: List<String>, name: String): String? = try {
+        val bitmap = render(title, lines)
+        val file = File.createTempFile("nuke-report-", ".png", context.cacheDir)
+        try {
+            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
+            write(context, file, name)
+        } finally {
+            bitmap.recycle()
+            file.delete()
+        }
+    } catch (_: IOException) {
+        null
+    } catch (_: RuntimeException) {
+        null
+    }
+
+    private fun render(title: String, lines: List<String>): Bitmap {
+        val width = WIDTH
+        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
+            color = Color.WHITE
+            textSize = 46f
+            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
+        }
+        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
+            color = Color.WHITE
+            textSize = 32f
+        }
+        val height = (PADDING * 2 + 64 + lines.size * LINE_HEIGHT).coerceAtLeast(PADDING * 2 + 64)
+        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
+        val canvas = Canvas(bitmap)
+        canvas.drawColor(Color.rgb(24, 24, 27))
+        canvas.drawText(title, PADDING.toFloat(), (PADDING + 48).toFloat(), titlePaint)
+        var y = PADDING + 64 + 32
+        for (line in lines) {
+            canvas.drawText(line, PADDING.toFloat(), y.toFloat(), bodyPaint)
+            y += LINE_HEIGHT
+        }
+        return bitmap
+    }
+
+    private fun write(context: Context, file: File, name: String): String? {
+        val resolver = context.contentResolver
+        val fileName = if (name.endsWith(".png")) name else "$name.png"
+        if (Build.VERSION.SDK_INT < 29) {
+            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nuke")
+            if (!directory.isDirectory && !directory.mkdirs()) return null
+            val target = File(directory, fileName)
+            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
+            return "${Environment.DIRECTORY_DOWNLOADS}/Nuke/$fileName"
+        }
+        val values = ContentValues().apply {
+            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
+            put(MediaStore.Downloads.MIME_TYPE, "image/png")
+            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Nuke")
+            put(MediaStore.Downloads.IS_PENDING, 1)
+        }
+        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
+        try {
+            resolver.openOutputStream(uri)?.use { target -> file.inputStream().use { it.copyTo(target) } }
+                ?: return null
+            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
+            return "${Environment.DIRECTORY_DOWNLOADS}/Nuke/$fileName"
+        } catch (error: Exception) {
+            resolver.delete(uri, null, null)
+            throw error
+        }
+    }
+
+    private const val WIDTH = 1080
+    private const val PADDING = 56
+    private const val LINE_HEIGHT = 52
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/DownloadTasksDialog.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/DownloadTasksDialog.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/DownloadTasksDialog.kt
@@ -0,0 +1,122 @@
+package io.github.nuke.oss.ui.hostsettings
+
+import android.content.Intent
+import androidx.activity.compose.rememberLauncherForActivityResult
+import androidx.activity.result.contract.ActivityResultContracts
+import androidx.compose.foundation.layout.Arrangement
+import androidx.compose.foundation.layout.Column
+import androidx.compose.foundation.layout.Row
+import androidx.compose.foundation.layout.fillMaxWidth
+import androidx.compose.runtime.Composable
+import androidx.compose.runtime.LaunchedEffect
+import androidx.compose.runtime.getValue
+import androidx.compose.runtime.mutableStateOf
+import androidx.compose.runtime.remember
+import androidx.compose.runtime.setValue
+import androidx.compose.ui.Alignment
+import androidx.compose.ui.Modifier
+import androidx.compose.ui.platform.LocalContext
+import androidx.compose.ui.res.stringResource
+import androidx.compose.ui.unit.dp
+import io.github.nuke.oss.R
+import io.github.nuke.oss.app.shared.media.DownloadConsoles
+import io.github.nuke.oss.app.shared.media.DownloadTask
+import io.github.nuke.oss.app.shared.media.TaskStage
+import io.github.nuke.oss.app.shared.media.TaskState
+import io.github.nuke.oss.app.shared.settings.HostFeature
+import io.github.nuke.oss.app.tiktok.feature.feed.DownloadVideoWithName
+import io.github.nuke.oss.designsystem.NukeDialog
+import io.github.nuke.oss.designsystem.NukeDialogTextButton
+import kotlinx.coroutines.delay
+
+internal const val DOWNLOAD_TASKS = "DownloadVideoWithName"
+
+// What the queue is doing, and where the files go: the gallery folders by default, or a SAF folder the user picks here.
+// The queue lives in the host process, so this reads it directly; nothing here touches the network or the gallery.
+@Composable
+internal fun DownloadTasksDialog(
+    feature: HostFeature,
+    title: String,
+    onSaveOptions: (Map<String, Any>) -> Unit,
+    onDismiss: () -> Unit,
+) {
+    val context = LocalContext.current
+    val console = DownloadConsoles.current()
+    var tasks by remember { mutableStateOf(console?.snapshot().orEmpty()) }
+    LaunchedEffect(console) {
+        while (true) {
+            tasks = console?.snapshot().orEmpty()
+            delay(POLL_MILLIS)
+        }
+    }
+    val tree = feature.options.firstOrNull { it.option.key == DownloadVideoWithName.TREE }?.saved as? String
+    val saved = { feature.options.associate { it.option.key to it.saved } }
+    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
+        uri ?: return@rememberLauncherForActivityResult
+        runCatching {
+            context.contentResolver.takePersistableUriPermission(
+                uri,
+                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
+            )
+        }
+        onSaveOptions(saved() + (DownloadVideoWithName.TREE to uri.toString()))
+    }
+    NukeDialog(
+        visible = true,
+        onDismissRequest = onDismiss,
+        title = title,
+        actions = {
+            NukeDialogTextButton(stringResource(R.string.host_download_pick_folder), { picker.launch(null) }, Modifier.weight(1f))
+            NukeDialogTextButton(stringResource(R.string.host_download_clear), { console?.clearFinished() }, Modifier.weight(1f))
+            NukeDialogTextButton(stringResource(R.string.manager_close), onDismiss, Modifier.weight(1f))
+        },
+    ) {
+        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
+            OptionsBodyText(
+                if (tree.isNullOrBlank()) stringResource(R.string.host_download_folder_default)
+                else stringResource(R.string.host_download_folder_picked, tree),
+            )
+            if (!tree.isNullOrBlank()) {
+                NukeDialogTextButton(
+                    stringResource(R.string.host_download_folder_reset),
+                    { onSaveOptions(saved() + (DownloadVideoWithName.TREE to "")) },
+                )
+            }
+            if (tasks.isEmpty()) {
+                OptionsBodyText(stringResource(R.string.host_download_empty))
+                return@Column
+            }
+            for (task in tasks.asReversed()) {
+                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
+                    Column(Modifier.weight(1f)) {
+                        OptionsBodyText(task.name.ifBlank { task.id.toString() })
+                        OptionsBodyText(state(task))
+                    }
+                    if (task.state == TaskState.QUEUED || task.state == TaskState.RUNNING) {
+                        NukeDialogTextButton(stringResource(R.string.host_download_cancel), { console?.cancel(task.id) })
+                    }
+                }
+            }
+        }
+    }
+}
+
+@Composable
+private fun state(task: DownloadTask): String = when (task.state) {
+    TaskState.QUEUED -> stringResource(R.string.host_download_queued)
+    TaskState.RUNNING -> stringResource(R.string.host_download_running, (task.fraction * 100).toInt())
+    TaskState.SUCCEEDED -> stringResource(R.string.host_download_succeeded)
+    TaskState.CANCELLED -> stringResource(R.string.host_download_cancelled)
+    TaskState.FAILED -> stringResource(
+        R.string.host_download_failed,
+        stringResource(
+            when (task.stage) {
+                TaskStage.RESOLVE -> R.string.host_download_stage_resolve
+                TaskStage.SAVE -> R.string.host_download_stage_save
+                else -> R.string.host_download_stage_download
+            },
+        ) + ": " + task.reason.orEmpty(),
+    )
+}
+
+private const val POLL_MILLIS = 500L
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt
@@ -105,6 +105,9 @@
     "McpServer" to FeatureLabel(R.string.host_feature_mcp_server, R.string.host_feature_mcp_server_description, FeatureCategory.SYSTEM),
     "HideStatusBar" to FeatureLabel(R.string.host_feature_status_bar, R.string.host_feature_status_bar_description, FeatureCategory.LAYOUT),
     "LongVideoNotice" to FeatureLabel(R.string.host_feature_long_video, R.string.host_feature_long_video_description, FeatureCategory.FEED),
+    "LocalAnalytics" to FeatureLabel(R.string.host_feature_local_analytics, R.string.host_feature_local_analytics_description, FeatureCategory.WELLBEING),
+    "WebDavUpload" to FeatureLabel(R.string.host_feature_webdav, R.string.host_feature_webdav_description, FeatureCategory.SHARING),
+    "SaveVideoAudio" to FeatureLabel(R.string.host_feature_save_audio, R.string.host_feature_save_audio_description, FeatureCategory.SHARING),
     "TimedClose" to FeatureLabel(R.string.host_feature_timed_close, R.string.host_feature_timed_close_description, FeatureCategory.WELLBEING),
     "ProgressTime" to FeatureLabel(R.string.host_feature_progress_time, R.string.host_feature_progress_time_description, FeatureCategory.PLAYBACK),
     "AllowAnyForward" to FeatureLabel(R.string.host_feature_any_forward, R.string.host_feature_any_forward_description, FeatureCategory.SHARING),
@@ -149,6 +152,7 @@
     "BlockNotificationPrompts" to FeatureLabel(R.string.host_feature_block_notification_prompts, R.string.host_feature_block_notification_prompts_description, FeatureCategory.SYSTEM),
     "AllowFeedDownloads" to FeatureLabel(R.string.host_feature_allow_feed_downloads, R.string.host_feature_allow_feed_downloads_description, FeatureCategory.SHARING),
     "DownloadOriginalVideo" to FeatureLabel(R.string.host_feature_download_original_video, R.string.host_feature_download_original_video_description, FeatureCategory.SHARING),
+    "DownloadVideoWithName" to FeatureLabel(R.string.host_feature_download_video_with_name, R.string.host_feature_download_video_with_name_description, FeatureCategory.SHARING),
     "DownloadOriginalImages" to FeatureLabel(R.string.host_feature_download_original_images, R.string.host_feature_download_original_images_description, FeatureCategory.SHARING),
     "CommentOriginalImage" to FeatureLabel(R.string.host_feature_comment_original_image, R.string.host_feature_comment_original_image_description, FeatureCategory.COMMENTS),
     "SaveCommentAudio" to FeatureLabel(R.string.host_feature_save_comment_audio, R.string.host_feature_save_comment_audio_description, FeatureCategory.COMMENTS),
@@ -187,6 +191,21 @@
             "homepage_hot_container" to R.string.host_choice_top_hot_container, "homepage_mediumvideo" to R.string.host_choice_top_mediumvideo, "homepage_hangout" to R.string.host_choice_top_hangout, "homepage_tablive" to R.string.host_choice_top_tablive, "homepage_nearby" to R.string.host_choice_top_nearby, "homepage_follow" to R.string.host_choice_top_follow, "homepage_mall" to R.string.host_choice_top_mall, "homepage_groupon" to R.string.host_choice_top_groupon, "homepage_pad_hot" to R.string.host_choice_top_pad_hot, "homepage_learn" to R.string.host_choice_top_learn, "homepage_pad_game" to R.string.host_choice_top_pad_game, "homepage_pad_campaign" to R.string.host_choice_top_pad_campaign, "homepage_pad_web" to R.string.host_choice_top_pad_web, "homepage_xtab" to R.string.host_choice_top_xtab, "homepage_shopping_lite" to R.string.host_choice_top_shopping_lite, "homepage_pad_cinema" to R.string.host_choice_top_pad_cinema, "homepage_pad_kids_v2" to R.string.host_choice_top_pad_kids_v2, "homepage_novel_lite" to R.string.host_choice_top_novel_lite, "homepage_multiple_content" to R.string.host_choice_top_multiple_content, "homepage_video_hotsoon" to R.string.host_choice_top_video_hotsoon, "homepage_playlet_stream" to R.string.host_choice_top_playlet_stream,
         ),
     ),
+    "DownloadOriginalVideo/source" to OptionLabel(
+        R.string.host_option_download_source, R.string.host_option_download_source_description,
+        mapOf(
+            "auto" to R.string.host_choice_download_source_auto, "play" to R.string.host_choice_download_source_play,
+            "h264" to R.string.host_choice_download_source_h264, "h265" to R.string.host_choice_download_source_h265,
+            "download" to R.string.host_choice_download_source_download,
+        ),
+    ),
+    "WebDavUpload/address" to OptionLabel(R.string.host_option_webdav_address, R.string.host_option_webdav_address_description),
+    "WebDavUpload/user" to OptionLabel(R.string.host_option_webdav_user, R.string.host_option_webdav_user_description),
+    "WebDavUpload/password" to OptionLabel(R.string.host_option_webdav_password, R.string.host_option_webdav_password_description),
+    "WebDavUpload/directory" to OptionLabel(R.string.host_option_webdav_directory, R.string.host_option_webdav_directory_description),
+    "DownloadVideoWithName/template" to OptionLabel(R.string.host_option_download_name_template, R.string.host_option_download_name_template_description),
+    "DownloadVideoWithName/folder" to OptionLabel(R.string.host_option_download_folder, R.string.host_option_download_folder_description),
+    "DownloadVideoWithName/tree" to OptionLabel(R.string.host_option_download_tree, R.string.host_option_download_tree_description),
     "CustomPlaybackSpeed/speed" to OptionLabel(
         R.string.host_option_speed, R.string.host_option_speed_description, mapOf("host" to R.string.host_choice_speed_host),
     ),
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsActivity.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsActivity.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsActivity.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsActivity.kt
@@ -104,6 +104,8 @@
                 // With nothing to report the picker is not opened; the screen says so instead of writing an empty file.
                 onExportFailures = { if (settings.hasFailures()) exportFailures.launch(failuresFileName()) else settings.noFailures() },
                 onImportConfig = { importConfig.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
+                onApplyConfig = settings::applyConfig,
+                onCancelConfig = settings::cancelConfigImport,
                 onDismissConfigNotice = settings::dismissConfigNotice,
                 onSafeMode = settings::setSafeMode,
                 onRestartHost = { restartHost(this) },
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsScreen.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsScreen.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsScreen.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsScreen.kt
@@ -47,6 +47,7 @@
 import io.github.nuke.oss.R
 import io.github.nuke.oss.BuildConfig
 import io.github.nuke.oss.app.shared.settings.ActivityRecord
+import io.github.nuke.oss.app.shared.settings.ConfigImportMode
 import io.github.nuke.oss.app.shared.integrity.DeviceTrust
 import io.github.nuke.oss.app.shared.settings.HostAccount
 import io.github.nuke.oss.app.shared.settings.PetConsole
@@ -94,7 +95,7 @@
 // Not a page: this tile opens the import and export dialog.
 private const val CONFIG = "config"
 // Features without options whose row still opens a dialog of their own.
-private val DIALOG_FEATURES = setOf(PROTOCOL_SENDER, MCP_SERVER)
+private val DIALOG_FEATURES = setOf(PROTOCOL_SENDER, MCP_SERVER, LOCAL_ANALYTICS, DOWNLOAD_TASKS)
 
 // The feature switch screen that runs inside the host process. The query is local: it only filters this list.
 @Composable
@@ -118,6 +119,8 @@
     onCleanConfig: () -> Unit,
     onExportConfig: () -> Unit,
     onImportConfig: () -> Unit,
+    onApplyConfig: (ConfigImportMode) -> Unit,
+    onCancelConfig: () -> Unit,
     onExportFailures: () -> Unit,
     onDismissConfigNotice: () -> Unit,
     onSafeMode: (Boolean) -> Unit,
@@ -183,6 +186,15 @@
         }
         // Both exports and the import answer here, whichever page asked, so the notice cannot be lost behind a page change.
         state.configNotice?.let { notice -> ConfigNoticeDialog(notice, onDismissConfigNotice, onRestartHost) }
+        // A checked file asks how it meets the stored preferences before anything is written.
+        state.pendingConfig?.let { pending ->
+            ConfigPreviewDialog(
+                pending.preview,
+                onMerge = { onApplyConfig(ConfigImportMode.MERGE) },
+                onReplace = { onApplyConfig(ConfigImportMode.REPLACE) },
+                onDismiss = onCancelConfig,
+            )
+        }
         val editing = optionsFor?.let { id -> state.features.firstOrNull { it.id == id } }
         if (editing != null && live) {
             val title = featureLabels[editing.id]?.let { stringResource(it.title) } ?: editing.id
@@ -218,6 +230,14 @@
                     onDismiss = { optionsFor = null },
                     loadContacts = loadContacts,
                 )
+            } else if (editing.id == DOWNLOAD_TASKS) {
+                DownloadTasksDialog(editing, title, { onOptions(editing.id, it) }) { optionsFor = null }
+            } else if (editing.id == LOCAL_ANALYTICS) {
+                AnalyticsDialog(
+                    directory = featureFiles?.let { java.io.File(it, LOCAL_ANALYTICS) },
+                    title = title,
+                    onDismiss = { optionsFor = null },
+                )
             } else if (editing.id == AUTO_STREAK) {
                 AutoStreakDialog(
                     feature = editing,
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsViewModel.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsViewModel.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsViewModel.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsViewModel.kt
@@ -13,7 +13,9 @@
 import io.github.nuke.oss.app.shared.diagnostics.FailureRecord
 import io.github.nuke.oss.app.shared.diagnostics.FailureStore
 import io.github.nuke.oss.app.shared.settings.ActivityRecord
+import io.github.nuke.oss.app.shared.settings.ConfigImportMode
 import io.github.nuke.oss.app.shared.settings.ConfigImportRejected
+import io.github.nuke.oss.app.shared.settings.ConfigPreview
 import io.github.nuke.oss.app.shared.settings.HostAccount
 import io.github.nuke.oss.app.shared.settings.HostContactList
 import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
@@ -50,6 +52,8 @@
     // The outcome of the last configuration or failure export, or configuration import, until the dialog that shows it is
     // dismissed.
     val configNotice: ConfigNotice? = null,
+    // A configuration file that was read and checked, waiting for the choice between merging and replacing.
+    val pendingConfig: PendingConfig? = null,
     // The features waiting for a restart when the screen opened. Leaving asks for a restart only when something is waiting
     // that was not already, so a change from an earlier visit is not asked about again, and switching a change back
     // leaves nothing to ask about.
@@ -65,6 +69,9 @@
         feature.options.any { it.pending }
 }.map { it.id }.toSet()
 
+// A configuration file that was read and checked, waiting for the user to choose how it meets the stored values.
+internal data class PendingConfig(val text: String, val preview: ConfigPreview)
+
 internal sealed interface ConfigNotice {
     data object Exported : ConfigNotice
     data object ExportFailed : ConfigNotice
@@ -166,25 +173,39 @@
         mutableState.update { it.copy(configNotice = notice) }
     }
 
-    // Writes nothing unless the whole file is a valid configuration for this host. The running features keep their
-    // state until the host restarts; this screen shows the imported values at once.
+    // Reads and checks the file, then waits for the choice between merging and replacing; a rejected file changes nothing.
     suspend fun importConfig(resolver: ContentResolver, uri: Uri) {
         val target = session ?: return
         val text = withContext(Dispatchers.IO) { readConfig(resolver, uri) }
             ?: return mutableState.update { it.copy(configNotice = ConfigNotice.ReadFailed) }
+        val preview = try {
+            target.config.preview(text)
+        } catch (rejected: ConfigImportRejected) {
+            return mutableState.update { it.copy(configNotice = ConfigNotice.Rejected(rejected.reason, rejected.detail)) }
+        }
+        mutableState.update { it.copy(pendingConfig = PendingConfig(text, preview)) }
+    }
+
+    // Writes the file the preview was made from. The running features keep their state until the host restarts; this
+    // screen shows the imported values at once.
+    fun applyConfig(mode: ConfigImportMode) {
+        val target = session ?: return
+        val pending = mutableState.value.pendingConfig ?: return
         val notice = try {
-            ConfigNotice.Imported(target.config.import(text))
+            ConfigNotice.Imported(target.config.import(pending.text, mode))
         } catch (rejected: ConfigImportRejected) {
             ConfigNotice.Rejected(rejected.reason, rejected.detail)
         }
         mutableState.update {
             it.copy(
-                configNotice = notice, features = target.entries(), ui = UiAppearance(target.ui).read(),
-                bugReports = target.setup.bugReports(),
+                configNotice = notice, pendingConfig = null, features = target.entries(),
+                ui = UiAppearance(target.ui).read(), bugReports = target.setup.bugReports(),
             )
         }
     }
 
+    fun cancelConfigImport() = mutableState.update { it.copy(pendingConfig = null) }
+
     fun dismissConfigNotice() = mutableState.update { it.copy(configNotice = null) }
 
     // Takes effect at the next host start; turning it on restarts the host from the screen.
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/ModuleDebug.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/ModuleDebug.kt
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/ModuleDebug.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/ModuleDebug.kt
@@ -6,6 +6,7 @@
 import androidx.compose.foundation.background
 import androidx.compose.foundation.layout.Arrangement
 import androidx.compose.foundation.layout.Box
+import androidx.compose.foundation.layout.Column
 import androidx.compose.foundation.layout.Row
 import androidx.compose.foundation.layout.fillMaxWidth
 import androidx.compose.foundation.layout.heightIn
@@ -47,6 +48,7 @@
 import io.github.nuke.oss.app.shared.diagnostics.FailurePhases
 import io.github.nuke.oss.app.shared.diagnostics.FailureRecord
 import io.github.nuke.oss.app.shared.settings.ConfigImportRejected
+import io.github.nuke.oss.app.shared.settings.ConfigPreview
 import io.github.nuke.oss.app.shared.settings.FeatureDisplay
 import io.github.nuke.oss.app.shared.settings.HostFeature
 import io.github.nuke.oss.app.shared.settings.display
@@ -403,3 +405,33 @@
 
 private fun ConfigNotice.isFailureReport() =
     this is ConfigNotice.FailuresExported || this == ConfigNotice.NoFailures || this == ConfigNotice.FailuresExportFailed
+
+// A file that was read and checked waits here for the choice between merging it into the stored preferences and
+// replacing them, so neither happens by accident and the numbers are visible before anything is written.
+@Composable
+internal fun ConfigPreviewDialog(
+    preview: ConfigPreview,
+    onMerge: () -> Unit,
+    onReplace: () -> Unit,
+    onDismiss: () -> Unit,
+) {
+    NukeDialog(
+        visible = true,
+        onDismissRequest = onDismiss,
+        title = stringResource(R.string.host_debug_config_preview_title),
+        actions = {
+            NukeDialogTextButton(stringResource(R.string.home_cancel), onDismiss, Modifier.weight(1f))
+            NukeDialogTextButton(stringResource(R.string.host_debug_config_merge), onMerge, Modifier.weight(1f))
+            NukeDialogAccentButton(stringResource(R.string.host_debug_config_replace), onReplace, Modifier.weight(1f))
+        },
+    ) {
+        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
+            OptionsBodyText(stringResource(R.string.host_debug_config_preview_summary, preview.added.size, preview.changed.size, preview.unchanged))
+            if (preview.replacedAway.isEmpty()) {
+                OptionsBodyText(stringResource(R.string.host_debug_config_preview_kept))
+            } else {
+                OptionsBodyText(stringResource(R.string.host_debug_config_preview_replaced_away, preview.replacedAway.size))
+            }
+        }
+    }
+}
diff --git a/apps/module/src/main/res/values/home_strings.xml b/apps/module/src/main/res/values/home_strings.xml
--- a/apps/module/src/main/res/values/home_strings.xml
+++ b/apps/module/src/main/res/values/home_strings.xml
@@ -71,6 +71,12 @@
     <string name="host_debug_config_other_host">This configuration was exported from %1$s, not this app. Nothing was changed.</string>
     <string name="host_debug_config_newer">This configuration comes from a newer NukeApp-OSS. Nothing was changed.</string>
     <string name="host_debug_config_invalid">The configuration has an unknown or malformed setting, %1$s. Nothing was changed.</string>
+    <string name="host_debug_config_preview_title">Import configuration</string>
+    <string name="host_debug_config_preview_summary">%1$d to add, %2$d to change, %3$d unchanged.</string>
+    <string name="host_debug_config_preview_replaced_away">Replacing also removes %1$d settings the file does not have.</string>
+    <string name="host_debug_config_preview_kept">Merging keeps settings the file does not have.</string>
+    <string name="host_debug_config_merge">Merge</string>
+    <string name="host_debug_config_replace">Replace</string>
     <string name="host_debug_summary">Overview</string>
     <string name="host_debug_features">Features</string>
     <string name="host_debug_detail_title">Feature details</string>
@@ -124,4 +130,23 @@
     <string name="home_background_failed">Could not use that image. Choose a smaller image file that this device can read.</string>
     <string name="home_language_english" translatable="false">English</string>
     <string name="home_language_chinese" translatable="false">简体中文</string>
+
+    <string name="host_download_empty">Nothing has been downloaded yet.</string>
+    <string name="host_download_queued">Queued</string>
+    <string name="host_download_running">Downloading, %1$d%%</string>
+    <string name="host_download_succeeded">Saved</string>
+    <string name="host_download_cancelled">Cancelled</string>
+    <string name="host_download_failed">Failed at %1$s</string>
+    <string name="host_download_stage_resolve">resolving the post</string>
+    <string name="host_download_stage_download">downloading</string>
+    <string name="host_download_stage_save">saving</string>
+    <string name="host_download_cancel">Cancel</string>
+    <string name="host_download_clear">Clear finished</string>
+
+    <string name="host_download_pick_folder">Choose folder</string>
+    <string name="host_download_folder_default">Files go to the gallery folders (Movies/douyin or Pictures/douyin).</string>
+    <string name="host_download_folder_picked">Files go to %1$s</string>
+    <string name="host_download_folder_reset">Use the gallery folders</string>
+    <string name="host_option_download_tree">Save folder</string>
+    <string name="host_option_download_tree_description">The folder the files are written to, chosen here rather than in the gallery.</string>
 </resources>
diff --git a/apps/module/src/main/res/values/host_settings_strings.xml b/apps/module/src/main/res/values/host_settings_strings.xml
--- a/apps/module/src/main/res/values/host_settings_strings.xml
+++ b/apps/module/src/main/res/values/host_settings_strings.xml
@@ -109,6 +109,13 @@
     <string name="host_option_speed_description">Applied when a video starts. Host default keeps Douyin\'s own speed.</string>
     <string name="host_option_cache_count">Count</string>
     <string name="host_option_cache_count_description">0 keeps the host default.</string>
+    <string name="host_option_download_source">Source</string>
+    <string name="host_option_download_source_description">Which address a download is taken from. A rendition the video does not carry falls back to the playable address; Download keeps the host address, which may carry a watermark.</string>
+    <string name="host_choice_download_source_auto">Automatic (playable)</string>
+    <string name="host_choice_download_source_play">Play address</string>
+    <string name="host_choice_download_source_h264">H.264</string>
+    <string name="host_choice_download_source_h265">H.265</string>
+    <string name="host_choice_download_source_download">Download address</string>
     <string name="host_option_danmaku_words">Words</string>
     <string name="host_option_danmaku_words_description">One word per line. Added to the host\'s own list.</string>
     <string name="host_option_dpi">DPI</string>
@@ -663,6 +670,15 @@
     <string name="host_feature_block_notification_prompts_description">Closes the guide that asks for notification permission and the separate system request, without changing any permission. Requests that also ask for other permissions are left alone.</string>
     <string name="host_feature_allow_feed_downloads">Lift download limits</string>
     <string name="host_feature_allow_feed_downloads_description">Turns on the host\'s download action by lifting the local download permission checks.</string>
+    <string name="host_analytics_loading">Reading the local database…</string>
+    <string name="host_analytics_empty">Nothing recorded yet. Turn this feature on and restart Douyin.</string>
+    <string name="host_analytics_today">Today: %1$d videos, %2$s visible</string>
+    <string name="host_analytics_week">Last 7 days: %1$d videos, %2$s visible</string>
+    <string name="host_analytics_authors">Authors seen in the last 7 days: %1$d</string>
+    <string name="host_analytics_hours">Busiest hours (7 days)</string>
+    <string name="host_analytics_close">Close</string>
+    <string name="host_feature_local_analytics">Local analytics</string>
+    <string name="host_feature_local_analytics_description">Records what this device watched: how many videos, how long each was visible, and which authors. Everything stays on this device, identifiers are hashed, and nothing is sent anywhere.</string>
     <string name="host_feature_download_original_video">Download videos without watermark</string>
     <string name="host_feature_download_original_video_description">Uses the playback source address when a video is downloaded.</string>
     <string name="host_feature_download_original_images">Download image sets and live photos without watermark</string>
@@ -880,4 +896,28 @@
     <string name="host_option_auto_spark_claim_hour">Hour</string>
     <string name="host_option_auto_spark_claim_hour_description">The daily claim time, in this phone\'s time zone.</string>
     <string name="host_option_auto_spark_claim_minute">Minute</string>
+
+    <string name="host_feature_webdav">Upload saved files to WebDAV</string>
+    <string name="host_feature_webdav_description">Sends the files this module saves (comment images, voice messages and the like) to a WebDAV collection, with Basic authentication. Host downloads are not included: they never pass through the module. Uploads run in the background and never delay the save.</string>
+    <string name="host_option_webdav_address">Address</string>
+    <string name="host_option_webdav_address_description">The collection\'s address, http or https, for example https://dav.example.com/dav.</string>
+    <string name="host_option_webdav_user">User name</string>
+    <string name="host_option_webdav_user_description">Basic authentication user name. Leave empty for a server without authentication.</string>
+    <string name="host_option_webdav_password">Password</string>
+    <string name="host_option_webdav_password_description">Basic authentication password, stored in the host\'s settings like every other option and never written to the log.</string>
+    <string name="host_option_webdav_directory">Folder</string>
+    <string name="host_option_webdav_directory_description">A folder under the address, created when it does not exist. Empty uploads straight into the address.</string>
+    <string name="host_feature_download_video_with_name">Save videos with your own name and folder</string>
+    <string name="host_feature_download_video_with_name_description">Takes over the long-press save item: the video is fetched by this module from its playable address and written to Movies/douyin/&lt;folder&gt; under the name the template builds. A post without a playable video is left to the host.</string>
+    <string name="host_option_download_name_template">Name template</string>
+    <string name="host_option_download_name_template_description">Tokens: #author#, #desc#, #aid#, #date#, #time#, #datetime#, #timestamp#, #index#, #type#, #ext#. A token without a value becomes nothing. Unknown characters are replaced, and an empty result falls back to the time of the download.</string>
+    <string name="host_option_download_folder">Folder</string>
+    <string name="host_option_download_folder_description">One folder name under Movies/douyin. Only letters, digits, _ and - are kept; anything else falls back to video.</string>
+
+    <string name="host_feature_save_audio">Save a video\'s sound as MP3</string>
+    <string name="host_feature_save_audio_description">When this module saves a video, its audio track is decoded and written to Music/douyin/audio as a real MP3 (192 kbit/s). Only files this module saves are handled; the host\'s own downloads are not. The conversion runs in the background.</string>
+
+    <string name="host_analytics_export">Export image</string>
+    <string name="host_analytics_exported">Saved to %1$s</string>
+    <string name="host_analytics_export_failed">Could not write the image.</string>
 </resources>
diff --git a/apps/module/src/main/res/values-zh/home_strings.xml b/apps/module/src/main/res/values-zh/home_strings.xml
--- a/apps/module/src/main/res/values-zh/home_strings.xml
+++ b/apps/module/src/main/res/values-zh/home_strings.xml
@@ -71,6 +71,12 @@
     <string name="host_debug_config_other_host">该配置导出自 %1$s，不是本应用，未做任何更改。</string>
     <string name="host_debug_config_newer">该配置来自更新版本的 NukeApp-OSS，未做任何更改。</string>
     <string name="host_debug_config_invalid">配置中有未知或格式错误的设置 %1$s，未做任何更改。</string>
+    <string name="host_debug_config_preview_title">导入配置</string>
+    <string name="host_debug_config_preview_summary">新增 %1$d 项，修改 %2$d 项，未变 %3$d 项。</string>
+    <string name="host_debug_config_preview_replaced_away">整体替换还会删除文件中没有的 %1$d 项设置。</string>
+    <string name="host_debug_config_preview_kept">合并导入会保留文件中没有的设置。</string>
+    <string name="host_debug_config_merge">合并</string>
+    <string name="host_debug_config_replace">替换</string>
     <string name="host_debug_summary">状态概览</string>
     <string name="host_debug_features">功能</string>
     <string name="host_debug_detail_title">功能详情</string>
@@ -122,4 +128,23 @@
     <string name="home_card_blur_off">卡片直接透出背景</string>
     <string name="home_background_remove">移除背景</string>
     <string name="home_background_failed">无法使用这张图片。请选择本机可读取、体积更小的图片文件。</string>
+
+    <string name="host_download_empty">还没有下载任务。</string>
+    <string name="host_download_queued">排队中</string>
+    <string name="host_download_running">下载中，%1$d%%</string>
+    <string name="host_download_succeeded">已保存</string>
+    <string name="host_download_cancelled">已取消</string>
+    <string name="host_download_failed">失败于%1$s</string>
+    <string name="host_download_stage_resolve">解析作品</string>
+    <string name="host_download_stage_download">下载</string>
+    <string name="host_download_stage_save">写入文件</string>
+    <string name="host_download_cancel">取消</string>
+    <string name="host_download_clear">清空已完成</string>
+
+    <string name="host_download_pick_folder">选择目录</string>
+    <string name="host_download_folder_default">文件写入相册目录（Movies/douyin 或 Pictures/douyin）。</string>
+    <string name="host_download_folder_picked">文件写入 %1$s</string>
+    <string name="host_download_folder_reset">改回相册目录</string>
+    <string name="host_option_download_tree">保存目录</string>
+    <string name="host_option_download_tree_description">文件写入的目录，在这里选择而不是相册。</string>
 </resources>
diff --git a/apps/module/src/main/res/values-zh/host_settings_strings.xml b/apps/module/src/main/res/values-zh/host_settings_strings.xml
--- a/apps/module/src/main/res/values-zh/host_settings_strings.xml
+++ b/apps/module/src/main/res/values-zh/host_settings_strings.xml
@@ -108,6 +108,13 @@
     <string name="host_option_speed_description">视频开始播放时生效。“跟随抖音”保持抖音自己的倍速。</string>
     <string name="host_option_cache_count">数量</string>
     <string name="host_option_cache_count_description">0 表示保持抖音默认。</string>
+    <string name="host_option_download_source">下载来源</string>
+    <string name="host_option_download_source_description">选择从哪个地址取视频。视频没有该路流时回退到播放地址；选“下载地址”用抖音自己的地址，可能带水印。</string>
+    <string name="host_choice_download_source_auto">自动（播放地址）</string>
+    <string name="host_choice_download_source_play">播放地址</string>
+    <string name="host_choice_download_source_h264">H.264</string>
+    <string name="host_choice_download_source_h265">H.265</string>
+    <string name="host_choice_download_source_download">下载地址</string>
     <string name="host_option_danmaku_words">屏蔽词</string>
     <string name="host_option_danmaku_words_description">每行一个词，会加到抖音自己的列表里。</string>
     <string name="host_option_dpi">DPI</string>
@@ -662,6 +669,15 @@
     <string name="host_feature_block_notification_prompts_description">屏蔽索要通知权限的引导弹窗及独立系统授权请求，不修改已有授权。同时申请其他权限的请求保持原样。</string>
     <string name="host_feature_allow_feed_downloads">解除本地下载限制</string>
     <string name="host_feature_allow_feed_downloads_description">启用宿主下载操作，解除本地下载权限判断。</string>
+    <string name="host_analytics_loading">正在读取本地数据库…</string>
+    <string name="host_analytics_empty">还没有记录。打开这个功能并重启抖音后才会开始统计。</string>
+    <string name="host_analytics_today">今天：%1$d 条，可见 %2$s</string>
+    <string name="host_analytics_week">近 7 天：%1$d 条，可见 %2$s</string>
+    <string name="host_analytics_authors">近 7 天看过的作者：%1$d 位</string>
+    <string name="host_analytics_hours">最爱刷的时段（近 7 天）</string>
+    <string name="host_analytics_close">关闭</string>
+    <string name="host_feature_local_analytics">本地分析</string>
+    <string name="host_feature_local_analytics_description">记录本机看过的视频：条数、每条真正可见的时长、以及作者。数据只留在本机，标识先做哈希，不发送到任何地方。</string>
     <string name="host_feature_download_original_video">视频无水印下载</string>
     <string name="host_feature_download_original_video_description">下载视频时使用播放源地址。</string>
     <string name="host_feature_download_original_images">图集与实况无水印下载</string>
@@ -879,4 +895,28 @@
     <string name="host_option_auto_spark_claim_hour">小时</string>
     <string name="host_option_auto_spark_claim_hour_description">每天的领取时间，按手机当前时区。</string>
     <string name="host_option_auto_spark_claim_minute">分钟</string>
+
+    <string name="host_feature_webdav">保存的文件上传到 WebDAV</string>
+    <string name="host_feature_webdav_description">把本模块保存的文件（评论图片、语音等）通过 Basic 认证上传到 WebDAV 目录。宿主自己的下载不在此列：它们不经过模块。上传在后台进行，不会拖慢保存。</string>
+    <string name="host_option_webdav_address">地址</string>
+    <string name="host_option_webdav_address_description">目录地址，http 或 https，例如 https://dav.example.com/dav。</string>
+    <string name="host_option_webdav_user">用户名</string>
+    <string name="host_option_webdav_user_description">Basic 认证用户名；服务器不需要认证时留空。</string>
+    <string name="host_option_webdav_password">密码</string>
+    <string name="host_option_webdav_password_description">Basic 认证密码，和其它选项一样存在宿主的设置里，日志里不会出现。</string>
+    <string name="host_option_webdav_directory">子目录</string>
+    <string name="host_option_webdav_directory_description">地址下的子目录，不存在时自动创建；留空则直接上传到地址里。</string>
+    <string name="host_feature_download_video_with_name">按自己的命名和文件夹保存视频</string>
+    <string name="host_feature_download_video_with_name_description">接管长按菜单的保存项：由本模块从播放地址下载，写到 Movies/douyin/&lt;文件夹&gt;，文件名由模板生成。没有可播放视频的作品仍然交给宿主处理。</string>
+    <string name="host_option_download_name_template">命名模板</string>
+    <string name="host_option_download_name_template_description">可用标记：#author#、#desc#、#aid#、#date#、#time#、#datetime#、#timestamp#、#index#、#type#、#ext#。没有值的标记展开为空；非法字符会被替换，结果为空时退回下载时间。</string>
+    <string name="host_option_download_folder">文件夹</string>
+    <string name="host_option_download_folder_description">Movies/douyin 下的一个文件夹名。只保留字母、数字、_ 和 -，其它内容退回 video。</string>
+
+    <string name="host_feature_save_audio">把视频原声保存为 MP3</string>
+    <string name="host_feature_save_audio_description">本模块保存视频时，把它的音轨解码并写成 Music/douyin/audio 下的真正 MP3（192 kbit/s）。只处理本模块保存的文件，宿主自己的下载不在此列。转换在后台进行。</string>
+
+    <string name="host_analytics_export">导出图片</string>
+    <string name="host_analytics_exported">已保存到 %1$s</string>
+    <string name="host_analytics_export_failed">无法写入图片。</string>
 </resources>
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/integrity/HostApkTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/integrity/HostApkTest.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/integrity/HostApkTest.kt
@@ -0,0 +1,53 @@
+package io.github.nuke.oss.app.shared.integrity
+
+import java.io.File
+import java.nio.file.Files
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertNull
+import org.junit.Test
+
+class HostApkTest {
+    private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
+
+    @Test fun `the digest is the SHA-256 of the file`() {
+        val file = Files.createTempFile("host", ".apk").toFile()
+        val bytes = "an apk stand-in".toByteArray()
+        try {
+            file.writeBytes(bytes)
+            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
+                .joinToString("") { "%02x".format(it) }
+            assertEquals(expected, HostApk.digest(file))
+            assertEquals(64, expected.length)
+        } finally {
+            file.delete()
+        }
+    }
+
+    @Test fun `an empty list leaves the host unchecked`() {
+        assertEquals(HostVerdict.UNCONFIGURED, HostApk.verdict(digest, emptySet()))
+        assertEquals(HostVerdict.UNCONFIGURED, HostApk.verdict(null, emptySet()))
+    }
+
+    @Test fun `a listed digest is known however it is written`() {
+        assertEquals(HostVerdict.KNOWN, HostApk.verdict(digest, setOf(digest)))
+        assertEquals(HostVerdict.KNOWN, HostApk.verdict(digest.uppercase(), setOf(digest)))
+    }
+
+    @Test fun `another build is unknown and an unreadable file is its own verdict`() {
+        assertEquals(HostVerdict.UNKNOWN, HostApk.verdict(digest, setOf(digest.reversed())))
+        assertEquals(HostVerdict.UNREADABLE, HostApk.verdict(null, setOf(digest)))
+    }
+
+    @Test fun `a file that does not exist has no digest`() {
+        val file = File(Files.createTempDirectory("host").toFile(), "missing.apk")
+        assertNull(HostApk.digest(file))
+    }
+
+    @Test fun `every shipped digest is lowercase hex, so a filled list compares cleanly`() {
+        for (entry in KnownHostApks.DIGESTS) {
+            assertEquals(entry.lowercase(), entry)
+            assertEquals(64, entry.length)
+            assertEquals(true, entry.all { it in "0123456789abcdef" })
+        }
+    }
+}
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/media/DownloadQueueTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/media/DownloadQueueTest.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/media/DownloadQueueTest.kt
@@ -0,0 +1,114 @@
+package io.github.nuke.oss.app.shared.media
+
+import java.util.concurrent.CountDownLatch
+import java.util.concurrent.TimeUnit
+import org.junit.After
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+class DownloadQueueTest {
+    private val queues = ArrayList<DownloadQueue>()
+
+    @After fun stopQueues() {
+        for (queue in queues) queue.close()
+    }
+
+    private fun queue(threads: Int = 2): DownloadQueue = DownloadQueue(threads).also { queues += it }
+
+    private fun DownloadQueue.await(millis: Long = 5_000, task: (DownloadTask) -> Boolean) {
+        val deadline = System.currentTimeMillis() + millis
+        while (System.currentTimeMillis() < deadline) {
+            if (snapshot().any(task)) return
+            Thread.sleep(5)
+        }
+        throw AssertionError("task did not reach the expected state: ${snapshot()}")
+    }
+
+    @Test fun `a finished task keeps the bytes it reported`() {
+        val queue = queue()
+        queue.submit("clip") { progress ->
+            progress.update(512, 1024)
+            progress.update(1024, 1024)
+        }
+        queue.await { it.state == TaskState.SUCCEEDED }
+        val task = queue.recent().single()
+        assertEquals(1024, task.bytes)
+        assertEquals(1024, task.total)
+        assertEquals(1f, task.fraction)
+        assertEquals("clip", task.name)
+    }
+
+    @Test fun `a failure keeps its stage and reason`() {
+        val queue = queue()
+        queue.submit("clip", TaskStage.SAVE) { throw IllegalStateException("no gallery") }
+        queue.await { it.state == TaskState.FAILED }
+        val task = queue.snapshot().single()
+        assertEquals(TaskStage.SAVE, task.stage)
+        assertEquals("no gallery", task.reason)
+    }
+
+    @Test fun `a queued task is cancelled without ever running`() {
+        val queue = queue(threads = 1)
+        val started = CountDownLatch(1)
+        val release = CountDownLatch(1)
+        queue.submit("first") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
+        assertTrue(started.await(5, TimeUnit.SECONDS))
+        var ran = false
+        val second = queue.submit("second") { ran = true }
+        assertEquals(true, queue.cancel(second))
+        release.countDown()
+        queue.await { it.id == second && it.state == TaskState.CANCELLED }
+        assertTrue(!ran)
+    }
+
+    @Test fun `a running task stops when the downloader checks for cancellation`() {
+        val queue = queue()
+        val running = CountDownLatch(1)
+        val id = queue.submit("clip") { progress ->
+            running.countDown()
+            while (true) {
+                progress.check()
+                Thread.sleep(5)
+            }
+        }
+        assertTrue(running.await(5, TimeUnit.SECONDS))
+        assertEquals(true, queue.cancel(id))
+        queue.await { it.state == TaskState.CANCELLED }
+    }
+
+    @Test fun `cancelling a finished task changes nothing`() {
+        val queue = queue()
+        val id = queue.submit("clip") { }
+        queue.await { it.state == TaskState.SUCCEEDED }
+        assertEquals(false, queue.cancel(id))
+        assertEquals(TaskState.SUCCEEDED, queue.snapshot().single().state)
+    }
+
+    @Test fun `one thread runs the tasks one after another`() {
+        val queue = queue(threads = 1)
+        val order = ArrayList<String>()
+        queue.submit("first") { order += "first"; Thread.sleep(30) }
+        queue.submit("second") { order += "second" }
+        queue.await { it.name == "second" && it.state == TaskState.SUCCEEDED }
+        assertEquals(listOf("first", "second"), order)
+    }
+
+    @Test fun `finished tasks are dropped when asked and never count as running`() {
+        val queue = queue()
+        queue.submit("clip") { }
+        queue.await { it.state == TaskState.SUCCEEDED }
+        assertEquals(1, queue.recent().size)
+        queue.clearFinished()
+        assertEquals(emptyList<DownloadTask>(), queue.snapshot())
+    }
+
+    @Test fun `the queue keeps only the newest finished tasks`() {
+        val queue = queue()
+        for (index in 1..30) queue.submit("clip $index") { }
+        assertTrue(queue.awaitIdle())
+        assertEquals(20, queue.snapshot().size)
+        assertEquals("clip 11", queue.snapshot().first().name)
+        assertEquals("clip 30", queue.snapshot().last().name)
+    }
+}
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/media/MediaNamingTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/media/MediaNamingTest.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/media/MediaNamingTest.kt
@@ -0,0 +1,78 @@
+package io.github.nuke.oss.app.shared.media
+
+import java.util.TimeZone
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+class MediaNamingTest {
+    private val utc = TimeZone.getTimeZone("UTC")
+
+    @Test fun `tokens expand from the metadata`() {
+        val name = MediaNaming.fileName(
+            "#author#_#desc#_#aid#",
+            mapOf("author" to "alice", "desc" to "hello world", "aid" to "42"),
+            extension = "mp4",
+            now = 1_700_000_000_000,
+            timeZone = utc,
+        )
+        assertEquals("alice_hello_world_42", name)
+    }
+
+    @Test fun `a token without a value becomes nothing and separators collapse`() {
+        val name = MediaNaming.fileName("#author#_#desc#", mapOf("author" to "alice"), "mp4", now = 0, timeZone = utc)
+        assertEquals("alice", name)
+    }
+
+    @Test fun `unknown tokens and braces forms are supported`() {
+        val name = MediaNaming.fileName("{author}-#nope#-#type#", mapOf("author" to "a", "type" to "video"), "mp4", now = 0, timeZone = utc)
+        assertEquals("a-video", name)
+    }
+
+    @Test fun `path separators wildcards and control characters are replaced`() {
+        val name = MediaNaming.fileName("#desc#", mapOf("desc" to "a/b\\c:d*e?f\"g<h>i|j\u0007k"), "mp4", now = 0, timeZone = utc)
+        assertEquals("a_b_c_d_e_f_g_h_i_j_k", name)
+    }
+
+    @Test fun `leading and trailing dots underscores and dashes are trimmed`() {
+        val name = MediaNaming.fileName("#desc#", mapOf("desc" to " ..__--name--__.. "), "mp4", now = 0, timeZone = utc)
+        assertEquals("name", name)
+    }
+
+    @Test fun `an index is appended in two digits when the template has none`() {
+        val name = MediaNaming.fileName("#desc#", mapOf("desc" to "pic"), "png", index = 3, now = 0, timeZone = utc)
+        assertEquals("pic_03", name)
+    }
+
+    @Test fun `a template with an index token is not appended to`() {
+        val name = MediaNaming.fileName("#desc#_#index#", mapOf("desc" to "pic"), "png", index = 3, now = 0, timeZone = utc)
+        assertEquals("pic_03", name)
+    }
+
+    @Test fun `date and time tokens use the given time zone`() {
+        val name = MediaNaming.fileName("#date#_#time#_#datetime#", emptyMap(), "mp4", now = 1_700_000_000_000, timeZone = utc)
+        assertEquals("20231114_221320_20231114_221320", name)
+    }
+
+    @Test fun `a long name is cut by bytes and keeps whole characters`() {
+        val name = MediaNaming.fileName("#desc#", mapOf("desc" to "汉".repeat(200)), "mp4", now = 0, timeZone = utc)
+        assertTrue(name, name.toByteArray(Charsets.UTF_8).size <= MediaNaming.MAX_BYTES)
+        assertTrue(name.all { it == '汉' })
+        assertTrue(name.isNotEmpty())
+    }
+
+    @Test fun `a template that expands to nothing falls back to the download time`() {
+        assertEquals("media_1700", MediaNaming.fileName("#nope#", emptyMap(), "mp4", now = 1_700, timeZone = utc))
+        assertEquals("media_1700", MediaNaming.fileName("---", emptyMap(), "mp4", now = 1_700, timeZone = utc))
+    }
+
+    @Test fun `only a plain folder name is accepted`() {
+        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder(""))
+        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder("  "))
+        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder("../escape"))
+        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder("my folder"))
+        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder(".."))
+        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder("x".repeat(41)))
+        assertEquals("mine-1_2", MediaNaming.folder(" mine-1_2 "))
+    }
+}
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/media/Mp3EncoderTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/media/Mp3EncoderTest.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/media/Mp3EncoderTest.kt
@@ -0,0 +1,45 @@
+package io.github.nuke.oss.app.shared.media
+
+import java.io.ByteArrayOutputStream
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+// jump3r is plain Java, so the encoder runs in a unit test exactly as it does in the host.
+class Mp3EncoderTest {
+    private fun encode(sampleRate: Int, channels: Int, seconds: Int): ByteArray {
+        val out = ByteArrayOutputStream()
+        val frames = sampleRate * seconds
+        val pcm = ByteArray(frames * channels * 2) { index -> if (index % 2 == 0) 0x10 else 0x00 }
+        Mp3Encoder(sampleRate, channels).use { encoder ->
+            encoder.encode(pcm, pcm.size) { data, count -> out.write(data, 0, count) }
+            encoder.finish { data, count -> out.write(data, 0, count) }
+        }
+        return out.toByteArray()
+    }
+
+    @Test fun `one second of stereo PCM becomes MP3 frames`() {
+        val bytes = encode(44100, 2, 1)
+        assertTrue("expected MP3 output, got ${bytes.size} bytes", bytes.size > 1_000)
+        // Either an ID3 tag or a bare MPEG audio frame sync.
+        val id3 = bytes.size >= 3 && String(bytes, 0, 3, Charsets.US_ASCII) == "ID3"
+        assertTrue(id3 || (bytes[0].toInt() and 0xFF) == 0xFF)
+    }
+
+    @Test fun `a mono encoder accepts mono PCM and more data means more output`() {
+        val short = encode(22050, 1, 1)
+        val long = encode(22050, 1, 2)
+        assertTrue(short.isNotEmpty())
+        assertTrue(long.size > short.size)
+    }
+
+    @Test fun `finishing a stream that never received a sample writes nothing and does not throw`() {
+        val out = ByteArrayOutputStream()
+        Mp3Encoder(44100, 2).use { encoder -> encoder.finish { data, count -> out.write(data, 0, count) } }
+        assertEquals(0, out.size())
+    }
+
+    @Test fun `the output is the same for the same input`() {
+        assertEquals(encode(44100, 2, 1).toList(), encode(44100, 2, 1).toList())
+    }
+}
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/net/WebDavClientTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/net/WebDavClientTest.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/net/WebDavClientTest.kt
@@ -0,0 +1,126 @@
+package io.github.nuke.oss.app.shared.net
+
+import com.sun.net.httpserver.HttpServer
+import java.io.File
+import java.net.InetSocketAddress
+import java.nio.file.Files
+import java.util.Base64
+import org.junit.After
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+// The client is plain java.net, so the server in these tests is the JDK's own: no emulator and no network.
+class WebDavClientTest {
+    private val servers = ArrayList<HttpServer>()
+
+    @After fun stopServers() {
+        for (server in servers) server.stop(0)
+        servers.clear()
+    }
+
+    private class Recorded(val method: String, val path: String, val authorization: String?, val body: ByteArray)
+
+    private fun server(handle: (Recorded) -> Int): Pair<HttpServer, MutableList<Recorded>> {
+        val recorded = ArrayList<Recorded>()
+        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
+        server.createContext("/") { exchange ->
+            val body = exchange.requestBody.use { it.readBytes() }
+            val request = Recorded(
+                exchange.requestMethod, exchange.requestURI.rawPath,
+                exchange.requestHeaders.getFirst("Authorization"), body,
+            )
+            recorded += request
+            val status = handle(request)
+            exchange.sendResponseHeaders(status, -1)
+            exchange.close()
+        }
+        server.start()
+        servers += server
+        return server to recorded
+    }
+
+    private fun file(text: String): File = Files.createTempFile("upload", ".png").toFile().apply {
+        writeBytes(text.toByteArray())
+        deleteOnExit()
+    }
+
+    private fun base(server: HttpServer): String = "http://127.0.0.1:${server.address.port}/dav"
+
+    @Test fun `a file is put into the collection that already exists`() {
+        val (server, requests) = server { if (it.method == "MKCOL") 405 else 201 }
+        val result = WebDavClient(base(server), "me", "secret", "pictures").upload(file("image bytes"), "a.png")
+        assertTrue("$result", result is WebDavResult.Uploaded)
+        assertEquals(201, (result as WebDavResult.Uploaded).status)
+        assertEquals(listOf("MKCOL", "PUT"), requests.map { it.method })
+        assertEquals("/dav/pictures", requests[0].path)
+        assertEquals("/dav/pictures/a.png", requests[1].path)
+        assertEquals("image bytes", String(requests[1].body))
+        val expected = "Basic " + Base64.getEncoder().encodeToString("me:secret".toByteArray())
+        assertEquals(listOf(expected, expected), requests.map { it.authorization })
+    }
+
+    @Test fun `a missing collection is created first`() {
+        val (server, requests) = server { if (it.method == "MKCOL") 201 else 201 }
+        assertTrue(WebDavClient(base(server), "me", "secret", "a/b").upload(file("x"), "b.png") is WebDavResult.Uploaded)
+        assertEquals("/dav/a/b", requests[0].path)
+        assertEquals("/dav/a/b/b.png", requests[1].path)
+    }
+
+    @Test fun `a rejected file keeps the status the server answered`() {
+        val (server, _) = server { if (it.method == "MKCOL") 405 else 401 }
+        val result = WebDavClient(base(server), "me", "secret").upload(file("x"), "b.png")
+        assertEquals(WebDavResult.Failed("upload rejected", 401), result)
+    }
+
+    @Test fun `a collection that cannot be created stops the upload`() {
+        val (server, requests) = server { 403 }
+        val result = WebDavClient(base(server), "me", "secret", "private").upload(file("x"), "b.png")
+        assertEquals(WebDavResult.Failed("collection not created", 403), result)
+        assertEquals(listOf("MKCOL"), requests.map { it.method })
+    }
+
+    @Test fun `a name with spaces and characters is escaped in the path`() {
+        val (server, requests) = server { if (it.method == "MKCOL") 405 else 201 }
+        WebDavClient(base(server), "me", "secret").upload(file("x"), "评论 图1.png")
+        // No folder, so the collection already exists as the address itself and only the PUT is made.
+        assertEquals("/dav/%E8%AF%84%E8%AE%BA%20%E5%9B%BE1.png", requests[0].path)
+    }
+
+    @Test fun `a redirect to another origin does not carry the credentials`() {
+        val (other, otherRequests) = server { 201 }
+        // The first server redirects the PUT; the client must re-send it to the second one without the header.
+        val redirecting = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
+            createContext("/") { exchange ->
+                val body = exchange.requestBody.use { it.readBytes() }
+                if (exchange.requestMethod == "MKCOL") {
+                    exchange.sendResponseHeaders(405, -1)
+                } else {
+                    exchange.responseHeaders.add("Location", "http://127.0.0.1:${other.address.port}/elsewhere/a.png")
+                    exchange.sendResponseHeaders(307, -1)
+                }
+                exchange.close()
+            }
+            start()
+        }
+        servers += redirecting
+        val result = WebDavClient("http://127.0.0.1:${redirecting.address.port}/dav", "me", "secret")
+            .upload(File.createTempFile("redirect", ".png").apply { writeBytes("x".toByteArray()) }, "a.png")
+        assertEquals(201, (result as WebDavResult.Uploaded).status)
+        assertEquals(null, otherRequests.single().authorization)
+        assertEquals(listOf("PUT"), otherRequests.map { it.method })
+    }
+
+    @Test fun `an address that is not http is refused before any request`() {
+        assertEquals(
+            WebDavResult.Failed("unsupported address"),
+            WebDavClient("ftp://example.com/dav", "me", "secret").upload(file("x"), "b.png"),
+        )
+        assertEquals(WebDavResult.Failed("unsupported address"), WebDavClient("", "", "").upload(file("x"), "b.png"))
+    }
+
+    @Test fun `a file that is gone is reported instead of uploaded`() {
+        val missing = File(Files.createTempDirectory("gone").toFile(), "nothing.png")
+        assertEquals(WebDavResult.Failed("no file to upload"), WebDavClient("https://example.com", "", "").upload(missing, "x"))
+    }
+}
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/settings/ModuleConfigTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/settings/ModuleConfigTest.kt
--- a/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/settings/ModuleConfigTest.kt
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/shared/settings/ModuleConfigTest.kt
@@ -83,6 +83,32 @@
         assertEquals(mapOf<String, Any?>("enabled.A" to true), values.stored)
     }
 
+    @Test fun `merging keeps the preferences the file does not carry`() {
+        values.stored.putAll(mapOf("enabled.A" to true, "ui.theme" to 2, "enabled.OnlyHere" to true))
+        val text = """{"format":"io.github.nuke.oss.config","version":1,"host":"$host","values":{"enabled.A":false}}"""
+        assertEquals(1, config.import(text, ConfigImportMode.MERGE))
+        assertEquals(mapOf("enabled.A" to false, "ui.theme" to 2, "enabled.OnlyHere" to true), values.stored)
+    }
+
+    @Test fun `a preview counts what an import would do and writes nothing`() {
+        values.stored.putAll(mapOf("enabled.A" to true, "ui.theme" to 2, "enabled.Gone" to true))
+        val text = """{"format":"io.github.nuke.oss.config","version":1,"host":"$host","values":{"enabled.A":false,"option.A.words":"[\"x\"]","ui.theme":2}}"""
+        val preview = config.preview(text)
+        assertEquals(listOf("option.A.words"), preview.added)
+        assertEquals(listOf("enabled.A"), preview.changed)
+        assertEquals(1, preview.unchanged)
+        assertEquals(listOf("enabled.Gone"), preview.replacedAway)
+        assertEquals(mapOf<String, Any?>("enabled.A" to true, "ui.theme" to 2, "enabled.Gone" to true), values.stored)
+    }
+
+    @Test fun `a preview of a file for another host is rejected like the import`() {
+        val rejected = assertThrows(ConfigImportRejected::class.java) {
+            config.preview("""{"format":"io.github.nuke.oss.config","version":1,"host":"io.github.nuke.oss.fixture","values":{}}""")
+        }
+        assertEquals(ConfigImportRejected.Reason.OTHER_HOST, rejected.reason)
+        assertEquals("io.github.nuke.oss.fixture", rejected.detail)
+    }
+
     @Test fun `cleaning removes every value and the module's files`() {
         values.stored.putAll(preferences())
         values.stored += mapOf("failure.A" to "record", "setup.safe_mode" to true)
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/AnalyticsRecorderTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/AnalyticsRecorderTest.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/AnalyticsRecorderTest.kt
@@ -0,0 +1,107 @@
+package io.github.nuke.oss.app.tiktok
+
+import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsRecorder
+import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsSink
+import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsVisit
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertTrue
+import org.junit.Test
+import java.time.LocalDateTime
+import java.time.ZoneId
+
+class AnalyticsRecorderTest {
+    private val zone = "UTC"
+    private fun at(day: Int, hour: Int, minute: Int = 0, second: Int = 0) =
+        LocalDateTime.of(2026, 11, day, hour, minute, second).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
+
+    private class Recording : AnalyticsSink {
+        val visits = ArrayList<AnalyticsVisit>()
+        val ranks = ArrayList<Triple<Long, String, String>>()
+        val collection = ArrayList<Boolean>()
+        var closed = false
+        override fun visit(visit: AnalyticsVisit) { visits += visit }
+        override fun rank(day: Long, kind: String, key: String) { ranks += Triple(day, kind, key) }
+        override fun collection(started: Boolean) { collection += started }
+        override fun close() { closed = true }
+    }
+
+    private fun recorder(sink: AnalyticsSink, now: () -> Long) =
+        AnalyticsRecorder(sink, clock = now, zone = { ZoneId.of(zone) })
+
+    @Test fun `a video change closes the previous visit with the playing time`() {
+        val sink = Recording()
+        var now = at(1, 20)
+        val recorder = recorder(sink) { now }
+        recorder.start()
+        recorder.videoChanged("a", "author", "feed")
+        now += 8_000
+        recorder.videoChanged("b", null, "feed")
+        assertEquals(1, sink.visits.size)
+        val visit = sink.visits.single()
+        assertEquals(8_000, visit.visibleMs)
+        assertEquals("a", visit.aweme)
+        assertEquals(20, visit.hour)
+        assertEquals(AnalyticsRecorder.END_CHANGED, visit.endReason)
+        assertEquals(listOf(Triple(visit.day, AnalyticsRecorder.KIND_AUTHOR, "author")), sink.ranks)
+        assertEquals(listOf(true), sink.collection)
+    }
+
+    @Test fun `paused time does not count as visible`() {
+        val sink = Recording()
+        var now = at(2, 9)
+        val recorder = recorder(sink) { now }
+        recorder.start()
+        recorder.videoChanged("a", "author", "feed")
+        now += 5_000
+        recorder.paused()
+        now += 60_000
+        recorder.playing()
+        now += 3_000
+        recorder.stopped()
+        val visit = sink.visits.single()
+        assertEquals(8_000, visit.visibleMs)
+        assertEquals(AnalyticsRecorder.END_CLOSED, visit.endReason)
+        assertEquals(listOf(true, false), sink.collection)
+    }
+
+    @Test fun `a change while the player is paused does not count as playing`() {
+        val sink = Recording()
+        var now = at(4, 10)
+        val recorder = recorder(sink) { now }
+        recorder.start()
+        recorder.videoChanged("a", "author", "feed")
+        now += 4_000
+        recorder.paused()
+        now += 30_000
+        recorder.videoChanged("b", "author", "feed")
+        now += 6_000
+        recorder.stopped()
+        assertEquals(listOf("a" to 4_000L, "b" to 0L), sink.visits.map { it.aweme to it.visibleMs })
+    }
+
+    @Test fun `a video changed before the first play report still counts as playing`() {
+        val sink = Recording()
+        var now = at(5, 11)
+        val recorder = recorder(sink) { now }
+        recorder.videoChanged("a", "author", "feed")
+        now += 7_000
+        recorder.stopped()
+        assertEquals(7_000, sink.visits.single().visibleMs)
+    }
+
+    @Test fun `a video without an id is not recorded`() {        val sink = Recording()
+        val recorder = recorder(sink) { at(3, 12) }
+        recorder.videoChanged(null, "author", "feed")
+        recorder.videoChanged("", "author", "feed")
+        recorder.stopped()
+        assertTrue(sink.visits.isEmpty())
+    }
+
+    @Test fun `the digest is stable and hides the identifier`() {
+        val first = AnalyticsRecorder.digest("7312345678901234567")
+        val second = AnalyticsRecorder.digest("7312345678901234567")
+        assertEquals(first, second)
+        assertEquals(64, first.length)
+        assertTrue("7312345678901234567" !in first)
+    }
+}
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/AnalyticsSummaryTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/AnalyticsSummaryTest.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/AnalyticsSummaryTest.kt
@@ -0,0 +1,24 @@
+package io.github.nuke.oss.app.tiktok
+
+import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsDay
+import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsSummary
+import org.junit.Assert.assertEquals
+import org.junit.Test
+
+class AnalyticsSummaryTest {
+    private fun summary(vararg days: AnalyticsDay) =
+        AnalyticsSummary(0, 0, days.toList(), 0, emptyList())
+
+    @Test fun `day rows are oldest first whatever order the query returned`() {
+        val rows = summary(AnalyticsDay(3, 10, 5_000), AnalyticsDay(1, 4, 1_000), AnalyticsDay(2, 7, 2_000)).dayRows()
+        assertEquals(listOf(1L, 2L, 3L), rows.map { it.day })
+    }
+
+    @Test fun `a summary without days has no rows and no data`() {
+        val empty = summary()
+        assertEquals(emptyList<AnalyticsDay>(), empty.dayRows())
+        assertEquals(false, empty.hasData)
+        assertEquals(0, empty.weekViews)
+        assertEquals(0L, empty.weekVisibleMs)
+    }
+}
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/DownloadVideoWithNameTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/DownloadVideoWithNameTest.kt
new file mode 100644
--- /dev/null
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/DownloadVideoWithNameTest.kt
@@ -0,0 +1,141 @@
+package io.github.nuke.oss.app.tiktok
+
+import io.github.nuke.oss.app.shared.media.DownloadQueue
+import io.github.nuke.oss.app.tiktok.feature.feed.DownloadVideoWithName
+import io.github.nuke.oss.app.tiktok.query.DownloadQueries
+import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
+import io.github.nuke.oss.core.Logger
+import org.junit.After
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+class DownloadVideoWithNameTest : HookHarness() {
+    private val queue = DownloadQueue(1)
+
+    @After fun stopQueue() {
+        queue.close()
+    }
+
+    class Author(@JvmField var nickname: String?)
+    class Address(@JvmField var urlList: List<String>?)
+    class Video(@JvmField var play_addr: Address?) {
+        fun play(): Address? = play_addr
+    }
+
+    class Picture(@JvmField var urlList: List<String>?)
+
+    class Aweme(
+        @JvmField var author: Author? = null,
+        @JvmField var desc: String? = null,
+        @JvmField var aweme_id: String? = "1",
+        @JvmField var video: Video? = null,
+        @JvmField var images: List<Any>? = null,
+    ) {
+        fun getVideo(): Video? = video
+    }
+
+    class Panel(@JvmField var post: Aweme?) {
+        var clicks = 0
+        fun click(view: Any?) { clicks++ }
+        fun downloadAweme(): Aweme? = post
+    }
+
+    class HostView(private val context: Any) {
+        fun getContext(): Any = context
+    }
+
+    private val click = method(Panel::class.java, "click", Any::class.java)
+    private val post = method(Panel::class.java, "downloadAweme")
+    private val play = method(Video::class.java, "play")
+
+    private fun feature(files: RecordingMediaFiles, vararg saved: Pair<String, Any>) = DownloadVideoWithName(
+        resolverOf(
+            DownloadQueries.PANEL_DOWNLOAD_CLICK to click,
+            DownloadQueries.PANEL_AWEME to post,
+            DownloadQueries.VIDEO_PLAY to play,
+        ),
+        loader, { files }, Logger {},
+        values(DownloadVideoWithName.ID, DownloadVideoWithName.OPTIONS, *saved),
+        queue = queue,
+    )
+
+    private fun aweme(video: Video?) = Aweme(
+        author = Author("alice"), desc = "hello world", aweme_id = "42", video = video,
+    )
+
+    @Test fun `the long press save is taken over and the file is named from the template`() {
+        val files = RecordingMediaFiles()
+        enable(feature(files, "template" to "#author#_#aid#", "folder" to "mine"))
+        val panel = Panel(aweme(Video(Address(listOf("https://v/play.mp4")))))
+        backend.call(click, panel, HostView("host"))
+        queue.awaitIdle()
+        assertEquals(0, panel.clicks)
+        val saved = files.saved.single()
+        assertEquals(listOf("https://v/play.mp4"), saved.urls)
+        assertEquals(ImageFolder.VIDEO, saved.folder)
+        assertEquals("alice_42", saved.name)
+    }
+
+    @Test fun `the folder option reaches the writer`() {
+        val files = RecordingMediaFiles()
+        enable(feature(files, "folder" to "../escape"))
+        val panel = Panel(aweme(Video(Address(listOf("https://v/play.mp4")))))
+        backend.call(click, panel, HostView("host"))
+        queue.awaitIdle()
+        assertEquals(ImageFolder.VIDEO, files.saved.single().folder)
+        assertEquals("alice_hello_world", files.saved.single().name)
+    }
+
+    @Test fun `a post without a video keeps the host behavior`() {
+        val files = RecordingMediaFiles()
+        enable(feature(files))
+        val panel = Panel(aweme(null))
+        backend.call(click, panel, HostView("host"))
+        queue.awaitIdle()
+        assertEquals(1, panel.clicks)
+        assertTrue(files.saved.isEmpty())
+    }
+
+    @Test fun `a video without a playable address keeps the host behavior`() {
+        val files = RecordingMediaFiles()
+        enable(feature(files))
+        val panel = Panel(aweme(Video(Address(null))))
+        backend.call(click, panel, HostView("host"))
+        queue.awaitIdle()
+        assertEquals(1, panel.clicks)
+        assertTrue(files.saved.isEmpty())
+    }
+
+    @Test fun `an image post saves every picture with its index in the name`() {
+        val files = RecordingMediaFiles()
+        enable(feature(files, "template" to "#author#_#desc#"))
+        val post = aweme(null).apply {
+            images = listOf(Picture(listOf("https://v/1.png")), Picture(listOf("https://v/2.png")))
+        }
+        backend.call(click, Panel(post), HostView("host"))
+        queue.awaitIdle()
+        assertEquals(listOf("https://v/1.png", "https://v/2.png"), files.saved.map { it.urls.single() })
+        assertEquals(listOf("alice_hello_world_01", "alice_hello_world_02"), files.saved.map { it.name })
+        assertEquals(listOf(ImageFolder.IMAGE, ImageFolder.IMAGE), files.saved.map { it.folder })
+    }
+
+    @Test fun `a post without a video or pictures keeps the host behavior`() {
+        val files = RecordingMediaFiles()
+        enable(feature(files))
+        val panel = Panel(aweme(null))
+        backend.call(click, panel, HostView("host"))
+        queue.awaitIdle()
+        assertEquals(1, panel.clicks)
+        assertTrue(files.saved.isEmpty())
+    }
+
+    @Test fun `a base64 address is decoded before the download`() {
+        val files = RecordingMediaFiles()
+        enable(feature(files, "template" to "#desc#"))
+        val encoded = java.util.Base64.getEncoder().encodeToString("https://v/from-b64.mp4".toByteArray())
+        backend.call(click, Panel(aweme(Video(Address(listOf(encoded))))), HostView("host"))
+        queue.awaitIdle()
+        assertEquals(listOf("https://v/from-b64.mp4"), files.saved.single().urls)
+    }
+}
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/FakeHostUi.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/FakeHostUi.kt
--- a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/FakeHostUi.kt
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/FakeHostUi.kt
@@ -264,15 +264,30 @@
 }
 
 internal class RecordingMediaFiles : io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles {
-    class Saved(val urls: List<String>, val audio: Boolean, val folder: io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder)
+    class Saved(val urls: List<String>, val audio: Boolean, val folder: io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder, val name: String?)
     val saved = ArrayList<Saved>()
     val converted = ArrayList<java.io.File>()
     var needsPermission = false
     var failure: IllegalStateException? = null
     override fun requestStoragePermission(activity: Any) = needsPermission
-    override fun save(context: Any, urls: List<String>, audio: Boolean, folder: io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder) {
+    override fun save(
+        context: Any,
+        urls: List<String>,
+        audio: Boolean,
+        folder: io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder,
+        name: String?,
+        progress: (bytes: Long, total: Long) -> Unit,
+        cancelled: () -> Boolean,
+        tree: String?,
+    ) {
         failure?.let { throw it }
-        saved += Saved(urls, audio, folder)
+        saved += Saved(urls, audio, folder, name)
+        progress(1, 1)
+    }
+    class SavedFile(val file: java.io.File, val audio: Boolean, val folder: io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder, val name: String?)
+    val files = ArrayList<SavedFile>()
+    override fun saveFile(context: Any, file: java.io.File, audio: Boolean, folder: io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder, name: String?) {
+        files += SavedFile(file, audio, folder, name)
     }
     val texts = ArrayList<Pair<String, String>>()
     var textFailure = false
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/MediaFeaturesTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/MediaFeaturesTest.kt
--- a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/MediaFeaturesTest.kt
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/MediaFeaturesTest.kt
@@ -4,6 +4,7 @@
 import com.ss.android.ugc.aweme.feed.share.video.SharePrivacyVideoApi
 import com.ss.android.ugc.awemepushapi.FailedType
 import io.github.nuke.oss.R
+import io.github.nuke.oss.app.shared.settings.OptionValues
 import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
 import io.github.nuke.oss.app.tiktok.feature.feed.AllowFeedDownloads
 import io.github.nuke.oss.app.tiktok.feature.feed.BypassListenFilter
@@ -122,7 +123,11 @@
 
     @Test fun `the video address is the playable one when the aweme has a video`() {
         val choose = method(Chooser::class.java, "choose", Aweme::class.java)
-        enable(DownloadOriginalVideo(resolver(DownloadQueries.VIDEO_ADDRESS to listOf(choose), DownloadQueries.VIDEO_PLAY to listOf(method(Video::class.java, "play"))), loader))
+        enable(DownloadOriginalVideo(
+            resolver(DownloadQueries.VIDEO_ADDRESS to listOf(choose), DownloadQueries.VIDEO_PLAY to listOf(method(Video::class.java, "play"))),
+            loader,
+            OptionValues.defaults(DownloadOriginalVideo.OPTIONS),
+        ))
         assertEquals("play", backend.call(choose, Chooser(), Aweme(Video())))
         assertEquals("original", backend.call(choose, Chooser(), Aweme(null)))
     }
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/StreakEngineTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/StreakEngineTest.kt
--- a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/StreakEngineTest.kt
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/StreakEngineTest.kt
@@ -104,6 +104,26 @@
         assertTrue(sends.sent.isEmpty())
     }
 
+    @Test fun `a friend the user already wrote to today is skipped`() {
+        val store = MemoryStore(StreakState("1"))
+        val engine = StreakEngine("1", store)
+        val sends = Sends()
+        engine.adopt(plan(), at(1, 8))
+        engine.tick(plan(), at(1, 19, 1), { true }, { at(1, 19, 1) }, { PreparedMessage(it.text) },
+            skip = { uid, _ -> uid == "11" }) { uid, text, _ ->
+            sends.sent += uid to text
+            StreakOutcome(StreakStatus.ACKNOWLEDGED)
+        }
+        assertEquals(listOf("22" to "hi"), sends.sent)
+        assertEquals(listOf("22"), store.state.history.map { it.uid })
+    }
+
+    @Test fun `a chat the user wrote to today does not keep the worker awake`() {
+        val engine = StreakEngine("1", MemoryStore(StreakState("1")))
+        engine.adopt(plan(setOf("11")), at(1, 8))
+        assertEquals(at(2, 19), engine.nextWakeAt(plan(setOf("11")), at(1, 19, 1)) { _, _ -> true })
+    }
+
     @Test fun `a claim left by an ended process reads back as unknown`() {
         val attempt = StreakAttempt("a", "11", 0, 0, StreakStatus.CLAIMED, StreakMode.TEXT)
         val store = MemoryStore(StreakState("1", history = listOf(attempt)))
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -20,6 +20,7 @@
 kavaref = "1.1.1"
 quickjsKt = "1.0.15"
 markdownRenderer = "0.45.0"
+jump3r = "1.0.5"
 
 [plugins]
 android-application = { id = "com.android.application", version.ref = "agp" }
@@ -43,6 +44,7 @@
 quickjs-kt = { module = "io.github.dokar3:quickjs-kt", version.ref = "quickjsKt" }
 markdown-renderer-m3 = { module = "com.mikepenz:multiplatform-markdown-renderer-m3", version.ref = "markdownRenderer" }
 markdown-renderer-code = { module = "com.mikepenz:multiplatform-markdown-renderer-code", version.ref = "markdownRenderer" }
+jump3r = { module = "de.sciss:jump3r", version.ref = "jump3r" }
 activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity" }
 lifecycle-viewmodel = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
 lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }

```
