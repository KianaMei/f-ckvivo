package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuMarkerClientTest {
    private fun entry(progress: Long, text: String, sender: String) = Triple(progress, text, sender)

    @Test
    fun `same sentence from three senders inside five seconds becomes a mark`() {
        val entries = listOf(
            entry(121_000, "前方高能", "a"),
            entry(122_500, "前方高能", "b"),
            entry(124_000, " 前方高能 ", "c"),
        )
        assertEquals(listOf(120_000L), DanmakuMarkerClient.consensus(entries, 600_000))
    }

    @Test
    fun `two senders and far apart repeats are ignored`() {
        val entries = listOf(
            entry(121_000, "前方高能", "a"),
            entry(122_500, "前方高能", "b"),
            entry(181_000, "前方高能", "c"),
        )
        assertTrue(DanmakuMarkerClient.consensus(entries, 600_000).isEmpty())
    }

    @Test
    fun `marks outside the playable window are dropped`() {
        val entries = listOf(
            entry(10_000, "打卡", "a"),
            entry(10_500, "打卡", "b"),
            entry(11_000, "打卡", "c"),
        )
        assertTrue(DanmakuMarkerClient.consensus(entries, 600_000).isEmpty())
        assertTrue(DanmakuMarkerClient.consensus(entries, 15_000).isEmpty())
    }

    @Test
    fun `bursts from the same sentence merge into one horizontal range`() {
        val entries = (0 until 3).flatMap { sender ->
            listOf(entry(300_000 + sender.toLong() * 500, "泪目", "s$sender"),
                entry(305_000 + sender.toLong() * 500, "泪目", "s$sender"))
        }
        val marks = DanmakuMarkerClient.consensus(entries, 900_000)
        assertEquals(listOf(300_000L), marks)
    }
}
