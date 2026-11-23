package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.app.tiktok.query.DownloadQueries
import io.github.nuke.oss.runtime.FeatureScope

internal fun interface StoryDownloadMenu {
    fun install(scope: FeatureScope, actionFor: (aweme: Any) -> ((context: Any) -> Boolean)?)

    companion object {
        val QUERIES = listOf(DownloadQueries.PRESS_PANEL_VIEW, DownloadQueries.PRESS_PANEL_PARAMS, DownloadQueries.PRESS_PARAMS_AWEME)
    }
}
