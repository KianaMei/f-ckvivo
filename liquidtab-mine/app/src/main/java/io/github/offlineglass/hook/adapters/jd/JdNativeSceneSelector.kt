package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** Uses live GPU nodes only for verified native pages; media keeps PixelCopy. */
internal class JdNativeSceneSelector {
    private var parent: ViewGroup? = null
    private var observer: ViewTreeObserver? = null
    private var dirty = true
    private var tab = -1
    private var nextProbe = 0L
    private var scene: View? = null
    private val visibleRect = android.graphics.Rect()
    private val pendingViews = ArrayDeque<View>()
    private val rendererTypes = java.util.WeakHashMap<Class<*>, Boolean>()
    private var probing = false
    private fun isWebRenderer(view: View): Boolean {
        rendererTypes[view.javaClass]?.let { return it }
        val result = resolveWebRenderer(view)
        if (rendererTypes.size < 128) rendererTypes[view.javaClass] = result
        return result
    }
    private fun resolveWebRenderer(view: View): Boolean {
        if (view is android.webkit.WebView) return true
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "com.jingdong.hybrid.ui.JDWebView" ||
                type.name == "com.jd.libs.xwin.widget.XWebView") return true
            type = type.superclass
        }
        return false
    }
    private fun needsContainerClipCheck(view: View): Boolean {
        if (view !is ViewGroup) return false
        val parent = view.parent as? ViewGroup ?: return false
        // This is only a hint to run the ORIGINAL clipping query, never a
        // geometric verdict that hides a view or enables live sampling.
        // Checking each on-screen leaf walked every ancestor repeatedly.
        return !view.matrix.isIdentity || view.right <= parent.scrollX ||
            view.bottom <= parent.scrollY || view.left >= parent.scrollX + parent.width ||
            view.top >= parent.scrollY + parent.height
    }
    private val listener = ViewTreeObserver.OnGlobalLayoutListener { dirty = true }

    fun onFocusChanged(focused: Boolean) {
        if (focused) { dirty = true; nextProbe = 0L }
    }

    fun pick(host: GlassHostLayout, index: Int, unsafeChannel: Boolean): View? {
        if (unsafeChannel) {
            // Returning from H5 must revalidate the real visible page even
            // when its container identity and bottom-tab index did not change.
            scene = null; dirty = true; nextProbe = 0L
            return null
        }
        val container = host.parent as? ViewGroup ?: return null
        if (parent !== container) {
            detach()
            parent = container
            observer = container.viewTreeObserver.also { it.addOnGlobalLayoutListener(listener) }
        }
        val now = SystemClock.uptimeMillis()
        if (tab != index) { tab = index; dirty = true; nextProbe = 0L; scene = null }
        if (probing) return scene
        if (!dirty || now < nextProbe) return scene?.takeIf { it.isAttachedToWindow && it.isShown }
        dirty = false
        nextProbe = now + 350L
        val hostIndex = container.indexOfChild(host)
        if (hostIndex < 0) return null
        var candidate: View? = null
        var bestArea = 0L
        for (i in 0 until hostIndex) {
            val view = container.getChildAt(i)
            if (view is AdapterOwnedOverlay || view is GlassHostLayout ||
                view is android.view.SurfaceView || !view.isShown || view.alpha <= .001f) continue
            val area = view.width.toLong() * view.height
            if (view.width >= container.width * .8f && view.height >= container.height * .5f && area > bestArea) {
                candidate = view; bestArea = area
            }
        }
        val candidateRoot = candidate ?: run { scene = null; return null }
        val root = JdClassicLayout.content(candidateRoot) ?: run { scene = null; return null }
        val stack = pendingViews
        stack += root
        var visited = 0
        var clipChecks = 0
        probing = true
        android.os.Trace.beginSection("LiquidTab.JD.sceneEligibility")
        try {
        while (stack.isNotEmpty() && visited++ < 2000) {
            val view = stack.removeLast()
            if (view.visibility != View.VISIBLE || view.alpha <= .001f) continue
            // Pagers keep offscreen web pages VISIBLE. Only the actually
            // clipped/visible page determines whether live RenderNode reuse
            // is safe; never reference JD's custom Vulkan WebView functor
            // from a second scene (multi-second flush stalls while scrolling).
            val unsafe = view is android.view.SurfaceView || view is android.view.TextureView || isWebRenderer(view)
            if ((unsafe || needsContainerClipCheck(view)) && view.width > 0 && view.height > 0) {
                clipChecks++
                if (!view.getGlobalVisibleRect(visibleRect)) continue
            }
            if (unsafe) {
                scene = null
                return null
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                if (stack.size >= 2000) { scene = null; return null }
                stack += view.getChildAt(i)
            }
        }
        scene = root.takeIf { stack.isEmpty() }
        // Keep Android's existing child RenderNodes. A forced full-page
        // hardware layer is invalidated by every destination/scroll update,
        // rasterizing a screen-sized texture before sampling a narrow lens.
        // Clip the live nodes directly instead; optics and resolution remain.
        return scene
        } finally {
            stack.clear()
            probing = false
            if (android.os.Trace.isEnabled()) android.os.Trace.setCounter("LiquidTab.JD.sceneClipChecks", clipChecks.toLong())
            android.os.Trace.endSection()
        }
    }

    fun detach() {
        if (observer?.isAlive == true) observer?.removeOnGlobalLayoutListener(listener)
        parent = null; observer = null; scene = null; tab = -1; nextProbe = 0L; dirty = true
        pendingViews.clear()
        rendererTypes.clear()
    }
}
