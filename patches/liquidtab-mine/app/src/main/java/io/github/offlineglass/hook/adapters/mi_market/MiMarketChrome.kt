package io.github.offlineglass.hook.adapters.mi_market

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.*
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Market 4.129 native pagers remain the owners of search, downloads and routing. */
internal class MiMarketChrome(private val context: Context, private val backdropState: MiMarketNavigationState) {
    private class Overlay(context: Context) : FrameLayout(context), AdapterOwnedOverlay
    private data class Pager(val owner: Any, val view: View, val adapter: Any, val pager: View, val labels: List<String>)
    private data class Original(val alpha: Float, val visibility: Int, val height: Int,
        val background: android.graphics.drawable.Drawable?, val tint: android.content.res.ColorStateList?)
    private val originals = WeakHashMap<View, Original>()
    private val hiddenRoots = WeakHashMap<View, Boolean>()
    private data class Input(val clickable: Boolean, val longClickable: Boolean, val focusable: Boolean)
    private val nativeInputs = WeakHashMap<View, Input>()
    private data class BehaviorBinding(val params: Any, val behavior: Any, val setter: java.lang.reflect.Method)
    private val behaviors = WeakHashMap<View, BehaviorBinding>()
    private val behaviorContracts = HashMap<Class<*>, Pair<java.lang.reflect.Method, java.lang.reflect.Method>?>()
    private val relativeParams = WeakHashMap<View, android.widget.RelativeLayout.LayoutParams>()
    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int, val clip: Boolean)
    private val paddings = WeakHashMap<ViewGroup, Padding>()
    private val removedHeaderDecorations = WeakHashMap<View, Any>()
    private val appliedInsets = WeakHashMap<ViewGroup, List<Int>>()
    private val preparedPagers = WeakHashMap<View, List<Int>>()
    private var sceneEpoch = 0L
    private val window = (context as? android.app.Activity)?.window
    private var windowState: Pair<Int, Boolean>? = null
    private fun edgeToEdge(enabled: Boolean) {
        val window = window ?: return
        if (enabled) {
            if (windowState == null) windowState = window.statusBarColor to window.isStatusBarContrastEnforced
            if (window.statusBarColor != Color.TRANSPARENT) window.statusBarColor = Color.TRANSPARENT
            if (window.isStatusBarContrastEnforced) window.isStatusBarContrastEnforced = false
        } else windowState?.let {
            window.statusBarColor=it.first; window.isStatusBarContrastEnforced=it.second
            windowState=null
        }
    }
    private val overlayPosition = IntArray(2)
    private val barPosition = IntArray(2)
    private val fields = HashMap<Pair<Class<*>, String>, java.lang.reflect.Field?>()
    private val pagerCache = WeakHashMap<Any, java.lang.ref.WeakReference<Pager>>()
    private val panels = HashMap<Int, GlassHostLayout>()
    private var overlay: Overlay? = null
    private var edgeVeil: MiMarketEdgeVeil? = null
    private var content: FrameLayout? = null
    private var main: GlassHostLayout? = null
    private var primary: Pager? = null
    private var secondary: Pager? = null
    private var pinned = emptyList<Int>()
    private var menuEntries = emptyList<Pair<Pager, Int>>()
    private var search: View? = null
    private var download: View? = null
    private var page: Any? = null
    private var visualPage = -1
    private var lastScan = 0L
    private val renderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bindingPending = false
    private var chromeTarget = -1
    private var chromeShown = false
    private var chromeAnimationGeneration = 0L
    private var chromeAnimator: android.animation.ValueAnimator? = null
    private var headerSlots = 0
    private var presentedSlider: Pair<Float,Float>? = null
    private var sliderFrom: Pair<Float,Float>? = null
    private var sliderProgress = 1f
    private var sliderAnimator: android.animation.ValueAnimator? = null

    private fun morphSlider() {
        sliderAnimator?.cancel()
        sliderFrom = presentedSlider ?: return
        sliderProgress = 0f
        sliderAnimator = android.animation.ValueAnimator.ofFloat(0f,1f).apply {
            duration=240L
            interpolator=android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { sliderProgress=it.animatedValue as Float; panels[0]?.postInvalidateOnAnimation() }
            start()
        }
    }

    private fun transitionChrome(target: Int, shown: Boolean) {
        if (chromeTarget == target && chromeShown == shown) return
        val wasShown = chromeShown
        chromeTarget = target; chromeShown = shown
        val generation = ++chromeAnimationGeneration
        chromeAnimator?.cancel()
        val layer = overlay ?: return
        if (!shown && layer.visibility != View.VISIBLE) return
        if (shown) layer.visibility = View.VISIBLE
        if (shown && wasShown) {
            // Only the indicator morphs between the two persistent top layouts.
            for (role in listOf(0,1,2)) panels[role]?.let {
                it.alpha=1f; it.translationY=0f; it.syncOpticalVisibilityCompanions()
            }
            chromeAnimator=null
            return
        }
        val initialAlpha = if (shown) {
            if (layer.visibility == View.VISIBLE) panels[0]?.alpha ?: 0f else 0f
        } else panels[0]?.alpha ?: 1f
        fun present(fraction: Float) {
            val opacity = initialAlpha + ((if (shown) 1f else 0f) - initialAlpha) * fraction
            for (role in listOf(0,1,2)) panels[role]?.let { panel ->
                panel.alpha = opacity; panel.translationY = 0f
                // SurfaceView is a sibling; keep its transform/opacity matched.
                panel.syncOpticalVisibilityCompanions()
                panel.postInvalidateOnAnimation()
            }
        }
        present(0f)
        chromeAnimator = android.animation.ValueAnimator.ofFloat(0f,1f).apply {
            duration = if (shown) 220L else 180L
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { if (generation == chromeAnimationGeneration) present(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (generation != chromeAnimationGeneration) return
                    present(1f)
                    // Keep the parent of the independent SurfaceViews mounted.
                    // Invisible chrome stays alpha=0 and pauses its readbacks.
                    chromeAnimator = null
                }
            })
            start()
        }
    }
    private var presentation = ""
    private var channelIdentity = ""
    private var menuOpen = false
    private var menuOffset = 0f
    private var lastTouchY = 0f
    private var touchStartY = 0f
    private val paints = Array(5) { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    private val d get() = context.resources.displayMetrics.density
    private fun dp(value: Float) = (value * d).roundToInt()
    private fun call(obj: Any?, name: String, vararg args: Any?): Any? = obj?.let {
        runCatching { XposedHelpers.callMethod(it, name, *args) }.getOrNull()
    }
    private fun field(obj: Any, name: String): Any? {
        val key = obj.javaClass to name
        if (!fields.containsKey(key)) {
            var type: Class<*>? = obj.javaClass
            var found: java.lang.reflect.Field? = null
            while (type != null && found == null) {
                found = runCatching { type.getDeclaredField(name) }.getOrNull()
                type = type.superclass
            }
            fields[key] = found?.apply { isAccessible = true }
        }
        return runCatching { fields[key]?.get(obj) }.getOrNull()
    }
    private fun find(root: View, name: String): View? {
        val id = root.resources.getIdentifier(name, "id", "com.xiaomi.market")
        return if (id == 0) null else root.findViewById(id)
    }
    private fun pager(owner: Any?): Pager? {
        owner ?: return null
        val adapter = field(owner, "adapter") ?: return null
        val pager = field(owner, "pager") as? View ?: return null
        val root = call(owner, "getView") as? View ?: return null
        val count = (call(adapter, "getCount") as? Number)?.toInt()?.takeIf { it in 1..100 } ?: return null
        pagerCache[owner]?.get()?.let { cached ->
            if (cached.adapter === adapter && cached.pager === pager && cached.view === root && cached.labels.size == count) return cached
        }
        val titles = (0 until count).map { call(adapter, "getPageTitle", it)?.toString().orEmpty().trim() }
        if (titles.any { it.isBlank() }) return null
        return Pager(owner, root, adapter, pager, titles).also { pagerCache[owner] = java.lang.ref.WeakReference(it) }
    }
    private fun selected(pager: Pager): Int = (call(pager.pager, "getCurrentItem") as? Number)?.toInt() ?: 0
    private fun choose(pager: Pager, index: Int) {
        if (index !in pager.labels.indices) return
        call(pager.pager, "setCurrentItem", index, true)
        closeMenu()
        panels.values.forEach { it.postInvalidateOnAnimation() }
        lastScan = 0L
    }
    private fun remember(view: View) { originals.getOrPut(view) {
        Original(view.alpha, view.visibility, view.layoutParams?.height ?: -2, view.background, view.backgroundTintList)
    } }
    private fun clearBackground(view: View?) {
        view ?: return
        remember(view)
        if (view.background != null) view.background = null
        if (view.backgroundTintList != null) view.backgroundTintList = null
    }
    private fun hide(view: View?, collapse: Boolean) {
        view ?: return
        if (hiddenRoots[view] == collapse &&
            (if (collapse) view.visibility == View.GONE else view.alpha == 0f)) return
        remember(view)
        hiddenRoots[view] = collapse
        disableNativeInputs(view)
        if (collapse) { if (view.visibility != View.GONE) view.visibility = View.GONE }
        else {
            view.animate().cancel()
            if (view.alpha != 0f) view.alpha = 0f
        }
    }
    private fun disableNativeInputs(view: View) {
        nativeInputs.getOrPut(view) { Input(view.isClickable,view.isLongClickable,view.isFocusable) }
        if (view.isClickable) view.isClickable = false
        if (view.isLongClickable) view.isLongClickable = false
        if (view.isFocusable) view.isFocusable = false
        if (view is ViewGroup) for (i in 0 until view.childCount) disableNativeInputs(view.getChildAt(i))
    }
    private fun restore() {
        removedHeaderDecorations.forEach { (view, decoration) -> call(view, "addItemDecoration", decoration) }
        removedHeaderDecorations.clear()
        nativeInputs.forEach { (view, old) -> view.isClickable=old.clickable; view.isLongClickable=old.longClickable; view.isFocusable=old.focusable }
        nativeInputs.clear()
        behaviors.forEach { (view, saved) -> if (view.layoutParams === saved.params) runCatching { saved.setter.invoke(saved.params, saved.behavior) } }
        behaviors.clear()
        relativeParams.forEach { (view, params) -> view.layoutParams = params }
        relativeParams.clear()
        paddings.forEach { (view, old) ->
            if (view is android.webkit.WebView) MiMarketWebInsets.remove(view)
            view.setPadding(old.left,old.top,old.right,old.bottom); view.clipToPadding = old.clip
        }
        paddings.clear()
        appliedInsets.clear(); preparedPagers.clear()
        originals.forEach { (view, old) ->
            view.alpha = old.alpha
            if (hiddenRoots[view] == true) view.visibility = old.visibility
            view.background = old.background; view.backgroundTintList = old.tint
            view.layoutParams?.let { if (it.height != old.height) { it.height = old.height; view.layoutParams = it } }
        }
        originals.clear()
        hiddenRoots.clear()
    }

    fun update(host: GlassHostLayout, nativePage: Any?, index: Int) {
        main = host
        val parent = host.parent as? FrameLayout ?: return
        if (content !== parent) { dispose(); main = host; content = parent }
        val veil = edgeVeil ?: MiMarketEdgeVeil(context).also {
            edgeVeil=it
            parent.addView(it,parent.indexOfChild(host).coerceAtLeast(0),FrameLayout.LayoutParams(-1,-1))
        }
        veil.visibility=if(host.config.enabled)View.VISIBLE else View.GONE
        val status=host.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: dp(28f)
        veil.update(context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES,status+dp(if(index in 0..2)110f else 24f),
            (host.top-dp(28f)).coerceAtLeast(0))
        val active = index in 0..2 && host.config.enabled
        if (!active) {
            if (!host.config.enabled) {
                ++chromeAnimationGeneration; chromeAnimator?.cancel(); chromeAnimator=null
                overlay?.visibility=View.GONE; chromeShown=false; chromeTarget=-1
                restore()
            } else transitionChrome(index,false)
            closeMenu(); edgeToEdge(false); page = null; return
        }
        if (nativePage == null) { bindingPending = true; return }
        edgeToEdge(true)
        val now = SystemClock.uptimeMillis()
        hiddenRoots.forEach { (view, collapse) ->
            if (collapse) { if (view.visibility != View.GONE) view.visibility = View.GONE }
            else if (view.alpha != 0f) { view.animate().cancel(); view.alpha = 0f }
        }
        if (now - lastScan < 120L && page === nativePage && visualPage == index) return
        lastScan = now
        (call(nativePage,"getView") as? View)?.let { nativeRoot ->
            for (name in listOf("search_view_container","pager_tab_container","native_home_page_appbar_layout","sticky_header_wrapper")) {
                hide(find(nativeRoot,name),false)
            }
        }
        val model = pager(nativePage) ?: run { bindingPending = true; return }
        val wanted = when (index) {
            0 -> listOf("推荐", "榜单", "金米奖")
            1 -> listOf("推荐", "榜单", "上新", "分类")
            else -> listOf("游戏榜", "软件榜")
        }
        val nextPinned = wanted.mapNotNull { title -> model.labels.indexOfFirst { it == title }.takeIf { it >= 0 } }
        // Do not replace unfamiliar server data with invented destinations.
        if (nextPinned.size != wanted.size) { bindingPending = true; return }
        if (page !== nativePage || visualPage != index) {
            closeMenu(); page = nativePage; visualPage = index; sceneEpoch++
        }
        bindingPending = false
        val nextChannel = "$index:${selected(model)}"
        if (channelIdentity != nextChannel) { channelIdentity = nextChannel; sceneEpoch++ }
        primary = model
        pinned = nextPinned
        for (name in listOf("page_header_gradient_bg", "header_normal_full_bg", "native_home_page_bg")) {
            hide(find(model.view, name), false)
        }
        for (name in listOf("native_home_page_appbar_layout", "native_home_page_appbar_toolbar_layout", "pager_tab_container")) {
            clearBackground(find(model.view, name))
        }
        val searchContainer = find(model.view, "search_view_container")
        val nativeSearch = searchContainer?.let { field(it, "mainSearchView") as? View }
            ?: searchContainer?.let { find(it, "search_view") }
        search = nativeSearch?.let { field(it, "textViewSwitcher") as? View } ?: nativeSearch
        download = find(model.view, "download_view")
        hide(searchContainer, false)
        val strip = find(model.view, "pager_tab_container")
        hide(strip, false)
        hide(field(model.owner, "basePagerTabContainer") as? View, false)
        hide(field(model.owner, "tabContainer") as? View, false)
        secondary = if (index == 2) {
            val child = call(model.adapter, "getFragment", selected(model), false)
            pager(child)
        } else null
        secondary?.let { hide(find(it.view, "pager_tab_container"), true) }
        val entries = if (index == 0) model.labels.indices.filter { it !in pinned }.map { model to it }
            else secondary?.let { nested -> nested.labels.indices.map { nested to it } }.orEmpty()
        if (menuEntries.map { it.first.owner to it.second } != entries.map { it.first.owner to it.second }) closeMenu()
        menuEntries = entries
        val root = overlay ?: Overlay(context).also { layer ->
            overlay = layer
            layer.clipChildren = false; layer.clipToPadding = false; layer.translationZ = 20f * d
            parent.addView(layer, FrameLayout.LayoutParams(-1, -1))
        }
        root.visibility = View.VISIBLE
        ensurePanel(0, pinned.size, true)
        ensurePanel(1, 1, false)
        if (index != 1) ensurePanel(2, 1, false)
        val insets = host.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())
        root.getLocationInWindow(overlayPosition)
        host.getLocationInWindow(barPosition)
        val top = ((insets?.top ?: dp(28f)) - overlayPosition[1]).coerceAtLeast(0) + dp(6f)
        prepareViewport(model, top + dp(48f) + overlayPosition[1], parent.height - host.top + dp(12f))
        val margin = dp(16f); val circle = dp(40f); val gap = dp(8f)
        val hasMenu = index == 0 || index == 2
        val available = parent.width - margin * 2
        val capsuleWidth = available - circle - gap - if (hasMenu) circle + gap else 0
        place(0, margin + if (hasMenu) circle + gap else 0, top, capsuleWidth, circle)
        place(1, parent.width - margin - circle, top, circle, circle)
        panels[2]?.visibility = if (hasMenu) View.VISIBLE else View.GONE
        if (hasMenu) place(2, margin, top, circle, circle)
        if (menuOpen) placeMenu(top + circle + gap)
        val nextPresentation = "$sceneEpoch:${selected(model)}:${secondary?.let(::selected)}:$menuOpen:${context.resources.configuration.uiMode}"
        if (presentation != nextPresentation) {
            presentation = nextPresentation
            panels.values.forEach { it.postInvalidateOnAnimation() }
        }
        transitionChrome(index,true)
    }

    private fun detachBehavior(view: View?) {
        view ?: return
        val params = view.layoutParams ?: return
        if (!behaviorContracts.containsKey(params.javaClass)) {
            // 4.129 obfuscates these methods to f()/o(Behavior). Bind the
            // verified Coordinator contract by type, not the unobfuscated name.
            val behaviorType = generateSequence(params.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .map { it.returnType }
                .firstOrNull { it.name == "androidx.coordinatorlayout.widget.CoordinatorLayout\$Behavior" }
            val pair = behaviorType?.let { MiMarketBindingContracts.uniqueAccessors(params.javaClass, it) }
            behaviorContracts[params.javaClass] = pair?.let {
                it[0].isAccessible = true; it[1].isAccessible = true
                it[0] to it[1]
            }
        }
        val (getter,setter) = behaviorContracts[params.javaClass] ?: return
        val behavior = runCatching { getter.invoke(params) }.getOrNull() ?: return
        behaviors.getOrPut(view) { BehaviorBinding(params,behavior,setter) }
        runCatching { setter.invoke(params, null) }.getOrElse { return }
        view.requestLayout()
    }
    private fun prepareViewport(model: Pager, safeTopWindow: Int, bottomInset: Int) {
        // Verified 4.129 XML: Home uses Coordinator scrolling behavior; Game
        // and Rank place web_pager below pager_tab_container in RelativeLayout.
        val nativeHeader = find(model.view, "native_home_page_appbar_layout")
        hide(nativeHeader, false)
        hide(find(model.view, "sticky_header_wrapper"), false)
        val geometry = listOf(content?.width ?: 0,content?.height ?: 0,safeTopWindow,bottomInset)
        if (preparedPagers[model.pager] != geometry) {
            detachBehavior(nativeHeader)
            detachBehavior(model.pager)
            detachBehavior(find(model.view, "sticky_header_wrapper"))
            if (model.pager.top != 0) model.pager.offsetTopAndBottom(-model.pager.top)
            preparedPagers[model.pager] = geometry
        }
        val params = model.pager.layoutParams
        if (params is android.widget.RelativeLayout.LayoutParams && !relativeParams.containsKey(model.pager)) {
            relativeParams[model.pager] = params
            model.pager.layoutParams = android.widget.RelativeLayout.LayoutParams(params).apply {
                removeRule(android.widget.RelativeLayout.BELOW)
                removeRule(android.widget.RelativeLayout.ALIGN_BASELINE)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                topMargin = 0; bottomMargin = 0; height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        val child = call(model.adapter,"getFragment",selected(model),false)
        val pageRoot = call(child,"getView") as? View ?: return
        // 4.129 NativeHomePagePagerFragment.childFragmentLifecycleCallbacks:
        // non-feed / assemble roots get pager_web_tab_height - 5.59dp padding;
        // feed RecyclerViews get rvItemDecorationForFirstChild instead.
        // Both belong to the removed native tab strip, not the feed's content.
        val nativeHeaderDecoration = field(model.owner, "rvItemDecorationForFirstChild")
        if (model.owner.javaClass.name == "com.xiaomi.market.business_ui.main.home.NativeHomePagePagerFragment" &&
            pageRoot is ViewGroup && pageRoot.paddingTop > 0 &&
            generateSequence(pageRoot.javaClass as Class<*>?) { it.superclass }.none {
                it.name == "androidx.recyclerview.widget.RecyclerView" || it.name == "android.webkit.WebView" ||
                    it.name == "androidx.core.widget.NestedScrollView"
            }) {
            paddings.getOrPut(pageRoot) { Padding(pageRoot.paddingLeft,pageRoot.paddingTop,pageRoot.paddingRight,pageRoot.paddingBottom,pageRoot.clipToPadding) }
            pageRoot.setPadding(pageRoot.paddingLeft,0,pageRoot.paddingRight,pageRoot.paddingBottom)
        }
        pager(child)?.takeIf { it.pager !== model.pager }?.let { nested ->
            // NativeRankFragment is itself a pager placed BELOW a second strip.
            hide(find(nested.view,"pager_tab_container"),true)
            prepareViewport(nested,safeTopWindow,bottomInset)
            return
        }
        val stack = ArrayDeque<View>(); stack += pageRoot
        var visited = 0
        val pos = IntArray(2)
        model.pager.getLocationInWindow(pos)
        val safeTop = (safeTopWindow - pos[1]).coerceAtLeast(0)
        while (stack.isNotEmpty() && visited++ < 600) {
            val view = stack.removeLast()
            if (view is GlassHostLayout || view is AdapterOwnedOverlay ||
                view.visibility != View.VISIBLE || view.alpha <= 0f ||
                view.javaClass.name == "com.xiaomi.market.common.view.NativeEmptyLoadingView") continue
            if (view is ViewGroup) {
                val scrolling = view is android.webkit.WebView || generateSequence(view.javaClass as Class<*>?) { it.superclass }
                    .any { it.name == "androidx.recyclerview.widget.RecyclerView" || it.name == "androidx.core.widget.NestedScrollView" }
                val visible = Rect()
                if (scrolling && view.height > (content?.height ?: Int.MAX_VALUE) / 3 &&
                    view.getGlobalVisibleRect(visible) && visible.width() > view.width / 2) {
                    if (nativeHeaderDecoration != null && !removedHeaderDecorations.containsKey(view)) {
                        val count = (call(view,"getItemDecorationCount") as? Number)?.toInt() ?: 0
                        if ((0 until count).any { call(view,"getItemDecorationAt",it) === nativeHeaderDecoration }) {
                            call(view,"removeItemDecoration",nativeHeaderDecoration)
                            removedHeaderDecorations[view] = nativeHeaderDecoration
                        }
                    }
                    view.getLocationInWindow(pos)
                    val insetGeometry = geometry + listOf(pos[1],view.height)
                    if (appliedInsets[view] == insetGeometry) continue
                    val atStart = !view.canScrollVertically(-1)
                    val selectedPage = selected(model)
                    val boundPage = page
                    val old = paddings.getOrPut(view) { Padding(view.paddingLeft,view.paddingTop,view.paddingRight,view.paddingBottom,view.clipToPadding) }
                    // Native header spacing has been replaced, not added to.
                    view.getLocationInWindow(pos)
                    val top = (safeTopWindow - pos[1]).coerceAtLeast(0)
                    val bottom = maxOf(old.bottom,bottomInset)
                    if (view is android.webkit.WebView) {
                        if (view.paddingTop != 0 || view.paddingBottom != 0) view.setPadding(old.left,0,old.right,0)
                        MiMarketWebInsets.apply(view,top,bottom)
                    } else if (view.paddingTop != top || view.paddingBottom != bottom) view.setPadding(old.left,top,old.right,bottom)
                    view.clipToPadding = false
                    appliedInsets[view] = insetGeometry
                    if (atStart) view.postOnAnimation {
                        if (!view.isAttachedToWindow || appliedInsets[view] != insetGeometry ||
                              page !== boundPage || selected(model) != selectedPage) return@postOnAnimation
                        // RecyclerView otherwise preserves the old absolute first
                        // child anchor across a padding change. Apply the new
                        // inset before its first traversal, once per geometry.
                        val manager = call(view, "getLayoutManager")
                        if (manager != null && (call(view, "getScrollState") as? Number)?.toInt() == 0) {
                            call(manager, "scrollToPositionWithOffset", 0, 0)
                        } else if (view is android.webkit.WebView && view.scrollY == 0) {
                            view.scrollTo(0, 0)
                        }
                        view.postInvalidateOnAnimation()
                    }
                    continue
                }
                for (i in 0 until view.childCount) stack += view.getChildAt(i)
            }
        }
    }

    private fun reportViewport(view: ViewGroup, model: Pager, safeTop: Int) {
        if (!view.isAttachedToWindow) return
        val pos = IntArray(2); view.getLocationInWindow(pos)
        val line = "page=${model.owner.javaClass.simpleName}/${model.labels.getOrNull(selected(model))} " +
            "view=${view.javaClass.name} xy=${pos.toList()} size=${view.width}x${view.height} " +
            "safe=$safeTop pad=${view.paddingTop} scroll=${view.scrollY} " +
            "first=${view.getChildAt(0)?.let { "${it.javaClass.simpleName}:${it.top}:${it.height}" }} " +
            "ancestors=${generateSequence(view as View?) { it.parent as? View }.take(7).joinToString { v ->
                val p=IntArray(2); v.getLocationInWindow(p)
                "${v.javaClass.simpleName}@${p[1]}:pad${v.paddingTop}:bg${(v.background as? android.graphics.drawable.ColorDrawable)?.color}"
            }}\n"
        runCatching { java.io.File(context.getExternalFilesDir(null),"liquidtab-market-viewport.txt").appendText(line) }
        if (view is android.webkit.WebView) {
            view.evaluateJavascript("""(function(){return JSON.stringify({ready:document.readyState,dpr:devicePixelRatio,y:scrollY,body:document.body&&getComputedStyle(document.body).paddingTop,nodes:Array.from(document.querySelectorAll('body,body>*,body>*>*')).slice(0,24).map(function(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);return {tag:e.tagName,id:e.id,top:r.top,h:r.height,pad:s.paddingTop,margin:s.marginTop,position:s.position}})});})()""") { value ->
                runCatching { java.io.File(context.getExternalFilesDir(null),"liquidtab-market-dom.txt").writeText(value) }
            }
        }
    }

    private companion object {
        private val materials = List(5) { role ->
            TopBarMaterialPolicy(
                role = when (role) { 0 -> TopBarRole.CHANNELS; 3 -> TopBarRole.SEARCH; 4 -> TopBarRole.MENU; else -> TopBarRole.ACTION },
                heightDp = if (role == 3) 44f else 40f,
                cornerRadiusPercent = if (role == 4) 16f else 50f,
                iconOnly = true, selectedAccent = role == 0, accentColor = 0xFFFF6900.toInt(),
            )
        }
        fun materialConfig(config: GlassConfig, role: Int) = materials[role].apply(config)
    }
    private fun ensurePanel(role: Int, count: Int, slider: Boolean): GlassHostLayout {
        if (role == 0 && headerSlots != count) {
            if (headerSlots > 0 && chromeShown) morphSlider()
            headerSlots=count
        }
        panels[role]?.let { panel -> panel.updateAdapterSlotCount(count, selection(role)); return panel }
        val state = MaterialState(role)
        val panel = GlassHostLayout(context, materialConfig(main!!.config, role), count,
            nativeSelectionReliable = true, navigationSource = content, sliderEnabled = slider,
            adapterOverride = MaterialAdapter(state, role)).apply {
            tag = role; contentDescription = when (role) { 0 -> "频道"; 1 -> "下载管理"; 2 -> "更多频道"; 3 -> "搜索"; else -> "频道菜单" }
            elevation = 0f
            forceHasOverlappingRendering(false)
        }
        panels[role] = panel
        overlay!!.addView(panel, FrameLayout.LayoutParams(1, 1))
        return panel
    }
    private fun place(role: Int, x: Int, y: Int, width: Int, height: Int) {
        val panel = panels[role] ?: return
        val params = panel.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.height != height || params.leftMargin != x || params.topMargin != y) {
            params.width = width.coerceAtLeast(1); params.height = height.coerceAtLeast(1)
            params.gravity = Gravity.TOP or Gravity.LEFT; params.leftMargin = x; params.topMargin = y
            panel.layoutParams = params
        }
    }
    private fun selection(role: Int): Int = if (role == 0) primary?.let { pinned.indexOf(selected(it)).coerceAtLeast(0) } ?: 0 else 0
    private fun clickNative(view: View?) { view?.performClick() }
    fun isHomeRankSelected(): Boolean = visualPage==0 && primary?.let { it.labels.getOrNull(selected(it))=="榜单" } == true
    fun openHomeRank(owner: Any?): Boolean {
        val model=pager(owner) ?: return false
        val index=model.labels.indexOf("榜单")
        if(index<0)return false
        choose(model,index)
        return true
    }
    fun openHomeRecommendation(owner: Any?): Boolean {
        val model=pager(owner) ?: return false
        val index=model.labels.indexOf("推荐")
        if(index<0)return false
        choose(model,index)
        return true
    }
    fun openSearch(): Boolean {
        if (search?.isAttachedToWindow == true && search?.performClick() == true) return true
        // Verified in 4.129 MainSearchView: retain Market's search intent/routing
        // factory when the profile page has no native search widget to click.
        return runCatching {
            val utils = Class.forName("com.xiaomi.market.util.MarketUtils", false, context.classLoader)
            val intent = utils.getMethod("getSearchActivityIntent").invoke(null) as android.content.Intent
            utils.getMethod("startSearchActivity", Context::class.java, android.content.Intent::class.java,
                Boolean::class.javaPrimitiveType).invoke(null, context, intent, false)
            true
        }.getOrDefault(false)
    }
    private fun tap(role: Int, index: Int): Boolean {
        if (bindingPending) return true
        when (role) {
            0 -> primary?.let { pinned.getOrNull(index)?.let { slot -> choose(it, slot) } }
            1 -> clickNative(download)
            2 -> if (menuOpen) closeMenu() else if (menuEntries.isNotEmpty()) {
                menuOpen = true; menuOffset = 0f
                val panel = ensurePanel(4, 1, false)
                panel.visibility = View.VISIBLE
                panel.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { lastTouchY = event.y; touchStartY = event.y; true }
                        MotionEvent.ACTION_MOVE -> {
                            val limit = (menuEntries.size * dp(48f) + dp(16f) - panel.height).coerceAtLeast(0).toFloat()
                            menuOffset = (menuOffset + lastTouchY - event.y).coerceIn(0f, limit)
                            lastTouchY = event.y; panel.postInvalidateOnAnimation(); true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (kotlin.math.abs(event.y - touchStartY) < dp(8f)) {
                                val y = event.y + menuOffset - dp(8f)
                                if (y >= 0f) {
                                    val slot = (y / dp(48f)).toInt()
                                    menuEntries.getOrNull(slot)?.let { choose(it.first, it.second) }
                                }
                            }
                            true
                        }
                        else -> true
                    }
                }
                lastScan = 0L
            }
            3 -> clickNative(search)
        }
        return true
    }
    private fun placeMenu(top: Int) {
        val parent = content ?: return
        val width = minOf(dp(188f), parent.width - dp(32f))
        val height = minOf((parent.height - top - dp(144f)).coerceAtLeast(dp(48f)), menuEntries.size * dp(48f) + dp(16f))
        place(4, dp(16f), top, width, height)
    }
    private fun closeMenu() { menuOpen = false; panels[4]?.visibility = View.GONE }
    private fun draw(canvas: Canvas, frame: AdapterNavigationFrame, role: Int) = synchronized(paints[role]) {
        val ink = paints[role]
        val color = if (frame.darkGlass) Color.WHITE else 0xFF242424.toInt()
        ink.color = color; ink.style = Paint.Style.FILL
        ink.textSize = (when (role) { 0 -> 15f; 4 -> 16f; else -> 13f }) * frame.scaledDensity
        ink.textAlign = if (role == 4) Paint.Align.LEFT else Paint.Align.CENTER
        fun label(text: String, x: Float, y: Float) {
            val fm = ink.fontMetrics; canvas.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, ink)
        }
        when (role) {
            0 -> primary?.let { model -> pinned.forEachIndexed { visual, native ->
                ink.color = if (selected(model) == native) 0xFFFF6900.toInt() else color
                label(model.labels[native], 4f*d + (visual + .5f)*frame.slotWidth, frame.height/2f)
            } }
            1 -> {
                val x=frame.width/2f; val y=frame.height/2f
                ink.style=Paint.Style.STROKE; ink.strokeWidth=2f*d; ink.strokeCap=Paint.Cap.ROUND
                canvas.drawLine(x,y-9*d,x,y+5*d,ink)
                canvas.drawLine(x-5*d,y,x,y+5*d,ink); canvas.drawLine(x+5*d,y,x,y+5*d,ink)
                canvas.drawLine(x-8*d,y+10*d,x+8*d,y+10*d,ink)
            }
            2 -> { for (i in -1..1) canvas.drawCircle(frame.width/2f+i*5*d,frame.height/2f,1.5f*d,ink) }
            3 -> {
                ink.style=Paint.Style.STROKE; ink.strokeWidth=1.8f*d
                canvas.drawCircle(23*d,frame.height/2f-2*d,6*d,ink)
                canvas.drawLine(27*d,frame.height/2f+3*d,32*d,frame.height/2f+8*d,ink)
                ink.style=Paint.Style.FILL; label("搜索应用和游戏",frame.width/2f,frame.height/2f)
            }
            4 -> {
                canvas.clipRect(0,0,frame.width,frame.height)
                menuEntries.forEachIndexed { i, (model, slot) ->
                    ink.color=if (selected(model)==slot) 0xFFFF6900.toInt() else color
                    label(model.labels[slot],22*d,8*d+(i+.5f)*dp(48f)-menuOffset)
                }
            }
        }
        ink.style=Paint.Style.FILL
    }

    private inner class MaterialState(private val role: Int) : AppNavigationState {
        @Volatile private var ready = false
        @Volatile private var renderer: GlassHostLayout? = null
        private var renderedEpoch = -1L
        private var compositorMode: Boolean? = null
        private var wasActive = false
        private val copyBarrier = MiMarketFrameBarrier()
        private val blurFrames = MiMarketBlurFrames()
        private var opticalDirty = true
        override fun onOpticalBackdropChanged() { blurFrames.captured(); opticalDirty = true }
        override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean? =
            if (animating || opticalDirty || !ready || (role == 0 && sliderAnimator?.isRunning == true)) null else false
        override fun indicatorHorizontalGeometry(center: Float, width: Float): Pair<Float,Float>? {
            if (role != 0) return null
            val from=sliderFrom
            val geometry=if(from != null && sliderProgress < 1f) {
                (from.first+(center-from.first)*sliderProgress) to
                    (from.second+(width-from.second)*sliderProgress)
            } else center to width
            presentedSlider=geometry
            return geometry
        }
        override fun opticalBlurNodeNeedsRecord(prepared: Boolean, backdrop: android.graphics.Bitmap?, width: Int,
            height: Int, effect: android.graphics.RenderEffect?) = blurFrames.needsRecord(prepared, backdrop, width, height, effect)
        override fun onOpticalBlurNodeRecorded(backdrop: android.graphics.Bitmap?, width: Int,
            height: Int, effect: android.graphics.RenderEffect?) = blurFrames.recorded()
        override fun scheduleOpticalSurfaceCopy(root: View, copy: () -> Unit) =
            copyBarrier.schedule(root, opticalCaptureEpoch(), ::opticalCaptureEpoch, copy)
        override fun onHostFrame(host: View) {
            renderer = host as? GlassHostLayout
            val epoch = opticalCaptureEpoch()
            if (renderedEpoch != epoch) {
                renderedEpoch = epoch
                // A new page needs a fresh sample, not a new Surface/host.
                // Keep the previously submitted glass visible during handoff.
                ready = false
                opticalDirty = true
                copyBarrier.reset()
                renderer?.postInvalidateOnAnimation()
            }
        }
        override fun onOpticalBackdropCaptured(barSource: Rect?) {
            if (!ready) renderer?.queueOpticalSurfaceRender()
        }
        override fun opticalSurfaceCopyHandler() = renderHandler
        override fun deferOpticalCopyRenderToUi() = true
        private fun active() = overlay?.visibility == View.VISIBLE &&
            (chromeShown || chromeAnimator?.isRunning == true) &&
            (role != 4 || menuOpen) && (role != 2 || visualPage != 1)
        override val allowsSoftwareBackdropCapture get() = backdropState.allowsSoftwareBackdropCapture
        override fun skipLiveBackdrop(selectedIndex: Int) = backdropState.skipLiveBackdrop(selectedIndex)
        override fun resolveBackdropScene(host: View): View? {
            // The accessory host is nested in Overlay, unlike the bottom bar.
            // Resolve the native content sibling explicitly; sampling its own
            // parent would find only glass controls and produce an empty scene.
            val root = this@MiMarketChrome.content ?: return null
            return backdropState.backdropSceneViews(root, root.childCount)?.firstOrNull {
                it.visibility == View.VISIBLE && it.alpha > 0f
            }
        }
        override fun backdropSceneViews(content: ViewGroup, hostIndex: Int) =
            backdropState.backdropSceneViews(content, content.childCount)
        override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) =
            if (role in 0..2) overlay?.visibility == View.VISIBLE else active()
        override fun allowsOpticalSurfacePipeline() =
            (role in 0..2 || active()) && backdropState.allowsOpticalSurfacePipeline()
        override fun maintainsCompositorSamplingWithoutSurface() =
            active() && backdropState.maintainsCompositorSamplingWithoutSurface()
        override fun pauseBackdropWork(host: View) = !active()
        override fun pauseOpticalSurfaceProbe() = !active()
        override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
            // Use the bottom bar's verified native/WebView renderer decision.
            backdropState.updateBackdropRendererMode(host)
            val next = allowsOpticalSurfacePipeline()
            val visible = active()
            if (compositorMode != next) {
                compositorMode = next
                ready = false
                host.liveBackdropNodeForAdapter.setRenderEffect(null)
                host.liveBackdropNodeForAdapter.discardDisplayList()
                host.clearLiveBackdropForAdapter()
                host.invalidate()
            }
            // Cached policy is not proof that the actual Surface survived.
            // Restart probes on reveal, and recover a lost buffer on any page.
            if (next && visible && (!wasActive || !host.opticalSurfaceRunningForAdapter)) {
                host.enableOpticalSurfaceRendererForAdapter()
            } else if (!next && host.opticalSurfaceRunningForAdapter) {
                host.disableOpticalSurfaceRendererForAdapter()
            }
            wasActive = visible
            return true
        }
        override fun enterDirectOpticalBackdrop(surface: android.view.SurfaceView, pipelineActive: Boolean): Boolean {
            if (allowsOpticalSurfacePipeline()) return false
            surface.visibility = View.GONE
            return true
        }
        override fun opticalCaptureEpoch() = sceneEpoch + backdropState.opticalCaptureEpoch()
        override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = true
        override fun suppressRedrawnSource() = false
        override fun drawsNavigationContent() = true
        override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = selection(role)
        override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = this@MiMarketChrome.draw(canvas, frame, role)
        override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) = tap(role, index)
        override fun allowsHostTouch() = active() && role != 4 && !bindingPending
        override fun onOpticalSurfaceFrameInvalidated() {
            resetRenderer()
        }
        private fun resetRenderer() {
            ready = false
            opticalDirty = true
            compositorMode = null
            copyBarrier.reset()
            renderer?.let { backdropState.invalidateBackdropHost(it); it.postInvalidateOnAnimation() }
        }
        override fun onHostDetached() { resetRenderer() }
        override fun onHostWindowFocusChanged(hasFocus: Boolean) {
            if (hasFocus) {
                resetRenderer()
            }
        }
        // Rebuilding a shader is not Surface loss. Resetting the renderer here
        // would make its own padding/effect configuration request another bind.
        override fun onOpticalRendererConfigurationChanged() { opticalDirty = true; ready = false }
        override fun onOpticalSurfacePipelineEnabled(wasActive: Boolean) { if (!wasActive) ready = false }
        override fun onOpticalSurfaceFrameRendered(): Boolean { val first = !ready; ready = true; opticalDirty = false; return first }
        // These panels sample the window Surface, which excludes their optical
        // SurfaceViews. Never paint a waiting-frame fallback into that window:
        // PixelCopy would capture our own white panel and glyphs indefinitely.
        // onOpticalBackdropCaptured queues the first render after publication.
        override fun holdDirectBackdropUntilOpticalFrame(liveBackdropActive: Boolean, hasDisplayList: Boolean) = false
        override fun handleSystemBack(host: View): Boolean { if (!menuOpen) return false; closeMenu(); return true }
    }
    private class MaterialAdapter(private val state: AppNavigationState, private val role: Int) : TargetAdapter {
        override val key = "mi-market-chrome-$role"
        override fun normalizeConfig(config: GlassConfig) = materialConfig(config, role)
        override val indicatorInteractionPolicy get() = materials[role].indicatorInteractionPolicy
        override val ownsNavigationDrawing = true
        override val ownsNavigationTap = true
        override val actionOnlyIndices get() = if (role == 0) emptySet<Int>() else setOf(0)
        override val managesHostSiblingOrder = true
        override val skipNavigationSnapshot = true
        override val managesNativeTabVisibility = true
        override val allowsSystemBackgroundBlur = false
        override val reuseOpticalBlur = true
        override val reuseSurfaceBlurDuringBarAnimation = true
        override val usesDirectOpticalBackdropMode = true
        override val usesOpticalSurfacePipeline = true
        override val countsOpticalCompositorCopies = true
        override val keepsOpticalSurfaceDuringBarAnimation = true
        override val opticalSurfaceOverflowDp = 48f
        override val deferCompanionRemovalOnDetach = true
        override val ownsOpticalSurfaceLayout = true
        override val ownsHostLayout = true
        override fun opticalSurfaceLayout(contentWidth: Int, contentHeight: Int, hostLeft: Int, hostTop: Int,
            hostWidth: Int, hostHeight: Int, overflow: Int) = OpticalSurfaceLayout(
            hostWidth + overflow * 2, hostHeight + overflow * 2,
            hostLeft - overflow, hostTop - overflow, 0, 0, Gravity.TOP or Gravity.LEFT,
        )
        override fun opticalCaptureGeometry(rootWidth: Int, rootHeight: Int, left: Int, top: Int, width: Int, height: Int, padding: Int) =
            MiMarketAdapter.opticalCaptureGeometry(rootWidth,rootHeight,left,top,width,height,padding)
        override fun createNavigationState(context: Context) = state
    }
    fun dispose(deferRemoval: Boolean = false) {
        sliderAnimator?.cancel(); sliderAnimator=null; sliderFrom=null; presentedSlider=null
        headerSlots=0; sliderProgress=1f
        ++chromeAnimationGeneration; chromeAnimator?.cancel(); chromeAnimator=null
        chromeShown=false; chromeTarget=-1
        edgeToEdge(false)
        restore()
        // Removing siblings during ViewGroup.dispatchDetachedFromWindow shifts
        // its child array while Android is still iterating the old child count.
        val oldPanels=panels.values.toList()
        val oldOverlay=overlay
        val oldVeil=edgeVeil
        val removeOwnedViews=Runnable {
            oldPanels.forEach { (it.parent as? ViewGroup)?.removeView(it) }
            oldOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            oldVeil?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
        panels.clear()
        overlay = null; content = null; primary = null; secondary = null; page = null
        edgeVeil=null
        if(deferRemoval)renderHandler.post(removeOwnedViews)else removeOwnedViews.run()
        fields.clear()
        pagerCache.clear()
        behaviorContracts.clear()
    }
}
