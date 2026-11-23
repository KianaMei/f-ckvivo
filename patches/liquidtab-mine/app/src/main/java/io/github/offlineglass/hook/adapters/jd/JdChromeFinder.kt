package io.github.offlineglass.hook.adapters.jd

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** JD floating Flash controls and cart back-to-top discovery, bounded and app-owned. */
internal class JdChromeFinder(private val host: GlassHostLayout) {
    fun findNavigationGroup(content: ViewGroup, wanted: Class<*>?): ViewGroup? {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += content to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val (view, depth) = stack.removeLast()
            if (view is ViewGroup) {
                val matched = if (wanted != null) view.javaClass === wanted
                    else view.javaClass.simpleName == "NavigationGroup"
                if (matched) return view
                if (depth < 12) {
                    for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
                }
            }
        }
        return null
    }
    fun findCheckoutBar(root: ViewGroup, rootWidth: Int, rootHeight: Int): View? {
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        var best: View? = null
        var bestBottom = -1
        while (stack.isNotEmpty() && visited++ < 900) {
            val view = stack.removeLast()
            if (view === host || view is GlassHostLayout || view is AdapterOwnedOverlay) continue
            if (view.visibility != View.VISIBLE || view.alpha <= 0.05f) continue
            // Taro adds/removes a loading sibling around its footer. Its last
            // child order is not a readiness signal; use the verified TDTextView
            // labels to recognize the footer on the first mounted frame.
            if (view.javaClass.name == "com.taro.core.react.views.text.TDTextView" &&
                runCatching { view.javaClass.getMethod("getText").invoke(view)?.toString()?.contains("结算") == true }.getOrDefault(false)) {
                var row = view.parent as? ViewGroup
                var footer: ViewGroup? = null
                repeat(6) {
                    val candidate = row ?: return@repeat
                    if (candidate.width >= rootWidth * .98f && candidate.height in 80..320 && !isInsideScrollContainer(candidate)) {
                        val children = ArrayDeque<View>(); children += candidate
                        var count = 0
                        while (children.isNotEmpty() && count++ < 80) {
                            val child = children.removeLast()
                            val text = if (child is android.widget.TextView) child.text?.toString() else
                                if (child.javaClass.name == "com.taro.core.react.views.text.TDTextView")
                                    runCatching { child.javaClass.getMethod("getText").invoke(child)?.toString() }.getOrNull() else null
                            if (text?.contains("全选") == true) { footer = candidate; break }
                            if (child is ViewGroup) for (i in 0 until child.childCount) children += child.getChildAt(i)
                        }
                    }
                    row = candidate.parent as? ViewGroup
                }
                // Hide the entire native footer shell, including its opaque
                // parent background, rather than only its inner button row.
                footer?.let { return it }
            }
            if (view is ViewGroup && view.width >= rootWidth * 0.98f && view.height in 80..320) {
                val location = IntArray(2).also(view::getLocationInWindow)
                val bottom = location[1] + view.height
                if (location[0] <= 2 && bottom >= rootHeight * 0.78f &&
                    !isInsideScrollContainer(view) && isLastVisibleChild(view) &&
                    !parentHasCheckoutShape(view, rootWidth) && bottom > bestBottom
                ) {
                    best = view
                    bestBottom = bottom
                }
            }
            if (view is ViewGroup) {
                // Descendants already reject Scroll/Refresh ancestors. Still
                // inspect the container itself, then prune its feed children.
                val name = view.javaClass.simpleName
                if (name.contains("Scroll") || name.contains("Refresh")) continue
                for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index)
            }
        }
        return best
    }

    private fun parentHasCheckoutShape(view: View, rootWidth: Int): Boolean {
        val parent = view.parent as? ViewGroup ?: return false
        if (parent.visibility != View.VISIBLE || parent.alpha <= 0.05f) return false
        return parent.width >= rootWidth * 0.98f && parent.height in 80..320
    }

    private fun isLastVisibleChild(view: View): Boolean {
        val parent = view.parent as? ViewGroup ?: return false
        for (index in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(index)
            if (child.visibility == View.VISIBLE) return child === view
        }
        return false
    }
    fun findFlashBottomControls(root: ViewGroup, rootWidth: Int, rootHeight: Int): Pair<View?, View?> {

        val stack = ArrayDeque<View>()

        stack += root

        var visited = 0

        var banner: View? = null

        var bannerBottom = -1

        var cart: View? = null

        var cartBottom = -1

        val loc = IntArray(2)

        while (stack.isNotEmpty() && visited++ < 1200) {

            val view = stack.removeLast()

            if (view === host || view is GlassHostLayout ||

                view is AdapterOwnedOverlay

            ) continue

            val name = view.javaClass.simpleName

            // Prune the feed's subtrees: the staggered-grid RecyclerView and

            // the inner pagers hold hundreds of TNViewGroup items and would

            // exhaust the visit budget before the floating controls (which

            // sit OUTSIDE them, as direct children of the fragment view) are

            // ever reached.

            if (view is ViewGroup &&

                (name.contains("RecyclerView") || name.contains("ViewPager"))

            ) continue

            if (view.visibility != View.VISIBLE || view.alpha <= 0.05f) continue

            if (view is ViewGroup) {

                if (name == "HourlyGoScreenBottomBarView" &&

                    view.width >= rootWidth * 0.98f && view.height in 80..200

                ) {

                    view.getLocationInWindow(loc)

                    val bottom = loc[1] + view.height

                    if (bottom > bannerBottom && !isInsideScrollContainer(view)) {

                        banner = view

                        bannerBottom = bottom

                    }

                    // Do not descend into the banner: its inner TN wrappers

                    // share the strip shape.

                    continue

                }

                for (index in view.childCount - 1 downTo 0) {

                    stack += view.getChildAt(index)

                }

            }

            // Floating cart FAB: near-square image view (90�?80 px) hugging

            // the right edge in the lower half, outside scroll containers.

            // Its parent container (the FAB's hit area) is lifted instead.

            if (view is android.widget.ImageView && view.width in 90..180 &&

                view.height in 90..180 &&

                kotlin.math.abs(view.width - view.height) <= 28

            ) {

                view.getLocationInWindow(loc)

                val right = loc[0] + view.width

                val bottom = loc[1] + view.height

                if (right >= rootWidth - 220 && bottom >= rootHeight * 0.55f &&

                    bottom <= rootHeight + 60 &&

                    !isInsideScrollContainer(view) && !isInsideRecyclerOrPager(view)

                ) {

                    val parent = view.parent as? ViewGroup

                    if (parent != null && parent.width in 90..220 && parent.height in 90..220) {

                        parent.getLocationInWindow(loc)

                        val parentBottom = loc[1] + parent.height

                        if (parentBottom > cartBottom) {

                            cart = parent

                            cartBottom = parentBottom

                        }

                    }

                }

            }

        }

        val sb = StringBuilder(

            "JdFlash find: root=${rootWidth}x$rootHeight banner=" +

                (banner?.let { b ->

                    b.getLocationInWindow(loc)

                    "${b.javaClass.simpleName} h=${b.height} y=${loc[1]}"

                } ?: "null") +

                " cart=" +

                (cart?.let { c ->

                    c.getLocationInWindow(loc)

                    "${c.javaClass.simpleName} h=${c.height} y=${loc[1]}"

                } ?: "null"),

        )

        android.util.Log.i("JdGlassDiag", sb.toString())

        return banner to cart

    }



    /**

     * Structural signature of the cart's floating back-to-top control: a

     * near-square ViewGroup (90�?80 px, width≈height) hugging the right edge,

     * in the lower half of the window, outside every scroll container. Feed

     * cards are 1161 px wide, thumbnails live inside the scroll view, and the

     * checkout strip is full-width �?none of them match.

     */

    fun findBackToTopButtons(root: ViewGroup, rootWidth: Int, rootHeight: Int): List<View> {
        val stack = ArrayDeque<View>()
        stack += root
        val candidates = ArrayList<View>()
        val location = IntArray(2)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 900) {
            val view = stack.removeLast()
            if (view === host || view is GlassHostLayout || view is AdapterOwnedOverlay ||
                view.visibility != View.VISIBLE || view.alpha <= .05f) continue
            if (view is ViewGroup) {
                val name = view.javaClass.simpleName
                if (name.contains("Scroll") || name.contains("Refresh") || name.contains("Recycler")) continue
                if (view.width in 90..180 && view.height in 90..180 &&
                    kotlin.math.abs(view.width - view.height) <= 24) {
                    view.getLocationInWindow(location)
                    if (location[0] + view.width >= rootWidth - 80 &&
                        location[1] + view.height >= rootHeight * .5f && !isInsideScrollContainer(view)) {
                        candidates += view
                        // A nested wrapper is the same button, not another slot.
                        continue
                    }
                }
                for (i in view.childCount - 1 downTo 0) stack += view.getChildAt(i)
            }
        }
        return candidates.sortedBy { it.getLocationInWindow(location); location[1] }
            .takeLast(2)
    }

    private fun isInsideScrollContainer(view: View): Boolean {
        var node = view
        var guard = 0
        while (guard++ < 12) {
            val parent = node.parent as? ViewGroup ?: return false
            val name = parent.javaClass.simpleName
            if (name.contains("Scroll") || name.contains("Refresh")) return true
            node = parent
        }
        return false
    }

    private fun isInsideRecyclerOrPager(view: View): Boolean {
        var node = view
        var guard = 0
        while (guard++ < 14) {
            val parent = node.parent as? ViewGroup ?: return false
            val name = parent.javaClass.simpleName
            if (name.contains("RecyclerView") || name.contains("ViewPager")) return true
            node = parent
        }
        return false
    }
}

