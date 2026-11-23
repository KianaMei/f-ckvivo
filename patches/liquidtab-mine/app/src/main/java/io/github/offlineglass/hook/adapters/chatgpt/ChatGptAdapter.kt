package io.github.offlineglass.hook.adapters.chatgpt

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** ChatGPT owns its composer overlay; it does not participate in tab-bar discovery. */
internal object ChatGptAdapter : TargetAdapter {
    override val key = "chatgpt"
    override val ownsNavigationFinding = true

    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        ChatGptComposerGlass.install(lpparam.classLoader)
    }

    override fun findNavigation(root: View, spec: TargetSpec) = null

    override fun beforeNavigationScan(activity: android.app.Activity) { ChatGptComposerGlass.scan(activity) }
}
