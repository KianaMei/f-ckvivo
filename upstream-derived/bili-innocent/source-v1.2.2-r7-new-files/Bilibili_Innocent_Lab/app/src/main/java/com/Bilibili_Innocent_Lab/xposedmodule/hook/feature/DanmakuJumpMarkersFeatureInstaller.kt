package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorPlayerBindings
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorVideoId
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * 弹幕刷屏时间点标记 + 点击跳转。
 *
 * 数据不走 Chronos 脚本补丁：宿主持有播放器时按 bvid/cid 只读拉取 `seg.so`，
 * 本地做「同一句话 5 秒内被 ≥3 个不同发送者刷屏」的共识，只把结果画在进度条上方。
 * 点击刻线通过播放器 core 的 seekTo 跳转，不依赖宿主弹幕引擎私有协议。
 */
internal class DanmakuJumpMarkersFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id: String = ID
    override val capabilityIds: List<String> get() = if (enabled) listOf(ID) else emptyList()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val access = SponsorPlayerAccess.resolve(loader) ?: run {
            environment.logInfo("danmaku_marker_skip", "[BIL] 弹幕刷屏标记跳过: missing-player-structure")
            return FeatureInstallResult.Skipped("missing-player-structure")
        }
        val detail = Lookup.classOrNull(loader, DETAIL)?.takeIf { Activity::class.java.isAssignableFrom(it) } ?: run {
            environment.logInfo("danmaku_marker_skip", "[BIL] 弹幕刷屏标记跳过: missing-detail-activity")
            return FeatureInstallResult.Skipped("missing-detail-activity")
        }
        val lifecycle = listOf("callActivityOnResume", "callActivityOnPause", "callActivityOnDestroy").map { name ->
            Lookup.methodOrNull(Instrumentation::class.java, name, Activity::class.java)
                ?: return@map null
        }
        if (lifecycle.any { it == null }) {
            environment.logInfo("danmaku_marker_skip", "[BIL] 弹幕刷屏标记跳过: missing-detail-lifecycle")
            return FeatureInstallResult.Skipped("missing-detail-lifecycle")
        }
        val hooks = lifecycle.filterNotNull()
        this.access = access
        this.env = environment
        var count = 0

        environment.registrar.constructor("danmaku.marker.wrapper", access.wrapper) { after {
            if (hasThrowable) return@after
            val owner = argOrNull(access.wrapperOwnerIndex) ?: return@after
            val wrapper = instance ?: return@after
            val core = argOrNull(access.wrapperCoreIndex) ?: return@after
            bindings.wrapper(owner, wrapper, core); refresh()
        } }
        access.scope?.let { scope ->
            count++
            environment.registrar.constructor("danmaku.marker.scope", scope) { after {
                if (hasThrowable) return@after
                val owner = argOrNull(2) ?: return@after
                val activity = (argOrNull(1) as? android.content.Context)?.let(::activityOf) ?: return@after
                bindings.activity(owner, activity); refresh()
            } }
        }
        access.containerScope?.let { scope ->
            count++
            environment.registrar.constructor("danmaku.marker.container", scope.constructor) { after {
                if (hasThrowable) return@after
                val activity = (argOrNull(scope.contextIndex) as? android.content.Context)?.let(::activityOf) ?: return@after
                val container = argOrNull(scope.containerIndex) ?: return@after
                val core = runCatching { scope.core.invoke(container) }.getOrNull() ?: return@after
                bindings.container(core, activity); refresh()
            } }
        }
        environment.registrar.exact("danmaku.marker.media", access.run.declaringClass, access.run.name,
            *access.run.parameterTypes) {
            before {
                val owner = instance ?: return@before
                setObjectExtra("danmaku_marker_epoch", bindings.begin(owner))
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
        hooks.forEachIndexed { index, method ->
            environment.registrar.exact("danmaku.marker.life.$index", method.declaringClass, method.name,
                *method.parameterTypes) {
                if (index == 0) after {
                    val activity = argOrNull(0) as? Activity ?: return@after
                    if (!hasThrowable && detail.isInstance(activity)) {
                        foreground = WeakReference(activity)
                        refresh()
                    }
                } else before {
                    val activity = argOrNull(0) as? Activity ?: return@before
                    if (foreground.get() === activity) {
                        foreground = WeakReference(null)
                        detach(true)
                    }
                }
            }
            count++
        }
        environment.reportStatus(CHANNEL_STATUS, "installed")
        environment.logInfo("danmaku_marker_installed",
            "[BIL] 弹幕刷屏标记已安装 hooks=$count detail=${detail.name.substringAfterLast('.')}")
        return FeatureInstallResult.Installed(count)
    }

    private var foreground = WeakReference<Activity>(null)
    private var current: Session? = null
    private var access: SponsorPlayerAccess? = null
    private var env: HookEnvironment? = null
    private val bindings = SponsorPlayerBindings()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BIL-DanmakuMarkers").apply { isDaemon = true }
    }

    private fun refresh() {
        handler.post {
            val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                ?: run { detach(false); return@post }
            val entry = bindings.select(activity) ?: run { detach(false); return@post }
            val owner = entry.first
            val candidate = entry.second
            val wrapper = candidate.wrapper.get() ?: run { detach(false); return@post }
            val video = access?.video(owner) ?: run { detach(false); return@post }
            if (current?.let { it.video == video && it.activity.get() === activity } == true) return@post
            detach(false)
            val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return@post
            current = Session(video, owner, wrapper, activity, root).also { it.start() }
        }
    }

    private fun accessOf(): SponsorPlayerAccess? = access

    private fun detach(screenLeft: Boolean) {
        current?.stop()
        current = null
    }

    private inner class Session(val video: SponsorVideoId, owner: Any, wrapper: Any,
        activity: Activity, root: FrameLayout) {
        val activity = WeakReference(activity)
        private val owner = WeakReference(owner)
        private val wrapper = WeakReference(wrapper)
        private val root = WeakReference(root)
        private val overlay = DanmakuMarkerView(root.context, ::duration, ::position, ::seek)
        @Volatile private var loaded: DanmakuMarkerScan? = null

        fun start() {
            overlay.attach(root.get() ?: return)
            executor.execute {
                val result = DanmakuMarkerClient.scan(video) { trace ->
                    handler.post { if (current === this) logInfo("marker_scan", "[BIL] 弹幕抽样 $trace") }
                }
                loaded = result
                handler.post {
                    if (current !== this) return@post
                    overlay.marks(result?.points.orEmpty())
                    logInfo("marker_ready", "[BIL] 弹幕标记 video=${video.bvid} points=${result?.points?.size ?: 0} " +
                        "segments=${result?.segments ?: 0} danmaku=${result?.danmaku ?: 0}")
                }
            }
        }

        fun stop() {
            overlay.detach()
            loaded = null
        }

        fun duration(): Long = runCatching {
            val core = wrapper.get() ?: return 0L
            (accessOf()?.duration?.invoke(core) as? Int)?.toLong() ?: 0L
        }.getOrDefault(0L)

        fun position(): Long = runCatching {
            val core = wrapper.get() ?: return 0L
            (accessOf()?.position?.invoke(core) as? Int)?.toLong() ?: 0L
        }.getOrDefault(0L)

        fun seek(targetMs: Long): Boolean = runCatching {
            val core = wrapper.get() ?: return false
            val owner = owner.get() ?: return false
            val activity = activity.get() ?: return false
            if (current !== this || activity.isFinishing || activity.isDestroyed) return false
            val live = accessOf()?.video(owner) ?: return false
            if (live.bvid != video.bvid || live.cid != video.cid) return false
            val total = duration()
            if (total <= 0 || targetMs !in 0 until total) return false
            accessOf()?.seek?.invoke(core, targetMs.toInt(), false)
            true
        }.getOrDefault(false)
    }

    private fun logInfo(key: String, message: String) { env?.logInfo(key, message) }

    private companion object {
        const val ID = "danmaku_jump_markers"
        const val CHANNEL_STATUS = "danmaku_jump_markers_status"
        const val DETAIL = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
    }

    private fun activityOf(context: android.content.Context): Activity? {
        var value: android.content.Context? = context
        while (value is android.content.ContextWrapper) {
            if (value is Activity) return value
            value = value.baseContext
        }
        return value as? Activity
    }
}
