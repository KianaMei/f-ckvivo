package io.github.nuke.oss.app.tiktok.feature.analytics

import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Writes the feed's video changes and playback into a local SQLite file, so the settings screen can show how much this
// device watched and which authors, without asking the host and without sending anything anywhere. Identifiers are
// hashed before they reach the database. Off by default; turning the switch off closes the visit and records the
// boundary, so the time it was off is not read as a day without videos.
@HookEntry
internal class LocalAnalytics(
    private val events: FeedEvents,
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : RuntimeFeature {
    override val id = ID
    private val awemeId = NamedFieldAlias("aid", "aweme_id")
    private val author = NamedCall("getAuthor")
    private val authorId = NamedFieldAlias("uid", "sec_uid")

    override fun install(scope: FeatureScope) {
        val sink = AsyncAnalyticsSink(SqliteAnalyticsSink(File(directory, DATABASE), clock))
        val recorder = AnalyticsRecorder(sink, clock, zone)
        recorder.start()
        scope.own(AutoCloseable {
            recorder.stopped()
            sink.drain()
        })
        events.onVideoChanged(scope, ID) { aweme ->
            recorder.videoChanged(hash(awemeId, aweme), hash(authorId, author.of(aweme)?.invoke(aweme)), SOURCE_FEED)
        }
        events.onPlayback(scope, ID, onPlay = recorder::playing, onPause = recorder::paused)
    }

    private fun hash(field: NamedFieldAlias, owner: Any?): String? {
        if (owner == null) return null
        val value = field.of(owner)?.get(owner)?.toString()?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
        return AnalyticsRecorder.digest(value)
    }

    companion object : TikTokFeature {
        const val ID = "LocalAnalytics"
        private const val DATABASE = "analytics.db"
        private const val SOURCE_FEED = "feed"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(LocalAnalytics(events, File(featureFiles, ID)))
        }
    }
}

// The host calls the recorder on its own threads, so the statements run on one analytics thread and a feed scroll never
// waits for the database. drain writes what is still queued when the feature is turned off.
internal class AsyncAnalyticsSink(private val delegate: AnalyticsSink) : AnalyticsSink {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuke-analytics").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    override fun visit(visit: AnalyticsVisit) = submit { delegate.visit(visit) }
    override fun rank(day: Long, kind: String, key: String) = submit { delegate.rank(day, kind, key) }
    override fun action(day: Long, feature: String, stage: String) = submit { delegate.action(day, feature, stage) }
    override fun collection(day: Long, started: Boolean) = submit { delegate.collection(day, started) }
    override fun close() = submit { delegate.close() }

    fun drain() {
        if (closed) return
        closed = true
        try {
            executor.execute {
                try {
                    delegate.close()
                } catch (_: RuntimeException) {
                }
            }
        } catch (_: RuntimeException) {
        }
        executor.shutdown()
        try {
            executor.awaitTermination(DRAIN_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun submit(job: () -> Unit) {
        if (closed) return
        try {
            executor.execute {
                try {
                    job()
                } catch (_: RuntimeException) {
                }
            }
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        const val DRAIN_MILLIS = 2_000L
    }
}
