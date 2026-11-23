package io.github.offlineglass.hook.adapters.bilibili_island

import android.content.Context
import android.os.SystemClock
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Only extends the two Bilibili media allowlist decisions; native freeform checks remain authoritative. */
internal object BilibiliIslandBridge {
    private val bound = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val enabled = HashMap<String, Boolean>()
    private val requested = HashMap<String, Long>()
    private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        val owner = loader.loadClass("com.android.systemui.statusbar.notification.DynamicIslandPluginController")
        val methods = owner.declaredMethods.filter { it.name == "onPluginLoaded" && !it.isBridge && !it.isSynthetic }
        val loaded = methods.singleOrNull()?.takeIf { method ->
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && method.parameterTypes.size == 3 &&
                method.parameterTypes[0].name == "com.android.systemui.plugins.Plugin" &&
                method.parameterTypes[1] == Context::class.java &&
                method.parameterTypes[2].name == "com.android.systemui.plugins.PluginLifecycleManager"
        } ?: error("Native island plugin lifecycle contract unavailable")
        XposedBridge.hookMethod(loaded, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val plugin = param.args[0] ?: return
                val context = param.args[1] as? Context ?: return
                if (context.packageName != "miui.systemui.plugin") return
                runCatching { bind(plugin.javaClass.classLoader ?: return, context) }
                    .onFailure { XposedBridge.log("LiquidTab Bilibili island binding rejected: $it") }
            }
        })
        installed = true
        XposedBridge.log("LiquidTab Bilibili island: waiting for native plugin lifecycle")
    }

    private fun refresh(context: Context, pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - (requested[pkg] ?: -10_000L) < 2000L) return
        requested[pkg] = now
        HookConfigReader.readFreshAsync(context, pkg) { snapshot ->
            enabled[pkg] = snapshot?.enabled == true
        }
    }

    private fun bind(loader: ClassLoader, context: Context) {
        val manager = loader.loadClass("miui.systemui.notification.NotificationSettingsManager")
        if (manager in bound) return
        val eligibility = BilibiliIslandContract.eligibility(manager)
        val app = context.applicationContext ?: context
        listOf("tv.danmaku.bili", "com.bilibili.app.in").forEach { refresh(app, it) }
        XposedBridge.hookMethod(eligibility, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkg = param.args[0] as? String ?: return
                if (!BilibiliIslandContract.target(pkg) || param.hasThrowable()) return
                // No provider/Binder lookup on the system animation/input path.
                refresh(app, pkg)
                if (enabled[pkg] == true) param.result = true
            }
        })
        bound += manager
        XposedBridge.log("LiquidTab Bilibili island: native media eligibility bound; other checks retained")
    }
}
