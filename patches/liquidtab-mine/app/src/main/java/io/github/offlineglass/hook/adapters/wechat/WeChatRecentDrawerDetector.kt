package io.github.offlineglass.hook.adapters.wechat

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference

/** Native recent-page detection; the host owns both visibility animations. */
internal class WeChatRecentDrawerDetector {
    private var panel = WeakReference<View>(null)

    fun isVisible(activity: Activity?, minimumVisibleFraction: Float = 0.25f): Boolean {
        val root = activity?.window?.decorView as? ViewGroup ?: return false
        val cached = panel.get()
        if (cached != null && cached.isAttachedToWindow && cached.rootView === root) {
            return isExpanded(cached, root, minimumVisibleFraction)
        }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 4096) {
            val (view, depth) = stack.removeLast()
            if (view.javaClass.name == "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer") {
                panel = WeakReference(view)
                return isExpanded(view, root, minimumVisibleFraction)
            }
            if (view is ViewGroup && depth < 40) {
                for (index in view.childCount - 1 downTo 0) {
                    stack += view.getChildAt(index) to depth + 1
                }
            }
        }
        return false
    }

    companion object {
    internal fun isExpanded(view: View, root: View, minimumVisibleFraction: Float): Boolean {
        if (!view.isShown || view.alpha <= 0.01f || root.height <= 0) return false
        val rect = Rect()
        // This is a ConversationListView header. A collapsed/offscreen header
        // can keep its full measured height, so test the visible intersection.
        return view.getGlobalVisibleRect(rect) &&
            rect.width() >= root.width * 0.75f && rect.height() >= root.height * minimumVisibleFraction
    }
    }
}
