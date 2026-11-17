package io.github.offlineglass.hook.adapters.jd

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.ref.WeakReference

/** Native navigation receiver; timing belongs to the common first-visit gate. */
internal class JdTabTouch(private val host: GlassHostLayout, private val preview: JdPagePreview) {
    var pendingTarget = -1
        private set
    private var cachedSource: WeakReference<ViewGroup>? = null
    private var cachedRow: WeakReference<ViewGroup>? = null
    private fun row(source: ViewGroup?): ViewGroup? {
        source ?: return null
        if (cachedSource?.get() === source) cachedRow?.get()?.takeIf { it.isAttachedToWindow }?.let { return it }
        val stack = ArrayDeque<View>(); stack += source
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 40) {
            val view = stack.removeLast()
            var type: Class<*>? = view.javaClass
            while (type != null) {
                if (type.name == "com.jingdong.common.unification.navigationbar.newbar.NavigationGroup" && view is ViewGroup) {
                    cachedSource = WeakReference(source); cachedRow = WeakReference(view)
                    return view
                }
                type = type.superclass
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        return null
    }
    fun selected(source: ViewGroup?, count: Int): Int? {
        val group = row(source) ?: return null
        val id = runCatching { XposedHelpers.callMethod(group, "getCheckId") as Int }.getOrNull() ?: return null
        for (index in 0 until minOf(group.childCount, count)) {
            val nativeId = runCatching { XposedHelpers.callMethod(group.getChildAt(index), "getNavigationId") as Int }.getOrNull()
            if (nativeId == id) return index
        }
        return null
    }
    fun dispatch(source: ViewGroup?, index: Int, count: Int): Boolean {
        val group = row(source) ?: return false
        val button = group.getChildAt(index.takeIf { it in 0 until minOf(group.childCount, count) } ?: return false)
        val touch = runCatching { XposedHelpers.callMethod(button, "getTouchView") as View }.getOrNull() ?: return false
        if (!touch.isAttachedToWindow || !touch.isEnabled || !host.hasWindowFocus()) return false
        cancel()
        pendingTarget = index
        android.os.Trace.beginSection("LiquidTab.JD.nativeTabClick")
        var accepted = false
        try {
            JdNativePageTransitions.click(host) {
                JdNavigationClickGate.click { accepted = touch.performClick() }
            }
        } finally {
            pendingTarget = -1
            android.os.Trace.endSection()
        }
        // Never retry a business click or mark a native cooldown rejection visited.
        return accepted && selected(source, count) == index
    }
    fun cancel() {
        pendingTarget = -1
        if (preview.target >= 0) preview.finish()
        preview.nativeWillDispatch()
    }
}
