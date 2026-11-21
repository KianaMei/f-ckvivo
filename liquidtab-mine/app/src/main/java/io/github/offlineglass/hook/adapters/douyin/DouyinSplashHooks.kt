package io.github.offlineglass.hook.adapters.douyin

import android.content.Context
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Modifier

internal object DouyinSplashHooks {
    private var started = false
    @Synchronized fun prepare(context: Context) {
        if (started) return
        started = true
        // APK discovery runs outside startup class initialization and never waits on UI.
        Thread({
            val status = java.io.File(context.getExternalFilesDir(null), "dy_splash_binding_status.txt")
            runCatching {
                val apk = java.io.File(context.applicationInfo.sourceDir)
                val identity = "1:${apk.absolutePath}:${apk.length()}:${apk.lastModified()}"
                val cache = java.io.File(context.cacheDir, "dy_splash_admission.properties")
                val properties = java.util.Properties()
                if (cache.isFile) runCatching { cache.inputStream().use(properties::load) }
                val binding = if (properties.getProperty("identity") == identity &&
                    properties.getProperty("owner") != null && properties.getProperty("method") != null)
                    DouyinSplashIndex.Binding(properties.getProperty("owner"), properties.getProperty("method"))
                else DouyinSplashIndex.resolve(apk.absolutePath).also {
                    properties.setProperty("identity", identity)
                    properties.setProperty("owner", it.owner())
                    properties.setProperty("method", it.method())
                    runCatching { cache.outputStream().use { stream -> properties.store(stream, "APK-scoped verified call edge") } }
                }
                val owner = Class.forName(binding.owner(), false, context.classLoader)
                val method = owner.getDeclaredMethod(binding.method(), Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                require(Modifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    // The gate also drives the splash state machine's cold-start
                    // PrepareState: forcing false interrupts it ("isEntryEnable
                    // false" -> prepareInterrupt), which left the feed's landing
                    // page without its prepared handoff — an empty right column
                    // and caption until the first swipe. Observe the native
                    // decision instead; skipping the ad stays with the display
                    // layer (NukeApp SkipSplashAd jumps the ad activity).
                    private var logged = 0
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (logged < 5) {
                            logged++
                            android.util.Log.i("LiquidTab", "splash admission gate native=${p.result}")
                        }
                    }
                })
                status.writeText("time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()} observing native cold/hot admission gate (no override)\n")
            }.onFailure {
                runCatching { status.writeText("time=${System.currentTimeMillis()} failed=$it\n") }
                XposedBridge.log("[LiquidTab] Douyin splash contract unavailable: $it")
            }
        }, "DouyinSplashBinding").start()
    }
}
