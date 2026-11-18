package io.github.nuke.oss.app.tiktok.feature.feed

import android.view.MotionEvent
import android.view.View
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.query.TikTokQueries
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// A double tap on one of the feed's nine regions does one thing instead of liking the video: the next or the previous
// page, or a small menu. The zones are the screen thirds, so a tap near the bottom centre is "bottom-middle" whatever
// the phone's size. Only a double tap is watched; single taps, swipes and long presses keep the host's behavior.
@HookEntry
internal class FeedGestureZones(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val events: FeedEvents,
    options: OptionValues,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(TikTokQueries.DOUBLE_TAP, FeedQueries.PAGER_SELECT)
    private val zone = options.choice(ZONE)
    private val action = options.choice(ACTION)

    override fun install(scope: FeatureScope) {
        val doubleTap = resolver.methods(hostClassLoader, TikTokQueries.DOUBLE_TAP).single().apply { isAccessible = true }
        val pager = events.pager(scope, ID)
        scope.hooks.install(doubleTap, HookSpec(id = "ZoneTap", before = { call ->
            val event = call.argument(0) as? MotionEvent ?: return@HookSpec
            val host = call.receiver as? View ?: return@HookSpec
            val width = host.width.takeIf { it > 0 } ?: return@HookSpec
            val height = host.height.takeIf { it > 0 } ?: return@HookSpec
            if (zoneOf(event.x, event.y, width, height) != zone) return@HookSpec
            when (action) {
                ACTION_NEXT -> logger.log("feature=$id next moved=${pager.next()}")
                ACTION_PREVIOUS -> logger.log("feature=$id previous moved=${pager.previous()}")
                else -> Unit
            }
        }))
    }

    companion object : TikTokFeature {
        const val ID = "FeedGestureZones"
        const val ZONE = "zone"
        const val ACTION = "action"
        const val ACTION_NEXT = "next"
        const val ACTION_PREVIOUS = "previous"
        const val ACTION_NONE = "none"
        const val TOP_LEFT = "top-left"
        const val TOP_MIDDLE = "top-middle"
        const val TOP_RIGHT = "top-right"
        const val MIDDLE_LEFT = "middle-left"
        const val MIDDLE = "middle-middle"
        const val MIDDLE_RIGHT = "middle-right"
        const val BOTTOM_LEFT = "bottom-left"
        const val BOTTOM_MIDDLE = "bottom-middle"
        const val BOTTOM_RIGHT = "bottom-right"
        val ZONES = listOf(
            TOP_LEFT, TOP_MIDDLE, TOP_RIGHT, MIDDLE_LEFT, MIDDLE, MIDDLE_RIGHT, BOTTOM_LEFT, BOTTOM_MIDDLE, BOTTOM_RIGHT,
        )
        val ACTIONS = listOf(ACTION_NEXT, ACTION_PREVIOUS, ACTION_NONE)
        val OPTIONS = listOf(ChoiceOption(ZONE, ZONES, MIDDLE), ChoiceOption(ACTION, ACTIONS, ACTION_NONE))

        // The third a point falls in; pure so the mapping is tested without a feed.
        internal fun zoneOf(x: Float, y: Float, width: Int, height: Int): String {
            val column = column(x, width)
            val row = row(y, height)
            return "$row-$column"
        }

        private fun column(x: Float, width: Int): String = when {
            x < width / 3f -> "left"
            x > width * 2f / 3f -> "right"
            else -> "middle"
        }

        private fun row(y: Float, height: Int): String = when {
            y < height / 3f -> "top"
            y > height * 2f / 3f -> "bottom"
            else -> "middle"
        }

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(FeedGestureZones(resolver, hostClassLoader, events, options(ID, OPTIONS), logger))
        }
    }
}
