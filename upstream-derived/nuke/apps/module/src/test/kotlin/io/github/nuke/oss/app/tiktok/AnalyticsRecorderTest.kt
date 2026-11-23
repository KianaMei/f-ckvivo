package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsRecorder
import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsSink
import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AnalyticsRecorderTest {
    private val zone = "UTC"
    private fun at(day: Int, hour: Int, minute: Int = 0, second: Int = 0) =
        LocalDateTime.of(2026, 11, day, hour, minute, second).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private class Recording : AnalyticsSink {
        val visits = ArrayList<AnalyticsVisit>()
        val ranks = ArrayList<Triple<Long, String, String>>()
        val collection = ArrayList<Boolean>()
        var closed = false
        override fun visit(visit: AnalyticsVisit) { visits += visit }
        override fun rank(day: Long, kind: String, key: String) { ranks += Triple(day, kind, key) }
        override fun collection(started: Boolean) { collection += started }
        override fun close() { closed = true }
    }

    private fun recorder(sink: AnalyticsSink, now: () -> Long) =
        AnalyticsRecorder(sink, clock = now, zone = { ZoneId.of(zone) })

    @Test fun `a video change closes the previous visit with the playing time`() {
        val sink = Recording()
        var now = at(1, 20)
        val recorder = recorder(sink) { now }
        recorder.start()
        recorder.videoChanged("a", "author", "feed")
        now += 8_000
        recorder.videoChanged("b", null, "feed")
        assertEquals(1, sink.visits.size)
        val visit = sink.visits.single()
        assertEquals(8_000, visit.visibleMs)
        assertEquals("a", visit.aweme)
        assertEquals(20, visit.hour)
        assertEquals(AnalyticsRecorder.END_CHANGED, visit.endReason)
        assertEquals(listOf(Triple(visit.day, AnalyticsRecorder.KIND_AUTHOR, "author")), sink.ranks)
        assertEquals(listOf(true), sink.collection)
    }

    @Test fun `paused time does not count as visible`() {
        val sink = Recording()
        var now = at(2, 9)
        val recorder = recorder(sink) { now }
        recorder.start()
        recorder.videoChanged("a", "author", "feed")
        now += 5_000
        recorder.paused()
        now += 60_000
        recorder.playing()
        now += 3_000
        recorder.stopped()
        val visit = sink.visits.single()
        assertEquals(8_000, visit.visibleMs)
        assertEquals(AnalyticsRecorder.END_CLOSED, visit.endReason)
        assertEquals(listOf(true, false), sink.collection)
    }

    @Test fun `a change while the player is paused does not count as playing`() {
        val sink = Recording()
        var now = at(4, 10)
        val recorder = recorder(sink) { now }
        recorder.start()
        recorder.videoChanged("a", "author", "feed")
        now += 4_000
        recorder.paused()
        now += 30_000
        recorder.videoChanged("b", "author", "feed")
        now += 6_000
        recorder.stopped()
        assertEquals(listOf("a" to 4_000L, "b" to 0L), sink.visits.map { it.aweme to it.visibleMs })
    }

    @Test fun `a video changed before the first play report still counts as playing`() {
        val sink = Recording()
        var now = at(5, 11)
        val recorder = recorder(sink) { now }
        recorder.videoChanged("a", "author", "feed")
        now += 7_000
        recorder.stopped()
        assertEquals(7_000, sink.visits.single().visibleMs)
    }

    @Test fun `a video without an id is not recorded`() {        val sink = Recording()
        val recorder = recorder(sink) { at(3, 12) }
        recorder.videoChanged(null, "author", "feed")
        recorder.videoChanged("", "author", "feed")
        recorder.stopped()
        assertTrue(sink.visits.isEmpty())
    }

    @Test fun `the digest is stable and hides the identifier`() {
        val first = AnalyticsRecorder.digest("7312345678901234567")
        val second = AnalyticsRecorder.digest("7312345678901234567")
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue("7312345678901234567" !in first)
    }
}
