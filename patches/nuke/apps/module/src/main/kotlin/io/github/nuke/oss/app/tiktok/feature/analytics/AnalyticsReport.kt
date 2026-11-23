package io.github.nuke.oss.app.tiktok.feature.analytics

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.Instant
import java.time.ZoneId

internal data class AnalyticsDay(val day: Long, val views: Int, val visibleMs: Long)

// What the settings dialog shows: today, the last days together, how many authors, and the busiest hours. Only counts,
// never an identifier, since the rows behind them hold digests.
internal data class AnalyticsSummary(
    val todayViews: Int,
    val todayVisibleMs: Long,
    val days: List<AnalyticsDay>,
    val authors: Int,
    val hours: List<Pair<Int, Int>>,
) {
    val hasData: Boolean get() = days.isNotEmpty() || hours.isNotEmpty()

    // Oldest first, so an exported image reads like a calendar.
    fun dayRows(): List<AnalyticsDay> = days.sortedBy { it.day }
    val weekViews: Int get() = days.sumOf { it.views }
    val weekVisibleMs: Long get() = days.sumOf { it.visibleMs }
}

internal object AnalyticsReport {
    // A read-only look at the local database; null when the feature has never run or the file no longer opens.
    fun read(file: File, zone: ZoneId, now: Long = System.currentTimeMillis(), days: Int = 7): AnalyticsSummary? {
        if (!file.isFile) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        val from = today - (days - 1)
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val rows = ArrayList<AnalyticsDay>()
                db.rawQuery("SELECT day, views, visible_ms FROM daily WHERE day >= ? ORDER BY day", arrayOf(from.toString())).use { cursor ->
                    while (cursor.moveToNext()) rows += AnalyticsDay(cursor.getLong(0), cursor.getInt(1), cursor.getLong(2))
                }
                val hours = ArrayList<Pair<Int, Int>>()
                db.rawQuery(
                    "SELECT hour, SUM(views) FROM hours WHERE day >= ? GROUP BY hour ORDER BY SUM(views) DESC LIMIT 3",
                    arrayOf(from.toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) hours += cursor.getInt(0) to cursor.getInt(1)
                }
                val authors = db.rawQuery(
                    "SELECT COUNT(DISTINCT item) FROM ranks WHERE kind = 'author' AND day >= ?",
                    arrayOf(from.toString()),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
                val todayRow = rows.firstOrNull { it.day == today }
                AnalyticsSummary(todayRow?.views ?: 0, todayRow?.visibleMs ?: 0L, rows, authors, hours)
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}
