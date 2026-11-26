package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorPlayerBindings
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorVideoId
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 弹幕刷屏时间点标记 + 点击跳转。
 *
 * 数据不走 Chronos 脚本补丁：宿主持有播放器时按 bvid/cid 只读拉取 `seg.so`，
 * 本地做「同一句话 5 秒内被 ≥3 个不同发送者刷屏」的共识，只把结果画在进度条上方。
 * 点击刻线通过播放器 core 的 seekTo(position, accurate=true) 精确跳转，不依赖宿主弹幕引擎私有协议。
 */
internal class DanmakuJumpMarkersFeatureInstaller(
    private val enabled: Boolean,
    private val cachedClasses: () -> SponsorPlayerClasses? = { null }
) : FeatureInstaller {
    @Volatile var requiresAdaptationRetry = false
        private set
    private val registrationStarted = AtomicBoolean(false)

    override val id: String = ID
    override val capabilityIds: List<String> get() = if (enabled) listOf(ID) else emptyList()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val access = SponsorPlayerLocator.direct(loader)?.player
            ?: cachedClasses()?.let { SponsorPlayerLocator.resolve(loader, it)?.player }
        if (access == null) {
            val firstMiss = !requiresAdaptationRetry
            requiresAdaptationRetry = true
            if (firstMiss) {
                environment.logInfo("danmaku_marker_skip", "[BIL] 弹幕刷屏标记跳过: missing-player-structure")
            }
            return FeatureInstallResult.Skipped("missing-player-structure")
        }
        requiresAdaptationRetry = false
        val detail = Lookup.classOrNull(loader, DETAIL)?.takeIf { Activity::class.java.isAssignableFrom(it) } ?: run {
            environment.logInfo("danmaku_marker_skip", "[BIL] 弹幕刷屏标记跳过: missing-detail-activity")
            return FeatureInstallResult.Skipped("missing-detail-activity")
        }
        val lifecycle = listOf("callActivityOnResume", "callActivityOnPause", "callActivityOnDestroy").map { name ->
            Lookup.methodOrNull(Instrumentation::class.java, name, Activity::class.java)?.takeIf {
                it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(Activity::class.java)
            } ?: run {
                environment.logInfo("danmaku_marker_skip", "[BIL] 弹幕刷屏标记跳过: missing-detail-lifecycle")
                return FeatureInstallResult.Skipped("missing-detail-lifecycle")
            }
        }
        // 只重试尚未解析的结构；注册开始后不重复挂钩。
        if (!registrationStarted.compareAndSet(false, true)) return FeatureInstallResult.Skipped("already-registered")

        this.access = access
        this.env = environment
        var count = 0

        environment.registrar.constructor("danmaku.marker.wrapper", access.wrapper) {
            after {
                if (hasThrowable) return@after
                val owner = argOrNull(access.wrapperOwnerIndex) ?: return@after
                val wrapper = instance ?: return@after
                val core = argOrNull(access.wrapperCoreIndex) ?: return@after
                bindings.wrapper(owner, wrapper, core)
                refresh()
            }
        }
        count++

        access.scope?.let { scope ->
            environment.registrar.constructor("danmaku.marker.scope", scope) {
                after {
                    if (hasThrowable) return@after
                    val owner = argOrNull(2) ?: return@after
                    val activity = (argOrNull(1) as? Context)?.let(::activityOf)?.takeIf(detail::isInstance) ?: return@after
                    bindings.activity(owner, activity)
                    refresh()
                }
            }
            count++
        }

        access.containerScope?.let { scope ->
            environment.registrar.constructor("danmaku.marker.container", scope.constructor) {
                after {
                    if (hasThrowable) return@after
                    val activity = (argOrNull(scope.contextIndex) as? Context)
                        ?.let(::activityOf)?.takeIf(detail::isInstance) ?: return@after
                    val container = argOrNull(scope.containerIndex) ?: return@after
                    val core = runCatching { scope.core.invoke(container) }.getOrNull() ?: return@after
                    bindings.container(core, activity)
                    refresh()
                }
            }
            count++
        }

        environment.registrar.exact(
            "danmaku.marker.media",
            access.run.declaringClass,
            access.run.name,
            *access.run.parameterTypes
        ) {
            before {
                val owner = instance ?: return@before
                setObjectExtra("danmaku_marker_epoch", bindings.begin(owner))
                if (current?.owner?.get() === owner) {
                    current?.invalid?.set(true)
                    postMain("danmaku_jump_markers.invalidate") {
                        if (current?.invalid?.get() == true) detach(false)
                    }
                }
            }
            after {
                if (hasThrowable) return@after
                val owner = instance ?: return@after
                if (!access.bound(owner, argOrNull(0), argOrNull(1))) return@after
                val epoch = getObjectExtra("danmaku_marker_epoch") as? Long ?: return@after
                bindings.ready(owner, epoch)
                refresh()
            }
        }
        count++

        lifecycle.forEachIndexed { index, method ->
            environment.registrar.exact(
                "danmaku.marker.life.$index",
                method.declaringClass,
                method.name,
                *method.parameterTypes
            ) {
                if (index == 0) {
                    after {
                        val activity = argOrNull(0) as? Activity ?: return@after
                        if (!hasThrowable && detail.isInstance(activity)) {
                            foreground = WeakReference(activity)
                            refresh()
                        }
                    }
                } else {
                    before {
                        val activity = argOrNull(0) as? Activity ?: return@before
                        if (!detail.isInstance(activity)) return@before
                        if (foreground.get() === activity) {
                            foreground = WeakReference(null)
                            current?.invalid?.set(true)
                            postMain("danmaku_jump_markers.pause") {
                                if (current?.activity?.get() === activity) detach(true)
                            }
                        }
                        if (index == 2) bindings.destroy(activity)
                    }
                }
            }
            count++
        }

        environment.reportCapability(ID, FeatureInstallResult.Installed(count))
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(CHANNEL_STATUS, "installed")
        environment.logInfo(
            "danmaku_marker_installed",
            "[BIL] 弹幕刷屏标记已安装 hooks=$count detail=${detail.name.substringAfterLast('.')}"
        )
        return FeatureInstallResult.Installed(count)
    }

    @Volatile private var foreground = WeakReference<Activity>(null)
    @Volatile private var current: Session? = null
    @Volatile private var access: SponsorPlayerAccess? = null
    @Volatile private var env: HookEnvironment? = null
    private val bindings = SponsorPlayerBindings()
    private val handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val executor by lazy(LazyThreadSafetyMode.NONE) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "BIL-DanmakuMarkers").apply { isDaemon = true }
        }
    }

    private fun postMain(key: String, block: () -> Unit) {
        handler.post(HostThreadGuard.runnable(key, block))
    }

    private fun refresh() {
        postMain("danmaku_jump_markers.refresh") {
            val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                ?: run { detach(false); return@postMain }
            val entry = bindings.select(activity) ?: run { detach(false); return@postMain }
            val owner = entry.first
            val candidate = entry.second
            val wrapper = candidate.wrapper.get() ?: run { detach(false); return@postMain }
            val video = access?.video(owner) ?: run { detach(false); return@postMain }
            val epoch = candidate.epoch.get()
            if (current?.let {
                    !it.invalid.get() &&
                        it.video == video &&
                        it.activity.get() === activity &&
                        it.wrapper.get() === wrapper &&
                        it.epoch == epoch
                } == true
            ) {
                return@postMain
            }
            detach(false)
            val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return@postMain
            val session = Session(video, owner, wrapper, candidate, epoch, activity, root)
            current = session
            session.start()
        }
    }

    private fun accessOf(): SponsorPlayerAccess? = access

    private fun detach(@Suppress("UNUSED_PARAMETER") screenLeft: Boolean) {
        val old = current ?: return
        current = null
        old.stop()
    }

    private inner class Session(
        val video: SponsorVideoId,
        owner: Any,
        wrapper: Any,
        private val candidate: SponsorPlayerBindings.Candidate,
        val epoch: Long,
        activity: Activity,
        root: FrameLayout
    ) {
        val activity = WeakReference(activity)
        val owner = WeakReference(owner)
        val wrapper = WeakReference(wrapper)
        private val root = WeakReference(root)
        val invalid = AtomicBoolean(false)
        private val observedReported = AtomicBoolean(false)
        private val progressAlignPending = AtomicBoolean(false)
        private val unregister = mutableListOf<Pair<Method, Any>>()
        private val overlay = DanmakuMarkerView(root.context, ::duration, ::position, ::seek)
        @Volatile private var loaded: DanmakuMarkerScan? = null

        fun start() {
            val container = root.get() ?: return
            overlay.attach(container)
            val playerAccess = accessOf()
            if (playerAccess != null) {
                observe(playerAccess.releaseObserver, playerAccess.registerRelease, playerAccess.unregisterRelease) { name, _ ->
                    when (name) {
                        "onPlayerWillRelease" -> {
                            invalid.set(true)
                            owner.get()?.let { bindings.release(it, epoch) }
                            postMain("danmaku_jump_markers.release") {
                                if (current === this) detach(false)
                            }
                        }
                        "onPlayerItemWillChanged", "onPlayerItemRelease" -> {
                            postMain("danmaku_jump_markers.item_release") {
                                if (current === this) overlay.syncPlacement(force = true)
                            }
                        }
                    }
                }
                observe(playerAccess.progressObserver, playerAccess.registerProgress, playerAccess.unregisterProgress) { name, _ ->
                    if (name == "onPlayerProgressChange" &&
                        !invalid.get() &&
                        progressAlignPending.compareAndSet(false, true)
                    ) {
                        postMain("danmaku_jump_markers.progress") {
                            progressAlignPending.set(false)
                            if (current === this && !invalid.get()) {
                                overlay.syncPlacement(force = false)
                            }
                        }
                    }
                }
            }
            reportObserved()
            executor.execute(HostThreadGuard.runnable("danmaku_jump_markers.scan") {
                val result = DanmakuMarkerClient.scan(video) { trace ->
                    postMain("danmaku_jump_markers.trace") {
                        if (current === this && !invalid.get()) logInfo("marker_scan", "[BIL] 弹幕抽样 $trace")
                    }
                }
                loaded = result
                postMain("danmaku_jump_markers.ready") {
                    if (current !== this || invalid.get()) return@postMain
                    val points = result?.points.orEmpty()
                    if (points.isNotEmpty()) reportObserved()
                    overlay.marks(points)
                    logInfo(
                        "marker_ready",
                        "[BIL] 弹幕标记 video=${video.bvid} points=${points.size} " +
                            "segments=${result?.segments ?: 0} danmaku=${result?.danmaku ?: 0}"
                    )
                }
            })
        }

        private fun reportObserved() {
            if (observedReported.compareAndSet(false, true)) {
                env?.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
            }
        }

        private fun observe(
            type: Class<*>,
            register: Method,
            remove: Method,
            callback: (String, Array<out Any?>?) -> Unit
        ) {
            val weak = WeakReference(this)
            val observer = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "InnocentLabDanmakuMarkerObserver"
                    else -> {
                        if (weak.get()?.invalid?.get() == false) {
                            HostThreadGuard.run("danmaku_jump_markers.observer") {
                                callback(method.name, args)
                            }
                        }
                        hostProxyDefaultValue(method.returnType)
                    }
                }
            }
            val target = wrapper.get() ?: return
            HostThreadGuard.run("danmaku_jump_markers.observe_register") {
                register.invoke(target, observer)
                unregister += remove to observer
            }
        }

        fun stop() {
            invalid.set(true)
            wrapper.get()?.let { target ->
                unregister.asReversed().forEach { (method, observer) ->
                    HostThreadGuard.run("danmaku_jump_markers.unregister") {
                        method.invoke(target, observer)
                    }
                }
            }
            unregister.clear()
            overlay.detach()
            loaded = null
        }

        fun duration(): Long = HostThreadGuard.call("danmaku_jump_markers.duration", 0L) {
            val core = wrapper.get() ?: return@call 0L
            (accessOf()?.duration?.invoke(core) as? Int)?.toLong() ?: 0L
        }

        fun position(): Long = HostThreadGuard.call("danmaku_jump_markers.position", 0L) {
            val core = wrapper.get() ?: return@call 0L
            (accessOf()?.position?.invoke(core) as? Int)?.toLong() ?: 0L
        }

        fun seek(targetMs: Long): Boolean = HostThreadGuard.call("danmaku_jump_markers.seek", false) {
            val core = wrapper.get() ?: return@call false
            val ownerObj = owner.get() ?: return@call false
            val act = activity.get() ?: return@call false
            val playerAccess = accessOf() ?: return@call false
            if (invalid.get() || current !== this || act.isFinishing || act.isDestroyed) return@call false
            if (!bindings.current(ownerObj, candidate, epoch, core, act)) return@call false
            val live = playerAccess.video(ownerObj) ?: return@call false
            if (live.bvid != video.bvid || live.cid != video.cid) return@call false
            val total = duration()
            performAccurateJumpSeek(playerAccess, core, targetMs, total, env)
        }
    }

    private fun logInfo(key: String, message: String) {
        env?.logInfo(key, message)
    }

    internal companion object {
        const val ID = "danmaku_jump_markers"
        const val CHANNEL_STATUS = "danmaku_jump_markers_status"
        const val DETAIL = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"

        internal fun performAccurateJumpSeek(
            access: SponsorPlayerAccess,
            core: Any,
            targetMs: Long,
            durationMs: Long,
            environment: HookEnvironment?
        ): Boolean {
            if (durationMs <= 0L || targetMs !in 0L until durationMs) return false
            access.seek.invoke(core, targetMs.toInt(), SponsorPlayerAccess.ACCURATE_SEEK)
            environment?.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
            return true
        }
    }

    private fun activityOf(context: Context): Activity? {
        var value: Context? = context
        repeat(8) {
            if (value is Activity) return value
            val next = (value as? ContextWrapper)?.baseContext ?: return null
            if (next === value) return null
            value = next
        }
        return null
    }
}
