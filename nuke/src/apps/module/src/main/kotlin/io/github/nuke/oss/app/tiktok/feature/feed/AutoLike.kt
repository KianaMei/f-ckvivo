package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Likes a video after it has been playing for a while, using the host's own like button: the presenter's click handler
// runs exactly as if the button had been tapped, so the feed's state, animation and event reporting all stay the host's.
// Nothing is liked twice: a video the account already liked is skipped, and each video at most once per run.
@HookEntry
internal class AutoLike(
    private val hostClassLoader: ClassLoader,
    private val events: FeedEvents,
    options: OptionValues,
    private val logger: Logger,
) : RuntimeFeature {
    override val id = ID
    private val afterSeconds = options.number(AFTER_SECONDS)
    private val maxPerSession = options.number(MAX_PER_SESSION).toInt()
    private val presenter = AtomicReference<WeakReference<Any>?>(null)
    private val generation = AtomicLong()
    private val count = AtomicInteger()
    private val liked = HashSet<String>()
    private val diggLayout = NamedField("diggClickLayout")
    private val getAweme = NamedCall("getAweme")
    private val userDigg = NamedCall("getUserDigg")
    private val awemeId = NamedFieldAlias("aid", "aweme_id")
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nuke-autolike").apply { isDaemon = true }
    }
    // Tests replace this with an immediate run; the feature itself waits afterSeconds on the scheduler.
    private val later: (Runnable, Long) -> Unit = { task, seconds -> scheduler.schedule(task, seconds, TimeUnit.SECONDS) }


    override fun install(scope: FeatureScope) {
        if (afterSeconds <= 0) {
            logger.log("feature=$id skipped after_seconds=$afterSeconds")
            return
        }
        val type = PRESENTER.toClass(hostClassLoader)
        val constructor = type.resolve().firstConstructor { emptyParameters() }.self
        constructor.isAccessible = true
        scope.hooks.install(constructor, HookSpec(
            id = "Presenter",
            requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
            after = { call -> call.receiver?.let { presenter.set(WeakReference(it)) } },
        ))
        scope.own(AutoCloseable { scheduler.shutdownNow() })
        // Every video is a new chance, and the pending check of the previous one is dropped with it.
        events.onVideoChanged(scope, ID) { schedule(it) }
    }

    private fun schedule(aweme: Any) {
        val ticket = generation.incrementAndGet()
        val playable = (userDigg.of(aweme)?.invoke(aweme) as? Boolean) != true
        if (!playable) return
        val id = (awemeId.of(aweme)?.get(aweme) as? String).orEmpty()
        later({
            if (generation.get() != ticket) return@later
            val already = id.isNotEmpty() && synchronized(liked) { id in liked }
            val digg = (userDigg.of(aweme)?.invoke(aweme) as? Boolean)
            if (!Rules.mayLike(digg, already, count.get(), maxPerSession)) return@later
            if (id.isNotEmpty()) synchronized(liked) { liked.add(id) }
            val host = presenter.get()?.get() ?: return@later
            val current = runCatching { getAweme.of(host)?.invoke(host) }.getOrNull() ?: return@later
            if (current !== aweme) return@later
            val layout = runCatching { diggLayout.of(host)?.get(host) }.getOrNull() ?: return@later
            try {
                layout.callByName("performClick")
                count.incrementAndGet()
                logger.log("feature=$id liked")
            } catch (failure: RuntimeException) {
                logger.log("feature=$id like_failed ${failure.javaClass.simpleName}")
            }
        }, afterSeconds)
    }

    // The decision, kept apart from the hook so it can be checked without a host: an already liked video and a full
    // session never produce a like.
    internal object Rules {
        fun mayLike(userDigg: Boolean?, alreadyLikedHere: Boolean, sessionCount: Int, max: Int): Boolean =
            userDigg != true && !alreadyLikedHere && sessionCount < max
    }

    companion object : TikTokFeature {
        const val ID = "AutoLike"
        const val AFTER_SECONDS = "after-seconds"
        const val MAX_PER_SESSION = "max-per-session"
        private const val PRESENTER = "com.ss.android.ugc.aweme.base.module.interaction.InteractionDiggPresenter"
        val OPTIONS = listOf(
            NumberOption(AFTER_SECONDS, default = 8, min = 0, max = 120),
            NumberOption(MAX_PER_SESSION, default = 20, min = 1, max = 200),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(AutoLike(hostClassLoader, events, options(ID, OPTIONS), logger))
        }
    }
}
