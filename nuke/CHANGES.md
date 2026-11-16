# nuke/src 相对上游基准的全部改动

* 基准：`dartcv/NukeApp-OSS` `dev` @ `22d272d5a5f25e23b4afa4a6e30832ebbac54ba2`
* 对照方式：解开该提交的 tarball，与 `nuke/src` 比对（`core.autocrlf=false`，无换行噪声）
* 结果：**8 个文件修改 + 1 个新增**（另含 `UPSTREAM.md`），244 行新增 / 16 行删除

| 主题 | 文件 | 内容 |
|---|---|---|
| 续火跳过 | `AutoStreak.kt`、`StreakEngine.kt`、`SelfSpeech.kt`(新)、`StreakEngineTest.kt` | 用户今天已在某会话发过言 → 不写 claim、不发送；`nextWakeAt` 同步排除，避免每秒空转；两条单测 |
| 下载来源 | `DownloadOriginals.kt`、`DownloadQueries.kt`、`HostFeatureLabels.kt`、`values/host_settings_strings.xml`、`values-zh/host_settings_strings.xml` | `source` 选项：auto / play / h264 / h265 / download；缺流回退播放地址；设置页中英文案 |
| 说明 | `UPSTREAM.md`(新) | 基准提交与我们改过的文件清单 |

完整 diff（只含上述 9 个文件；`gradlew.bat` 保持上游 CRLF 原样，不计入改动）：

```diff
diff --git a/UPSTREAM.md b/UPSTREAM.md
new file mode 100644
index 0000000..ed4196f
--- /dev/null
+++ b/UPSTREAM.md
@@ -0,0 +1,30 @@
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
+| `apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/SelfSpeech.kt` | 新增 | 每账号一份 `speech.dat`：`本地日 → 会话ID`，最多留 7 天 |
+| `apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/StreakEngine.kt` | `tick` / `nextWakeAt` 增加 `skip` | 用户今天已在某会话发过言 → 不写 claim、不发送；同时不再把它算作 pending（否则 worker 每秒空转） |
+| `apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/AutoStreak.kt` | 观察聊天行 + uid↔会话映射 + 跳过日志 | 用已有 `ChatAdapterEvents` 记录「我方今日发言」；`ContactQueries.SINGLE_CONVERSATION_ID` 映射 uid |
+| `apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/StreakEngineTest.kt` | 新增两条用例 | 跳过后不写 claim；全跳过时 `nextWakeAt` 落次日 |
+| `apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/query/DownloadQueries.kt` | 新增 3 个字段查询 | `play_addr` / `play_addr_h264` / `play_addr_265` |
+| `apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadOriginals.kt` | `DownloadOriginalVideo` 增加 `source` 选项 | 下载来源：auto / play / h264 / h265 / download；缺流回退播放地址 |
+| `apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt` | 选项标签 | 设置页显示下载来源 |
+| `apps/module/src/main/res/values/host_settings_strings.xml`、`values-zh/…` | 新增文案 | 同上（中/英） |
+
+## 约定
+
+* 改动直接改这里的文件，按功能说明写在提交信息里；这里没有 `patches/` 之类的东西。
+* 本目录**未提交**到 ganvivo 仓库时，改动只存在于工作区（`git status` 可见），不要 `git clean -xdf`。
+* 上游更新（可选）：把上游新版本单独克隆到临时目录，与本目录 `diff` 后手工并回来；**不要**把上游 `.git` 引入本目录。
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/AutoStreak.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/AutoStreak.kt
index 56fa717..3d0b3ab 100644
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/AutoStreak.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/AutoStreak.kt
@@ -5,6 +5,7 @@ import io.github.nuke.oss.app.shared.dex.DexResolver
 import io.github.nuke.oss.app.shared.dex.UsesDexQueries
 import io.github.nuke.oss.app.shared.dex.methods
 import io.github.nuke.oss.app.shared.registry.HookEntry
+import io.github.nuke.oss.app.shared.reflect.NamedCall
 import io.github.nuke.oss.app.shared.settings.ActivityRecord
 import io.github.nuke.oss.app.shared.settings.ActivityStatus
 import io.github.nuke.oss.app.shared.settings.ChoiceOption
@@ -15,7 +16,9 @@ import io.github.nuke.oss.app.shared.settings.TextOption
 import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
 import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
 import io.github.nuke.oss.app.tiktok.feature.comment.CustomCommentStyle
+import io.github.nuke.oss.app.tiktok.query.ContactQueries
 import io.github.nuke.oss.app.tiktok.query.StreakQueries
+import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
 import io.github.nuke.oss.app.tiktok.sdk.chat.ChatSender
 import io.github.nuke.oss.app.tiktok.sdk.chat.SendResult
 import io.github.nuke.oss.core.Logger
@@ -23,6 +26,7 @@ import io.github.nuke.oss.runtime.FeatureScope
 import io.github.nuke.oss.runtime.RuntimeFeature
 import java.io.File
 import java.security.MessageDigest
+import java.time.Instant
 import java.time.ZoneId
 
 // Keeps chat streaks going: once a day, from the chosen time, sends one message to each chosen friend through the
@@ -30,11 +34,14 @@ import java.time.ZoneId
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
@@ -46,9 +53,21 @@ internal class AutoStreak(
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
@@ -68,8 +87,14 @@ internal class AutoStreak(
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
@@ -77,7 +102,40 @@ internal class AutoStreak(
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
@@ -101,8 +159,10 @@ internal class AutoStreak(
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
@@ -159,7 +219,7 @@ internal class AutoStreak(
         private const val SEND_TIMEOUT_MILLIS = 30_000L
 
         override fun create(services: TikTokFeatureServices) = with(services) {
-            listOf(AutoStreak(resolver, hostClassLoader, options(ID, OPTIONS), logger, streakDirectory, CustomCommentStyle.accountUserId(hostClassLoader, logger)))
+            listOf(AutoStreak(resolver, hostClassLoader, chatRows, options(ID, OPTIONS), logger, streakDirectory, CustomCommentStyle.accountUserId(hostClassLoader, logger)))
         }
 
         // The sends of the signed-in account, newest first, for the settings screen; empty without an account or before
@@ -186,10 +246,14 @@ internal class AutoStreak(
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
index 0000000..f382cee
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
index 4681e70..8137123 100644
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/StreakEngine.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/StreakEngine.kt
@@ -62,10 +62,12 @@ internal class StreakEngine(private val account: String, private val store: Stre
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
@@ -78,12 +80,17 @@ internal class StreakEngine(private val account: String, private val store: Stre
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
@@ -101,6 +108,9 @@ internal class StreakEngine(private val account: String, private val store: Stre
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
index c8d9913..aa39e0b 100644
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadOriginals.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadOriginals.kt
@@ -7,6 +7,8 @@ import io.github.nuke.oss.app.shared.dex.methods
 import io.github.nuke.oss.app.shared.reflect.NamedCall
 import io.github.nuke.oss.app.shared.reflect.NamedField
 import io.github.nuke.oss.app.shared.registry.HookEntry
+import io.github.nuke.oss.app.shared.settings.ChoiceOption
+import io.github.nuke.oss.app.shared.settings.OptionValues
 import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
 import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
 import io.github.nuke.oss.app.tiktok.query.DownloadQueries
@@ -19,26 +21,60 @@ import io.github.nuke.oss.runtime.FeatureScope
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
+    override val dexQueries = listOf(
+        DownloadQueries.VIDEO_ADDRESS, DownloadQueries.VIDEO_PLAY,
+        DownloadQueries.VIDEO_PLAY_ADDR, DownloadQueries.VIDEO_PLAY_H264, DownloadQueries.VIDEO_PLAY_H265,
+    )
     private val getVideo = NamedCall("getVideo")
+    private val source = options.choice(SOURCE)
 
     override fun install(scope: FeatureScope) {
         val (address, play) = resolver.methods(hostClassLoader, DownloadQueries.VIDEO_ADDRESS, DownloadQueries.VIDEO_PLAY)
+        val (plain, h264, h265) = resolver.fields(
+            hostClassLoader, DownloadQueries.VIDEO_PLAY_ADDR, DownloadQueries.VIDEO_PLAY_H264, DownloadQueries.VIDEO_PLAY_H265,
+        )
         address.isAccessible = true
         play.isAccessible = true
+        listOf(plain, h264, h265).forEach { it.isAccessible = true }
         scope.hooks.install(address, HookSpec(id = "PlayAddress", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
+            // "download" is the host's own address: the hook stays out of the way entirely.
+            if (source == SOURCE_DOWNLOAD) return@HookSpec
             val aweme = call.argument(0) ?: return@HookSpec
             val video = getVideo.of(aweme)?.invoke(aweme) ?: return@HookSpec
-            play.invoke(video)?.let(call::returnEarly)
+            val chosen = when (source) {
+                SOURCE_PLAY -> plain.get(video)
+                SOURCE_H264 -> h264.get(video)
+                SOURCE_H265 -> h265.get(video)
+                else -> null
+            }
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
 
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/query/DownloadQueries.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/query/DownloadQueries.kt
index e3739d3..a4f073e 100644
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/query/DownloadQueries.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/query/DownloadQueries.kt
@@ -54,6 +54,10 @@ internal object DownloadQueries {
     }
 
     val VIDEO_DOWNLOAD = videoField("download_addr")
+    // The three playable addresses a download can be taken from, read straight from their serialized fields.
+    val VIDEO_PLAY_ADDR = videoField("play_addr")
+    val VIDEO_PLAY_H264 = videoField("play_addr_h264")
+    val VIDEO_PLAY_H265 = videoField("play_addr_265")
     val VIDEO_WATERMARK = videoField("has_watermark")
     val VIDEO_SUFFIX_WATERMARK = videoField("has_download_suffix_logo_addr")
 
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt
index f1b52fb..74eec36 100644
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt
@@ -187,6 +187,14 @@ internal val optionLabels = mapOf<String, OptionLabel>(
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
     "CustomPlaybackSpeed/speed" to OptionLabel(
         R.string.host_option_speed, R.string.host_option_speed_description, mapOf("host" to R.string.host_choice_speed_host),
     ),
diff --git a/apps/module/src/main/res/values-zh/host_settings_strings.xml b/apps/module/src/main/res/values-zh/host_settings_strings.xml
index 7efdc96..26b3f80 100644
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
diff --git a/apps/module/src/main/res/values/host_settings_strings.xml b/apps/module/src/main/res/values/host_settings_strings.xml
index 506c7ce..4740d8a 100644
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
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/StreakEngineTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/StreakEngineTest.kt
index dd082ca..afb6aef 100644
--- a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/StreakEngineTest.kt
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/StreakEngineTest.kt
@@ -104,6 +104,26 @@ class StreakEngineTest {
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
```
