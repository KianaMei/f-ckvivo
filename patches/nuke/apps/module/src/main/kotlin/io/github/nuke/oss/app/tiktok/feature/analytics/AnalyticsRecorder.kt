package io.github.nuke.oss.app.tiktok.feature.analytics

import java.time.Instant
import java.time.ZoneId
import java.security.MessageDigest

// Turns the feed's events into finished visits: a video change or the feature stopping closes the one before it, and
// only the time the video was actually playing counts as visible. Everything here runs on the host's thread, so it does
// no I/O of its own; the sink decides where the rows go.
internal class AnalyticsRecorder(
    private val sink: AnalyticsSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private class Open(val aweme: String, val author: String, val source: String, val startedAt: Long) {
        var visibleMs = 0L
    }

    private var open: Open? = null
    // Null until the host reports its first play or pause, so a feed that never reports one keeps the old behaviour.
    private var playing: Boolean? = null
    private var playingSince: Long? = null

    // The feature is on: everything from here is tracked.
    fun start() {
        sink.collection(true)
    }

    fun videoChanged(aweme: String?, author: String?, source: String) {
        finish(END_CHANGED)
        val key = aweme?.takeIf(String::isNotEmpty) ?: return
        val now = clock()
        open = Open(key, author.orEmpty(), source, now)
        // Only the time a video is actually playing counts: a change while the player is paused starts at zero and
        // waits for the play report instead of counting from the change.
        playingSince = if (playing == false) null else now
    }

    fun playing() {
        playing = true
        if (open != null && playingSince == null) playingSince = clock()
    }

    fun paused() {
        playing = false
        playingSince?.let { since -> open?.let { it.visibleMs += (clock() - since).coerceAtLeast(0) } }
        playingSince = null
    }

    // The feature is off or the host process ends: close the visit and stop counting.
    fun stopped(reason: String = END_CLOSED) {
        finish(reason)
        sink.collection(false)
    }

    private fun finish(reason: String) {
        val visit = open ?: return
        val now = clock()
        playingSince?.let { visit.visibleMs += (now - it).coerceAtLeast(0) }
        playingSince = null
        open = null
        val start = Instant.ofEpochMilli(visit.startedAt).atZone(zone())
        val startDay = start.toLocalDate().toEpochDay()
        sink.visit(AnalyticsVisit(startDay, start.hour, visit.aweme, visit.author, visit.source, visit.visibleMs, now, reason))
        if (visit.author.isNotEmpty()) sink.rank(startDay, KIND_AUTHOR, visit.author)
    }

    companion object {
        const val KIND_AUTHOR = "author"
        const val END_CHANGED = "changed"
        const val END_CLOSED = "closed"

        private val DIGEST = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        // Identifiers never reach the database as they are; a stable digest is enough to count repeats.
        fun digest(value: String): String {
            val digest = DIGEST.get()!!
            digest.reset()
            return digest.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
