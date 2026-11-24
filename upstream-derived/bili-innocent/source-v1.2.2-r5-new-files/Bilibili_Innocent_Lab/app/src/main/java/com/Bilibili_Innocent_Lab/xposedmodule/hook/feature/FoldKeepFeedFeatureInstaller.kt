package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

internal class FoldKeepFeedFeatureInstaller(
    private val enabled: Boolean,
    private val hostVersionCode: Int
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        if (!enabled) {
            environment.reportStatus(CHANNEL, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        val loader = environment.classLoader ?: run {
            environment.reportStatus(CHANNEL, "missing-class-loader")
            return FeatureInstallResult.Skipped("missing-class-loader")
        }
        if (hostVersionCode == WINDOW_PANE_LAYOUT_ONLY_VERSION) {
            return installWindowPaneLayoutOnly(environment, loader)
        }
        val configClass = KavaMemberLookup.classOrNull(loader, CONFIG_CLASS) ?: run {
            environment.reportStatus(CHANNEL, "missing-host-structure")
            return FeatureInstallResult.Skipped("missing-host-structure")
        }
        var installed = 0
        for (name in KEEP_METHODS) {
            val method = KavaMemberLookup.methodOrNull(configClass, name)?.takeIf {
                it.declaringClass == configClass && Modifier.isStatic(it.modifiers) &&
                    it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.isEmpty()
            }
            if (method == null) {
                environment.logInfo("fold_keep_absent_$name", "[BIL] $ID missing-static-boolean: $name")
                continue
            }
            val firstHit = AtomicBoolean(false)
            val registered = runCatching {
                environment.registrar.exact("fold.keep.$name", method.declaringClass, name) {
                    replaceAny {
                        if (!firstHit.get() && firstHit.compareAndSet(false, true)) {
                            // 日志失败也不改变原先的布尔替换结果。
                            runCatching { environment.logInfo("fold_keep_hit_$name", "[BIL] $ID getter-hit: $name") }
                        }
                        true
                    }
                }
            }.onFailure { throwable ->
                environment.logError("fold_keep_register_error_$name", "[BIL] $ID register-failed: $name: $throwable")
            }.isSuccess
            if (registered) {
                installed += 1
                environment.logInfo("fold_keep_registered_$name", "[BIL] $ID registered: $name")
            }
        }
        return if (installed > 0) {
            environment.reportStatus(CHANNEL, "success")
            FeatureInstallResult.Installed(installed)
        } else {
            environment.reportStatus(CHANNEL, "no-safe-hook-point")
            FeatureInstallResult.Skipped("no-safe-hook-point")
        }
    }

    private fun installWindowPaneLayoutOnly(environment: HookEnvironment, loader: ClassLoader): FeatureInstallResult {
        val pane = KavaMemberLookup.classOrNull(loader, WINDOW_PANE_CLASS)
        val size = KavaMemberLookup.classOrNull(loader, WINDOW_SIZE_CLASS)
        val method = if (pane != null && size != null) {
            KavaMemberLookup.methodOrNull(pane, WINDOW_PANE_RESIZE, size, java.lang.Boolean.TYPE)?.takeIf {
                it.declaringClass == pane && !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE
            }
        } else null
        if (method == null || size == null) {
            environment.reportStatus(CHANNEL, "missing-host-structure")
            return FeatureInstallResult.Skipped("missing-host-structure")
        }
        val firstHit = AtomicBoolean(false)
        val registered = runCatching {
            environment.registrar.exact("fold.keep.window-pane-layout-only", method.declaringClass, method.name,
                size, java.lang.Boolean.TYPE) {
                before {
                    if (argOrNull(0) == null || argOrNull(1) != true) return@before
                    args[1] = false
                    if (!firstHit.get() && firstHit.compareAndSet(false, true)) {
                        runCatching { environment.logInfo("fold_keep_layout_only_hit", "[BIL] $ID layout-only-hit: window-pane") }
                    }
                }
            }
        }.onFailure { throwable ->
            environment.logError("fold_keep_layout_only_register_error", "[BIL] $ID register-failed: window-pane: $throwable")
        }.isSuccess
        if (!registered) {
            environment.reportStatus(CHANNEL, "registration-failed")
            return FeatureInstallResult.Skipped("registration-failed")
        }
        environment.logInfo("fold_keep_layout_only_registered", "[BIL] $ID registered: window-pane-layout-only")
        environment.reportStatus(CHANNEL, "success")
        return FeatureInstallResult.Installed()
    }

    companion object {
        const val ID = "fold_keep_not_refresh"
        private const val CHANNEL = "fold_keep_not_refresh"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val WINDOW_PANE_LAYOUT_ONLY_VERSION = 9180300
        private const val WINDOW_PANE_CLASS = "com.bilibili.pegasus.components.windowpane.WindowPaneStyleComponent"
        private const val WINDOW_SIZE_CLASS = "androidx.window.core.layout.WindowSizeClass"
        private const val WINDOW_PANE_RESIZE = "f"
        private const val CONFIG_CLASS = "com.bilibili.pegasus.PegasusDDConfigKt"
        private val KEEP_METHODS = listOf(
            "getEnableFoldKeepNotRefresh",
            "getEnableKeepFeedsOnWideColumnSwitch"
        )
    }
}
