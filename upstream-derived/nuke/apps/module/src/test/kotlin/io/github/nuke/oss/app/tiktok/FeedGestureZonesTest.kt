package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.FeedGestureZones
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedGestureZonesTest {
    private fun zone(x: Float, y: Float) = FeedGestureZones.zoneOf(x, y, 1080, 2400)

    @Test fun `the nine regions follow the screen thirds`() {
        assertEquals("top-left", zone(10f, 10f))
        assertEquals("top-middle", zone(540f, 10f))
        assertEquals("top-right", zone(1070f, 10f))
        assertEquals("middle-left", zone(10f, 1200f))
        assertEquals("middle-middle", zone(540f, 1200f))
        assertEquals("middle-right", zone(1070f, 1200f))
        assertEquals("bottom-left", zone(10f, 2390f))
        assertEquals("bottom-middle", zone(540f, 2390f))
        assertEquals("bottom-right", zone(1070f, 2390f))
    }

    @Test fun `a point exactly on a boundary belongs to the middle band, as the host's own taps do`() {
        assertEquals("middle-middle", zone(360f, 800f))
        assertEquals("middle-middle", zone(720f, 800f))
    }

    @Test fun `every configured zone is one the mapping can produce`() {
        val produced = HashSet<String>()
        for (row in 0..2) for (column in 0..2) {
            produced += zone(180f + column * 360f, 400f + row * 800f)
        }
        assertEquals(FeedGestureZones.ZONES.toSet(), produced)
    }
}
