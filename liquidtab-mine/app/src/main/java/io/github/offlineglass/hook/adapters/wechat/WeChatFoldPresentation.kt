package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap

/** Suppress only WeChat's separate floating copy, never a conversation-list row. */
internal object WeChatFoldPresentation {
    private val roots = WeakHashMap<View, Boolean>()
    private val banners = WeakHashMap<View, Boolean>()
    private val owners = WeakHashMap<View, java.lang.ref.WeakReference<View>>()
    private var installed = false

    fun manage(root: View) {
        roots[root] = true
    }

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        val list = XposedHelpers.findClass("com.tencent.mm.ui.conversation.ConversationListView", loader)
        val writer = list.getDeclaredMethod("setFoldBanner", View::class.java)
        check(writer.returnType == Void.TYPE && AbsListView::class.java.isAssignableFrom(list))
        XposedBridge.hookMethod(writer, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject as? View ?: return
                val banner = param.args.firstOrNull() as? View
                if (banner == null) {
                    owners.remove(owner)?.get()?.let { banners.remove(it); it.invalidate() }
                    return
                }
                owners.put(owner, java.lang.ref.WeakReference(banner))?.get()?.let { previous ->
                    if (previous !== banner) banners.remove(previous)
                }
                banners[banner] = true
                banner.invalidate()
            }
        })
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (banners[view] != true || roots[view.rootView] != true || !isFloating(view)) return
                // Leave draw(Canvas,parent,time) running so native animation
                // clocks and end callbacks still advance. Suppress only paint.
                if (param.args.size == 3) {
                    // Retire any display list recorded before ownership was
                    // established; retain normal native animation processing.
                    view.invalidate()
                    return
                }
                param.result = null
            }
        }
        XposedBridge.hookAllMethods(View::class.java, "draw", hook)
        XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchDraw", hook)
    }

    fun release(root: View) { roots.remove(root); root.invalidate() }

    fun nativeBanner(root: View): ViewGroup? = owners.entries.mapNotNull { (owner, reference) ->
        reference.get()?.takeIf { owner.rootView === root && it.rootView === root && it.isAttachedToWindow }
    }.distinct().singleOrNull() as? ViewGroup

    fun isFloating(view: View): Boolean {
        var current: View? = view
        var wrapper = false
        while (current != null) {
            if (current is AbsListView || current.javaClass.name.contains("RecyclerView") ||
                current.javaClass.name == "com.tencent.mm.ui.conversation.ConversationFolderItemView") return false
            if (banners[current] == true) wrapper = true
            current = current.parent as? View
        }
        return wrapper
    }
}
