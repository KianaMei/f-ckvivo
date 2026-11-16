# nuke/src 相对上游基准的全部改动

* 基准：`dartcv/NukeApp-OSS` `dev` @ `22d272d5a5f25e23b4afa4a6e30832ebbac54ba2`
* 对照方式：解开该提交的 tarball，与 `nuke/src` 比对（`core.autocrlf=false`，无换行噪声）
* 结果：**15 个文件修改 + 4 个新增**

## 已实现

| 主题 | 文件 | 内容 |
|---|---|---|
| 续火跳过 | `streak/SelfSpeech.kt`(新)、`streak/StreakEngine.kt`、`streak/AutoStreak.kt`、`StreakEngineTest.kt` | 今天我方已在某会话发言 → 该好友不写 claim、不发送；`nextWakeAt` 同步排除（防每秒空转）；2 条单测 |
| 下载来源 | `feed/DownloadOriginals.kt`、`query/DownloadQueries.kt`、`hostsettings/HostFeatureLabels.kt`、`values*/host_settings_strings.xml`、`MediaFeaturesTest.kt` | `source` 选项：auto / play / h264 / h265 / download；只解析所选那一路（缺流回退播放地址），`download` 完全不介入 |
| 本地分析（采集） | `analytics/AnalyticsSink.kt`、`AnalyticsRecorder.kt`、`SqliteAnalyticsSink.kt`、`LocalAnalytics.kt`、`AnalyticsRecorderTest.kt` | SQLite：visits / daily / hours / ranks / actions / collection；可见时长只算播放中；标识先哈希；后台线程写；默认关闭；4 条单测 |
| 本地分析（查看） | `analytics/AnalyticsReport.kt`、`hostsettings/AnalyticsDialog.kt`、`HostSettingsScreen.kt`、`values*/host_settings_strings.xml` | 设置页点该功能打开报告：今天/近 7 天条数与可见时长、作者数、最常刷的 3 个时段；只读本地库 |

## 验证

* `:apps:module:compileDebugKotlin` 通过（本机无 android-37，用 compileSdk 36 的临时副本验证，源码未改）。
* 新增单测 6 条全部通过；整包单测失败集合与上游基线**完全一致**（47 条，全部因本机缺 Rust 原生库）。
* 相对基准的 diff 只含上面这些文件，`gradlew.bat` 保持上游 CRLF。

## 完整 diff

```diff
diff --git a/UPSTREAM.md b/UPSTREAM.md
new file mode 100644
index 0000000..00582ea
--- /dev/null
+++ b/UPSTREAM.md
@@ -0,0 +1,36 @@
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
+| `feature/chat/streak/SelfSpeech.kt`（新） | 每账号 `speech.dat`：`本地日 → 会话ID`，留 7 天 | 记住"今天我在这个会话说过话" |
+| `feature/chat/streak/StreakEngine.kt` | `tick` / `nextWakeAt` 增加 `skip` | 已发言的好友不写 claim、不发送；不算 pending（否则每秒空转） |
+| `feature/chat/streak/AutoStreak.kt` | 观察聊天行、uid↔会话映射、跳过日志 | 用已有 `ChatAdapterEvents` 记录我方发言 |
+| `test/.../StreakEngineTest.kt` | 新增 2 条用例 | 跳过后不写 claim；全跳过时 `nextWakeAt` 落次日 |
+| `feature/feed/DownloadOriginals.kt` | `DownloadOriginalVideo` 增加 `source` 选项 | auto / play / h264 / h265 / download；只解析所选那一路 |
+| `query/DownloadQueries.kt` | 新增 3 个字段查询 | `play_addr` / `play_addr_h264` / `play_addr_265` |
+| `ui/hostsettings/HostFeatureLabels.kt` | 选项标签 + 新功能标签 | 设置页显示 |
+| `res/values*/host_settings_strings.xml` | 新增文案（中/英） | 同上 |
+| `feature/analytics/AnalyticsSink.kt`（新） | 接收端接口与 `AnalyticsVisit` | 与存储解耦，可单测 |
+| `feature/analytics/AnalyticsRecorder.kt`（新） | 观看会话的开启/关闭/可见时长/哈希 | 纯逻辑，可单测 |
+| `feature/analytics/SqliteAnalyticsSink.kt`（新） | SQLite 表：visits / daily / hours / ranks / actions / collection | 本地账本，失败静默 |
+| `feature/analytics/LocalAnalytics.kt`（新） | `@HookEntry` 功能，默认关闭；接 `FeedEvents` 的视频切换与播放/暂停 | 采集入口 |
+| `test/.../AnalyticsRecorderTest.kt`（新） | 4 条用例 | 时长、暂停不计、无 id 不记、哈希稳定 |
+| `test/.../MediaFeaturesTest.kt` | 适配 `DownloadOriginalVideo` 新构造参数 | 保持既有用例可编译 |
+
+## 约定
+
+* 改动直接改这里的文件，按功能说明写在提交信息里；这里没有 `patches/` 之类的东西。
+* 本目录**未提交**到 ganvivo 仓库时，改动只存在于工作区（`git status` 可见），不要 `git clean -xdf`。
+* 上游更新（可选）：把上游新版本单独克隆到临时目录，与本目录 `diff` 后手工并回来；**不要**把上游 `.git` 引入本目录。
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsRecorder.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsRecorder.kt
new file mode 100644
index 0000000..49e404d
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsRecorder.kt
@@ -0,0 +1,88 @@
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
+    private var playingSince: Long? = null
+
+    // The feature is on: everything from here is tracked.
+    fun start() {
+        sink.collection(day(clock()), true)
+    }
+
+    fun videoChanged(aweme: String?, author: String?, source: String) {
+        finish(END_CHANGED)
+        val key = aweme?.takeIf(String::isNotEmpty) ?: return
+        val now = clock()
+        open = Open(key, author.orEmpty(), source, now)
+        playingSince = now
+    }
+
+    fun playing() {
+        if (open != null && playingSince == null) playingSince = clock()
+    }
+
+    fun paused() {
+        playingSince?.let { since -> open?.let { it.visibleMs += (clock() - since).coerceAtLeast(0) } }
+        playingSince = null
+    }
+
+    // A feature-triggered action, kept as a count per feature so the settings screen can show what is used.
+    fun action(feature: String, stage: String = "run") {
+        sink.action(day(clock()), feature, stage)
+    }
+
+    // The feature is off or the host process ends: close the visit and stop counting.
+    fun stopped(reason: String = END_CLOSED) {
+        finish(reason)
+        sink.collection(day(clock()), false)
+    }
+
+    fun close() {
+        finish(END_CLOSED)
+        sink.close()
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
+    private fun day(now: Long): Long = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toEpochDay()
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
index 0000000..f3e98d4
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsReport.kt
@@ -0,0 +1,54 @@
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
index 0000000..078bef1
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/AnalyticsSink.kt
@@ -0,0 +1,24 @@
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
+    fun action(day: Long, feature: String, stage: String)
+    // A switch being turned on or off, so a gap in the data can be told apart from an untracked period.
+    fun collection(day: Long, started: Boolean)
+    fun close()
+}
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/LocalAnalytics.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/LocalAnalytics.kt
new file mode 100644
index 0000000..2cd6d6f
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/LocalAnalytics.kt
@@ -0,0 +1,113 @@
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
+    override fun action(day: Long, feature: String, stage: String) = submit { delegate.action(day, feature, stage) }
+    override fun collection(day: Long, started: Boolean) = submit { delegate.collection(day, started) }
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
index 0000000..23f8160
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/analytics/SqliteAnalyticsSink.kt
@@ -0,0 +1,75 @@
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
+            db.execSQL("CREATE TABLE IF NOT EXISTS actions(id INTEGER PRIMARY KEY AUTOINCREMENT, day INTEGER NOT NULL, feature TEXT NOT NULL, stage TEXT NOT NULL, ts INTEGER NOT NULL)")
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
+    override fun action(day: Long, feature: String, stage: String) = write { db ->
+        db.execSQL("INSERT INTO actions(day, feature, stage, ts) VALUES(?, ?, ?, ?)", arrayOf<Any>(day, feature, stage, clock()))
+    }
+
+    override fun collection(day: Long, started: Boolean) = write { db ->
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
index c8d9913..b3722a1 100644
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
@@ -19,26 +21,57 @@ import io.github.nuke.oss.runtime.FeatureScope
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
 
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/AnalyticsDialog.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/AnalyticsDialog.kt
new file mode 100644
index 0000000..dab3e84
--- /dev/null
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/AnalyticsDialog.kt
@@ -0,0 +1,72 @@
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
+import androidx.compose.runtime.setValue
+import androidx.compose.ui.Modifier
+import androidx.compose.ui.res.stringResource
+import androidx.compose.ui.unit.dp
+import io.github.nuke.oss.R
+import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsReport
+import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsSummary
+import io.github.nuke.oss.designsystem.NukeDialog
+import io.github.nuke.oss.designsystem.NukeDialogTextButton
+import java.io.File
+import java.time.ZoneId
+import kotlinx.coroutines.Dispatchers
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
+    LaunchedEffect(directory) {
+        summary = withContext(Dispatchers.IO) {
+            directory?.let { AnalyticsReport.read(File(it, DATABASE), ZoneId.systemDefault()) }
+        }
+        loaded = true
+    }
+    NukeDialog(
+        visible = true,
+        onDismissRequest = onDismiss,
+        title = title,
+        actions = {
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
+                }
+            }
+        }
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
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt
index f1b52fb..c2500cb 100644
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt
@@ -105,6 +105,7 @@ internal val featureLabels = mapOf(
     "McpServer" to FeatureLabel(R.string.host_feature_mcp_server, R.string.host_feature_mcp_server_description, FeatureCategory.SYSTEM),
     "HideStatusBar" to FeatureLabel(R.string.host_feature_status_bar, R.string.host_feature_status_bar_description, FeatureCategory.LAYOUT),
     "LongVideoNotice" to FeatureLabel(R.string.host_feature_long_video, R.string.host_feature_long_video_description, FeatureCategory.FEED),
+    "LocalAnalytics" to FeatureLabel(R.string.host_feature_local_analytics, R.string.host_feature_local_analytics_description, FeatureCategory.WELLBEING),
     "TimedClose" to FeatureLabel(R.string.host_feature_timed_close, R.string.host_feature_timed_close_description, FeatureCategory.WELLBEING),
     "ProgressTime" to FeatureLabel(R.string.host_feature_progress_time, R.string.host_feature_progress_time_description, FeatureCategory.PLAYBACK),
     "AllowAnyForward" to FeatureLabel(R.string.host_feature_any_forward, R.string.host_feature_any_forward_description, FeatureCategory.SHARING),
@@ -187,6 +188,14 @@ internal val optionLabels = mapOf<String, OptionLabel>(
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
diff --git a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsScreen.kt b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsScreen.kt
index c5ac75c..dd28bf6 100644
--- a/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsScreen.kt
+++ b/apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostSettingsScreen.kt
@@ -94,7 +94,7 @@ private const val SCRIPTS = "scripts"
 // Not a page: this tile opens the import and export dialog.
 private const val CONFIG = "config"
 // Features without options whose row still opens a dialog of their own.
-private val DIALOG_FEATURES = setOf(PROTOCOL_SENDER, MCP_SERVER)
+private val DIALOG_FEATURES = setOf(PROTOCOL_SENDER, MCP_SERVER, LOCAL_ANALYTICS)
 
 // The feature switch screen that runs inside the host process. The query is local: it only filters this list.
 @Composable
@@ -218,6 +218,12 @@ internal fun HostSettingsScreen(
                     onDismiss = { optionsFor = null },
                     loadContacts = loadContacts,
                 )
+            } else if (editing.id == LOCAL_ANALYTICS) {
+                AnalyticsDialog(
+                    directory = featureFiles?.let { java.io.File(it, LOCAL_ANALYTICS) },
+                    title = title,
+                    onDismiss = { optionsFor = null },
+                )
             } else if (editing.id == AUTO_STREAK) {
                 AutoStreakDialog(
                     feature = editing,
diff --git a/apps/module/src/main/res/values-zh/host_settings_strings.xml b/apps/module/src/main/res/values-zh/host_settings_strings.xml
index 7efdc96..d26995c 100644
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
diff --git a/apps/module/src/main/res/values/host_settings_strings.xml b/apps/module/src/main/res/values/host_settings_strings.xml
index 506c7ce..75e9566 100644
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
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/AnalyticsRecorderTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/AnalyticsRecorderTest.kt
new file mode 100644
index 0000000..aebf92f
--- /dev/null
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/AnalyticsRecorderTest.kt
@@ -0,0 +1,85 @@
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
+        val actions = ArrayList<Pair<String, String>>()
+        val collection = ArrayList<Boolean>()
+        var closed = false
+        override fun visit(visit: AnalyticsVisit) { visits += visit }
+        override fun rank(day: Long, kind: String, key: String) { ranks += Triple(day, kind, key) }
+        override fun action(day: Long, feature: String, stage: String) { actions += feature to stage }
+        override fun collection(day: Long, started: Boolean) { collection += started }
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
+    @Test fun `a video without an id is not recorded`() {
+        val sink = Recording()
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
diff --git a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/MediaFeaturesTest.kt b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/MediaFeaturesTest.kt
index 06233ee..348d5ad 100644
--- a/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/MediaFeaturesTest.kt
+++ b/apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/MediaFeaturesTest.kt
@@ -4,6 +4,7 @@ import com.ss.android.ugc.aweme.feed.model.VideoItemParams
 import com.ss.android.ugc.aweme.feed.share.video.SharePrivacyVideoApi
 import com.ss.android.ugc.awemepushapi.FailedType
 import io.github.nuke.oss.R
+import io.github.nuke.oss.app.shared.settings.OptionValues
 import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
 import io.github.nuke.oss.app.tiktok.feature.feed.AllowFeedDownloads
 import io.github.nuke.oss.app.tiktok.feature.feed.BypassListenFilter
@@ -122,7 +123,11 @@ class MediaFeaturesTest : HookHarness() {
 
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
