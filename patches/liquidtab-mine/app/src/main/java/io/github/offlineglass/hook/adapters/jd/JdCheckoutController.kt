package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Bounded discovery and same-frame positioning of JD's cart checkout strip. */
internal class JdCheckoutController(
    private val finder: JdChromeFinder,
    private val backToTop: JdBackToTopController,
) {
    var bar: WeakReference<View>? = null
    var nextFindAt = 0L
    var discoveryExhausted = false
    var discoveryResetAt = 0L
    private val offsets = WeakHashMap<View, Int>()
    private var liquid: JdCartLiquidButtons? = null
    private var liquidSource: ViewGroup? = null
    private var sourceAlpha = 1f
    private var nextLiquidProbe = 0L
    private var visiblePage = -1
    private val sourceAlphas = WeakHashMap<View, Float>()
    private var cartRoot: WeakReference<ViewGroup>? = null
    private var previewScene: View? = null

    fun setPreviewScene(scene: View?) {
        if (previewScene === scene) return
        previewScene = scene
        liquid?.invalidate()
    }

    // Established only by walking upwards from the verified cart checkout.
    // An arbitrary visible React surface may belong to a different tab.
    private fun cartScene(glass: GlassHostLayout): ViewGroup? =
        cartRoot?.get()?.takeIf { it.isAttachedToWindow && it.isShown }

    fun ensureCartOverlay(glass: GlassHostLayout) {
        if (liquid != null) return
        val parent = glass.parent as? FrameLayout ?: return
        val dummy = FrameLayout(glass.context)
        liquid = JdCartLiquidButtons(glass.context, glass, dummy,
            View(glass.context), View(glass.context), View(glass.context),
            { previewScene ?: cartRoot?.get()?.takeIf { it.isAttachedToWindow && it.isShown } }, dummy).also {
            parent.addView(it, parent.indexOfChild(glass), FrameLayout.LayoutParams(-1,
                (88f * parent.resources.displayMetrics.density).toInt()))
            it.updateGeometry()
        }
    }

    fun onCartLayout(root: ViewGroup, host: View) {
        backToTop.onNativeLayout()
        // A React mount/layout is new evidence, even if the preceding layout
        // had no footer. Do not inherit an exhausted probe from another page.
        if (liquid?.validBinding() != true || liquidSource?.isShown != true) {
            nextFindAt = 0L
            nextLiquidProbe = 0L
            discoveryExhausted = false
        }
        lift(root, root.height, host)
    }

    // Called even during the host's animation-priority interval. Keep the
    // native footer hidden until this binding is released, including off-page.
    fun syncPageVisibility(index: Int) {
        if (visiblePage != index) {
            visiblePage = index
            if (index == 3) {
                discoveryExhausted = false
                nextFindAt = 0L
                nextLiquidProbe = 0L
            }
        }
        liquidSource?.let { if (it.alpha != 0f) it.alpha = 0f }
        liquid?.let {
            val wanted = if (index == 3) View.VISIBLE else View.INVISIBLE
            if (it.visibility != wanted) it.visibility = wanted
        }
    }

    private fun restoreLiquid() {
        liquid?.let { it.release(); (it.parent as? ViewGroup)?.removeView(it) }
        liquid = null
        liquidSource?.let { it.alpha = sourceAlpha }
        liquidSource = null
        sourceAlphas.forEach { (view, alpha) -> view.alpha = alpha }
        sourceAlphas.clear()
    }

    private fun liquidCart(source: View, host: View) {
        val glass = host as? GlassHostLayout ?: return
        val group = source as? ViewGroup
        if (glass.adapterSelectedIndex != 3 || group == null || !group.isShown) {
            syncPageVisibility(-1)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (liquidSource === group && liquid?.validBinding() == true) {
            syncPageVisibility(3)
            if (group.alpha != 0f) group.alpha = 0f
            liquid?.refresh()
            return
        }
        // A newly mounted footer can acquire its text in the next frame.
        // Never leave the native strip visible behind a 300ms failed-probe gate.
        if (now < nextLiquidProbe) return
        // JD 16.1.0 classes9.dex: TDTextView extends View, not TextView.
        // It exposes getText(); ReactRootView owns the JS touch dispatcher.
        var ancestor: View? = group
        var reactRoot: ViewGroup? = null
        while (ancestor != null) {
            var type: Class<*>? = ancestor.javaClass
            while (type != null) {
                if (type.name == "com.taro.core.react.ReactRootView") reactRoot = ancestor as? ViewGroup
                type = type.superclass
            }
            if (reactRoot != null) break
            ancestor = ancestor.parent as? View
        }
        val eventRoot = reactRoot ?: return
        cartRoot = WeakReference(eventRoot)
        val labels = ArrayList<Pair<View, String>>()
        val stack = ArrayDeque<View>()
        stack += group
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 100) {
            val view = stack.removeLast()
            if (view.visibility != View.VISIBLE) continue
            val text = if (view is TextView) view.text?.toString() else
                if (view.javaClass.name == "com.taro.core.react.views.text.TDTextView")
                    runCatching { view.javaClass.getMethod("getText").invoke(view)?.toString() }.getOrNull()
                else null
            if (!text.isNullOrEmpty()) labels += view to text
            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        val all = labels.firstOrNull { it.second.contains("全选") }?.first ?: return
        val pay = labels.firstOrNull { it.second.contains("结算") }?.first ?: return
        sourceAlphas.putIfAbsent(group, group.alpha)
        if (group.alpha != 0f) group.alpha = 0f
        fun branch(view: View, parent: ViewGroup): View? {
            var child = view
            while (child.parent !== parent) child = child.parent as? View ?: return null
            return child
        }
        var row = pay.parent as? ViewGroup
        var select: View? = null
        var checkout: View? = null
        var price: View? = null
        while (row != null && row !== group.parent) {
            val left = branch(all, row)
            val right = branch(pay, row)
            if (left != null && right != null && left !== right) {
                val others = (0 until row.childCount).map(row::getChildAt).filter {
                    it !== left && it !== right && it.visibility == View.VISIBLE
                }
                if (others.size == 1 && row.width >= group.width * .8f) {
                    select = left; checkout = right; price = others.single(); break
                }
            }
            row = row.parent as? ViewGroup
        }
        val selectView = select ?: return
        val priceView = price ?: return
        val checkoutView = checkout ?: return
        val parent = glass.parent as? FrameLayout ?: return
        var scene: View = group
        while (scene.parent !== parent) scene = scene.parent as? View ?: return
        if (scene === glass || scene is io.github.offlineglass.hook.adapters.AdapterOwnedOverlay) return
        ensureCartOverlay(glass)
        liquidSource = group
        sourceAlpha = sourceAlphas[group] ?: 1f
        group.alpha = 0f
        liquid?.bind(group, selectView, priceView, checkoutView, eventRoot)
    }

    fun onNavigationRebound() {
        discoveryExhausted = false
        nextFindAt = 0L
    }

    fun lift(root: ViewGroup, rootHeight: Int, host: View) {
        val glass = host as? GlassHostLayout ?: return
        if (glass.adapterSelectedIndex != 3) return
        ensureCartOverlay(glass)
        syncPageVisibility(3)
        liquid?.let { backToTop.correct(it, cartScene(glass), finder, it.capsuleTopInWindow()) }
        val rootWidth = root.width
        if (rootWidth <= 0 || rootHeight <= 0) return
        var current = bar?.get()
        if (current == null || !current.isAttachedToWindow || current.parent == null || !current.isShown) {
            bar = null
            val now = SystemClock.uptimeMillis()
            if (now < nextFindAt) return
            current = finder.findCheckoutBar(root, rootWidth, rootHeight)
            if (current != null) {
                bar = WeakReference(current)
                nextFindAt = 0L
            } else {
                discoveryExhausted = true
                nextFindAt = now + 100L
            }
        }
        if (current != null) correct(root, host)
    }

    fun correct(root: ViewGroup?, host: View) {
        val current = bar?.get()
        if (current == null || !current.isAttachedToWindow || current.parent == null) {
            bar = null
            if (discoveryExhausted) {
                val now = SystemClock.uptimeMillis()
                if (now >= discoveryResetAt) {
                    discoveryResetAt = now + 300L
                    discoveryExhausted = false
                    nextFindAt = 0L
                }
            }
            return
        }
        val scene = root ?: return
        val index = (host as? GlassHostLayout)?.adapterSelectedIndex ?: -1
        syncPageVisibility(index)
        if (index != 3) return
        if (scene.height <= 0 || host.width <= 0 || host.height <= 0) return
        liquidCart(current, host)
        if (liquid != null) {
            // The overlay owns geometry. Moving the invisible React footer
            // fights Taro's layout and needlessly invalidates the full page.
            backToTop.correct(current, cartScene(host as GlassHostLayout), finder, liquid?.capsuleTopInWindow())
            return
        }
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        val safeBottom = hostLocation[1] - CHECKOUT_BOTTOM_GAP_PX
        val barLocation = IntArray(2).also(current::getLocationInWindow)
        val delta = barLocation[1] + current.height - safeBottom
        if (abs(delta) > 6) {
            current.offsetTopAndBottom(-delta)
            offsets[current] = (offsets[current] ?: 0) - delta
        }
        liquidCart(current, host)
        backToTop.correct(current, scene, finder, liquid?.capsuleTopInWindow())
    }

    fun restore() {
        previewScene = null
        cartRoot = null
        restoreLiquid()
        nextLiquidProbe = 0L
        offsets.forEach { (view, applied) ->
            if (applied != 0 && view.isAttachedToWindow) view.offsetTopAndBottom(-applied)
        }
        offsets.clear()
        discoveryExhausted = false
    }

    private companion object { const val CHECKOUT_BOTTOM_GAP_PX = 8 }
}
