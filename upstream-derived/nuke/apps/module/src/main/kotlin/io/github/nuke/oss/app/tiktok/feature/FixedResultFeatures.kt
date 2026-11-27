package io.github.nuke.oss.app.tiktok.feature

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.app.tiktok.sdk.FixedResult
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

internal object FixedResultIds {
    const val KEEP_FEED_ON_RETURN = "KeepFeedOnReturn"
    const val KEEP_UNAVAILABLE_VIDEO = "KeepUnavailableVideo"
    const val ALLOW_BACKGROUND_PLAY = "AllowUncopyrightedBackgroundPlay"
    const val ALLOW_CAPTION_COPY = "AllowCaptionCopy"
    const val HIDE_PROFILE_VISITS = "HideProfileVisits"
    const val LIFT_FORWARD_LIMIT = "LiftForwardLimit"
    const val BLOCK_TYPING_STATUS_UPLOAD = "BlockTypingStatusUpload"
}

// The features that only answer one host method (or one set of them) with a fixed value.
@HookEntry
internal object FixedResultFeatures : TikTokFeature {
    override fun create(services: TikTokFeatureServices): List<RuntimeFeature> = with(services) {
        val boolean = Boolean::class.javaPrimitiveType!!
        listOf(
            // Keeps the videos the user left when the home feed moves on from them.
            KeepFeedOnReturn(resolver, hostClassLoader, logger),
            // The handlers that skip a video the host has found is no longer public are not run.
            FixedResult(FixedResultIds.KEEP_UNAVAILABLE_VIDEO, resolver, hostClassLoader, FeedQueries.UNAVAILABLE_VIDEO_SKIP, Void.TYPE, null),
            // The play-in-background check is told there is no copyright restriction.
            FixedResult(
                FixedResultIds.ALLOW_BACKGROUND_PLAY, resolver, hostClassLoader, FeedQueries.COPYRIGHT_GATE, boolean, false,
                FixedResult.Mode.REPLACE,
            ),
            // The image post text-selection popup is told copying is allowed, so its copy button copies the selected
            // text instead of being greyed out behind the author's "not copyable" setting.
            FixedResult(FixedResultIds.ALLOW_CAPTION_COPY, resolver, hostClassLoader, FeedQueries.CAPTION_COPY_GATE, boolean, true),
            // The coroutine that records a profile visit never runs, so the visit is not recorded.
            FixedResult(FixedResultIds.HIDE_PROFILE_VISITS, resolver, hostClassLoader, FeedQueries.PROFILE_VISIT, Any::class.java, null),
            FixedResult(FixedResultIds.LIFT_FORWARD_LIMIT, resolver, hostClassLoader, ChatQueries.FORWARD_LIMIT, Int::class.javaPrimitiveType!!, FORWARD_LIMIT),
            FixedResult(FixedResultIds.BLOCK_TYPING_STATUS_UPLOAD, resolver, hostClassLoader, ChatQueries.TYPING_STATUS, Void.TYPE, null),
        )
    }
}

private const val FORWARD_LIMIT = 1000

// The resume gate is the fragment's "go to the next video" (setCurrentItem(current + 1)); answering it "handled"
// without moving is what keeps the feed on return. The same call also advances the cold-start prediction page to
// the real first video. That page has no usable right-column buttons yet, so it must really advance. Only a page
// whose own feed chrome is already built may answer "handled" without running the host method.
//
// Temporary bind-chain tracer: the cold-start landing page keeps its feed chrome unbound until a swipe away and
// back, and the modules' intercept paths prove quiet at that moment. These observe-only hooks log the landing
// bind sequence (list arrival, adapter notify, pager settle, player switch) so the missing link can be read from
// one cold start instead of guessed from another removed feature.
private class KeepFeedOnReturn(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = FixedResultIds.KEEP_FEED_ON_RETURN
    override val dexQueries = listOf(FeedQueries.RESUME_GATE)

    override fun install(scope: FeatureScope) {
        val boolean = Boolean::class.javaPrimitiveType!!
        val gates = resolver.methodLists(hostClassLoader, FeedQueries.RESUME_GATE).flatten()
        for (gate in gates) {
            check(gate.returnType == boolean) {
                "${gate.declaringClass.name}.${gate.name} returns ${gate.returnType.name}, expected ${boolean.name}"
            }
            gate.isAccessible = true
        }
        gates.forEachIndexed { index, gate ->
            scope.hooks.install(gate, HookSpec(
                id = "KeepFeed$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { call ->
                    val chromeReady = feedChromeReady(call.receiver)
                    if (chromeReady) call.returnEarly(true)
                    logger.log("feature=$id resume_gate chrome_ready=$chromeReady action=${if (chromeReady) "keep" else "advance"}")
                },
            ))
        }
        installBindTrace(scope)
    }

    private fun installBindTrace(scope: FeatureScope) {
        val trace = { event: String -> logger.log("feature=$id trace_gated event=$event") }
        runCatching {
            resolver.methodLists(hostClassLoader, FeedQueries.FEED_RESULTS).flatten().forEachIndexed { index, results ->
                results.isAccessible = true
                scope.hooks.install(results, HookSpec(id = "TraceResults$index", after = { call ->
                    if (call.outcome is CallOutcome.Returned) trace("feed_results ${results.name} items=${(call.argument(0) as? List<*>)?.size}")
                }))
            }
        }
        runCatching {
            resolver.methods(hostClassLoader, FeedQueries.PAGER_SELECT).single().let { settle ->
                settle.isAccessible = true
                scope.hooks.install(settle, HookSpec(id = "TraceSettle", after = { call ->
                    if (call.outcome is CallOutcome.Returned) trace("pager_settle item=${call.argument(0)} smooth=${call.argument(1)} always=${call.argument(2)}")
                }))
            }
        }
        runCatching {
            resolver.methods(hostClassLoader, FeedQueries.AWEME_CHANGED).single().let { changed ->
                changed.isAccessible = true
                scope.hooks.install(changed, HookSpec(id = "TraceChanged", after = { call ->
                    if (call.outcome is CallOutcome.Returned) trace("aweme_changed")
                }))
            }
        }
        runCatching {
            resolver.methods(hostClassLoader, OptionQueries.FEED_ADAPTER_CHANGED).single().let { notify ->
                notify.isAccessible = true
                scope.hooks.install(notify, HookSpec(id = "TraceNotify", after = { call -> trace("adapter_notify") }))
            }
        }
    }

    private fun feedChromeReady(fragment: Any?): Boolean {
        val root = runCatching {
            fragment?.javaClass?.getMethod("getView")?.invoke(fragment) as? View
        }.getOrNull() ?: return false
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 2_000) {
            val view = pending.removeFirst()
            if (view.javaClass.name == RIGHT_BAR && rightBarHasButton(view)) return true
            if (view is ViewGroup) for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
        }
        return false
    }

    private fun rightBarHasButton(bar: View): Boolean {
        if (!bar.isShown || bar.width <= 0 || bar.height <= 0) return false
        val pending = ArrayDeque<View>()
        pending.add(bar)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 256) {
            val view = pending.removeFirst()
            if (view is ImageView && view.isShown && view.width > 0 && view.height > 0) return true
            if (view is ViewGroup) for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
        }
        return false
    }

    private companion object {
        const val RIGHT_BAR = "com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView"
    }
}
