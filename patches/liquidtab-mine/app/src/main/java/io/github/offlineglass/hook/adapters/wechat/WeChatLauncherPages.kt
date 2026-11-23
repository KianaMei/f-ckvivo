package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup

/** Identify LauncherUI's page owner by its native siblings, never APK resource numbers. */
internal object WeChatLauncherPages {
    private const val PAGER = "com.tencent.mm.ui.base.CustomViewPager"
    private const val TAB = "com.tencent.mm.ui.LauncherUIBottomTabView"
    private const val FROST = "com.tencent.mm.ui.FrostedContentView"

    fun isMainPager(view: View): Boolean {
        if (view.javaClass.name != PAGER || view !is ViewGroup) return false
        val parent = view.parent as? ViewGroup ?: return false
        // Both the current APK and the device hierarchy place the native tab
        // beside the main pager. Secondary/chat pagers do not own that tab.
        return (0 until parent.childCount).any { parent.getChildAt(it).javaClass.name == TAB }
    }

    fun owns(instance: Any?): Boolean {
        val page = instance as? View ?: return false
        if (page.javaClass.name != FROST) return false
        return (page.parent as? View)?.let(::isMainPager) == true
    }
}
