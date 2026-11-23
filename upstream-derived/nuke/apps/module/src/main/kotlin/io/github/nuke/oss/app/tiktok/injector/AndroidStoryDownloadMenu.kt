package io.github.nuke.oss.app.tiktok.injector

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.fields
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.tiktok.feature.feed.DownloadVideoWithName
import io.github.nuke.oss.app.tiktok.feature.feed.StoryDownloadMenu
import io.github.nuke.oss.app.tiktok.query.DownloadQueries
import io.github.nuke.oss.app.tiktok.sdk.ModuleStrings
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidStoryDownloadMenu(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val strings: ModuleStrings,
    private val logger: Logger,
) : StoryDownloadMenu {
    override fun install(scope: FeatureScope, actionFor: (Any) -> ((Any) -> Boolean)?) {
        val created = resolver.methods(hostClassLoader, DownloadQueries.PRESS_PANEL_VIEW).single()
        val (params, post) = resolver.fields(hostClassLoader, DownloadQueries.PRESS_PANEL_PARAMS, DownloadQueries.PRESS_PARAMS_AWEME)
        params.isAccessible = true
        post.isAccessible = true
        val active = AtomicBoolean(true)
        val rows = WeakHashMap<View, Unit>()
        val main = Handler(Looper.getMainLooper())
        scope.own(AutoCloseable {
            active.set(false)
            main.post {
                rows.keys.toList().forEach(::remove)
                rows.clear()
            }
        })
        scope.hooks.install(created, HookSpec(id = "StoryDownloadRow", after = { call ->
            if (!active.get() || call.outcome !is CallOutcome.Returned) return@HookSpec
            val panel = call.receiver ?: return@HookSpec
            val root = call.argument(0) as? ViewGroup ?: return@HookSpec
            val aweme = params.get(panel)?.let(post::get) ?: return@HookSpec
            val action = actionFor(aweme)
            val column = columnOf(root)
            if (column == null) {
                if (action != null) logger.log("feature=${DownloadVideoWithName.ID} story_row_skipped layout")
                return@HookSpec
            }
            column.findViewWithTag<View>(ROW_TAG)?.let(::remove)
            if (action == null) return@HookSpec
            val title = strings.get(root.context, R.string.host_story_download_entry) ?: return@HookSpec
            val row = Button(column.context).apply {
                tag = ROW_TAG
                text = title
                isAllCaps = false
            }
            row.setOnClickListener {
                if (!active.get() || !row.isAttachedToWindow || !row.isShown || params.get(panel)?.let(post::get) !== aweme) {
                    return@setOnClickListener
                }
                val context = row.context.applicationContext
                val queued = try {
                    action(context)
                } catch (failure: Exception) {
                    logger.log("feature=${DownloadVideoWithName.ID} story_queue_failed ${failure.javaClass.simpleName}")
                    false
                }
                if (queued) row.isEnabled = false
                val notice = if (queued) R.string.host_story_download_queued else R.string.host_story_download_unavailable
                strings.get(row.context, notice)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }
            column.addView(row, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            rows[row] = Unit
        }))
    }

    private fun columnOf(root: ViewGroup): LinearLayout? {
        val scroll = (0 until root.childCount).asSequence().map(root::getChildAt).filterIsInstance<ViewGroup>()
            .singleOrNull { it.javaClass.name == HOST_SCROLL && it.childCount == 1 } ?: return null
        return (scroll.getChildAt(0) as? LinearLayout)?.takeIf { it.orientation == LinearLayout.VERTICAL }
    }

    private fun remove(row: View) {
        row.setOnClickListener(null)
        (row.parent as? ViewGroup)?.removeView(row)
    }

    private companion object {
        const val HOST_SCROLL = "com.ss.android.ugc.aweme.feed.long_press_panel.ui.DampReboundNestedScrollView"
        const val ROW_TAG = "nuke-story-download"
    }
}
