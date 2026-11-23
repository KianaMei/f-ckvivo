package io.github.offlineglass.hook.adapters.xhs

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import io.github.offlineglass.BuildConfig
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object XhsLifecycleTrace {
    private const val TAG = "LiquidTabCover"
    private const val TRACE_FILE = "liquidtab-xhs-return.log"
    private const val MAX_FILE_BYTES = 128L * 1024L
    private val persistent = BuildConfig.XHS_RETURN_TRACE && Application.getProcessName() == "com.xingin.xhs"
    private val gateStates = WeakHashMap<View, Boolean>()
    private var writeFailed = false
    private val writer by lazy {
        ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(256),
            { task -> Thread(task, "LiquidTab-XhsReturnTrace").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            } },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    }

    fun install() {
        XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                log("create", activity)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onPause", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                log("pause", activity, " finishing=${activity.isFinishing}")
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onStop", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                log("stop", activity, " finishing=${activity.isFinishing}")
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                log("destroy", activity, " finishing=${activity.isFinishing}")
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onWindowFocusChanged", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (param.args.firstOrNull() as? Boolean != true) return
                log("focus", activity,
                    " paused=${XhsDetailCover.paused} active=[${XhsDetailCover.describe()}]")
            }
        })
        if (!persistent) return
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                log("post-resume", activity, " finishing=${activity.isFinishing}")
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "overridePendingTransition", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name !in XhsDetailGlass.COVERED_PAGES) return
                val enterAnim = param.args.getOrNull(0) as? Int ?: return
                val exitAnim = param.args.getOrNull(1) as? Int ?: return
                log("transition-request", activity, " finishing=${activity.isFinishing}" +
                    " enter=$enterAnim exit=$exitAnim threw=${param.hasThrowable()}")
            }
        })
    }

    private fun log(stage: String, activity: Activity, extra: String = "") {
        event(activity, "trace $stage ${activity.javaClass.simpleName}@" +
            Integer.toHexString(System.identityHashCode(activity)) + extra)
    }

    fun event(context: Context, message: String) {
        Log.i(TAG, message)
        record(context, message)
    }

    fun backdropDecision(host: View, surface: String, paused: Boolean) {
        if (!persistent || host.windowVisibility != View.VISIBLE || !host.isShown) return
        if (gateStates[host] == paused) return
        gateStates[host] = paused
        record(host.context, "gate surface=$surface view=${System.identityHashCode(host)} " +
            "paused=$paused focus=${host.hasWindowFocus()}")
    }

    fun record(context: Context, message: String) {
        if (!persistent) return
        val application = context.applicationContext ?: return
        val line = "${SystemClock.elapsedRealtime()} wall=${System.currentTimeMillis()} " +
            "pid=${Process.myPid()} build=${BuildConfig.VERSION_CODE} ${message.take(1024)}\n"
        writer.execute {
            runCatching { append(application.filesDir, line) }
                .onSuccess { writeFailed = false }
                .onFailure {
                    if (!writeFailed) Log.w(TAG, "return trace write failed: ${it.javaClass.simpleName}")
                    writeFailed = true
                }
        }
    }

    private fun append(directory: File, message: String) {
        val entry = message.toByteArray(Charsets.UTF_8)
        val target = File(directory, TRACE_FILE)
        if (target.length() + entry.size > MAX_FILE_BYTES) {
            val previous = File(directory, "$TRACE_FILE.1")
            Files.move(target.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        target.appendBytes(entry)
    }
}
