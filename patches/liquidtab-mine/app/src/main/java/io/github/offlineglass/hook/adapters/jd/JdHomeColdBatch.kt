package io.github.offlineglass.hook.adapters.jd

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XposedBridge
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/** Adopt JD's existing visible-slot-aware cache refresh batching, only at cold Home. */
internal object JdHomeColdBatch {
    private var configured = false
    private var initialEnabled = false
    private var started = false
    private var until = 0L
    private var configScope = 0
    private var adopted = false
    private var host = WeakReference<GlassHostLayout>(null)

    fun configure(context: Context) {
        initialEnabled = HookConfigReader.read(context, context.packageName).enabled
        configured = true
    }
    fun bindHost(value: GlassHostLayout) {
        if (host.get() !== value) host = WeakReference(value)
    }
    private fun active(): Boolean = configured && (host.get()?.config?.enabled ?: initialEnabled) &&
        SystemClock.uptimeMillis() < until
    private fun isHome(value: Any): Boolean {
        var type: Class<*>? = value.javaClass
        repeat(16) {
            val current = type ?: return false
            if (current.name == "com.jingdong.app.mall.home.JDHomeFragment") return true
            type = current.superclass
        }
        return false
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val fragment = Class.forName("androidx.fragment.app.Fragment", false, loader)
            val activityGetter = fragment.getMethod("getActivity")
            val create = fragment.getDeclaredMethod("performCreate", Bundle::class.java)
            check(create.returnType == Void.TYPE && !Modifier.isStatic(create.modifiers))
            val framework = XposedBridge.framework()
            framework.hook(create).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                if (!started && Looper.myLooper() == Looper.getMainLooper() && configured && initialEnabled &&
                    isHome(chain.thisObject)) {
                    started = true
                    runCatching {
                        val activity = activityGetter.invoke(chain.thisObject) as? Activity
                        check(activity?.javaClass?.name == "com.jingdong.app.mall.MainFrameActivity")
                        bindNativeBatching(chain.thisObject.javaClass.classLoader!!)
                        until = SystemClock.uptimeMillis() + 4_000L
                    }.onFailure { XposedBridge.log("LiquidTab JD Home cold batching unavailable: $it") }
                }
                chain.proceed()
            }
        }.onFailure { XposedBridge.log("LiquidTab JD Home lifecycle contract unavailable: $it") }
    }

    private fun bindNativeBatching(loader: ClassLoader) {
        val homeList = Class.forName("com.jingdong.common.recommend.ui.homerecommend.HomeChildRecyclerView", false, loader)
        val feature = Class.forName("com.jingdong.common.recommend.RecommendHomePerformanceFeature", false, loader)
        val manager = Class.forName("com.jingdong.common.recommend.forlist.RecommendProductManager", false, loader)
        val binding = JdHomeBatchContract.resolve(homeList, ViewGroup::class.java, manager, feature, JSONObject::class.java)
        val framework = XposedBridge.framework()
        val getter = framework.hook(binding.config()).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
            val result = chain.proceed()
            if (configScope > 0 && Looper.myLooper() == Looper.getMainLooper() && active() &&
                result is JSONObject && result.optString("enable") != "1") {
                // Clone only this call's config; never persist/overwrite server
                // settings or change the native delay, visible threshold or data.
                runCatching { JSONObject(result.toString()).put("enable", "1") }.getOrDefault(result).also {
                    if (it !== result && !adopted) {
                        adopted = true
                        android.util.Log.i("JdColdInit", "native Home visible-slot refresh batching adopted")
                    }
                }
            } else result
        }
        try {
            framework.hook(binding.completed()).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                if (!active() || Looper.myLooper() != Looper.getMainLooper()) chain.proceed()
                else {
                    configScope++
                    try { chain.proceed() } finally { configScope-- }
                }
            }
        } catch (error: Throwable) {
            getter.unhook()
            throw error
        }
    }
}
