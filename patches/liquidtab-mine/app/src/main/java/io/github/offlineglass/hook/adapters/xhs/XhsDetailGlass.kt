package io.github.offlineglass.hook.adapters.xhs


import android.app.Activity
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.TextView
import android.widget.ImageView
import io.github.offlineglass.BuildConfig
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.BloomTiltTracker
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.hook.globalGlassBottomGapPx
import io.github.offlineglass.rendering.KYANT_LENS_SHADER
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** Detail-page-only skin. Native hierarchy, listeners and touch bounds never change. */
/**
 * Gate read by the navigation host's per-frame capture loop
 * (GlassHostLayout installCaptureLoop -> AppNavigationState.pauseBackdropWork).
 * While a note-detail page exists, the home glass backdrop sampling is paused.
 * The return transition starts the instant home regains focus; a live
 * PixelCopy/RenderNode sampling pass in that instant makes the window produce
 * its final frame immediately, so the native detail->feed shrink animation is
 * skipped (bisect-verified: with the whole module off the animation plays).
 * Image notes open NoteDetailActivity; VIDEO notes open DetailFeedActivity
 * (the page XhsVideoViewport/XhsVideoButtons skin), which had the same
 * lost-transition symptom until it joined this cover. The cover is armed on
 * create/resume and retired only by a real finish (isFinishing), with a settle
 * delay: backgrounding the app with the detail on top keeps it armed, and a
 * quick back may stop the activity before the return transition ends.
 * WeakHashMap: a destroyed activity drops out by itself.
 */
internal object XhsDetailCover {
    /**
     * Settle window between the detail leaving and sampling resuming. onStop
     * normally fires after the return transition, but a detail dismissed right
     * after it opened can leave earlier (or be destroyed in the same pass), and
     * resuming sampling at that instant still eats the shrink animation.
     * Keeping the gate closed for a short window covers both orderings; the
     * frozen frame stays valid, so the extra pause is invisible.
     */
    private const val EXIT_SETTLE_MS = 500L
    private val active = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
    private val hosts = java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.view.View, Boolean>())
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingExits = java.util.WeakHashMap<Activity, Runnable>()
    // covertrace 274: the newest exit request timestamp, for the smoke test
    // below and the lifecycle trace in XhsLifecycleTrace.
    @Volatile private var lastExitRequestAt = 0L
    private var lastSmokeWarnAt = 0L
    val paused: Boolean get() = synchronized(active) { active.isNotEmpty() }
    fun describe(): String = synchronized(active) {
        active.joinToString { it.javaClass.simpleName }
    }
    fun enter(activity: Activity) {
        cancelPendingExit(activity)
        val added = synchronized(active) { active.add(activity) }
        if (added) XhsLifecycleTrace.event(activity,
            "enter ${activity.javaClass.simpleName} active=[${describe()}] t=${android.os.SystemClock.uptimeMillis()}")
    }
    /** Deferred exit: the resume must never race the host's return transition. */
    fun scheduleExit(activity: Activity) {
        lastExitRequestAt = android.os.SystemClock.uptimeMillis()
        if (BuildConfig.XHS_RETURN_TRACE) XhsLifecycleTrace.record(activity,
            "exit-scheduled ${activity.javaClass.simpleName} finishing=${activity.isFinishing} waitMs=$EXIT_SETTLE_MS")
        val task = Runnable {
            synchronized(pendingExits) { pendingExits.remove(activity) }
            exit(activity)
        }
        val previous = synchronized(pendingExits) { pendingExits.put(activity, task) }
        if (previous != null) handler.removeCallbacks(previous)
        handler.postDelayed(task, EXIT_SETTLE_MS)
    }
    /**
     * covertrace 274 smoke test: a covered page has finished (its exit is
     * still settling) yet the host gate reads OPEN — sampling would run in
     * the return window and eat the animation. Rate-limited; this is the
     * single condition the whole cover exists to prevent.
     */
    fun warnIfSamplingDuringReturnWindow(host: View) {
        val last = lastExitRequestAt
        if (last == 0L) return
        val now = android.os.SystemClock.uptimeMillis()
        val age = now - last
        if (age >= EXIT_SETTLE_MS || age < 0L) return
        if (now - lastSmokeWarnAt < 500L) return
        lastSmokeWarnAt = now
        val warning = "SMOKE gate open while exit settling age=${age}ms active=[${describe()}]"
        android.util.Log.w("LiquidTabCover", warning)
        if (BuildConfig.XHS_RETURN_TRACE) XhsLifecycleTrace.record(host.context, warning)
    }
    private fun cancelPendingExit(activity: Activity) {
        val task = synchronized(pendingExits) { pendingExits.remove(activity) } ?: return
        handler.removeCallbacks(task)
    }
    private fun exit(activity: Activity) {
        val cleared = synchronized(active) {
            active.remove(activity)
            active.isEmpty()
        }
        if (!cleared) return
        // Sampling resumes on the next preDraw; force one full redraw right
        // after so the bar labels (drawn against the previously frozen scene)
        // return without waiting for a tab switch.
        val pending = synchronized(hosts) { hosts.toList().also { hosts.clear() } }
        XhsLifecycleTrace.event(activity,
            "exit ${activity.javaClass.simpleName} hosts=${pending.size} t=${android.os.SystemClock.uptimeMillis()}")
        pending.forEach { host ->
            if (!host.isAttachedToWindow) return@forEach
            host.postInvalidateOnAnimation()
            host.postDelayed({
                if (host.isAttachedToWindow) {
                    host.invalidate()
                    (host.parent as? android.view.View)?.invalidate()
                }
            }, 80L)
        }
    }
    fun pauseBackdropWork(host: View, surface: String): Boolean {
        val blocked = paused
        if (blocked) synchronized(hosts) { hosts.add(host) }
        if (BuildConfig.XHS_RETURN_TRACE) XhsLifecycleTrace.backdropDecision(host, surface, blocked)
        return blocked
    }
}

internal object XhsDetailGlass {
    /** Every page that must pause home sampling; the toolbar skin below only
     * applies to the image-note page. */
    val COVERED_PAGES = setOf(
        "com.xingin.matrix.notedetail.NoteDetailActivity",
        "com.xingin.matrix.detail.activity.DetailFeedActivity",
    )
    private const val NOTE_DETAIL = "com.xingin.matrix.notedetail.NoteDetailActivity"
    private val sessions = WeakHashMap<Activity, Session>()
    private val paddingOwners = WeakHashMap<View, WeakReference<Session>>()
    private val artworkOwners = WeakHashMap<View, WeakReference<VerticalButtonSkin>>()
    private val observerOwners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Session, Boolean>())
    private val observers = ArrayList<XC_MethodHook.Unhook>()
    private val configWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "LiquidTab-XhsConfig").apply { isDaemon = true }
    }
    private fun observe(session: Session) {
        if (!observerOwners.add(session) || observers.isNotEmpty()) return
        observers += XposedBridge.hookAllMethods(Activity::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = sessions[param.thisObject as? Activity] ?: return
                (param.args.firstOrNull() as? android.view.MotionEvent)?.let(owner::observeTouch)
            }
        })
        observers += XposedBridge.hookAllMethods(View::class.java, "invalidateDrawable", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                artworkOwners[param.thisObject as? View]?.get()?.invalidateSelf()
            }
        })
        observers += XposedBridge.hookAllMethods(View::class.java, "setPadding", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = paddingOwners[param.thisObject as? View]?.get() ?: return
                if (owner.extending && param.args.size == 4) param.args[3] = 0
            }
        })
    }
    private fun unobserve(session: Session) {
        observerOwners.remove(session)
        if (observerOwners.isEmpty()) { observers.forEach { it.unhook() }; observers.clear() }
    }
    fun installHooks() {
        XhsLifecycleTrace.install()
        // Register before the detail activity's first traversal, not after its
        // initial resume/enter transition has already presented native controls.
        XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val name = activity.javaClass.name
                if (name in COVERED_PAGES) XhsDetailCover.enter(activity)
                if (name == NOTE_DETAIL) sessions.getOrPut(activity) { Session(activity) }.start()
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val name = activity.javaClass.name
                // Re-arm after a background round-trip: the detail is still on
                // top, so the next back press needs the same paused gate.
                if (name in COVERED_PAGES) XhsDetailCover.enter(activity)
                if (name == NOTE_DETAIL) sessions.getOrPut(activity) { Session(activity) }.start()
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name in COVERED_PAGES) XhsDetailCover.scheduleExit(activity)
                sessions.remove(activity)?.dispose()
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onStop", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name in COVERED_PAGES) {
                    // Backgrounding must keep the gate armed: the detail is
                    // still the top page, only a real finish retires the
                    // cover. A quick back can stop before the return
                    // transition ends, hence the deferred exit.
                    if (activity.isFinishing) XhsDetailCover.scheduleExit(activity)
                }
                sessions[activity]?.pause()
            }
        })
    }

    private class Session(activity: Activity) {
        private val root = activity.window.decorView as ViewGroup
        // 275: any layout/padding/background write from the detail skin that
        // lands inside the return-transition window makes the animating-out
        // window produce its final frame instantly, killing the shrink
        // animation (verified via 274 covertrace: gate was closed at every
        // return focus yet the animation still died after a comment round
        // trip, which rebinds this toolbar). isFinishing flips true before
        // the transition starts, so it bounds the whole window.
        private val activityRef = WeakReference(activity)
        private val isFinishing: Boolean get() = activityRef.get()?.isFinishing == true
        private var lastDskinLog = 0L
        private fun dskin(event: String) {
            val now = SystemClock.uptimeMillis()
            if (now - lastDskinLog < 500L) return
            lastDskinLog = now
            XhsLifecycleTrace.event(root.context, "dskin $event finishing=$isFinishing t=$now")
        }
        private val original = WeakHashMap<View, Drawable?>()
        private val originalClipping = WeakHashMap<ViewGroup, Triple<Boolean, Boolean, Boolean>>()
        private var row: ViewGroup? = null
        private var comment: TextView? = null
        private var skin: DetailBackground? = null
        private var lastCheck = 0L
        private var listening = false
        private var viewport: ViewGroup? = null
        private var viewportBottom = 0
        private var viewportPadding = 0
        private var viewportClip = true
        private var viewportParent: ViewGroup? = null
        private var parentPadding = 0
        private var parentClip = true
        var extending = false
            private set
        private var previousExtending: Boolean? = null
        @Volatile private var latestConfig = GlassConfig()
        private val configReading = java.util.concurrent.atomic.AtomicBoolean(false)
        private val buttonSkins = ArrayList<VerticalButtonSkin>()
        private var compactActions: List<View> = emptyList()
        private val originalBounds = WeakHashMap<View, Rect>()
        private var originalRowTranslation = 0f
        private var commentColors: android.content.res.ColorStateList? = null
        private var commentAlpha = 1f
        private var commentScaleX = 1f
        private var commentScaleY = 1f
        private var pressFeedback: XhsPressFeedback? = null
        private var observingTouch = false
        private val touchOrigin = IntArray(2)
        private val touchRoot = IntArray(2)
        fun observeTouch(event: android.view.MotionEvent) {
            val target = row?.takeIf { it.isShown } ?: run {
                if (observingTouch) pressFeedback?.reset()
                observingTouch = false
                return
            }
            val feedback = pressFeedback ?: return
            target.getLocationInWindow(touchOrigin); root.getLocationInWindow(touchRoot)
            val x = event.x + touchRoot[0] - touchOrigin[0]
            val y = event.y + touchRoot[1] - touchOrigin[1]
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN)
                observingTouch = x >= 0 && y >= 0 && x < target.width && y < target.height
            if (!observingTouch) return
            val local = android.view.MotionEvent.obtain(event)
            try {
                local.offsetLocation((touchRoot[0] - touchOrigin[0]).toFloat(), (touchRoot[1] - touchOrigin[1]).toFloat())
                feedback.observe(local)
            } finally { local.recycle() }
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL)
                observingTouch = false
        }
        private val listener = ViewTreeObserver.OnPreDrawListener {
            // Freeze every skin write while this activity is finishing: the
            // return transition runs between finish() and a late onStop, and
            // a single layout/padding/background write in that window makes
            // the window snap to its final frame.
            if (isFinishing) return@OnPreDrawListener true
            val previousRow = row
            val now = SystemClock.uptimeMillis()
            // Initial post-resume may run before the toolbar has dimensions.
            // Retry at the first drawable frame instead of waiting 600ms.
            if (row?.isShown != true || now - lastCheck >= 600L) {
                lastCheck = now
                update()
            }
            updateViewport()
            row?.let { allowShadowOverflow(it) }
            compactButtons()
            buttonSkins.forEach { it.refresh() }
            comment?.let { input ->
                val foreground = if (skin?.isDarkPage() == true) Color.WHITE else Color.BLACK
                if (input.currentTextColor != foreground) {
                    input.setTextColor(foreground)
                    input.compoundDrawables.filterNotNull().forEach { it.setTint(foreground) }
                }
                if (input.alpha != 1f) input.alpha = 1f
            }
            // The initial viewport extension requests a measure/layout pass.
            // Do not present its old native geometry in that transitional frame.
            if (row != null && row !== previousRow) {
                root.postInvalidateOnAnimation()
                false
            } else true
        }
        fun start() {
            observe(this)
            if (!listening) {
                root.viewTreeObserver.addOnPreDrawListener(listener)
                listening = true
            }
            root.post { update() }
        }
        private fun clear(view: View) {
            if (!original.containsKey(view)) original[view] = view.background
            if (view.background != null) view.background = null
        }
        private fun update() {
            // Provider IPC can stall; never wait for it inside a drawing traversal.
            if (configReading.compareAndSet(false, true)) {
                val context = root.context.applicationContext
                configWorker.execute {
                    try {
                        latestConfig = HookConfigReader.read(context, "com.xingin.xhs").normalized()
                    } finally { configReading.set(false) }
                }
            }
            val config = latestConfig
            if (!config.enabled || !config.xhsDetailGlass) { restore(); return }
            val label = comment?.takeIf { it.isAttachedToWindow && it.isShown }
                ?: findComment(root) ?: run { restore(); return }
            val candidate = label.parent as? ViewGroup ?: return
            // Fail closed if another app version changes the toolbar structure.
            val actions = (0 until candidate.childCount).map { candidate.getChildAt(it) }
                .filter { it !== label && it.isShown && it.isClickable }
            if (actions.size != 3 || candidate.height <= 0 || candidate.width <= 0) return
            if (row !== candidate) {
                restore()
                row = candidate
                originalRowTranslation = candidate.translationY
                comment = label
                commentColors = label.textColors
                commentAlpha = label.alpha
                commentScaleX = label.scaleX
                commentScaleY = label.scaleY
                label.setTextColor(Color.WHITE)
                label.alpha = 1f
                label.compoundDrawables.filterNotNull().forEach { it.mutate().setTint(Color.WHITE) }
                compactActions = actions
                (actions + label).forEach { originalBounds[it] = Rect(it.left, it.top, it.right, it.bottom) }
                skin = DetailBackground(candidate, label, actions)
                original[candidate] = candidate.background
                clear(label)
                // Only the compact native plate, never the page or keyboard.
                (candidate.parent as? ViewGroup)?.takeIf {
                    it.height <= candidate.height * 2
                }?.let { clear(it) }
                candidate.background = skin
                allowShadowOverflow(candidate)
                actions.forEach { action ->
                    (action as? ViewGroup)?.let { VerticalButtonSkin.create(it) }?.let {
                        buttonSkins.add(it)
                    }
                }
                val feedback = XhsPressFeedback(candidate, listOf(label) + actions) {
                    val scale = pressFeedback?.scale(0) ?: 1f
                    label.scaleX = commentScaleX * scale
                    label.scaleY = commentScaleY * scale
                    skin?.invalidateSelf()
                    buttonSkins.forEach { it.invalidateSelf() }
                }
                pressFeedback = feedback
                skin?.feedback = feedback
                buttonSkins.forEachIndexed { index, button ->
                    button.feedback = feedback
                    button.feedbackIndex = index + 1
                }
                discoverViewport(candidate)
                // 275 probe: a rebind-driven re-skin is a burst of layout and
                // background writes; if one of these lines ever shows up with
                // finishing=true (or inside a return window) it is the frame
                // that ate the animation.
                dskin("reskin ${candidate.javaClass.simpleName}")
                android.util.Log.i("LiquidTabInject", "XHS detail: two native glass capsules attached")
                android.util.Log.i("LiquidTabDetail",
                    "row actions=${actions.map { it.javaClass.simpleName + ":h" + it.height }} " +
                        "skins=${buttonSkins.size} viewgroup=${actions.firstOrNull() is ViewGroup}")
            }
            skin?.update(config)
            // Native theme/rebind code can replace these plates after attachment.
            for (view in original.keys.toList()) {
                if (view !== candidate && view.background != null) view.background = null
            }
            if (candidate.background !== skin) candidate.background = skin
            allowShadowOverflow(candidate)
        }
        private fun allowShadowOverflow(toolbar: ViewGroup) {
            // The native 56dp toolbar only leaves 4dp around a 48dp capsule,
            // so its ancestors must not clip the 10dp ambient shadow.
            var container: ViewGroup? = toolbar
            while (container != null) {
                if (!originalClipping.containsKey(container)) {
                    originalClipping[container] = Triple(
                        container.clipChildren, container.clipToPadding, container.clipToOutline)
                }
                if (container.clipChildren) container.clipChildren = false
                if (container.clipToPadding) container.clipToPadding = false
                if (container.clipToOutline) container.clipToOutline = false
                if (container === root) break
                container = container.parent as? ViewGroup
            }
        }
        private fun discoverViewport(toolbar: ViewGroup) {
            val toolbarPosition = IntArray(2)
            val position = IntArray(2)
            toolbar.getLocationInWindow(toolbarPosition)
            fun visit(view: View) {
                if (view === toolbar || !view.isShown) return
                if (view is ViewGroup && view.javaClass.name ==
                    "com.xingin.comment.consumer.list.CommentListView") {
                    view.getLocationInWindow(position)
                    if (view.width >= toolbar.width * 0.9f && view.height > toolbar.height * 2 &&
                        (view.parent as? View)?.javaClass?.name ==
                        "com.xingin.matrix.base.widgets.NestedScrollLayout") {
                        viewport = view
                        viewportBottom = view.bottom
                        viewportPadding = view.paddingBottom
                        viewportClip = view.clipToPadding
                        viewportParent = view.parent as ViewGroup
                        parentPadding = viewportParent!!.paddingBottom
                        parentClip = viewportParent!!.clipToPadding
                        paddingOwners[viewportParent!!] = WeakReference(this@Session)
                    }
                    return
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(root)
        }
        private fun updateViewport() {
            val list = viewport ?: return
            val toolbar = row ?: return
            val parent = viewportParent ?: return
            val keyboard = root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            val extend = !keyboard && toolbar.isShown && list.isShown
            extending = extend
            if (previousExtending == extend) return
            previousExtending = extend
            val wantedParentPadding = if (extend) 0 else parentPadding
            if (parent.paddingBottom != wantedParentPadding)
                parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, wantedParentPadding)
            parent.clipToPadding = if (extend) false else parentClip
            val clearance = if (extend) parentPadding else 0
            val wantedPadding = viewportPadding + clearance
            if (list.paddingBottom != wantedPadding)
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, wantedPadding)
            list.clipToPadding = if (extend) false else viewportClip
        }
        private fun findComment(view: View): TextView? {
            if (!view.isShown) return null
            if (view is TextView && view.text.toString() in setOf("说点什么...", "说点什么…")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                findComment(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        private fun compactButtons() {
            val toolbar = row ?: return
            val input = comment ?: return
            if (compactActions.size != 3 || toolbar.width <= 0) return
            val dp = toolbar.resources.displayMetrics.density
            // Keep native 48dp touch targets, with screen-relative outer margins.
            val slot = (48f * dp).toInt()
            val side = globalGlassBottomGapPx(toolbar)
            val end = toolbar.width - side
            val nativeStart = originalBounds[compactActions.first()]?.left ?: return
            val start = maxOf(nativeStart, end - slot * 3)
            compactActions.forEachIndexed { index, action ->
                val left = start + (end - start) * index / 3
                val right = start + (end - start) * (index + 1) / 3
                if (action.left != left || action.right != right) {
                    action.layout(left, action.top, right, action.bottom)
                    action.invalidate()
                    toolbar.invalidate()
                }
                // Compress artwork, not touch targets. Keep the complete group
                // centered in the capsule (whose leading inset is 4dp).
                val center = (start + 4f * dp + end) / 2f
                val spacing = (end - start) / 3f * 0.85f
                buttonSkins.getOrNull(index)?.setVisualCenter(
                    center + (index - 1) * spacing - action.left)
            }
            val inputRight = start - (12f * dp).toInt()
            if (inputRight > side && (input.right != inputRight || input.left != side))
                input.layout(side, input.top, inputRight, input.bottom)
            if (!extending) {
                toolbar.translationY = originalRowTranslation
                return
            }
            val position = IntArray(2)
            val rootPosition = IntArray(2)
            toolbar.getLocationOnScreen(position)
            root.getLocationOnScreen(rootPosition)
            val glassBottom = (toolbar.height + minOf(48f * dp, toolbar.height.toFloat())) / 2f
            val gap = globalGlassBottomGapPx(toolbar) * if (latestConfig.classicNavigation) 2 else 1
            val target = rootPosition[1] + root.height - gap
            val translation = target - (position[1] - toolbar.translationY + glassBottom)
            if (kotlin.math.abs(toolbar.translationY - translation) > 0.5f)
                toolbar.translationY = translation
        }
        private fun restore() {
            // 275 probe: restore() is itself a burst of writes; it must never
            // run inside a return-transition window.
            if (row != null) dskin("restore")
            pressFeedback?.reset()
            pressFeedback = null
            for ((container, clipping) in originalClipping) {
                container.clipChildren = clipping.first
                container.clipToPadding = clipping.second
                container.clipToOutline = clipping.third
            }
            originalClipping.clear()
            row?.translationY = originalRowTranslation
            comment?.let { input ->
                commentColors?.let { input.setTextColor(it) }
                input.alpha = commentAlpha
                input.scaleX = commentScaleX
                input.scaleY = commentScaleY
                input.compoundDrawables.filterNotNull().forEach { it.clearColorFilter(); it.setTintList(null) }
            }
            commentColors = null
            for ((view, rect) in originalBounds) view.layout(rect.left, rect.top, rect.right, rect.bottom)
            originalBounds.clear()
            compactActions = emptyList()
            extending = false
            previousExtending = null
            viewportParent?.let { paddingOwners.remove(it) }
            buttonSkins.forEach { it.restore() }
            buttonSkins.clear()
            viewport?.let { list ->
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, viewportPadding)
                list.clipToPadding = viewportClip
            }
            viewportParent?.let { parent ->
                parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, parentPadding)
                parent.clipToPadding = parentClip
            }
            viewportParent = null
            viewport = null
            val active = skin
            for ((view, background) in original) {
                if (view.background == null || view.background === active) view.background = background
            }
            original.clear()
            skin?.release()
            skin = null
            row = null
            comment = null
        }
        fun pause() {
            observingTouch = false
            unobserve(this)
            // A finishing activity is mid return-transition; even the press
            // feedback's scale reset is a write into that window.
            if (!isFinishing) pressFeedback?.reset()
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)
            listening = false
            skin?.pause()
        }
        fun dispose() {
            observingTouch = false
            unobserve(this)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)
            listening = false
            // Destroy fires up to ~800ms after the transition starts; the
            // skin stays on the dying window instead of writing it back.
            if (!isFinishing) restore()
        }
    }

    /** Reproject native artwork without changing button or child layout/touch geometry. */
    private class VerticalButtonSkin(
        private val button: ViewGroup, private val icon: ImageView, private val count: TextView,
    ) : Drawable() {
        var feedback: XhsPressFeedback? = null
        var feedbackIndex = 0
        private val oldForeground = button.foreground
        private val iconAlpha = icon.alpha
        private val countAlpha = count.alpha
        private val lottie = icon.javaClass.name.contains("LottieAnimationView")
        private var glyphX = Float.NaN
        private var glyphY = Float.NaN
        private var glyphSize = 0f
        private var measuredComposition: Any? = null
        private var measuredDrawable: Drawable? = null
        private var measuredGeometry = ""
        private var measuredSelected = false
        private var hasMeasuredGlyph = false
        private var hasVisibleAnchor = false
        private var lastProbe = 0L
        private var lastDrawable: Drawable? = null
        private var lastSelected = false
        private var lastProgress = Float.NaN
        private var lastComposition: Any? = null
        private val progressGetter = if (lottie) runCatching {
            icon.javaClass.getMethod("getProgress")
        }.getOrNull() else null
        private val compositionGetter = if (lottie) runCatching {
            icon.javaClass.getMethod("getComposition")
        }.getOrNull() else null
        private val animatingGetter = if (lottie) runCatching {
            icon.javaClass.getMethod("isAnimating")
        }.getOrNull() else null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var lastText = ""
        private var lastColor = 0
        private var lastGeometry = ""
        private var visualCenter = Float.NaN
        fun setVisualCenter(value: Float) {
            if (visualCenter != value) { visualCenter = value; invalidateSelf() }
        }
        init {
            artworkOwners[icon] = WeakReference(this)
            icon.alpha = 0f
            count.alpha = 0f
            button.foreground = this
        }
        fun refresh() {
            val text = count.text.toString()
            // Lottie retains the same Drawable while composition/progress and
            // selected state change. Its subclass may also consume drawable
            // invalidations before View.invalidateDrawable is reached.
            val progress = runCatching { (progressGetter?.invoke(icon) as? Number)?.toFloat() }.getOrNull()
            val composition = runCatching { compositionGetter?.invoke(icon) }.getOrNull()
            val geometry = "${button.width}:${button.height}:${icon.left}:${icon.top}:${icon.width}:${icon.height}"
            if (icon.width > 0 && icon.height > 0) {
                val changed = measuredComposition !== composition || measuredDrawable !== icon.drawable ||
                    measuredGeometry != geometry || measuredSelected != icon.isSelected
                if (changed) hasMeasuredGlyph = false
                // Unified fallback (icon center + view size) for every icon
                // kind. Static ImageViews measure immediately; Lottie measures
                // only once its animation settles. Without measuring, a static
                // icon's transparent padding shrank its visible glyph relative
                // to the measured heart, which read as inconsistent sizes.
                if (!hasVisibleAnchor) {
                    glyphX = icon.width / 2f
                    glyphY = icon.height / 2f
                    glyphSize = minOf(icon.width, icon.height).toFloat()
                }
                val animating = lottie &&
                    (runCatching { animatingGetter?.invoke(icon) as? Boolean }.getOrNull() == true)
                val settled = !animating && (progress == null || progress <= 0.001f || progress >= 0.999f)
                val now = SystemClock.uptimeMillis()
                if (!hasMeasuredGlyph && settled && now - lastProbe >= 250L) {
                    lastProbe = now
                    if (measureVisibleGlyph()) {
                        measuredComposition = composition
                        measuredDrawable = icon.drawable
                        measuredGeometry = geometry
                        measuredSelected = icon.isSelected
                        hasMeasuredGlyph = true
                        hasVisibleAnchor = true
                        invalidateSelf()
                    }
                }
            }
            if (lastText != text || lastColor != count.currentTextColor || lastGeometry != geometry ||
                lastDrawable !== icon.drawable || lastSelected != icon.isSelected ||
                lastComposition !== composition || (progress != null && lastProgress != progress)) {
                lastDrawable = icon.drawable
                lastComposition = composition
                lastSelected = icon.isSelected
                if (progress != null) lastProgress = progress
                lastText = text; lastColor = count.currentTextColor; lastGeometry = geometry
                invalidateSelf()
            }
            if (icon.alpha != 0f) icon.alpha = 0f
            if (count.alpha != 0f) count.alpha = 0f
        }
        private fun measureVisibleGlyph(): Boolean {
            // Inspect only this native icon on a small transparent canvas.
            // No page capture; display still draws the live native animation.
            val scale = minOf(1f, 192f / maxOf(icon.width, icon.height))
            val w = maxOf(1, kotlin.math.ceil(icon.width * scale).toInt())
            val h = maxOf(1, kotlin.math.ceil(icon.height * scale).toInt())
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            return try {
                val probe = Canvas(bitmap)
                probe.scale(scale, scale)
                icon.draw(probe)
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                var left = w; var right = -1; var top = h; var bottom = -1
                var count = 0
                for (y in 0 until h) for (x in 0 until w) {
                    if (Color.alpha(pixels[y * w + x]) >= 64) {
                        left = minOf(left, x); right = maxOf(right, x)
                        top = minOf(top, y); bottom = maxOf(bottom, y)
                        count++
                    }
                }
                if (count < 4 || right <= left || bottom <= top) false else {
                    glyphX = (left + right + 1f) / (2f * scale)
                    glyphY = (top + bottom + 1f) / (2f * scale)
                    glyphSize = (maxOf(right - left + 1, bottom - top + 1) + 2f) / scale
                    true
                }
            } catch (_: Exception) { false }
            finally { bitmap.recycle() }
        }
        override fun draw(canvas: Canvas) {
            val dp = button.resources.displayMetrics.density
            val sp = button.resources.displayMetrics.scaledDensity
            // Uniform capsule height, NOT min(48dp, button.height): the three
            // native action buttons have different heights (like 162 vs 115)
            // but their centers are aligned. Taking the min made each button
            // compute a different `top`, so the icon/text target Y differed by
            // ~9px between buttons and the row looked off-baseline. With a
            // uniform height, top = buttonCenter - 24dp for every button, so
            // icons and counts land on one line across all of them.
            val glassHeight = 48f * dp
            val top = (button.height - glassHeight) / 2f
            val iconSize = 24f * dp
            val targetCenterX = visualCenter.takeIf { it.isFinite() } ?: (button.width / 2f)
            val targetTop = top + 5f * dp
            val feedbackSave = canvas.save()
            val pressScale = feedback?.actionScale() ?: 1f
            val pressPivotX = feedback?.actionPivotX()?.minus(button.left) ?: targetCenterX
            canvas.scale(pressScale, pressScale, pressPivotX, button.height / 2f)
            // Unlike the other icons, the heart occupies only a small part of
            // its animation canvas. Center its measured visible artwork, then
            // map it to the exact same targetCenterX used by the count below.
            // All icons (lottie or static) share the same measured visible
            // center/size so a 24dp glyph means the same VISIBLE size for all.
            val sourceSize = glyphSize
            if (sourceSize > 0f && icon.width > 0 && icon.height > 0) {
                val sourceX = glyphX
                val sourceY = glyphY
                val save = canvas.save()
                // The heart's burst/bounce intentionally exceeds its resting
                // 24dp glyph. Keep the anchor/scale but don't crop animation.
                if (!lottie) canvas.clipRect(targetCenterX - iconSize / 2f, targetTop,
                    targetCenterX + iconSize / 2f, targetTop + iconSize)
                canvas.translate(targetCenterX, targetTop + iconSize / 2f)
                canvas.scale(iconSize / sourceSize, iconSize / sourceSize)
                canvas.translate(-sourceX, -sourceY)
                // Icon and count share targetCenterX; preserve native drawing.
                icon.draw(canvas)
                canvas.restoreToCount(save)
            }
            paint.color = count.currentTextColor
            paint.textSize = 11f * sp
            paint.typeface = count.typeface
            val metrics = paint.fontMetrics
            val textCenterY = top + 38f * dp
            canvas.drawText(count.text.toString(), targetCenterX,
                textCenterY - (metrics.ascent + metrics.descent) / 2f, paint)
            canvas.restoreToCount(feedbackSave)
        }
        fun restore() {
            artworkOwners.remove(icon)
            if (button.foreground === this) button.foreground = oldForeground
            if (icon.alpha == 0f) icon.alpha = iconAlpha
            if (count.alpha == 0f) count.alpha = countAlpha
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        companion object {
            fun create(button: ViewGroup): VerticalButtonSkin? {
                val children = (0 until button.childCount).map { button.getChildAt(it) }
                val icon = children.filterIsInstance<ImageView>().firstOrNull() ?: return null
                val text = children.filterIsInstance<TextView>().firstOrNull() ?: return null
                if (button.foreground != null) return null
                return VerticalButtonSkin(button, icon, text)
            }
        }
    }

    private class DetailBackground(
        private val row: ViewGroup, private val label: View, private val actions: List<View>,
    ) : Drawable() {
        var feedback: XhsPressFeedback? = null
        private class Capsule(index: Int) {
            val node = RenderNode("LiquidTab-XhsDetail-$index")
            val shadow = RenderNode("LiquidTab-XhsDetailShadow-$index")
            var shadowKey = ""
            val lens = runCatching { io.github.offlineglass.rendering.PostureGlass.shader(KYANT_LENS_SHADER) }.onFailure {
                android.util.Log.e("LiquidTabInject", "XHS lens creation failed", it)
            }.getOrNull()
            var effectKey = ""
        }
        private val capsules = List(2) { Capsule(it) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val location = IntArray(2)
        private val rowLocation = IntArray(2)
        private var config = GlassConfig()
        private val nodeField = runCatching {
            View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
        }.getOrNull()
        private var trackingTilt = false
        private val bloom = runCatching { RuntimeShader(GlassHostLayout.BLOOM_STROKE_DUAL_SHADER) }.getOrNull()
        private val lightingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lightingPath = Path()
        fun update(value: GlassConfig) {
            if (config != value) { config = value; invalidateSelf() }
            val track = value.enabled && value.liquidGlassEnabled && !value.solidBarEnabled &&
                value.postureEffectsEnabled && row.isShown && row.windowVisibility == View.VISIBLE
            if (track != trackingTilt) {
                trackingTilt = track
                if (track) BloomTiltTracker.register(row) else BloomTiltTracker.unregister(row)
            }
        }
        override fun draw(canvas: Canvas) {
            if (!canvas.isHardwareAccelerated || !row.isShown) return
            val density = row.resources.displayMetrics.density
            // Kyant0 catalog LiquidButton: height(48.dp), capsule shape.
            val height = minOf(48f * density, row.height.toFloat())
            if (height <= 0) return
            val first = actions.first()
            val last = actions.last()
            val y = (row.height - height) / 2f
            val left = RectF(label.left.toFloat(), y, label.right.toFloat(), y + height)
            val right = RectF(first.left + 4f * density, y,
                last.right.toFloat(), y + height)
            val dark = isDarkPage()
            for ((index, rect) in listOf(left, right).withIndex()) {
                if (rect.width() <= 0) continue
                val feedbackSave = canvas.save()
                val scale = if (index == 0) feedback?.scale(0) ?: 1f
                    else feedback?.actionScale() ?: 1f
                canvas.scale(scale, scale, rect.centerX(), rect.centerY())
                clip.setBottomBarSquircle(rect, height / 2f, config.cornerSmoothing)
                if (!dark) drawShadow(canvas, rect, density, capsules[index])
                val save = canvas.save()
                canvas.clipPath(clip)
                if (!config.solidBarEnabled) drawBackdrop(canvas, rect, density, dark, capsules[index])
                paint.style = Paint.Style.FILL
                // Native page tint follows app theme changes without screenshot sampling.
                paint.color = pageTint(dark)
                paint.alpha = if (config.solidBarEnabled) 255 else
                    (config.lightAlpha * 255).toInt()
                canvas.drawRect(rect, paint)
                feedback?.drawLight(canvas, rect, index)
                canvas.restoreToCount(save)
                if (config.outlineEnabled) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = density * 0.6f
                    paint.color = Color.WHITE
                    paint.alpha = 10
                    canvas.drawPath(clip, paint)
                    if (config.liquidGlassEnabled && !config.solidBarEnabled && config.outlineEnabled) drawHighlight(canvas, rect, density, dark)
                }
                canvas.restoreToCount(feedbackSave)
            }
        }
        private fun drawShadow(canvas: Canvas, rect: RectF, density: Float, capsule: Capsule) {
            val pad = (30f * density).toInt()
            val width = kotlin.math.ceil(rect.width()).toInt() + pad * 2
            val height = kotlin.math.ceil(rect.height()).toInt() + pad * 2
            val key = "$width:$height:$density:${config.cornerSmoothing}"
            if (capsule.shadowKey != key || !capsule.shadow.hasDisplayList()) {
                capsule.shadowKey = key
                capsule.shadow.setPosition(0, 0, width, height)
                capsule.shadow.setRenderEffect(RenderEffect.createBlurEffect(
                    10f * density, 10f * density, Shader.TileMode.DECAL))
                val recording = capsule.shadow.beginRecording(width, height)
                try {
                    val path = Path().apply {
                        setBottomBarSquircle(RectF(pad.toFloat(), pad.toFloat(),
                            pad + rect.width(), pad + rect.height()), rect.height() / 2f, config.cornerSmoothing)
                    }
                    recording.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(48, 0, 0, 0)
                    })
                } finally { capsule.shadow.endRecording() }
            }
            val save = canvas.save()
            // Shadow only surrounds the capsule; never darken transparent glass.
            canvas.clipOutPath(clip)
            canvas.translate(rect.left - pad, rect.top - pad)
            io.github.offlineglass.rendering.PostureGlass.shadow(canvas, row,
                RectF(pad.toFloat(), pad.toFloat(), pad + rect.width(), pad + rect.height()), 30f * density) {
                canvas.drawRenderNode(capsule.shadow)
            }
            canvas.restoreToCount(save)
        }
        private fun drawBackdrop(canvas: Canvas, rect: RectF, density: Float, dark: Boolean, capsule: Capsule) {
            val node = capsule.node
            val lens = capsule.lens
            val padding = (24f * density).toInt()
            val w = rect.width().toInt() + padding * 2
            val h = rect.height().toInt() + padding * 2
            node.setPosition(0, 0, w, h)
            lens?.let { io.github.offlineglass.rendering.PostureGlass.sync(
                it, row, config.postureEffectsEnabled && config.liquidGlassEnabled && !config.solidBarEnabled,
                config.liquidViscosity,
                config.flowHorizontalStrength, config.flowVerticalStrength,
            ) }
            val key = "$w:$h:${config.blurRadius}:${config.liquidGlassEnabled}"
            val postureChanged = lens?.let { io.github.offlineglass.rendering.PostureGlass.effectChanged(it) } == true
            if (capsule.effectKey != key || postureChanged) {
                capsule.effectKey = key
                // Detail controls favor near-clear optics; do not change global bars.
                val radius = minOf(config.blurRadius, 0.5f) * density
                val blur = if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
                val refraction = if (config.liquidGlassEnabled) lens?.let {
                    it.setFloatUniform("size", rect.width(), rect.height())
                    it.setFloatUniform("offset", -padding.toFloat(), -padding.toFloat())
                    it.setFloatUniform("cornerRadii", FloatArray(4) { rect.height() / 2f })
                    it.setFloatUniform("refractionHeight", 12f * density)
                    // Kyant0 LiquidButton's reference lens(12.dp, 24.dp).
                    it.setFloatUniform("refractionAmount", -24f * density)
                    it.setFloatUniform("depthEffect", 0f)
                    RenderEffect.createRuntimeShaderEffect(it, "content")
                } else null
                node.setRenderEffect(if (blur != null && refraction != null)
                    RenderEffect.createChainEffect(refraction, blur) else refraction ?: blur)
            }
            row.getLocationInWindow(rowLocation)
            val recording = node.beginRecording(w, h)
            try {
                recording.drawColor(pageTint(dark))
                // Replay disjoint sibling display lists only. Never capture an ancestor
                // containing this drawable: that would create recursive feedback.
                val layers = ArrayList<Pair<View, ViewGroup>>()
                var branch: View = row
                var parent = branch.parent as? ViewGroup
                while (parent != null) {
                    layers.add(branch to parent)
                    branch = parent
                    parent = branch.parent as? ViewGroup
                }
                // Composite far ancestors first. Only siblings behind the branch
                // belong to the backdrop; later siblings are foreground overlays.
                for ((currentBranch, currentParent) in layers.asReversed()) {
                    // A parent's background is not part of any sibling display
                    // list. Composite it first so uncovered areas retain the
                    // actual page color rather than a different fallback grey.
                    currentParent.background?.let { background ->
                        currentParent.getLocationInWindow(location)
                        val save = recording.save()
                        recording.translate(location[0] - rowLocation[0] - rect.left + padding,
                            location[1] - rowLocation[1] - rect.top + padding)
                        background.draw(recording)
                        recording.restoreToCount(save)
                    }
                    for (i in 0 until currentParent.childCount) {
                        val source = currentParent.getChildAt(i)
                        if (source === currentBranch) break
                        if (!source.isShown || source.alpha <= 0f) continue
                        val sourceNode = runCatching { nodeField?.get(source) as? RenderNode }.getOrNull() ?: continue
                        if (!sourceNode.hasDisplayList()) continue
                        source.getLocationInWindow(location)
                        val save = recording.save()
                        recording.translate(location[0] - rowLocation[0] - rect.left + padding - source.left - source.translationX,
                            location[1] - rowLocation[1] - rect.top + padding - source.top - source.translationY)
                        recording.drawRenderNode(sourceNode)
                        recording.restoreToCount(save)
                    }
                }
            } finally { node.endRecording() }
            val save = canvas.save()
            canvas.translate(rect.left - padding, rect.top - padding)
            canvas.drawRenderNode(node)
            canvas.restoreToCount(save)
        }
        private fun drawHighlight(canvas: Canvas, rect: RectF, density: Float, dark: Boolean) {
            val shader = bloom ?: return
            var gx = if (config.postureEffectsEnabled) BloomTiltTracker.gravityX else 0f
            var gy = if (config.postureEffectsEnabled) BloomTiltTracker.gravityY else 0f
            when (row.display?.rotation) {
                android.view.Surface.ROTATION_90 -> { val x = gx; gx = -gy; gy = x }
                android.view.Surface.ROTATION_180 -> { gx = -gx; gy = -gy }
                android.view.Surface.ROTATION_270 -> { val x = gx; gx = gy; gy = -x }
            }
            val length = kotlin.math.sqrt(gx * gx + gy * gy)
            if (length > 0.1f) { gx /= length; gy /= length } else { gx = 0f; gy = -1f }
            val c = 0.70710677f
            shader.setFloatUniform("halfView", rect.width() / 2f, rect.height() / 2f)
            // Detail buttons intentionally sit below the bar's minimum 0.5.
            shader.setFloatUniform("highlightAlpha", 0.25f)
            shader.setColorUniform("strokeColor", Color.WHITE)
            shader.setFloatUniform("strokeAlphaMul", 0.12f)
            shader.setFloatUniform("lightDir1", c * gx + c * gy, -c * gx + c * gy, -0.05f)
            shader.setColorUniform("lightColor1", Color.WHITE)
            shader.setFloatUniform("lightIntensity1", 1f)
            shader.setFloatUniform("lightDir2", 0f, 0.19611613f, -0.9805807f)
            shader.setColorUniform("lightColor2", Color.WHITE)
            shader.setFloatUniform("lightIntensity2", 0.4f)
            lightingPaint.shader = shader
            // On a light page SRC_OVER's dim shader lobes look like black edges.
            // Screen blending makes the white lighting additive, never darkening.
            lightingPaint.blendMode = BlendMode.SCREEN
            lightingPaint.style = Paint.Style.STROKE
            lightingPaint.strokeWidth = density
            lightingPath.setBottomBarSquircle(RectF(0f, 0f, rect.width(), rect.height()), rect.height() / 2f, config.cornerSmoothing)
            val save = canvas.save()
            canvas.translate(rect.left, rect.top)
            canvas.drawPath(lightingPath, lightingPaint)
            canvas.restoreToCount(save)
            lightingPaint.shader = null
            lightingPaint.blendMode = BlendMode.SRC_OVER
        }
        fun isDarkPage(): Boolean = when (config.themeMode) {
            GlassConfig.THEME_LIGHT -> false
            GlassConfig.THEME_DARK -> true
            else -> {
                val nativeNight = row.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                val color = pageTint(nativeNight)
                (Color.red(color) * 0.2126f + Color.green(color) * 0.7152f + Color.blue(color) * 0.0722f) < 128f
            }
        }
        private fun pageTint(dark: Boolean): Int {
            var ancestor = row.parent as? View
            repeat(8) {
                val view = ancestor ?: return if (dark) Color.rgb(25, 25, 31) else Color.WHITE
                val color = (view.background as? ColorDrawable)?.color
                if (color != null && Color.alpha(color) == 255) return color
                ancestor = view.parent as? View
            }
            return if (dark) Color.rgb(25, 25, 31) else Color.WHITE
        }
        fun pause() {
            if (trackingTilt) BloomTiltTracker.unregister(row)
            trackingTilt = false
        }
        fun release() { pause(); capsules.forEach { it.node.discardDisplayList(); it.shadow.discardDisplayList() } }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
