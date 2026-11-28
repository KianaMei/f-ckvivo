package io.github.offlineglass.hook.adapters.douyin

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Foldable/tablet two-pane message page ({@code MessageFragmentPad}) lift adapter.
 *
 * Verified on Douyin 41.2.0 (2312x2504 inner screen): {@code MessageFragmentPad}'s root
 * horizontal LinearLayout hosts the left conversation list full-height (0..2504) and the
 * right chat room container with native bottom clearance matching {@code MainBottomTabContainer}
 * (bottom=2363, clearance=141px). Because LiquidTab's floating glass bar sits higher
 * (top=2285, clearance=219px), the glass bar overlaps the bottom of the right chat composer.
 *
 * Lifts only the right chat pane cleanly above the floating glass bar while resting, restores
 * its native clearance while the IME is open (so adjustResize hugs the keyboard with no gap)
 * or when folded back to single-pane mode. No hardcoded obfuscated resource IDs and no
 * device-side temp files.
 */
internal object DouyinPadChatPages {
    private const val PAD_MESSAGE_FRAGMENT =
        "com.ss.android.ugc.aweme.im.sdk.module.session.rips.MessageFragmentPad"

    private data class OriginalLayout(val height: Int, val bottomMargin: Int)

    private val padRoots = WeakHashMap<ViewGroup, Boolean>()
    private val ownedPanes = WeakHashMap<View, OriginalLayout>()
    private val visibleRect = Rect()
    private val parentPos = IntArray(2)
    private val barPos = IntArray(2)
    private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        val fragmentType = Class.forName("androidx.fragment.app.Fragment", false, loader)
        val getView = fragmentType.getMethod("getView")
        val performViewCreated = fragmentType.getDeclaredMethod("performViewCreated")
        require(View::class.java.isAssignableFrom(getView.returnType) &&
            performViewCreated.returnType == Void.TYPE)
        XposedBridge.hookMethod(performViewCreated, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val fragment = param.thisObject ?: return
                if (!isPadMessageFragment(fragment.javaClass)) return
                val root = getView.invoke(fragment) as? ViewGroup ?: return
                synchronized(padRoots) { padRoots[root] = true }
            }
        })
        installed = true
    }

    private fun isPadMessageFragment(type: Class<*>): Boolean {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            if (current.name == PAD_MESSAGE_FRAGMENT || current.simpleName == "MessageFragmentPad") {
                return true
            }
            current = current.superclass
        }
        return false
    }

    fun isImeOpen(window: View?): Boolean {
        val insets = window?.rootWindowInsets ?: return false
        return insets.getInsets(WindowInsets.Type.ime()).bottom > 0
    }

    fun isSplitChatVisible(window: View?): Boolean = findActiveSplit(window) != null

    fun update(host: View, barHeightPx: Int) {
        val window = host.rootView ?: return
        val active = findActiveSplit(window)
        if (active == null) {
            restoreAllInWindow(window, keepPane = null)
            return
        }
        val (root, rightPane) = active
        restoreAllInWindow(window, keepPane = rightPane)

        val lp = rightPane.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val original = synchronized(ownedPanes) {
            ownedPanes.getOrPut(rightPane) { OriginalLayout(lp.height, lp.bottomMargin) }
        }

        val imeBottom = window.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        val glassHost = host as? GlassHostLayout
        val barEnabled = glassHost?.config?.enabled != false
        val barMeasured = barEnabled && glassHost != null && glassHost.height > 0 && glassHost.isShown

        root.getLocationOnScreen(parentPos)
        val parentBottomInScreen = parentPos[1] + root.height
        val barTopInScreen = if (barMeasured && glassHost != null) {
            glassHost.getLocationOnScreen(barPos)
            barPos[1] - glassHost.translationY.roundToInt()
        } else {
            0
        }

        val density = host.resources.displayMetrics.density
        val requiredClearance = if (!barEnabled) {
            0
        } else {
            DouyinPadChatLayout.computeClearance(
                root.height,
                parentBottomInScreen,
                barTopInScreen,
                barHeightPx,
                density,
                barMeasured,
                imeBottom,
            )
        }

        val adjustment = DouyinPadChatLayout.solveRightPaneLayout(
            root.height,
            original.height,
            original.bottomMargin,
            requiredClearance,
        )

        if (adjustment.useMarginGuard) {
            DouyinControlMarginGuard.own(rightPane, adjustment.bottomMargin)
        } else {
            DouyinControlMarginGuard.release(rightPane)
        }

        var changed = false
        if (lp.height != adjustment.height) {
            lp.height = adjustment.height
            changed = true
        }
        if (lp.bottomMargin != adjustment.bottomMargin) {
            lp.bottomMargin = adjustment.bottomMargin
            changed = true
        }
        if (changed) {
            rightPane.layoutParams = lp
            rightPane.requestLayout()
        }
    }

    fun dispose(window: View?) {
        restoreAllInWindow(window, keepPane = null)
    }

    private fun findActiveSplit(window: View?): Pair<ViewGroup, View>? {
        window ?: return null
        if (window.width <= 0 || window.height <= 0) return null
        val snapshot = synchronized(padRoots) { padRoots.keys.toList() }
        for (root in snapshot) {
            if (root.rootView !== window || !root.isAttachedToWindow || !root.isShown) continue
            if (root.width <= 0 || root.height <= 0) continue
            if (!root.getGlobalVisibleRect(visibleRect)) continue
            if (visibleRect.width() <= window.width / 2 || visibleRect.height() <= window.height / 2) continue
            val rightPane = findRightPane(root) ?: continue
            return root to rightPane
        }
        return null
    }

    private fun findRightPane(root: ViewGroup): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child !is ViewGroup || !child.isShown) continue
            if (DouyinPadChatLayout.isSplitRightPane(
                    root.width,
                    root.height,
                    child.left,
                    child.width,
                    child.height,
                )
            ) {
                return child
            }
        }
        return null
    }

    private fun restoreAllInWindow(window: View?, keepPane: View?) {
        val toRestore = ArrayList<Pair<View, OriginalLayout>>()
        synchronized(ownedPanes) {
            val iterator = ownedPanes.entries.iterator()
            while (iterator.hasNext()) {
                val (pane, original) = iterator.next()
                if (pane === keepPane) continue
                if (window == null || !pane.isAttachedToWindow || pane.rootView === window) {
                    toRestore += pane to original
                    iterator.remove()
                }
            }
        }
        for ((pane, original) in toRestore) {
            DouyinControlMarginGuard.release(pane)
            if (!pane.isAttachedToWindow) continue
            val lp = pane.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            var changed = false
            if (lp.height != original.height) {
                lp.height = original.height
                changed = true
            }
            if (lp.bottomMargin != original.bottomMargin) {
                lp.bottomMargin = original.bottomMargin
                changed = true
            }
            if (changed) {
                pane.layoutParams = lp
                pane.requestLayout()
            }
        }
    }
}
