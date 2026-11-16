package io.github.nuke.oss.app.tiktok.feature.analytics

// One finished look at a video. Times are milliseconds and days are local epoch days, so the store never needs a clock
// of its own; identifiers arrive already hashed by the caller.
internal data class AnalyticsVisit(
    val day: Long,
    val hour: Int,
    val aweme: String,
    val author: String,
    val source: String,
    val visibleMs: Long,
    val endedAt: Long,
    val endReason: String,
)

// Where the local analysis goes. Implementations never throw: a broken store costs the statistics, not the feed.
internal interface AnalyticsSink {
    fun visit(visit: AnalyticsVisit)
    fun rank(day: Long, kind: String, key: String)
    fun action(day: Long, feature: String, stage: String)
    // A switch being turned on or off, so a gap in the data can be told apart from an untracked period.
    fun collection(day: Long, started: Boolean)
    fun close()
}
