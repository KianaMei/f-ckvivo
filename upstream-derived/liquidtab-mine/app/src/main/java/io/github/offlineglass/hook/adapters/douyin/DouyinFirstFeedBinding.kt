package io.github.offlineglass.hook.adapters.douyin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Modifier

internal object DouyinFirstFeedBinding {
    fun install(context: Context, loader: ClassLoader) {
        val host = context.packageName
        if (host != "com.ss.android.ugc.aweme") return
        runCatching {
            val info = context.packageManager.getPackageInfo(host, PackageManager.PackageInfoFlags.of(0))
            // 41.2.0 ships under several build codes (410200 on the fold,
            // 410201 on the bar phone); the reflective class/method checks
            // below stay authoritative for the exact host semantics.
            if (info.longVersionCode !in 410200L..410299L || info.versionName != "41.2.0") return
            val manager = Class.forName("X.0zm2", false, loader)
            require(manager.getDeclaredField("LIZ").type.name == "com.ss.android.ugc.aweme.feed.model.VideoItemParams")
            val shouldDefer = manager.getDeclaredMethod("LIZ")
            require(!Modifier.isStatic(shouldDefer.modifiers) && shouldDefer.returnType == Boolean::class.javaPrimitiveType)
            XposedBridge.hookMethod(shouldDefer, object : XC_MethodHook() {
                private var reported = false

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.thisObject?.javaClass !== manager || param.hasThrowable() || param.result != true) return
                    if (Looper.myLooper() !== Looper.getMainLooper()) return
                    param.setResult(false)
                    if (!reported) {
                        reported = true
                        Log.i("LiquidTab", "first_feed_binding native_defer=true action=immediate")
                    }
                }
            })
            Log.i("LiquidTab", "first_feed_binding armed")
        }.onFailure { Log.w("LiquidTab", "first_feed_binding unavailable", it) }
    }
}
