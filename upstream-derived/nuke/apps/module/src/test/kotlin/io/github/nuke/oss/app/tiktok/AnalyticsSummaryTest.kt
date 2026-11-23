package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsDay
import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsSummaryTest {
    private fun summary(vararg days: AnalyticsDay) =
        AnalyticsSummary(0, 0, days.toList(), 0, emptyList())

    @Test fun `day rows are oldest first whatever order the query returned`() {
        val rows = summary(AnalyticsDay(3, 10, 5_000), AnalyticsDay(1, 4, 1_000), AnalyticsDay(2, 7, 2_000)).dayRows()
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.day })
    }

    @Test fun `a summary without days has no rows and no data`() {
        val empty = summary()
        assertEquals(emptyList<AnalyticsDay>(), empty.dayRows())
        assertEquals(false, empty.hasData)
        assertEquals(0, empty.weekViews)
        assertEquals(0L, empty.weekVisibleMs)
    }
}
