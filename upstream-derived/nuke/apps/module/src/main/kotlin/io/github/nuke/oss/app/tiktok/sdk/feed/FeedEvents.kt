package io.github.nuke.oss.app.tiktok.sdk.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.makeAccessible
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

// The feed's playback events: the current video changing, the player playing and
// pausing, and a video playing to its end. Each feature subscribes with its own hooks in its own scope, so turning a
// feature off removes only its subscriptions.
internal class FeedEvents(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val awemeName: String = AWEME,
) {
    // The id is aid in the host model and aweme_id in its serialized form; it tells a repeated report of one video apart.
    private val awemeId = NamedFieldAlias("aid", "aweme_id")
    private val getAdapter = NamedCall("getAdapter")
    private val getItem = object : ClassValue<Method?>() {
        override fun computeValue(type: Class<*>): Method? = type.resolve().optional(silent = true).firstMethodOrNull {
            name = "getItem"
            parameters(Int::class)
            modifiers(Modifiers.PUBLIC)
            superclass(interfaces = true)
        }?.self?.apply { makeAccessible() }
    }

    // The listener gets the video's Aweme once per change. The player reports the video it switches to; the pager
    // reports the page it settles on. Both may report the same video, which is passed on once.
    fun onVideoChanged(scope: FeatureScope, featureId: String, listener: (aweme: Any) -> Unit) {
        val aweme = awemeName.toClass(hostClassLoader)
        val (changed, page) = resolver.methods(hostClassLoader, VIDEO_CHANGED)
        val last = AtomicReference<String?>()
        val deliver = { video: Any? ->
            if (video != null && aweme.isInstance(video)) {
                val id = awemeId.of(video)?.get(video) as? String
                if (id == null || last.getAndSet(id) != id) listener(video)
            }
        }
        changed.isAccessible = true
        page.isAccessible = true
        scope.hooks.install(changed, HookSpec(id = "$featureId:VideoChanged", after = { call ->
            if (call.outcome is CallOutcome.Returned) deliver(call.argument(0))
        }))
        scope.hooks.install(page, HookSpec(id = "$featureId:PageSelected", after = { call ->
            val pager = call.receiver
            val index = call.argument(0) as? Int
            if (call.outcome is CallOutcome.Returned && pager != null && index != null) deliver(itemAt(pager, index))
        }))
    }

    // The feed pager the user is on, for features that move to the next video.
    fun pager(scope: FeatureScope, featureId: String): FeedPager {
        val page = resolver.methods(hostClassLoader, PAGER).single().apply { isAccessible = true }
        val tracked = FeedPager()
        scope.hooks.install(page, HookSpec(id = "$featureId:TrackPager", after = { call -> call.receiver?.let(tracked::seen) }))
        return tracked
    }

    fun onPlayback(scope: FeatureScope, featureId: String, onPlay: () -> Unit, onPause: () -> Unit) {
        val engine = resolver.methods(hostClassLoader, PLAYBACK).single().declaringClass
        val playDepth = ThreadLocal.withInitial { 0 }
        val pauseDepth = ThreadLocal.withInitial { 0 }
        val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
        val hookedCallbackClasses = ConcurrentHashMap.newKeySet<String>()

        fun installAction(method: Method, id: String, depth: ThreadLocal<Int>, action: () -> Unit) {
            if (!hookedMethods.add(method)) return
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(
                id = id,
                before = {
                    val level = (depth.get() ?: 0) + 1
                    depth.set(level)
                    if (level == 1) action()
                },
                after = {
                    val level = ((depth.get() ?: 0) - 1).coerceAtLeast(0)
                    if (level == 0) depth.remove() else depth.set(level)
                },
            ))
        }

        val play = engine.noArgument("play")
        val pause = engine.noArgument("pause")
        installAction(play, "$featureId:Play", playDepth, onPlay)
        installAction(pause, "$featureId:Pause", pauseDepth, onPause)

        // Android 17 AOT & Douyin 41.3+ can run playback through pooled MTTVideoEngine subclasses and
        // deliver state transitions through registered listener/callback objects without hitting base play().
        val engines = LinkedHashSet<Class<*>>().apply {
            add(engine)
            for (name in EXTRA_ENGINE_CLASSES) {
                runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()?.let(::add)
            }
        }
        for (target in engines) {
            for (name in PLAY_METHOD_NAMES) {
                target.optionalNoArgument(name)?.let { installAction(it, "$featureId:Play:${target.simpleName}:$name", playDepth, onPlay) }
            }
            for (name in PAUSE_METHOD_NAMES) {
                target.optionalNoArgument(name)?.let { installAction(it, "$featureId:Pause:${target.simpleName}:$name", pauseDepth, onPause) }
            }
            hookPlaybackRegistrars(scope, featureId, target, hookedMethods, hookedCallbackClasses, onPlay, onPause)
        }
    }

    private fun hookPlaybackRegistrars(
        scope: FeatureScope,
        featureId: String,
        target: Class<*>,
        hookedMethods: MutableSet<Method>,
        hookedCallbackClasses: MutableSet<String>,
        onPlay: () -> Unit,
        onPause: () -> Unit,
    ) {
        val methods = runCatching { target.declaredMethods }.getOrNull() ?: return
        for (method in methods) {
            if (method.isSynthetic || Modifier.isAbstract(method.modifiers) || method.parameterCount == 0) continue
            val name = method.name
            if (!(name.startsWith("set") || name.startsWith("add"))) continue
            if (!(name.endsWith("Listener") || name.endsWith("Callback"))) continue
            if (!hookedMethods.add(method)) continue
            method.isAccessible = true
            runCatching {
                scope.hooks.install(method, HookSpec(
                    id = "$featureId:Reg:${target.simpleName}:$name:${method.parameterCount}",
                    before = { call ->
                        for (index in 0 until method.parameterCount) {
                            val candidate = call.argument(index) ?: continue
                            val type = candidate.javaClass
                            if (isIgnoredCallbackType(type)) continue
                            if (!hookedCallbackClasses.add(type.name)) continue
                            hookPlaybackCallbackClass(scope, featureId, type, hookedMethods, onPlay, onPause)
                        }
                    },
                ))
            }
        }
    }

    private fun hookPlaybackCallbackClass(
        scope: FeatureScope,
        featureId: String,
        callbackType: Class<*>,
        hookedMethods: MutableSet<Method>,
        onPlay: () -> Unit,
        onPause: () -> Unit,
    ) {
        val methods = generateSequence<Class<*>>(callbackType) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { cls -> runCatching { cls.declaredMethods.asSequence() }.getOrDefault(emptySequence()) }
        for (method in methods) {
            if (method.isSynthetic || Modifier.isAbstract(method.modifiers)) continue
            when (method.name) {
                "onPlaybackStateChanged" -> {
                    val stateIndex = method.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType || it == Int::class.javaObjectType }
                    if (stateIndex < 0 || !hookedMethods.add(method)) continue
                    method.isAccessible = true
                    runCatching {
                        scope.hooks.install(method, HookSpec(
                            id = "$featureId:CbState:${callbackType.simpleName}:${method.parameterCount}",
                            before = { call ->
                                when (call.argument(stateIndex) as? Int) {
                                    STATE_PLAYING -> onPlay()
                                    STATE_PAUSED, STATE_STOPPED -> onPause()
                                }
                            },
                        ))
                    }
                }
                "onRenderStart" -> {
                    if (!hookedMethods.add(method)) continue
                    method.isAccessible = true
                    runCatching {
                        scope.hooks.install(method, HookSpec(
                            id = "$featureId:CbRender:${callbackType.simpleName}:${method.parameterCount}",
                            before = { call ->
                                // Avoid false play signals from preloaded engines whose playback state is not PLAYING.
                                val state = (0 until method.parameterCount)
                                    .asSequence()
                                    .mapNotNull { call.argument(it) }
                                    .mapNotNull(::playbackStateOf)
                                    .firstOrNull()
                                if (state == STATE_PLAYING) onPlay()
                            },
                        ))
                    }
                }
            }
        }
    }

    private val playbackStateMethod = NamedCall("getPlaybackState")

    private fun playbackStateOf(candidate: Any): Int? =
        runCatching { playbackStateMethod.of(candidate)?.invoke(candidate) as? Int }.getOrNull()

    private fun isIgnoredCallbackType(type: Class<*>): Boolean {
        val name = type.name
        return type.isPrimitive || name.startsWith("java.") || name.startsWith("kotlin.") || name.startsWith("android.")
    }

    private fun Class<*>.optionalNoArgument(name: String): Method? =
        runCatching {
            declaredMethods.firstOrNull {
                !it.isSynthetic && !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                    it.name == name && it.parameterCount == 0
            }?.apply { isAccessible = true }
        }.getOrNull()

    fun onCompletion(scope: FeatureScope, featureId: String, listener: () -> Unit) {
        val completion = resolver.methods(hostClassLoader, COMPLETION).single().apply { isAccessible = true }
        scope.hooks.install(completion, HookSpec(id = "$featureId:Completed", after = { call ->
            if (call.outcome is CallOutcome.Returned) listener()
        }))
    }

    // The player's progress listener reports the video ID, the position and the duration in milliseconds; hosts differ
    // in argument order, (String, long, long) or (long, long, String).
    fun onProgress(scope: FeatureScope, featureId: String, listener: (videoId: String?, position: Long, duration: Long) -> Unit) {
        val progress = resolver.methods(hostClassLoader, PROGRESS).single().apply { isAccessible = true }
        val idFirst = progress.parameterTypes[0] == String::class.java
        scope.hooks.install(progress, HookSpec(id = "$featureId:Progress", before = { call ->
            val id = call.argument(if (idFirst) 0 else 2) as? String
            val position = call.argument(if (idFirst) 1 else 0) as? Long
            val duration = call.argument(if (idFirst) 2 else 1) as? Long
            if (position != null && duration != null) listener(id, position, duration)
        }))
    }

    private fun itemAt(pager: Any, index: Int): Any? {
        val adapter = getAdapter.of(pager)?.invoke(pager) ?: return null
        return getItem.get(adapter.javaClass)?.invoke(adapter, index)
    }

    private fun Class<*>.noArgument(name: String): Method =
        resolve().optional(silent = true).method {
            this.name = name
            emptyParameters()
            isSynthetic = false
        }.singleOrNull()?.self?.apply { makeAccessible() } ?: error("${this.name} declares no single $name()")

    companion object {
        const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"
        private const val STATE_STOPPED = 0
        private const val STATE_PLAYING = 1
        private const val STATE_PAUSED = 2
        private val PLAY_METHOD_NAMES = listOf("play", "start", "resume")
        private val PAUSE_METHOD_NAMES = listOf("pause", "stop")
        private val EXTRA_ENGINE_CLASSES = listOf(
            "com.ss.ttvideoengine.TTVideoEngine",
            "com.ss.ttvideoengine.TTVideoEngineImpl",
            "com.ss.ttvideoengine.TTVideoEngineImplV2",
            "com.ss.android.ugc.aweme.video.simplayer.tt.MTTVideoEngine",
            "com.ss.android.ugc.aweme.video.simplayer.tt.MBaseTTVideoEngine",
            "com.ss.android.ugc.playerkit.simapicommon.SimplifyPlayerImpl",
        )

        // The queries each subscription resolves, which a feature using it declares as its own (see UsesDexQueries).
        val VIDEO_CHANGED: List<DexQuery> = listOf(FeedQueries.AWEME_CHANGED, FeedQueries.PAGER_SELECT)
        val PAGER: List<DexQuery> = listOf(FeedQueries.PAGER_SELECT)
        val PLAYBACK: List<DexQuery> = listOf(FeedQueries.VIDEO_ENGINE)
        val COMPLETION: List<DexQuery> = listOf(FeedQueries.PLAYBACK_COMPLETION)
        val PROGRESS: List<DexQuery> = listOf(FeedQueries.PLAY_PROGRESS)

        internal fun isLandscapeActivity(name: String?): Boolean =
            name != null && name.contains("Landscape", ignoreCase = true)
    }
}

// The last feed pager seen settling on a page. Only a weak reference is kept.
internal class FeedPager {
    @Volatile private var pager: WeakReference<Any>? = null
    private val currentItem = NamedCall("getCurrentItem")
    private val moveTo = object : ClassValue<Method?>() {
        override fun computeValue(type: Class<*>): Method? = type.resolve().optional(silent = true).firstMethodOrNull {
            name = "setCurrentItemWithDefaultVelocityOpt"
            parameters(Int::class)
            superclass()
        }?.self?.apply { makeAccessible() }
    }

    internal fun seen(view: Any) {
        if (pager?.get() !== view) pager = WeakReference(view)
    }

    // Moves the pager on by one page the way the host's own smooth scroll does. False when no pager is known.
    fun next(): Boolean = move(1)

    // Moves the pager back by one page; the first page stays, as the host's own swipe does.
    fun previous(): Boolean = move(-1)

    private fun move(delta: Int): Boolean {
        val view = pager?.get() ?: return false
        val index = currentItem.of(view)?.invoke(view) as? Int ?: return false
        val target = index + delta
        if (target < 0) return false
        val move = moveTo.get(view.javaClass) ?: return false
        move.invoke(view, target)
        return true
    }
}
