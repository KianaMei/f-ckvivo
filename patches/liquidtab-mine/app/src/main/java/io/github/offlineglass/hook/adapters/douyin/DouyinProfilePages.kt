package io.github.offlineglass.hook.adapters.douyin

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Roots returned by the actual profile fragments, before their first layout.
 * Verified against 40.8 ProfilePageFragmentV2 and BasePandaFragment; no resource
 * IDs, label guesses, view-tree searches or per-frame layout listener. */
internal object DouyinProfilePages {
    private val roots = WeakHashMap<View, Boolean>()
    private val visibleRect = Rect()
    private var installed = false
    private var statusWritten = false

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        for (name in arrayOf(
            "com.ss.android.ugc.aweme.profile.ProfilePageFragmentV2",
            "com.ss.android.ugc.aweme.profile.ui.core.BasePandaFragment",
            "com.ss.android.ugc.aweme.profile.ui.core.UserPandaFragment",
        )) {
            runCatching {
                val type = Class.forName(name, false, loader)
                val method = type.getDeclaredMethod("onCreateView", LayoutInflater::class.java,
                    ViewGroup::class.java, android.os.Bundle::class.java)
                require(View::class.java.isAssignableFrom(method.returnType))
                val hook = object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val root = p.result as? ViewGroup ?: return
                        var fragmentType: Class<*>? = p.thisObject.javaClass
                        var ownProfile = false
                        while (fragmentType != null) {
                            if (fragmentType.name == "com.ss.android.ugc.aweme.profile.ui.MyPandaFragment" ||
                                fragmentType.simpleName == "MyPandaFragment") ownProfile = true
                            fragmentType = fragmentType.superclass
                        }
                        // BasePanda creates both MyPanda and UserPanda roots.
                        // Keep both for content ownership, hide only other users.
                        roots[root] = !ownProfile
                        if (!statusWritten) {
                            statusWritten = true
                            runCatching {
                                java.io.File(root.context.getExternalFilesDir(null), "dy_profile_binding_status.txt")
                                    .writeText("time=${System.currentTimeMillis()} fragment=$name root=${root.javaClass.name}\n")
                            }
                        }
                    }
                }
                XposedBridge::class.java.getMethod("hookMethod", java.lang.reflect.Member::class.java, XC_MethodHook::class.java)
                    .invoke(null, method, hook)
            }.onFailure {
                XposedBridge.log("[LiquidTab] Douyin profile lifecycle binding unavailable $name: $it")
            }
        }
    }

    fun contains(view: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (roots.containsKey(node)) return true
            node = node.parent as? View
        }
        return false
    }

    fun isVisible(window: View?): Boolean {
        window ?: return false
        for (page in roots.keys) {
            if (roots[page] != true) continue
            // Some versions wrap MyPanda in a generic profile fragment. Its
            // ancestor is not an additional "other profile" overlay.
            var wrapsOwnPage = false
            for ((ownRoot, other) in roots) {
                if (other || !ownRoot.isAttachedToWindow || !ownRoot.isShown) continue
                var parent = ownRoot.parent as? View
                while (parent != null && parent !== window) {
                    if (parent === page) { wrapsOwnPage = true; break }
                    parent = parent.parent as? View
                }
                if (wrapsOwnPage) break
            }
            if (wrapsOwnPage) continue
            if (page.rootView === window && page.isAttachedToWindow && page.isShown &&
                page.alpha > .01f && page.getGlobalVisibleRect(visibleRect) &&
                visibleRect.width() > window.width / 2 && visibleRect.height() > window.height / 2) return true
        }
        return false
    }
}
