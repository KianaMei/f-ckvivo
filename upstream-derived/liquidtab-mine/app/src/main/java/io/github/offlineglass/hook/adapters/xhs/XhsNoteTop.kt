package io.github.offlineglass.hook.adapters.xhs

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RelativeLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.globalGlassBottomGapPx
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Floating chrome for NoteDetailActivity. The note presenter still owns each action. */
internal object XhsNoteTop {
    private val sessions = WeakHashMap<Activity, Session>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LiquidTab-XhsNoteTop").apply { isDaemon = true }
    }

    fun start(activity: Activity) = sessions.getOrPut(activity) { Session(activity) }.start()
    fun pause(activity: Activity) { sessions[activity]?.pause() }
    fun dispose(activity: Activity) { sessions.remove(activity)?.dispose() }

    private class Session(activity: Activity) {
        private val activityRef = WeakReference(activity)
        private val isFinishing: Boolean get() = activityRef.get()?.isFinishing == true
        private val root = activity.window.decorView as ViewGroup
        private var config: GlassConfig? = null
        private var skin: Skin? = null
        private var observer: ViewTreeObserver? = null
        private var layoutChanged = true
        private var configPending = false
        private var configAt = -1000L
        private var searchAt = -350L
        private var missingLogged = false
        private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { layoutChanged = true }
        private val onDraw = ViewTreeObserver.OnPreDrawListener { update(); true }
        private val retry = Runnable { root.invalidate() }

        fun start() {
            if (observer?.isAlive == true) return
            root.viewTreeObserver.also {
                observer = it
                it.addOnGlobalLayoutListener(onLayout)
                it.addOnPreDrawListener(onDraw)
            }
            layoutChanged = true
            refreshConfig()
        }

        private fun refreshConfig() {
            if (configPending) return
            configPending = true
            configAt = SystemClock.uptimeMillis()
            val context = root.context.applicationContext
            worker.execute {
                val next = runCatching { HookConfigReader.read(context, context.packageName).normalized() }.getOrNull()
                root.post {
                    configPending = false
                    if (observer != null && next != null && next != config) {
                        config = next
                        root.invalidate()
                    }
                }
            }
        }

        private fun update() {
            // Keep layout/background writes out of the return-transition window so
            // the native detail->feed shrink animation never snaps to its final frame.
            if (isFinishing) return
            val now = SystemClock.uptimeMillis()
            if (now - configAt >= 1000L) refreshConfig()
            val currentConfig = config ?: return
            if (!currentConfig.enabled || !currentConfig.xhsTopBar) {
                skin?.restore()
                skin = null
                layoutChanged = true
                return
            }
            val current = skin
            if (current != null && current.binding.isCurrent()) {
                current.update(currentConfig, layoutChanged)
                layoutChanged = false
                return
            }
            current?.suspendActions()
            if (!layoutChanged) return
            val delay = 350L - (now - searchAt)
            if (delay > 0L) {
                root.removeCallbacks(retry)
                root.postDelayed(retry, delay)
                return
            }
            searchAt = now
            layoutChanged = false
            val binding = XhsNoteTopBinding.find(root, current?.binding?.feed)
            if (binding == null) {
                if (!missingLogged) Log.w("LiquidTabXhsNoteTop", "note title/feed contract unavailable")
                missingLogged = true
                return
            }
            missingLogged = false
            current?.restore()
            skin = Skin(binding).also { it.update(currentConfig, true) }
            Log.i("LiquidTabXhsNoteTop", "note title attached to native feed and actions (split=${binding.isSplit})")
        }

        fun pause() {
            root.removeCallbacks(retry)
            skin?.pause()
            observer?.takeIf { it.isAlive }?.let {
                it.removeOnGlobalLayoutListener(onLayout)
                it.removeOnPreDrawListener(onDraw)
            }
            observer = null
        }

        fun dispose() {
            pause()
            if (!isFinishing) skin?.restore()
            skin = null
        }
    }

    private class Layer(context: Context) : FrameLayout(context), AdapterOwnedOverlay {
        var feedback: XhsPressFeedback? = null
        private var observingTouch = false
        init {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                observingTouch = super.dispatchTouchEvent(event)
                if (observingTouch) feedback?.observe(event)
                return observingTouch
            }
            if (observingTouch) {
                feedback?.observe(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    observingTouch = false
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private class Hit(context: Context, val target: () -> View?) : View(context) {
        init {
            isFocusable = true
            setOnClickListener {
                target()?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled && it.hasOnClickListeners() }
                    ?.performClick()
            }
        }
        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    }

    private class Skin(val binding: XhsNoteTopBinding) {
        private val context = binding.title.context
        private val plates = XhsNoteTopPlates(binding)
        private val hits = Layer(context)
        private var followControls = emptyList<XhsNoteTopBinding.Follow>()
        private var follow: XhsNoteTopBinding.Follow? = null
        private val actions = listOf(
            Hit(context) { binding.back },
            Hit(context) { binding.title },
            Hit(context) { follow?.control },
            Hit(context) { binding.share },
        )
        private val press = XhsPressFeedback(hits, actions, groupTrailingActions = false, changed = ::animatePress)
        private val animatedArtwork = LinkedHashMap<View, ArtworkTransform>()
        private var paused = false
        private val backgrounds = LinkedHashMap<View, Drawable?>()
        private val foregrounds = LinkedHashMap<View, Drawable?>()
        private val positions = LinkedHashMap<View, Pair<Float, Float>>()
        private val savedClips = LinkedHashMap<ViewGroup, Boolean>()
        private val nameClip = binding.nameGroup.clipBounds
        private val avatarScaleX = binding.avatarFrame.scaleX
        private val avatarScaleY = binding.avatarFrame.scaleY
        private val avatarPivot = binding.avatarFrame.takeIf { it.isPivotSet }?.let { it.pivotX to it.pivotY }
        private var headerPadding = binding.header.paddingTop
        private var appliedHeaderPadding = headerPadding
        private val titleClickable = binding.title.isClickable
        private val feedBelow = binding.status?.id ?: View.NO_ID
        private var feedMargin = (binding.feed.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        private var followPlate: View? = null
        private var lastNameText: CharSequence? = null
        private var lastSplitPaneVisible = false
        private var lastRowLeft = Int.MIN_VALUE
        private val density get() = binding.row.resources.displayMetrics.density
        private val buttonHeight get() = minOf(dp(40), binding.row.height)

        private fun buttonTop(height: Int): Int = ((binding.row.height - height) / 2 - dp(6)).coerceAtLeast(0)

        private fun unclip(group: ViewGroup?) {
            if (group == null) return
            if (!savedClips.containsKey(group)) savedClips[group] = group.clipChildren
            if (group.clipChildren) group.clipChildren = false
        }

        init {
            hits.feedback = press
            plates.feedback = press
            val full = ViewGroup.LayoutParams.MATCH_PARENT
            val host = binding.overlayHost
            unclip(host)
            unclip(binding.topBarRow)
            unclip(binding.backBox)
            unclip(binding.container)
            val chromeIndex = host.indexOfChild(binding.chromeLayer)
            if (binding.isSplit) {
                host.addView(plates, chromeIndex, RelativeLayout.LayoutParams(full, full))
                // Insert hits directly above topBarRow so it sits below EngageBarView and dialogs.
                host.addView(hits, host.indexOfChild(binding.chromeLayer) + 1, RelativeLayout.LayoutParams(full, full))
            } else {
                host.addView(plates, chromeIndex, FrameLayout.LayoutParams(full, full))
                host.addView(hits, FrameLayout.LayoutParams(full, full))
            }
            actions.forEach { hits.addView(it, FrameLayout.LayoutParams(0, 0)) }
        }

        fun update(config: GlassConfig, layoutChanged: Boolean) {
            val visible = binding.title.isShown
            val visibility = if (visible) View.VISIBLE else View.INVISIBLE
            val visibilityChanged = plates.visibility != visibility || hits.visibility != visibility
            if (plates.visibility != visibility) plates.visibility = visibility
            if (hits.visibility != visibility) hits.visibility = visibility
            if (!visible) { pause(); return }
            paused = false

            if (layoutChanged || visibilityChanged) followControls = binding.followControls()
            val next = followControls.singleOrNull { it.control.parent === binding.row && it.control.isShown }
            val followChanged = next?.control !== follow?.control
            if (followChanged) {
                releaseFollow()
                follow = next
            }
            clear(binding.topBarRow, true)
            clear(binding.backBox, true)
            clear(binding.back, true)
            clear(binding.container, true)
            clear(binding.title, true)
            clear(binding.row, true)
            clear(binding.titleStatus, true)
            clear(binding.status, true)
            if (binding.title.isClickable) binding.title.isClickable = false
            follow?.control?.let { followPlate = it; clear(it, false) }
            extendFeed()
            reserveTop()
            plates.update(config)
            val nameText = binding.name.text
            val splitVisible = binding.splitPaneVisible
            val rowLeft = leftInHost(binding.row)
            val geometryChanged = nameText != lastNameText || splitVisible != lastSplitPaneVisible || rowLeft != lastRowLeft
            if (layoutChanged || visibilityChanged || followChanged || geometryChanged) {
                lastNameText = nameText
                lastSplitPaneVisible = splitVisible
                lastRowLeft = rowLeft
                layout()
            }
        }

        fun pause() {
            if (paused) return
            paused = true
            press.reset()
            plates.pause()
        }

        private fun reserveTop() {
            val height = buttonHeight
            if (height <= 0) return
            val header = binding.header
            if (header.paddingTop != appliedHeaderPadding) headerPadding = header.paddingTop
            val rowTopInPage = if (binding.isSplit) topInHost(binding.row) else binding.container.top + topInHost(binding.row)
            // Leave a small gap below the glass edge in the scrolling header.
            val clearance = rowTopInPage + buttonTop(height) + height + dp(4)
            appliedHeaderPadding = headerPadding + clearance
            if (header.paddingTop != appliedHeaderPadding)
                header.setPadding(header.paddingLeft, appliedHeaderPadding, header.paddingRight, header.paddingBottom)
        }

        fun suspendActions() {
            if (hits.visibility == View.VISIBLE) hits.visibility = View.INVISIBLE
            pause()
        }

        private fun extendFeed() {
            val feed = binding.feed
            if (binding.isSplit) {
                val params = feed.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                if (params.topMargin == 0) return
                feedMargin = params.topMargin
                params.topMargin = 0
                feed.layoutParams = params
                return
            }
            val params = feed.layoutParams as? RelativeLayout.LayoutParams ?: return
            val below = params.getRule(RelativeLayout.BELOW)
            if (below != 0 && below != feedBelow) return
            if (below == 0 && params.topMargin == 0) return
            if (params.topMargin != 0) feedMargin = params.topMargin
            params.removeRule(RelativeLayout.BELOW)
            params.topMargin = 0
            feed.layoutParams = params
        }

        private fun layout() {
            val row = binding.row
            val rowWidth = row.width
            val height = buttonHeight
            if (height <= 0 || rowWidth <= 0) return
            val side = globalGlassBottomGapPx(row)
            val gap = dp(6)
            val top = buttonTop(height)
            val rowLeft = leftInHost(row)
            val y = topInHost(row) + top
            val backBoxLeft = binding.backBox?.let(::leftInHost) ?: rowLeft
            val backBoxWidth = binding.backBox?.width ?: rowWidth
            val followView = follow?.control?.takeIf { it.isShown && it.width > 0 }
            val followWidth = followView?.width?.coerceAtLeast(dp(56)) ?: 0
            val avatarScale = 0.88f
            val avatarRadius = binding.avatarDiameter() * avatarScaleX * avatarScale / 2f
            val name = binding.name
            val textLayout = name.layout?.takeIf { it.lineCount > 0 }
            val textLeft = textLayout?.getLineLeft(0) ?: 0f
            val textWidth = textLayout?.getLineWidth(0) ?: name.width.toFloat()

            val solved = XhsNoteTopLayout.compute(
                binding.isSplit,
                binding.splitPaneVisible,
                backBoxLeft,
                backBoxWidth,
                rowLeft,
                rowWidth,
                y,
                height,
                side,
                gap,
                followWidth,
                avatarRadius,
                dp(3).toFloat(),
                textWidth,
                dp(10).toFloat(),
            ) ?: return

            if (binding.isSplit) {
                val topBarBottom = binding.topBarRow?.bottom ?: 0
                val wantedHitHeight = maxOf(topBarBottom, y + height + dp(12))
                val hitParams = hits.layoutParams
                if (hitParams != null && hitParams.height != wantedHitHeight) {
                    hitParams.height = wantedHitHeight
                    hits.layoutParams = hitParams
                }
            }

            restoreArtwork()
            place(0, solved.back.left, solved.back.top, solved.back.width, solved.back.height)
            place(1, solved.author.left, solved.author.top, solved.author.width, solved.author.height)
            place(2, solved.follow.left, solved.follow.top, solved.follow.width, solved.follow.height)
            place(3, solved.share.left, solved.share.top, solved.share.width, solved.share.height)

            val centerY = solved.centerY
            center(binding.back, solved.back.left + solved.back.width / 2f, centerY)
            center(binding.share, solved.share.left + solved.share.width / 2f, centerY)
            val avatar = binding.avatar
            val frame = binding.avatarFrame
            frame.pivotX = avatar.left + avatar.width / 2f
            frame.pivotY = avatar.top + avatar.height / 2f
            frame.scaleX = avatarScaleX * avatarScale
            frame.scaleY = avatarScaleY * avatarScale
            move(frame, solved.avatarCenterX - leftInHost(frame) - frame.pivotX, centerY - topInHost(frame) - frame.pivotY)
            val nameLeft = leftInHost(name) + name.compoundPaddingLeft + textLeft - name.scrollX
            val lineCenter = name.baseline.takeIf { it >= 0 }?.let { baseline ->
                baseline + (name.paint.ascent() + name.paint.descent()) / 2f
            } ?: (name.height / 2f)
            val nameCenter = topInHost(name) + lineCenter - name.scrollY
            val nameShift = solved.nameStartX - nameLeft
            move(binding.nameGroup, nameShift, centerY - nameCenter)
            val clipWidth = (solved.author.right() - dp(8) - leftInHost(binding.nameGroup) - nameShift)
                .roundToInt().coerceAtLeast(0)
            val clip = Rect(0, 0, clipWidth, binding.nameGroup.height)
            if (binding.nameGroup.clipBounds != clip) binding.nameGroup.clipBounds = clip
            followView?.let { center(it, solved.follow.left + solved.follow.width / 2f, centerY) }
            describe(0, binding.back.contentDescription ?: "返回")
            describe(1, binding.name.text)
            describe(2, follow?.label?.text)
            describe(3, binding.share.contentDescription ?: "转发")
            animatePress()
        }

        private fun animatePress() {
            animateArtwork(binding.back, 0)
            animateArtwork(binding.avatarFrame, 1)
            animateArtwork(binding.nameGroup, 1)
            follow?.control?.let { animateArtwork(it, 2) }
            animateArtwork(binding.share, 3)
            plates.invalidate()
        }

        private fun animateArtwork(view: View, index: Int) {
            val scale = press.scale(index)
            if (scale == 1f) {
                animatedArtwork.remove(view)?.restore(view)
                return
            }
            val params = actions[index].layoutParams as FrameLayout.LayoutParams
            val centerX = params.leftMargin + params.width / 2f
            val centerY = params.topMargin + params.height / 2f
            val base = animatedArtwork.getOrPut(view) { ArtworkTransform(view) }
            // Scale the native artwork about the same center as its glass plate.
            val pivotX = leftInHost(view) + view.pivotX + base.x
            val pivotY = topInHost(view) + view.pivotY + base.y
            view.translationX = base.x + (pivotX - centerX) * (scale - 1f)
            view.translationY = base.y + (pivotY - centerY) * (scale - 1f)
            view.scaleX = base.scaleX * scale
            view.scaleY = base.scaleY * scale
        }

        private fun restoreArtwork() {
            animatedArtwork.forEach { (view, base) -> base.restore(view) }
            animatedArtwork.clear()
        }

        private fun place(index: Int, left: Int, top: Int, width: Int, height: Int) {
            plates.place(index, left, top, width, height)
            val view = actions[index]
            val visibility = if (width > 0) View.VISIBLE else View.GONE
            if (view.visibility != visibility) view.visibility = visibility
            val params = view.layoutParams as FrameLayout.LayoutParams
            if (params.leftMargin == left && params.topMargin == top && params.width == width && params.height == height) return
            params.gravity = android.view.Gravity.LEFT or android.view.Gravity.TOP
            params.leftMargin = left
            params.topMargin = top
            params.width = width
            params.height = height
            view.layoutParams = params
        }

        private fun describe(index: Int, label: CharSequence?) {
            if (actions[index].contentDescription != label) actions[index].contentDescription = label
        }

        private fun center(view: View, x: Float, y: Float) {
            move(view, x - leftInHost(view) - view.width / 2f, y - topInHost(view) - view.height / 2f)
        }

        private fun topInHost(view: View): Int {
            var top = view.top
            var parent = view.parent
            val host = binding.overlayHost
            while (parent is View && parent !== host) { top += parent.top; parent = parent.parent }
            return top
        }

        private fun leftInHost(view: View): Int {
            var left = view.left
            var parent = view.parent
            val host = binding.overlayHost
            while (parent is View && parent !== host) { left += parent.left; parent = parent.parent }
            return left
        }

        private fun move(view: View, x: Float, y: Float) {
            if (!positions.containsKey(view)) positions[view] = view.translationX to view.translationY
            if (view.translationX != x) view.translationX = x
            if (view.translationY != y) view.translationY = y
        }

        private fun clear(view: View?, foreground: Boolean) {
            if (view == null) return
            view.background?.let { backgrounds[view] = it; view.background = null }
            if (foreground) view.foreground?.let { foregrounds[view] = it; view.foreground = null }
        }

        private fun releaseFollow() {
            followPlate?.let { view ->
                animatedArtwork.remove(view)?.restore(view)
                backgrounds.remove(view)?.let { if (view.background == null) view.background = it }
                positions.remove(view)?.let { (x, y) -> view.translationX = x; view.translationY = y }
            }
            followPlate = null
        }

        fun restore() {
            pause()
            restoreArtwork()
            plates.release()
            val host = binding.overlayHost
            host.removeView(hits)
            host.removeView(plates)
            savedClips.forEach { (group, clip) -> group.clipChildren = clip }
            savedClips.clear()
            binding.title.isClickable = titleClickable
            binding.nameGroup.clipBounds = nameClip
            binding.avatarFrame.apply {
                scaleX = avatarScaleX
                scaleY = avatarScaleY
                if (avatarPivot == null) resetPivot() else {
                    pivotX = avatarPivot.first
                    pivotY = avatarPivot.second
                }
            }
            binding.header.let {
                if (it.paddingTop == appliedHeaderPadding && it.paddingTop != headerPadding)
                    it.setPadding(it.paddingLeft, headerPadding, it.paddingRight, it.paddingBottom)
            }
            positions.forEach { (view, position) -> view.translationX = position.first; view.translationY = position.second }
            backgrounds.forEach { (view, drawable) -> if (view.background == null) view.background = drawable }
            foregrounds.forEach { (view, drawable) -> if (view.foreground == null) view.foreground = drawable }
            if (binding.isSplit) {
                val params = binding.feed.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                if (binding.feed.parent === binding.splitBody && params.topMargin == 0 && feedMargin != 0) {
                    params.topMargin = feedMargin
                    binding.feed.layoutParams = params
                }
                return
            }
            val params = binding.feed.layoutParams as? RelativeLayout.LayoutParams ?: return
            if (binding.feed.parent === binding.page && params.getRule(RelativeLayout.BELOW) == 0 && feedBelow != View.NO_ID) {
                params.addRule(RelativeLayout.BELOW, feedBelow)
                params.topMargin = feedMargin
                binding.feed.layoutParams = params
            }
        }

        private fun dp(value: Int) = (density * value).roundToInt()
    }

    private class ArtworkTransform(view: View) {
        val x = view.translationX
        val y = view.translationY
        val scaleX = view.scaleX
        val scaleY = view.scaleY

        fun restore(view: View) {
            view.translationX = x
            view.translationY = y
            view.scaleX = scaleX
            view.scaleY = scaleY
        }
    }

}
