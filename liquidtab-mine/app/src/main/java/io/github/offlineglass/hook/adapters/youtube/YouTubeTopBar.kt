package io.github.offlineglass.hook.adapters.youtube

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.NavigationLabelLocalization
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** YouTube home-only top chrome. Native controls remain the semantic owners. */
internal object YouTubeTopBar {
    private val handoff = YouTubeChromeHandoff()
    private var lifecycleInstalled = false
    fun installLifecycle() {
        if (lifecycleInstalled) return
        lifecycleInstalled = true
        // Stable Activity callbacks, before the first draw; no native symbols.
        listOf("onPostCreate", "onContentChanged", "onConfigurationChanged").forEach { method ->
            io.github.offlineglass.hook.compat.XposedBridge.hookAllMethods(Activity::class.java, method,
                object : io.github.offlineglass.hook.compat.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? Activity)?.let { attach(it) }
                    }
                })
        }
    }
    private val sessions = WeakHashMap<Activity, WeakReference<Session>>()
    private val homeSelection = WeakHashMap<View, Boolean>()

    internal fun updateHomeSelection(root: View, selected: Boolean) {
        synchronized(homeSelection) { homeSelection[root] = selected }
    }

    private fun isHomeSelected(root: View): Boolean =
        synchronized(homeSelection) { homeSelection[root] == true }

    fun attach(activity: Activity) {
        if (activity.packageName != "com.google.android.youtube" || activity.isFinishing || activity.isDestroyed) return
        val name = activity.javaClass.name
        if (!name.contains("watchwhile.MainActivity") && !name.contains("Shell\$HomeActivity")) return
        val content = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        val existing = sessions[activity]?.get()
        if (existing?.canReuse(content) == true) { existing.update(); return }
        existing?.stop()
        if (handoff.consume(activity.taskId, SystemClock.uptimeMillis())) updateHomeSelection(content.rootView, true)
        Session(activity, content).also { sessions[activity] = WeakReference(it); it.start() }
    }

    private data class Tab(var view: TextView, val label: String)
    private data class Original(
        val alpha: Float,
        val clickable: Boolean,
        val longClickable: Boolean,
        val focusable: Boolean,
        val accessibility: Int,
        val elevation: Float,
        val visibility: Int,
        val outline: android.view.ViewOutlineProvider?,
    )

    private class Overlay(context: Context) : FrameLayout(context), AdapterOwnedOverlay {
        var acceptsTouch = false
        var onForegroundChanged: (() -> Unit)? = null
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            onForegroundChanged?.invoke()
        }
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            onForegroundChanged?.invoke()
        }
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean =
            acceptsTouch && super.dispatchTouchEvent(event)
        init {
            clipChildren = false; clipToPadding = false; isFocusable = false
            // A sibling navigation host can have a higher Z value than the
            // activity content's last child. Keep the custom header above it
            // so its controls receive the taps they visibly own.
            translationZ = 16f * resources.displayMetrics.density
        }
    }

    private class Session(
        private val activity: Activity,
        private val content: FrameLayout,
    ) : ViewTreeObserver.OnPreDrawListener {
        private val density = activity.resources.displayMetrics.density
        private val chromeParent: FrameLayout =
            (activity.window?.decorView as? FrameLayout) ?: content
        private val overlay = Overlay(activity)
        private val sourceState = WeakHashMap<View, Original>()
        private val nativeIds = HashMap<String, Int>()
        private val themeValue = android.util.TypedValue()
        private var frameDark: Boolean? = null
        private val frameSceneScratch = ArrayList<View>()
        private var browse: View? = null
        private var toolbar: ViewGroup? = null
        private var filterBar: ViewGroup? = null
        private var appbar: ViewGroup? = null
        private var nativeBackdrop: View? = null
        private var nativePageBackground: View? = null
        private var nativeStatusShade: View? = null
        private val topActions = YouTubeTopActions()
        private val notificationPage = YouTubeNotificationPage()
        private var notificationsActive = false
        private var notificationPresentation: Boolean? = null
        private var sceneIdentity = emptyList<View>()
        private val splitSceneBranches = WeakHashMap<View, Boolean>()
        private var notificationBadge: TextView? = null

        private var tabs = listOf("全部", "播客", "直播").map { Tab(TextView(activity), it) }
        private var visibleTabs = tabs.filter { it.label == "全部" || it.label == "直播" }
        private var selectedLabel: String? = null
        private var tabsRevision = 0
        private var channelBindingReady = false
        private var controls: LinearLayout? = null
        private val materials = ArrayList<GlassHostLayout>(4)
        private val homeLayout = YouTubeHomeLayout()
        private val motion = YouTubeTopBarMotion(overlay) { materials.forEach { it.invalidate() } }
        private var filterOriginalHeight: Int? = null
        private var filterOriginalOwner: ViewGroup? = null



        private var menu: View? = null
        private var menuCard: View? = null
        private var menuMaterial: GlassHostLayout? = null
        private var menuClosing = false
        var menuAnimating = false
            private set
        private var lastScan = 0L
        private var lastBindScan = 0L
        private var lastConfigRead = 0L
        private var config = GlassConfig()
        private var active = false
        private var ownsHomeLayout = false
        private var stopped = false
        private var frameSceneViews: List<View>? = null
        private var needsResumeRefresh = true
        private var foregroundUpdatePending = false
        private var observedTree: ViewTreeObserver? = null
        var backdropGeneration = 0
            private set
        private val contentPos = IntArray(2)
        private val viewPos = IntArray(2)
        private val reusableRect = android.graphics.Rect()
        private var lastNight = false
        private var lastEnglishLabels: Boolean? = null
        private var channels: YouTubeChannelsView? = null
        private var gradientNight: Boolean? = null
        private val topGradient = View(activity).apply { isClickable = false; translationZ = -1f }
        private val statusColor = activity.window.statusBarColor
        private val statusContrast = activity.window.isStatusBarContrastEnforced
        private var renderedSelection: String? = null
        private val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { stop() }
        }

        fun start() {
            overlay.onForegroundChanged = {
                if (!overlay.hasWindowFocus() || overlay.windowVisibility != View.VISIBLE) needsResumeRefresh = true
                if (!foregroundUpdatePending && !stopped) {
                    foregroundUpdatePending = true
                    overlay.post {
                        foregroundUpdatePending = false
                        if (!stopped) onPreDraw()
                    }
                }
            }
            content.addOnAttachStateChangeListener(attachListener)
            // Place chrome at the decor root. YouTube wraps android.R.id.content
            // in an accessibility layer that may sit above it for touch input.
            chromeParent.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            overlay.addView(topGradient, FrameLayout.LayoutParams(-1, 1))
            observeTree()
            update()
        }

        fun canReuse(currentContent: FrameLayout): Boolean =
            !stopped && content === currentContent && overlay.parent === chromeParent

        private fun observeTree() {
            val next = content.viewTreeObserver
            if (observedTree === next || !next.isAlive) return
            observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            next.addOnPreDrawListener(this)
            observedTree = next
        }

        fun update() {
            if (stopped || activity.isFinishing || activity.isDestroyed) return
            // All top materials draw after this window's pre-draw. Resolve the
            // identical safe subtree set once, without caching across frames.
            frameSceneViews = null
            frameDark = null
            observeTree()
            val foreground = activity.hasWindowFocus() && content.windowVisibility == View.VISIBLE
            if (!foreground) needsResumeRefresh = true
            if (foreground && needsResumeRefresh) {
                needsResumeRefresh = false
                backdropGeneration++
                lastBindScan = 0L; lastScan = 0L
                (materials.toList() + listOfNotNull(menuMaterial)).forEach {
                    it.invalidateProjectionCache()
                    it.invalidateNavigationSnapshot()
                    it.adapterScheduleBackdropRefresh(0L)
                    it.adapterScheduleBackdropRefresh(64L)
                }
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastConfigRead >= 500L) {
                config = HookConfigReader.read(activity, activity.packageName)
                lastConfigRead = now
            }
            if (!activity.hasWindowFocus() && ownsHomeLayout && config.enabled && config.youtubeTopBar &&
                !activity.isInPictureInPictureMode && isHomeSelected(content.rootView) &&
                !YouTubeSearchPage.active(content.rootView) &&
                activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                // Focus loss pauses work, without restoring native chrome in
                // the system's Recents snapshot or changing feed ownership.
                overlay.acceptsTouch = false
                suppressNative()
                motion.update(active, (statusInset() + dp(48)).toFloat(), density, false)
                return
            }
            if (browse?.isAttachedToWindow != true || toolbar?.isAttachedToWindow != true || now - lastBindScan >= 500L) {
                bind(); lastBindScan = now
            }
            val page = browse
            notificationsActive = notificationPage.active(page)
            val homePage = config.enabled && config.youtubeTopBar &&
                !YouTubeSearchPage.active(content.rootView) &&
                isHomeSelected(content.rootView) &&
                !activity.isInPictureInPictureMode &&
                activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                page?.isAttachedToWindow == true && page.isShown && page.width >= content.width * .8f &&
                page.getGlobalVisibleRect(reusableRect) && reusableRect.width() >= content.width * .7f
            active = homePage && !YouTubeDrawerPage.active(content.rootView)
            ownsHomeLayout = homePage
            if (!active) {
                overlay.acceptsTouch = false
                closeMenu()
                // Explore overlays Home; its opening does not change the
                // feed's layout ownership or reset its scrolling viewport.
                if (homePage) {
                    suppressNative()
                } else restoreNative()
                motion.update(false, (statusInset() + dp(48)).toFloat(), density,
                    activity.hasWindowFocus() && !activity.isInPictureInPictureMode)
                return
            }
            if (!notificationsActive && now - lastScan >= 350L) {
                discoverTabs(); lastScan = now
            }
            suppressNative()
            collapseNativeFilterBar()
            ensureControls()
            placeControls()
            ensureMaterials()
            placeMaterials()
            updateNotificationPresentation()
            placeGradient()
            notificationPage.updateInset(page, notificationsActive)
            page?.let {
                chromeParent.getLocationOnScreen(contentPos)
                it.getLocationOnScreen(viewPos)
                val row = controls?.layoutParams as? FrameLayout.LayoutParams
                val headerBottom = (row?.topMargin ?: statusInset()) + (row?.height ?: dp(48))
                val headerSpace = (contentPos[1] + headerBottom + dp(12) - viewPos[1]).coerceAtLeast(0)
                homeLayout.update(it, headerSpace)
            }
            if (activity.window.statusBarColor != Color.TRANSPARENT) activity.window.statusBarColor = Color.TRANSPARENT
            if (activity.window.isStatusBarContrastEnforced) activity.window.isStatusBarContrastEnforced = false
            overlay.acceptsTouch = activity.hasWindowFocus()
            overlay.pivotX = content.width / 2f
            overlay.pivotY = statusInset().toFloat()
            motion.update(true, (statusInset() + dp(48)).toFloat(), density, activity.hasWindowFocus())
            if (overlay.parent === chromeParent && chromeParent.indexOfChild(overlay) != chromeParent.childCount - 1) overlay.bringToFront()
        }

        override fun onPreDraw(): Boolean {
            runCatching { update() }.onFailure { error ->
                android.util.Log.e("YouTubeGlassDiag", "top bar update failed", error)
                active = false
                overlay.acceptsTouch = false
                // A transient failure does not return owned chrome to native.
                if (ownsHomeLayout) suppressNative()
            }
            return true
        }

        private fun find(root: View, name: String): View? {
            val id = nativeIds[name] ?: root.resources.getIdentifier(name, "id", "com.google.android.youtube")
                .also { if (it != 0) nativeIds[name] = it }
            return if (id != 0) root.findViewById(id) else null
        }

        private fun bind() {
            val root = activity.window?.decorView as? ViewGroup ?: return
            nativeStatusShade = find(root, "global_status_bar_view")
            val nextBrowse = find(root, "browse_fragment_layout_coordinator_layout")
                ?: browse?.takeIf { it.isAttachedToWindow }
            val scope = nextBrowse ?: root
            val nextAppbar = find(scope, "appbar_layout") as? ViewGroup
            val nextToolbar = find(nextAppbar ?: scope, "toolbar") as? ViewGroup
            val nextFilter = find(scope, "filter_bar") as? ViewGroup
            nativeBackdrop = find(scope, "frosted_top_bar_backdrop")
            nativePageBackground = find(scope, "pane_fragment_background_container")
            if (browse !== nextBrowse || toolbar !== nextToolbar || filterBar !== nextFilter || appbar !== nextAppbar) {
                channelBindingReady = false

                // Keep visual ownership while native instances are replaced.
                closeMenu()
                browse = nextBrowse; toolbar = nextToolbar; filterBar = nextFilter
                appbar = nextAppbar
                if (nextFilter !== filterOriginalOwner) {
                    // Preserve the concrete AppBar/LinearLayout LayoutParams,
                    // including native scroll flags. Only height is ours.
                    filterOriginalHeight = nextFilter?.layoutParams?.height
                    filterOriginalOwner = nextFilter
                }


                notificationBadge = nextToolbar?.let { find(it, "new_content_count") as? TextView }
                lastScan = 0L
            }
        }

        private fun discoverTabs() {
            val root = filterBar ?: return
            val found = ArrayList<Tab>()
            var visited = 0
            var incomplete = false
            fun walk(view: View, depth: Int) {
                if (depth > 10 || ++visited > 512) { incomplete = true; return }
                if (view is TextView && view.text.isNotBlank() &&
                    runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == "chip_cloud_chip_modern_text") {
                    val label = view.text.toString().trim()
                    found += Tab(view, label)
                    return
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    if (incomplete) break
                    walk(view.getChildAt(i), depth + 1)
                }
            }
            walk(root, 0)
            if (incomplete) {
                channelBindingReady = false
                android.util.Log.w("YouTubeGlassDiag", "Native channel discovery budget exceeded")
                return
            }
            if (found.isEmpty()) { channelBindingReady = false; return }
            val keys = found.map { labelKey(it.label) }
            if (keys.distinct().size != keys.size) {
                channelBindingReady = false
                android.util.Log.w("YouTubeGlassDiag", "Ambiguous native channel semantics")
                return
            }
            channelBindingReady = true
            // Native chips are recycled while scrolling. Keep stable semantic
            // entries and refresh their click targets without rebuilding ink.
            val previousOrder = tabs.map { labelKey(it.label) }
            val merged = tabs.associateBy { labelKey(it.label) }.toMutableMap()
            found.forEach { fresh ->
                val key = labelKey(fresh.label)
                val old = merged[key]
                if (old != null) old.view = fresh.view else merged[key] = fresh
            }
            val updated = YouTubeChannelPolicy.merge(previousOrder, keys).mapNotNull(merged::get)
            if (updated.map { it.label } != tabs.map { it.label }) tabsRevision++
            tabs = updated
        }

        private fun labelKey(label: String): String = when (label.lowercase()) {
            "all", "全部" -> "all"
            "podcast", "podcasts", "播客" -> "podcasts"
            "live", "直播" -> "live"
            else -> label.lowercase()
        }

        private fun isPinned(label: String) = labelKey(label) in setOf("all", "live")

        private fun remember(view: View) {
            sourceState.getOrPut(view) {
                Original(view.alpha, view.isClickable, view.isLongClickable, view.isFocusable,
                    view.importantForAccessibility, view.elevation, view.visibility, view.outlineProvider)
            }
        }

        private fun suppress(view: View) {
            remember(view)
            if (view.outlineProvider != null) view.outlineProvider = null
            if (view.alpha != 0f) view.alpha = 0f
            if (view !== appbar && view.elevation != 0f) view.elevation = 0f
            if (view.visibility == View.VISIBLE) view.visibility = View.INVISIBLE
            if (view.isClickable) view.isClickable = false
            if (view.isLongClickable) view.isLongClickable = false
            if (view.isFocusable) view.isFocusable = false
            if (view.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO) {
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }

        private fun suppressNative() {
            // Hide the complete native toolbar subtree. Hiding only the known
            // logo/actions lets experiments and version-specific controls leak.
            // The complete header owns its background as well as the toolbar.
            // Hide that root so its scroll scrim cannot cover the native feed.
            (appbar ?: toolbar)?.let(::suppress)
            // feed_filter_bar is independently scrollable and owns its own
            // ytBaseBackground; hiding only its chip labels leaves a slab.
            filterBar?.let(::suppress)
            nativeBackdrop?.let(::suppress)
            nativeStatusShade?.let(::suppress)
        }

        private fun restoreNative() {
            homeLayout.restore()
            notificationPage.restoreInset()
            activity.window.statusBarColor = statusColor
            activity.window.isStatusBarContrastEnforced = statusContrast
            sourceState.forEach { (view, old) ->
                view.outlineProvider = old.outline
                view.alpha = old.alpha
                if (view !== appbar) view.elevation = old.elevation
                view.visibility = old.visibility
                view.isClickable = old.clickable
                view.isLongClickable = old.longClickable
                view.isFocusable = old.focusable
                view.importantForAccessibility = old.accessibility
            }
            sourceState.clear()
            filterOriginalHeight?.let { original ->
                filterOriginalOwner?.let { owner ->
                    if (filterBar === owner && owner.layoutParams?.height != original) {
                        owner.layoutParams = owner.layoutParams.apply { height = original }
                    }
                }
            }
        }

        private fun collapseNativeFilterBar() {
            val bar = filterBar ?: return
            val params = bar.layoutParams ?: return
            if (params.height == 0) return
            params.height = 0
            bar.layoutParams = params
        }

        private fun ensureControls() {
            val dark = isDark()
            val englishLabels = config.managerEnglishNavigationLabels
            if (controls != null && lastNight == dark && lastEnglishLabels == englishLabels) return
            if (controls != null && lastEnglishLabels == englishLabels) {
                val ink = if (dark) Color.WHITE else Color.rgb(30, 30, 34)
                val row = controls ?: return
                for (index in 0 until row.childCount) (row.getChildAt(index) as? CircleAction)?.updateInk(ink)
                (channels?.getChildAt(0) as? MoreAction)?.updateInk(ink)
                lastNight = dark
                refreshSelection()
                return
            }
            if (lastEnglishLabels != null && lastEnglishLabels != englishLabels) closeMenu()
            controls?.let { overlay.removeView(it) }
            renderedSelection = null
            val ink = if (dark) Color.WHITE else Color.rgb(30, 30, 34)
            val circleFill = Color.TRANSPARENT
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                clipChildren = false; clipToPadding = false
            }
            val drawer = CircleAction(activity, circleFill, ink, Glyph.MENU, "打开侧边栏") {
                dispatchTopAction(YouTubeExploreContract.Role.EXPLORE)
            }
            row.addView(drawer, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(View(activity), LinearLayout.LayoutParams(dp(8), 1))
            val more = MoreAction(activity, ink).apply {
                contentDescription = "更多频道"
                setOnClickListener { toggleMenu() }
            }
            val capsule = YouTubeChannelsView(activity, more) {
                materials.getOrNull(1)?.invalidate()
            }.apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
            channels = capsule
            row.addView(capsule, LinearLayout.LayoutParams(0, dp(40), 1f))
            row.addView(View(activity), LinearLayout.LayoutParams(dp(8), 1))
            val notificationAction = CircleAction(activity, circleFill, ink, Glyph.BELL, "通知") {
                dispatchTopAction(YouTubeExploreContract.Role.NOTIFICATIONS)
            }
            row.addView(notificationAction, LinearLayout.LayoutParams(dp(40), dp(40)))
            val badge = notificationBadge?.text?.toString().orEmpty()
            if (badge.isNotBlank() && badge != "0") notificationAction.badge = badge.take(2)
            row.addView(View(activity), LinearLayout.LayoutParams(dp(8), 1))
            val searchAction = CircleAction(activity, circleFill, ink, Glyph.SEARCH, "搜索") {
                dispatchTopAction(YouTubeExploreContract.Role.SEARCH)
            }
            row.addView(searchAction, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.tag = capsule
            overlay.addView(row, FrameLayout.LayoutParams(-1, dp(48)))
            notificationPresentation = null
            controls = row; lastNight = dark
            lastEnglishLabels = englishLabels
            refreshSelection()
        }

        private fun localizedLabel(label: String): String =
            if (config.managerEnglishNavigationLabels)
                NavigationLabelLocalization.toEnglish("youtube", label)
            else label

        private fun isDark(): Boolean = frameDark ?: computeDark().also { frameDark = it }

        private fun computeDark(): Boolean {
            var count = 0
            var bright = 0
            for (tab in tabs) {
                val view = tab.view
                if (view.isAttachedToWindow) {
                    count++
                    val color = view.currentTextColor
                    if (Color.red(color)*.299f + Color.green(color)*.587f + Color.blue(color)*.114f > 150f) bright++
                }
            }
            if (count >= 3) return bright * 2 >= count
            val value = themeValue
            val nativeBackground = activity.resources.getIdentifier("ytBaseBackground", "attr", activity.packageName)
            for (attribute in intArrayOf(nativeBackground, android.R.attr.colorBackground)) {
                if (attribute != 0 && activity.theme.resolveAttribute(attribute, value, true) &&
                    value.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                    val color = value.data
                    return Color.red(color)*.299f + Color.green(color)*.587f + Color.blue(color)*.114f < 128f
                }
            }
            val mode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return mode == Configuration.UI_MODE_NIGHT_YES
        }

        private fun selectedTab(): View? {
            var selected: Tab? = null
            var ambiguous = false
            for (tab in tabs) {
                if (tab.view.isAttachedToWindow && (tab.view.isSelected || tab.view.isActivated ||
                        (tab.view.parent as? View)?.let { it.isSelected || it.isActivated } == true)) {
                    if (selected != null) { ambiguous = true; break }
                    selected = tab
                }
            }
            if (ambiguous) selected = null
            if (selected != null) selectedLabel = selected.label
            // A temporary recycle retains the last verified native selection.
            return selected?.view ?: tabs.firstOrNull { it.label == selectedLabel }?.view
                ?: tabs.firstOrNull { labelKey(it.label) == "all" }?.view
        }

        private fun selectNativeTab(tab: Tab) {
            if (!channelBindingReady) return
            clickNative(tab.view)
            lastScan = 0L
            discoverTabs()
            refreshSelection()
        }

        private fun refreshSelection() {
            if (notificationsActive) return
            val dark = isDark()
            val selected = selectedTab()
            val selectedKey = tabs.firstOrNull { it.view === selected }?.let { labelKey(it.label) }
            val signature = "$tabsRevision:$selectedKey:$dark"
            if (signature == renderedSelection) return
            renderedSelection = signature
            val byKey = tabs.associateBy { labelKey(it.label) }
            visibleTabs = YouTubeChannelPolicy.visible(tabs.map { labelKey(it.label) }, selectedKey).mapNotNull(byKey::get)
            channels?.bind(visibleTabs.map { tab ->
                YouTubeChannelsView.Entry(labelKey(tab.label), localizedLabel(tab.label), tab.view === selected) { selectNativeTab(tab) }
            }, dark)
        }
        private fun placeControls() {
            val top = toolbar ?: return
            val currentControls = controls ?: return
            content.getLocationOnScreen(contentPos)
            top.getLocationOnScreen(viewPos)
            val safeTop = statusInset()
            val width = content.width
            val rowWidth = (width - dp(32)).coerceAtLeast(dp(220))
            val left = (width - rowWidth) / 2
            // The native appbar scrolls with the feed. The redesigned chrome
            // is pinned below the system status/cutout safe area instead.
            val y = safeTop
            val topParams = FrameLayout.LayoutParams(rowWidth, dp(48), Gravity.TOP or Gravity.START).apply {
                leftMargin = left; topMargin = y
            }
            val old = currentControls.layoutParams as? FrameLayout.LayoutParams
            if (old == null || old.width != rowWidth || old.height != dp(48) || old.leftMargin != left ||
                old.topMargin != y || old.gravity != (Gravity.TOP or Gravity.START)
            ) currentControls.layoutParams = topParams
            refreshSelection()
            val badge = notificationBadge?.text?.toString().orEmpty()
            (currentControls.getChildAt(4) as? CircleAction)?.badge =
                badge.takeIf { it.isNotBlank() && it != "0" }?.take(2).orEmpty()
        }

        private fun ensureMaterials() {
            if (materials.size == 4 && materials.all { it.parent === overlay }) return
            materials.forEach {
                YouTubeBackdropRegistry.remove(content.rootView, it)
                (it.parent as? ViewGroup)?.removeView(it)
            }
            materials.clear()
            repeat(4) { index ->
                val state = TopMaterialState(this, index)
                val panel = GlassHostLayout(activity, config, if (index == 1) 4 else 1,
                    nonSelectableIndices = if (index == 1) setOf(0) else emptySet(),
                    nativeSelectionReliable = index == 1, navigationSource = null,
                    sliderEnabled = index == 1, adapterOverride = TopMaterialAdapter(state, index)).apply {
                    isClickable = false; isFocusable = false; elevation = 0f
                    contentDescription = "YouTube 顶栏液态玻璃"
                }
                materials += panel
                overlay.addView(panel, 0, FrameLayout.LayoutParams(1, 1))
                YouTubeBackdropRegistry.add(content.rootView, panel)
            }
            // Attachment may raise a host once; foreground ink stays above
            // every material in both child order and Android's Z ordering.
            controls?.bringToFront()
        }

        private fun toggleMenu() {
            if (menu != null) { closeMenu(); return }
            val extras = tabs.filterNot { isPinned(it.label) }
            if (extras.isEmpty()) return
            val row = controls ?: return
            val capsule = row.tag as? View ?: return
            val root = FrameLayout(activity).apply {
                isClickable = true; isFocusableInTouchMode = true
                setOnClickListener { closeMenu() }
                setOnKeyListener { _, key, event ->
                    if (key == android.view.KeyEvent.KEYCODE_BACK) {
                        if (event.action == android.view.KeyEvent.ACTION_UP) closeMenu()
                        true
                    } else false
                }
            }
            val card = FrameLayout(activity).apply { isClickable = true }
            val material = GlassHostLayout(activity, config, 1, nativeSelectionReliable = false,
                navigationSource = null, sliderEnabled = false,
                adapterOverride = TopMaterialAdapter(TopMaterialState(this, 4), 4)).apply {
                isClickable = false; isFocusable = false; elevation = 0f
            }
            card.addView(material, FrameLayout.LayoutParams(-1, -1))
            val list = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
            }
            extras.forEach { tab ->
                val entry = TextView(activity).apply {
                    text = localizedLabel(tab.label); textSize = 16f; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(22), 0, dp(22), 0)
                    setTextColor(if (selectedTab() === tab.view) 0xFFFF0033.toInt()
                        else if (isDark()) Color.WHITE else 0xFF202020.toInt())
                    contentDescription = localizedLabel(tab.label)
                    setOnClickListener {
                        if (!menuClosing) {
                            selectNativeTab(tab)
                            closeMenu(); refreshSelection()
                        }
                    }
                }
                list.addView(entry, LinearLayout.LayoutParams(-1, dp(48)))
            }
            val scroll = ScrollView(activity).apply { isFillViewport = true; translationZ = 1f; addView(list) }
            card.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            // Material attachment may raise it once. Foreground owns final ink.
            scroll.bringToFront()
            val rowParams = row.layoutParams as FrameLayout.LayoutParams
            val menuTop = rowParams.topMargin + dp(44) + dp(10)
            val menuLeft = rowParams.leftMargin + dp(48)
            val available = (content.height - menuTop - bottomSafeInset() - dp(96)).coerceAtLeast(dp(48))
            val height = minOf(dp(extras.size * 48 + 16), available)
            root.addView(card, FrameLayout.LayoutParams(dp(188), height, Gravity.TOP or Gravity.START).apply {
                leftMargin = menuLeft; topMargin = menuTop
            })
            card.pivotX = dp(4) + (capsule.width - dp(8)) / 8f
            card.pivotY = rowParams.topMargin + dp(24) - menuTop.toFloat()
            card.scaleX = .08f; card.scaleY = .08f; card.alpha = 0f
            menu = root; menuCard = card; menuMaterial = material
            menuClosing = false; menuAnimating = true
            overlay.addView(root, FrameLayout.LayoutParams(-1, -1)); root.requestFocus()
            YouTubeBackdropRegistry.add(content.rootView, material)
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240L)
                .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f))
                .setUpdateListener { material.invalidate() }
                .withEndAction {
                    if (menu === root && !menuClosing) {
                        menuAnimating = false
                        card.animate().setUpdateListener(null); material.invalidate()
                    }
                }.start()
        }
        private fun placeGradient() {
            val dark = isDark()
            if (gradientNight != dark) {
                val rgb = if (dark) 0x0018181B else 0x00FFFFFF
                val colors = IntArray(17) { index ->
                    val t = index / 16f
                    val alpha = (242f * (1f - t*t*(3f-2f*t))).toInt().coerceIn(0, 242)
                    (alpha shl 24) or rgb
                }
                topGradient.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
                gradientNight = dark
            }
            // The fade should finish just below the pinned row. Extending it
            // far into the feed creates a visible tinted slab while scrolling.
            // Keep the status-area fade out of the scrolling feed; the glass
            // pills have their own blur and surface directly below the inset.
            val height = statusInset()
            val params = topGradient.layoutParams
            if (params.width != content.width || params.height != height) {
                params.width = content.width; params.height = height
                topGradient.layoutParams = params
            }
        }
        private fun statusInset(): Int = content.rootWindowInsets?.getInsets(
            android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout(),
        )?.top ?: 0

        private fun bottomSafeInset(): Int = content.rootWindowInsets?.getInsets(
            android.view.WindowInsets.Type.navigationBars(),
        )?.bottom ?: 0

        private fun closeMenu() {
            val root = menu ?: return
            val card = menuCard
            fun remove() {
                if (menu !== root) return
                card?.animate()?.setUpdateListener(null)?.withEndAction(null)?.cancel()
                overlay.removeView(root)
                menuMaterial?.let { YouTubeBackdropRegistry.remove(content.rootView, it) }
                menu = null; menuCard = null; menuMaterial = null
                menuClosing = false; menuAnimating = false
            }
            if (!active || card == null || !root.isAttachedToWindow) { remove(); return }
            if (menuClosing) return
            menuClosing = true; menuAnimating = true
            card.animate().cancel()
            card.animate().scaleX(.08f).scaleY(.08f).alpha(0f).setDuration(180L)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.3f))
                .setUpdateListener { menuMaterial?.invalidate() }
                .withEndAction { remove() }.start()
        }

        private fun clickNative(view: View) {
            if (!view.isAttachedToWindow || !view.isEnabled) return
            sourceState.forEach { (native, old) -> restoreOne(native, old) }
            try {
                val stack = java.util.ArrayDeque<View>()
                stack.addLast(view)
                while (stack.isNotEmpty()) {
                    val node = stack.removeFirst()
                    if (node !== view && node.hasOnClickListeners() && node.isEnabled && node.performClick()) return
                    if (node is ViewGroup) for (i in 0 until node.childCount) stack.addLast(node.getChildAt(i))
                }
                var node: View? = view
                while (node != null && node !== filterBar?.parent && node !== toolbar) {
                    if (node.hasOnClickListeners() && node.isEnabled && node.performClick()) return
                    node = node.parent as? View
                }
                if (view.isEnabled) view.performClick()
            } finally {
                if (active) suppressNative()
            }
        }

        private fun restoreOne(view: View, old: Original) {
            view.alpha = old.alpha
            if (view !== appbar) view.elevation = old.elevation
            view.visibility = old.visibility
            view.isClickable = old.clickable
            view.isLongClickable = old.longClickable; view.isFocusable = old.focusable
            view.importantForAccessibility = old.accessibility
        }

        private fun dispatchTopAction(role: YouTubeExploreContract.Role) {
            if (!active) return
            if (notificationsActive && role != YouTubeExploreContract.Role.SEARCH) return
            // Native toolbar children may appear/recycle after the first bind.
            // Refresh the semantic roots on activation, not a cached menu index.
            bind()
            topActions.perform(role, listOfNotNull(appbar, toolbar, filterBar))
        }
        private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = radius; setColor(color)
        }

        private fun dp(value: Int) = (value * density).roundToInt()

        private fun placeMaterials() {
            val row = controls ?: return
            val capsule = row.tag as? View ?: return
            val rowLp = row.layoutParams as? FrameLayout.LayoutParams ?: return
            val rowX = rowLp.leftMargin
            val rowY = rowLp.topMargin
            val drawer = row.getChildAt(0)
            val top = rowY + dp(4)
            val circleSize = dp(40)
            materials.forEachIndexed { i, panel ->
                val left = when (i) {
                    0 -> rowX
                    1 -> rowX + drawer.width + dp(8)
                    2 -> rowX + row.width - dp(88)
                    else -> rowX + row.width - circleSize
                }
                val width = if (i == 1) capsule.width else circleSize
                val lp = panel.layoutParams as? FrameLayout.LayoutParams
                    ?: FrameLayout.LayoutParams(width, circleSize)
                if (lp.width != width || lp.height != circleSize || lp.leftMargin != left || lp.topMargin != top) {
                    lp.width = width; lp.height = circleSize; lp.leftMargin = left; lp.topMargin = top
                    lp.gravity = Gravity.TOP or Gravity.START
                    panel.layoutParams = lp
                }
            }
        }

        private fun updateNotificationPresentation() {
            val row = controls ?: return
            (row.getChildAt(4) as? CircleAction)?.isSelected = notificationsActive
            if (notificationPresentation == notificationsActive) return
            val initial = notificationPresentation == null
            notificationPresentation = notificationsActive
            // Reverse from the current pose. Right actions stay anchored while
            // the channel and drawer controls leave together, with their glass.
            val targets = listOfNotNull(row.getChildAt(0), channels) + materials.take(2)
            targets.forEach { view ->
                view.animate().cancel()
                view.isEnabled = !notificationsActive
                if (!notificationsActive) view.visibility = View.VISIBLE
                val opacity = if (notificationsActive) 0f else 1f
                val y = if (notificationsActive) -dp(54).toFloat() else 0f
                if (initial) {
                    view.alpha = opacity; view.translationY = y
                    view.visibility = if (notificationsActive) View.INVISIBLE else View.VISIBLE
                } else view.animate().alpha(opacity).translationY(y).setDuration(240L)
                    .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f))
                    .withEndAction { if (notificationsActive) view.visibility = View.INVISIBLE }.start()
            }
        }

        fun contentView(): FrameLayout = content
        fun dark(): Boolean = isDark()
        fun selectedPanelIndex(): Int {
            val selected = selectedTab()
            return visibleTabs.indexOfFirst { it.view === selected }.let { if (it < 0) 1 else it + 1 }
        }
        fun channelGeometry(): Pair<Float, Float>? = channels?.selectionGeometry()
        fun isActive(): Boolean = (active || motion.rendering) && !stopped &&
            !activity.isInPictureInPictureMode
        fun hasFocus(): Boolean = activity.hasWindowFocus()

        fun safeBackdropViews(): List<View> {
            frameSceneViews?.let { return it }
            val safe = frameSceneScratch.apply { clear() }
            val decodedVideo = materials.any { YouTubeBackdropRegistry.frame(content.rootView, it) != null } ||
                menuMaterial?.let { YouTubeBackdropRegistry.frame(content.rootView, it) != null } == true
            fun collect(view: View): Boolean {
                // Never include owned glass, even hidden. Invisible native
                // children are safe and must not drop Litho-mounted Drawables.
                if (view is AdapterOwnedOverlay || view is GlassHostLayout) return true
                // The page backing is below the decoder in native composition.
                // Replaying it over the copied video would paint the video white.
                // Keep its normal native scene on pages without decoded input.
                if (decodedVideo && view === nativePageBackground) return true
                if (view.visibility != View.VISIBLE || view.alpha <= .001f) return false
                if (view is android.view.SurfaceView || view is android.view.TextureView) return true
                val start = safe.size
                var split = splitSceneBranches.containsKey(view)
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    split = collect(view.getChildAt(i)) || split
                }
                if (!split) {
                    while (safe.size > start) safe.removeAt(safe.lastIndex)
                    if (view.width > 0 && view.height > 0) safe += view
                }
                if (split && view is ViewGroup) splitSceneBranches[view] = true
                return split
            }
            for (i in 0 until content.childCount) collect(content.getChildAt(i))
            if (safe.size != sceneIdentity.size || safe.indices.any { safe[it] !== sceneIdentity[it] }) {
                sceneIdentity = safe.toList()
                backdropGeneration++
            }
            return safe.also { frameSceneViews = it }
        }

        fun stop() {
            if (stopped) return
            handoff.save(activity.taskId, SystemClock.uptimeMillis(), activity.isChangingConfigurations, ownsHomeLayout)
            stopped = true; active = false

            frameSceneViews = null
            if (sessions[activity]?.get() === this) sessions.remove(activity)
            overlay.onForegroundChanged = null
            overlay.acceptsTouch = false
            motion.dispose()
            content.removeOnAttachStateChangeListener(attachListener)
            observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observedTree = null
            // The old window is being discarded, not relinquished to native.
            if (!activity.isChangingConfigurations) restoreNative()
            // Detachment is dispatched while Android is traversing children.
            // Remove the owned subtree after that traversal completes.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                closeMenu()
                materials.forEach { YouTubeBackdropRegistry.remove(content.rootView, it) }
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                materials.clear(); controls = null
            }
        }
    }

    private class TopMaterialState(private val session: Session, private val role: Int) : AppNavigationState {
        private val selectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FF0033.toInt() }
        private var materialHost: GlassHostLayout? = null
        private var recordedVideo: Bitmap? = null
        private var recordedGeneration = -1
        private var recordedEpoch = Int.MIN_VALUE
        private var recordedLive: Boolean? = null
        private var recordedBackdrop: Bitmap? = null
        private var recordedWidth = 0
        private var recordedHeight = 0
        private var recordedX = Int.MIN_VALUE
        private var recordedY = Int.MIN_VALUE
        private val recordPosition = IntArray(2)
        override val allowsSoftwareBackdropCapture = false
        override val backdropSceneViewsAreDescendants = true
        override fun resolveBackdropScene(host: View): View = session.contentView()
        override fun onHostFrame(host: View) { materialHost = host as? GlassHostLayout }
        override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) {
            if (session.isActive() && session.hasFocus() && host is GlassHostLayout)
                YouTubeBackdropRegistry.request(host)
        }
        override fun videoBackdrop() = YouTubeBackdropRegistry.frame(session.contentView().rootView, materialHost)
        override fun reuseOuterBackdropForSurface(surfaceActive: Boolean) = true
        override fun outerBackdropNeedsRecord(backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean): Boolean {
            session.safeBackdropViews()
            val video = videoBackdrop()
            val host = materialHost
            host?.getLocationInWindow(recordPosition)
            return !hasDisplayList || recordedGeneration != session.backdropGeneration ||
                recordedVideo !== video?.bitmap || recordedEpoch != (video?.epoch ?: -1) ||
                recordedLive != host?.liveBackdropActiveForAdapter || recordedBackdrop !== backdrop ||
                recordedWidth != width || recordedHeight != height ||
                (video != null && (recordedX != recordPosition[0] || recordedY != recordPosition[1]))
        }
        override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
            val video = videoBackdrop()
            recordedGeneration = session.backdropGeneration
            recordedVideo = video?.bitmap; recordedEpoch = video?.epoch ?: -1
            recordedLive = materialHost?.liveBackdropActiveForAdapter
            recordedBackdrop = backdrop; recordedWidth = width; recordedHeight = height
            materialHost?.getLocationInWindow(recordPosition)
            recordedX = recordPosition[0]; recordedY = recordPosition[1]
        }
        override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> {
            // The shared renderer passes the scene root's PARENT here. Only
            // explicit children of our scene root are safe: returning the root
            // would replay this overlay into its own RenderNode input.
            return session.safeBackdropViews()
        }
        override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = session.isActive()
        override fun forceDarkMode(selectedIndex: Int) = session.dark()
        override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = if (role == 1) session.selectedPanelIndex() else 0
        override fun indicatorHorizontalGeometry(center: Float, width: Float): Pair<Float, Float>? =
            if (role == 1) session.channelGeometry() else null
        override fun drawsNavigationContent() = role == 1
        override fun drawForeground(canvas: Canvas,
            frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame,
            selectionPath: Path, drawSource: () -> Unit): Boolean {
            if (role != 1) return false
            // Use the common slider's live contour, including its movement.
            // One fixed translucent YouTube red, independent of light/dark
            // neutral thumb colors. The common capsule/lens remains intact.
            canvas.drawPath(selectionPath, selectedFill)
            drawSource()
            return true
        }
        override fun allowsHostTouch() = false
        override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = session.isActive()
        override fun pauseBackdropWork(host: View) = !session.isActive() || !session.hasFocus() || host.windowVisibility != View.VISIBLE
        override fun suppressRedrawnSource() = false
        override fun forceLiveBackdropFrame(selectedIndex: Int) = role == 4 && session.menuAnimating
        override fun forceNavigationBarImmersed(host: GlassHostLayout) = !YouTubeSearchPage.active(host.rootView)
    }

    private class TopMaterialAdapter(private val state: TopMaterialState, private val role: Int) : TargetAdapter {
        private val material = TopBarMaterialPolicy(
            role = when (role) { 1 -> TopBarRole.CHANNELS; 3 -> TopBarRole.SEARCH; 4 -> TopBarRole.MENU; else -> TopBarRole.ACTION },
            cornerRadiusPercent = if (role == 4) 16f else 50f,
            iconOnly = true, selectedAccent = role == 1, accentColor = 0xFFFF0033.toInt(),
        )
        override val key = "youtube-top-material-$role"
        override val deferCompanionRemovalOnDetach = true
        override val ownsNavigationDrawing = true
        override val managesHostSiblingOrder = true
        override val skipNavigationSnapshot = true
        override val hasPerFrameScene = true
        override val allowRenderNodeRefresh = true
        override val skipBackdropStableDelay = true
        override val allowsSystemBackgroundBlur = false
        override val foregroundNavigationOverSelection = role == 1
        override fun createNavigationState(context: Context): AppNavigationState = state
        override fun normalizeConfig(config: GlassConfig) = material.apply(config)
        override val indicatorInteractionPolicy get() = material.indicatorInteractionPolicy
    }

    private enum class Glyph { MENU, BELL, SEARCH }

    private class MoreAction(context: Context, ink: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        fun updateInk(ink: Int) { paint.color = ink; invalidate() }
        init { isClickable = true; isFocusable = true }
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            for (i in -1..1) canvas.drawCircle(width / 2f + i * 4.5f * d, height / 2f, 1.3f * d, paint)
        }
    }

    private class CircleAction(
        context: Context,
        @Suppress("UNUSED_PARAMETER") fill: Int,
        private var ink: Int,
        private val glyph: Glyph,
        label: String,
        private val action: () -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bellPath = Path()
        private val bellArc = RectF()
        private var selectedAmount = 0f
        private var selectionAnimator: android.animation.ValueAnimator? = null
        fun updateInk(value: Int) { ink = value; invalidate() }
        override fun setSelected(selected: Boolean) {
            if (isSelected == selected) return
            super.setSelected(selected)
            selectionAnimator?.cancel()
            selectionAnimator = android.animation.ValueAnimator.ofFloat(selectedAmount, if (selected) 1f else 0f).apply {
                duration = 240L
                interpolator = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
                addUpdateListener { selectedAmount = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        override fun onDetachedFromWindow() {
            selectionAnimator?.cancel(); selectionAnimator = null
            selectedAmount = if (isSelected) 1f else 0f
            super.onDetachedFromWindow()
        }
        var badge: String = ""
            set(value) { if (field != value) { field = value; invalidate() } }
        init {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            contentDescription = label; isClickable = true; isFocusable = true
            setOnClickListener { action() }
        }
        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val c = width / 2f; val y = height / 2f; val s = 9f * density
            if (selectedAmount > 0f) {
                paint.style = Paint.Style.FILL; paint.color = Color.argb((153 * selectedAmount).roundToInt(), 255, 0, 51)
                canvas.drawCircle(c, y, minOf(width, height) / 2f, paint)
            }
            paint.color = android.graphics.Color.argb(255,
                (Color.red(ink) + (255 - Color.red(ink)) * selectedAmount).roundToInt(),
                (Color.green(ink) + (255 - Color.green(ink)) * selectedAmount).roundToInt(),
                (Color.blue(ink) + (255 - Color.blue(ink)) * selectedAmount).roundToInt())
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            when (glyph) {
                Glyph.MENU -> for (dy in -1..1) canvas.drawLine(c-s, y+dy*5*density, c+s, y+dy*5*density, paint)
                Glyph.SEARCH -> {
                    canvas.drawCircle(c-1.5f*density, y-1.5f*density, 6.8f*density, paint)
                    canvas.drawLine(c+3.5f*density, y+3.5f*density, c+9*density, y+9*density, paint)
                }
                Glyph.BELL -> {
                    val path = bellPath.apply {
                        reset()
                        moveTo(c-8*density, y+5*density); lineTo(c-6*density, y+2*density)
                        lineTo(c-6*density, y-3*density); quadTo(c, y-12*density, c+6*density, y-3*density)
                        lineTo(c+6*density, y+2*density); lineTo(c+8*density, y+5*density); close()
                    }
                    bellArc.set(c-2*density,y+5*density,c+2*density,y+9*density)
                    canvas.drawPath(path, paint); canvas.drawArc(bellArc,0f,180f,false,paint)
                }
            }
            if (badge.isNotBlank()) {
                paint.style = Paint.Style.FILL; paint.color = 0xFFFF0033.toInt()
                canvas.drawCircle(width*.76f, height*.23f, 5.5f*density, paint)
                if (badge.length > 1) {
                    paint.color = Color.WHITE; paint.textSize = 7f*density; paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(badge, width*.76f, height*.23f+2.4f*density, paint)
                }
            }
        }
    }
}




