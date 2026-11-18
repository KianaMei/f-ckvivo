package io.github.offlineglass.hook.adapters.douyin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.AppVideoBackdrop

/** All per-host Douyin navigation and page state lives with the Douyin adapter. */
internal class DouyinNavigationState(context: Context) : AppNavigationState {
    private val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = android.graphics.Color.argb(28, 255, 255, 255)
    }
    private var haloDensity = 0f
    override fun drawPanelEdgeGlow(canvas: Canvas, path: Path, density: Float) {
        if (density != haloDensity) {
            haloDensity = density
            haloPaint.strokeWidth = 0.8f * density
            haloPaint.setShadowLayer(18f * density, 0f, 0f, android.graphics.Color.argb(45, 255, 255, 255))
        }
        // The supplied path includes both surfaces when the action is detached.
        canvas.drawPath(path, haloPaint)
    }

    private val renderer = DouyinNavigationRenderer(context)
    private val pageController = DouyinPageController(context)
    private val videoSampler = DouyinVideoSampler(context, pageController::logDiagnostic)
    private var outerRecordedVideoEpoch = Int.MIN_VALUE
    private var outerRecordedBackdrop: Bitmap? = null
    private var outerRecordedWidth = 0
    private var outerRecordedHeight = 0
    private var outerRecordedSamplingBlocked = false
    private var messageInputId = 0
    private var messageInputRef = java.lang.ref.WeakReference<View>(null)
    private var chromeRoot = java.lang.ref.WeakReference<View>(null)
    private val messageInputRect = android.graphics.Rect()
    private fun messageComposerVisible(root: View?): Boolean {
        root ?: return false
        if (messageInputId == 0) {
            messageInputId = root.resources.getIdentifier("msg_et", "id", "com.ss.android.ugc.aweme")
        }
        if (messageInputId == 0) return false
        var input = messageInputRef.get()
        if (input == null || !input.isAttachedToWindow || input.rootView !== root) {
            input = root.findViewById<View>(messageInputId)
            messageInputRef = java.lang.ref.WeakReference(input)
        }
        return input != null && input.isShown && input.alpha > 0.01f &&
            input.getGlobalVisibleRect(messageInputRect) && !messageInputRect.isEmpty
    }
    override val blocksBackdropSampling: Boolean get() = pageController.shopPageActive
    override fun pauseBackdropWork(host: View): Boolean =
        (pageController.profilePageActive() || pageController.messageComposerVisible() ||
            DouyinSidebarPages.isVisible(host.rootView)) &&
            (!host.isShown || host.alpha <= 0.001f)

    /** Pad split mode removes the native root_view row while the feed column
     * narrows; the glass bar stays and yields into that column instead. */
    override fun retainNavigationWhenNativeRowHidden(selectedIndex: Int): Boolean =
        DouyinPadSplitPages.isVisible(chromeRoot.get())

    // The shared host retains its outer RenderNode while the live view scene is
    // active. Douyin's PixelCopy bitmap is painted into that node by value, so
    // a new video frame must invalidate the node even though the view tree and
    // the host's ordinary backdrop bitmap have not changed. The indicator
    // records every frame independently; without this policy only it moves.
    override fun outerBackdropNeedsRecord(
        backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean,
    ): Boolean = !hasDisplayList ||
        outerRecordedVideoEpoch != (videoSampler.snapshot()?.epoch ?: -1) ||
        outerRecordedBackdrop !== backdrop ||
        outerRecordedWidth != width || outerRecordedHeight != height ||
        outerRecordedSamplingBlocked != blocksBackdropSampling

    override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
        outerRecordedVideoEpoch = videoSampler.snapshot()?.epoch ?: -1
        outerRecordedBackdrop = backdrop
        outerRecordedWidth = width
        outerRecordedHeight = height
        outerRecordedSamplingBlocked = blocksBackdropSampling
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = renderer.draw(canvas, frame)
    override fun logDiagnostic(message: String) = pageController.logDiagnostic(message)
    override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) =
        videoSampler.request(host, pageAllowed, blocksBackdropSampling)
    override fun videoBackdrop(): AppVideoBackdrop? = videoSampler.snapshot()
    override fun pageAllowsNavigation(root: View?): Boolean =
        !pageController.messageComposerVisible() && !pageController.profilePageActive() &&
            !DouyinSidebarPages.isVisible(root)

    override fun drawForeground(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        selectionPath: Path,
        drawSource: () -> Unit,
    ): Boolean {
        renderer.drawForeground(canvas, frame, selectionPath, drawSource)
        return true
    }

    override fun onHostPreDraw(
        host: View,
        source: ViewGroup?,
        selected: () -> Int,
        enabled: Boolean,
        
        density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        // Freeze homepage reservations while the native drawer owns the
        // viewport. Visibility still uses the shared animated exit/entry path.
        if (chromeRoot.get() !== host.rootView) chromeRoot = java.lang.ref.WeakReference(host.rootView)
        if (!DouyinSidebarPages.isVisible(host.rootView)) pageController.update(host, barHeightPx)
    }

    override fun onHostFrame(host: View) = yieldToPadSplitColumn(host)

    private var splitMarginTarget = Int.MIN_VALUE
    private var splitMarginAnimator: android.animation.ValueAnimator? = null

    /** On the fold inner screen a split pane narrows the feed column while the
     * native row is removed; the glass bar slides into that column with the
     * video instead of leaving, then returns to window center when it closes.
     * Anchors via LEFT margin so the shared host's translationX slider stays
     * untouched. */
    private fun yieldToPadSplitColumn(host: View) {
        val parent = host.parent as? View ?: return
        val params = host.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val leftGravity = android.view.Gravity.BOTTOM or android.view.Gravity.LEFT
        val columnLeft = DouyinPadSplitPages.occupiedPaneLeft(host.rootView)
        val anchorWidth = if (columnLeft in 1 until parent.width) columnLeft else parent.width
        val target = ((anchorWidth - host.width) / 2).coerceAtLeast(0)
        if (target == splitMarginTarget && splitMarginAnimator?.isRunning != true) {
            // Settled: keep the LEFT anchor pinned even after layout pass resets.
            if (params.gravity != leftGravity || params.leftMargin != target) {
                val settle = if (params.gravity == leftGravity) params.leftMargin
                else ((parent.width - host.width) / 2).coerceAtLeast(0)
                if (settle == target) {
                    params.gravity = leftGravity
                    params.leftMargin = target
                    host.layoutParams = params
                }
            }
            return
        }
        if (target == splitMarginTarget) return  // already animating toward it
        splitMarginTarget = target
        splitMarginAnimator?.cancel()
        val start = if (params.gravity == leftGravity) params.leftMargin
        else ((parent.width - host.width) / 2).coerceAtLeast(0)
        splitMarginAnimator = android.animation.ValueAnimator.ofInt(start, target).apply {
            duration = 240L
            interpolator = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                val margin = animation.animatedValue as Int
                val lp = host.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return@addUpdateListener
                lp.gravity = leftGravity
                lp.leftMargin = margin
                host.layoutParams = lp
            }
        }.also { it.start() }
    }

    override fun onHostDetached() {
        splitMarginAnimator?.cancel()
        splitMarginAnimator = null
        splitMarginTarget = Int.MIN_VALUE
        DouyinBottomDecorations.restore(chromeRoot.get())
        pageController.dispose()
        videoSampler.dispose()
    }
}


