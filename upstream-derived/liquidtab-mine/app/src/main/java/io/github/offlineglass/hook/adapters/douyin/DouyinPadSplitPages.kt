package io.github.offlineglass.hook.adapters.douyin

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Fold/pad inner-screen split panes, verified live on 41.2 dumpsys:
 * pad_impl NewPadMultiScreenDrawerLayout (app:id/t-p) covers the window and
 * hosts side payloads (PadSplitCommentListPageFragment,
 * profile.PadProfilePageFragmentV2, ...) in a right-hand pane whose fragment
 * container swaps shown/hidden roots. The payload fragment shells declare no
 * onCreateView of their own, so bind the drawer View at construction and test
 * pane occupancy per frame: pane -> fragment container -> shown payload root.
 * Empty or GONE panes (closed panel, cached hidden copies) decide "closed".
 * No label, obfuscated resource ID, global Fragment hook or tree scan.
 */
internal object DouyinPadSplitPages {
    private val drawers = WeakHashMap<View, Boolean>()
    private val bounds = Rect()
    private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        val bound = ArrayList<String>()
        for (name in arrayOf(
            "com.ss.android.ugc.aweme.pad_impl.multi_screen.multi_screen.base.view.NewPadMultiScreenDrawerLayout",
            "com.ss.android.ugc.aweme.pad_impl.multi_screen.multi_screen.base.view.PadMultiScreenDrawerLayout",
        )) {
            runCatching {
                val type = Class.forName(name, false, loader)
                require(ViewGroup::class.java.isAssignableFrom(type))
                XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val drawer = param.thisObject as? View ?: return
                        drawers[drawer] = true
                        // One binding record per drawer instance, not per frame.
                        runCatching {
                            java.io.File(drawer.context.getExternalFilesDir(null), "dy_pad_split_binding_status.txt")
                                .appendText("time=${System.currentTimeMillis()} drawer=$name\n")
                        }
                    }
                })
                bound += name
            }.onFailure {
                XposedBridge.log("[LiquidTab] pad split drawer binding unavailable $name: $it")
            }
        }
        // Install-time marker: proves which module build is live in this host.
        runCatching {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication")
                .invoke(null) as? android.content.Context ?: return@runCatching
            java.io.File(app.getExternalFilesDir(null), "dy_pad_split_install_status.txt")
                .writeText("time=${System.currentTimeMillis()} bound=${bound.joinToString(";")}\n")
        }
    }

    fun isVisible(window: View?): Boolean = occupiedPaneLeft(window) != 0

    /** Window x where the occupied split pane begins (0 = no open pane). The
     * feed column narrows to this edge, so the floating bar centers inside it. */
    fun occupiedPaneLeft(window: View?): Int {
        window ?: return 0
        for (drawer in drawers.keys) {
            if (drawer.rootView !== window || !drawer.isAttachedToWindow || !drawer.isShown ||
                drawer.width <= 0 || drawer.height <= 0) continue
            val group = drawer as? ViewGroup ?: continue
            for (paneIndex in 0 until group.childCount) {
                val pane = group.getChildAt(paneIndex) as? ViewGroup ?: continue
                if (!pane.isShown || pane.width <= window.width / 5) continue
                for (containerIndex in 0 until pane.childCount) {
                    val container = pane.getChildAt(containerIndex) as? ViewGroup ?: continue
                    if (!container.isShown) continue
                    for (payloadIndex in 0 until container.childCount) {
                        val payload = container.getChildAt(payloadIndex)
                        if (!payload.isShown || payload.width <= 0 || payload.height <= 0) continue
                        var node: View? = payload
                        var alpha = 1f
                        while (node != null) { alpha *= node.alpha; node = node.parent as? View }
                        if (alpha <= .01f || !payload.getGlobalVisibleRect(bounds)) continue
                        // A split pane is a real side column, not a corner
                        // overlay; its payload spans a third of the window.
                        if (bounds.height() > window.height / 3) {
                            pane.getLocationInWindow(paneOrigin)
                            return paneOrigin[0]
                        }
                    }
                }
            }
        }
        return 0
    }

    private val paneOrigin = IntArray(2)
}
