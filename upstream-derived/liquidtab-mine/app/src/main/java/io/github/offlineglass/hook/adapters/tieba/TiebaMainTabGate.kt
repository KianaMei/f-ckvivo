package io.github.offlineglass.hook.adapters.tieba

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference

/**
 * 首页玻璃顶栏只属于首页。
 *
 * 贴吧的 ScrollTabBarLayout 挂在首页 PageView 上，而首页 ScrollFragmentTabHost 用
 * `offscreenPageLimit = size-1`：切到“进吧 / 小卖部 / 消息 / 我的”后首页 PageView 仍然
 * isShown。模块原先只判 isShown，三块玻璃就一直压在“进吧”自己的 H5 顶栏上（整宽搜索
 * 胶囊 + 一键签到），同一段区域还会吃掉触摸。
 *
 * 这里改用宿主自己的底部 tab 类型做闸门：只有首页（type 2）才放行。宿主契约读不到时
 * 一律放行，保持改动前的行为，避免版本漂移把首页顶栏一起关掉。
 */
internal object TiebaMainTabGate {
    private const val HOST = "com.baidu.tbadk.core.tabHost.FragmentTabHost"

    /** FragmentTabStructure.type：2 = 首页（RecommendFrsDelegateStatic），1 = 进吧。 */
    private const val HOME_TYPE = 2

    private const val CACHE_MS = 200L
    private const val MAX_VISITS = 800

    private var hostType: Class<*>? = null
    private var readType: java.lang.reflect.Method? = null
    private var hostRef = WeakReference<Any>(null)
    private var onTabChange: (() -> Unit)? = null

    private var checkedAt = 0L
    private var allowed = true
    private var reportedType: Int? = null

    fun install(loader: ClassLoader, callback: () -> Unit) {
        onTabChange = callback
        runCatching {
            val type = Class.forName(HOST, false, loader)
            val method = type.getMethod("getCurrentTabType")
            check(method.returnType == Int::class.javaPrimitiveType)
            check(View::class.java.isAssignableFrom(type))
            hostType = type
            readType = method
        }.onFailure { Log.w("LiquidTabTieba", "Bottom tab contract unavailable", it) }
        val type = hostType ?: return
        runCatching {
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = invalidate()
            }
            XposedBridge.hookAllMethods(type, "onPageSelected", hook)
            XposedBridge.hookAllMethods(type, "onTabSelectionChanged", hook)
        }.onFailure { Log.w("LiquidTabTieba", "Bottom tab notification unavailable", it) }
    }

    private fun invalidate() {
        checkedAt = 0L
        onTabChange?.invoke()
    }

    /** True while 首页 is the selected bottom tab, or while the contract is unreadable. */
    fun isHomeTab(root: View): Boolean {
        val reader = readType ?: return true
        val now = SystemClock.uptimeMillis()
        if (now - checkedAt < CACHE_MS) return allowed
        checkedAt = now
        val host = hostFor(root)
        val type = host?.let { runCatching { reader.invoke(it) as? Int }.getOrNull() }
        allowed = type == null || type == HOME_TYPE
        if (type != reportedType) {
            reportedType = type
            Log.i("LiquidTabTieba", "Bottom tab type=$type, home chrome ${if (allowed) "enabled" else "parked"}")
        }
        return allowed
    }

    private fun hostFor(root: View): Any? {
        val type = hostType ?: return null
        val cached = hostRef.get() as? View
        if (cached != null && cached.rootView === root) return cached
        val pending = ArrayDeque<View>()
        pending += root
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_VISITS) {
            val view = pending.removeFirst()
            if (type.isInstance(view)) {
                hostRef = WeakReference(view)
                return view
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) pending += view.getChildAt(index)
        }
        return null
    }
}
