package io.github.offlineglass.hook.adapters.bilibili

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.Collections
import java.util.WeakHashMap

/** Pause new homepage backdrop sampling while a video covers home and during its return. */
internal object BilibiliDetailCover {
    private val COVERED_PAGES = setOf(
        "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
        "com.bilibili.video.story.StoryVideoActivity",
        "com.bilibili.video.story.StoryTransparentActivity",
    )

    // A quick back can stop/destroy the detail before the window transition ends.
    private const val EXIT_SETTLE_MS = 500L
    private val active = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val hosts = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val handler = Handler(Looper.getMainLooper())
    private val pendingExits = WeakHashMap<Activity, Runnable>()

    fun install() {
        XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name in COVERED_PAGES) enter(activity)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                // Re-arm after a background round-trip.
                if (activity.javaClass.name in COVERED_PAGES) enter(activity)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name in COVERED_PAGES) scheduleExit(activity)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onStop", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                // Backgrounding is not a return to home; keep the cover armed.
                if (activity.javaClass.name in COVERED_PAGES && activity.isFinishing) scheduleExit(activity)
            }
        })
    }

    private fun enter(activity: Activity) {
        cancelPendingExit(activity)
        val added = synchronized(active) { active.add(activity) }
        if (added) Log.i("LiquidTabCover", "enter ${activity.javaClass.simpleName} t=${SystemClock.uptimeMillis()}")
    }

    /** Deferred exit: the resume must never race the host's return transition. */
    private fun scheduleExit(activity: Activity) {
        val task = Runnable {
            synchronized(pendingExits) { pendingExits.remove(activity) }
            exit(activity)
        }
        val previous = synchronized(pendingExits) { pendingExits.put(activity, task) }
        if (previous != null) handler.removeCallbacks(previous)
        handler.postDelayed(task, EXIT_SETTLE_MS)
    }

    private fun cancelPendingExit(activity: Activity) {
        val task = synchronized(pendingExits) { pendingExits.remove(activity) } ?: return
        handler.removeCallbacks(task)
    }

    private fun exit(activity: Activity) {
        val cleared = synchronized(active) {
            active.remove(activity)
            active.isEmpty()
        }
        if (!cleared) return
        val pending = synchronized(hosts) { hosts.toList().also { hosts.clear() } }
        Log.i("LiquidTabCover", "exit ${activity.javaClass.simpleName} hosts=${pending.size} t=${SystemClock.uptimeMillis()}")
        // Resume sampling and refresh labels without waiting for a tab switch.
        pending.forEach { host ->
            if (!host.isAttachedToWindow) return@forEach
            host.postInvalidateOnAnimation()
            host.postDelayed({
                if (host.isAttachedToWindow) {
                    host.invalidate()
                    (host.parent as? View)?.invalidate()
                }
            }, 80L)
        }
    }

    val paused: Boolean get() = synchronized(active) { active.isNotEmpty() }

    /** Remember only the sampling hosts that need a redraw when the cover ends. */
    fun pauseBackdropWork(host: View): Boolean {
        if (!paused) return false
        synchronized(hosts) { hosts.add(host) }
        return true
    }
}
