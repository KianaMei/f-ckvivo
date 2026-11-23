package io.github.nuke.oss.app.tiktok.feature.feed

import android.os.Handler
import android.os.Looper
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ConfirmQueries
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Follows the author of a video that has played for a while. The follow call is the host's own: its receiver is recorded
// from a real follow tap once, and later calls reuse it with another user ID. Until such a tap happens nothing is
// followed, which keeps the feature from inventing a receiver the host never handed out.
@HookEntry
internal class AutoFollow(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val events: FeedEvents,
    options: OptionValues,
    private val logger: Logger,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(ConfirmQueries.FOLLOW)
    private val afterSeconds = options.number(AFTER_SECONDS)
    private val maxPerSession = options.number(MAX_PER_SESSION).toInt()
    private val follow = AtomicReference<WeakReference<Any>>()
    private val method = AtomicReference<Method>()
    private val generation = AtomicLong()
    private val count = AtomicInteger()
    private val followed = HashSet<String>()
    private val author = NamedField("author")
    private val uid = NamedFieldAlias("uid", "sec_uid")
    private val followStatus = NamedFieldAlias("followStatus", "follow_status")
    private val nickname = NamedField("nickname")
    // Built on first use: the feature is constructed during the host's start and in tests, where Looper is absent.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nuke-autofollow").apply { isDaemon = true }
    }

    override fun install(scope: FeatureScope) {
        if (afterSeconds <= 0) {
            logger.log("feature=$id skipped after_seconds=$afterSeconds")
            return
        }
        val call = resolver.methods(hostClassLoader, ConfirmQueries.FOLLOW).single().apply { isAccessible = true }
        method.set(call)
        scope.own(AutoCloseable { scheduler.shutdownNow() })
        // A real follow tap hands over the receiver the follow API needs; it is kept as a weak reference only.
        scope.hooks.install(call, HookSpec(id = "Capture", before = { hooked ->
            val receiver = hooked.receiver
            if (receiver != null) follow.set(WeakReference(receiver))
        }))
        events.onVideoChanged(scope, ID) { schedule(it) }
    }

    private fun schedule(aweme: Any) {
        val ticket = generation.incrementAndGet()
        val user = runCatching { author.of(aweme)?.get(aweme) }.getOrNull() ?: return
        val target = (runCatching { uid.of(user)?.get(user) }.getOrNull() as? String)?.takeIf { it.isNotEmpty() } ?: return
        val status = (runCatching { followStatus.of(user)?.get(user) }.getOrNull() as? Number)?.toInt()
        if (!Rules.mayFollow(status, synchronized(followed) { target in followed }, count.get(), maxPerSession)) return
        val name = (runCatching { nickname.of(user)?.get(user) }.getOrNull() as? String).orEmpty()
        scheduler.schedule({
            if (generation.get() != ticket) return@schedule
            val receiver = follow.get()?.get() ?: return@schedule
            val call = method.get() ?: return@schedule
            if (!synchronized(followed) { followed.add(target) }) return@schedule
            // The host's follow path touches views and callbacks, so it runs where a tap would.
            main.post {
                try {
                    call.invoke(receiver, target)
                    count.incrementAndGet()
                    logger.log("feature=$id followed")
                } catch (failure: ReflectiveOperationException) {
                    logger.log("feature=$id follow_failed ${(failure.cause ?: failure).javaClass.simpleName}")
                }
            }
        }, afterSeconds, TimeUnit.SECONDS)
        if (name.isNotEmpty()) logger.log("feature=$id queued")
    }

    // The decision, kept apart from the hooks: a followed author and a full session never produce another follow.
    internal object Rules {
        fun mayFollow(followStatus: Int?, alreadyFollowedHere: Boolean, sessionCount: Int, max: Int): Boolean =
            followStatus != FOLLOWED && !alreadyFollowedHere && sessionCount < max
    }

    companion object : TikTokFeature {
        const val ID = "AutoFollow"
        const val AFTER_SECONDS = "after-seconds"
        const val MAX_PER_SESSION = "max-per-session"
        private const val FOLLOWED = 1
        val OPTIONS = listOf(
            NumberOption(AFTER_SECONDS, default = 10, min = 0, max = 120),
            NumberOption(MAX_PER_SESSION, default = 10, min = 1, max = 100),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(AutoFollow(resolver, hostClassLoader, events, options(ID, OPTIONS), logger))
        }
    }
}
