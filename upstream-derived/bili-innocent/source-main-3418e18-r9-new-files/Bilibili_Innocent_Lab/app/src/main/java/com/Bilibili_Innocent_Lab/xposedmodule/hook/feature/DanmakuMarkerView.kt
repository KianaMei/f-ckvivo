package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.SeekBar
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.lang.ref.WeakReference
import kotlin.math.abs

internal data class SeekBarBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val totalWidth: Int
)

internal sealed interface MarkerPlacement {
    data object Hidden : MarkerPlacement

    data class AlignedToSeekBar(
        val leftMargin: Int,
        val topMargin: Int,
        val width: Int,
        val height: Int
    ) : MarkerPlacement

    data class LandscapeFallback(
        val bottomMargin: Int,
        val height: Int
    ) : MarkerPlacement
}

/**
 * 刻线命中、手势判定与 SeekBar 对齐的纯策略；不依赖 Android Framework 以便 JVM 单测完整覆盖。
 */
internal object DanmakuMarkerLayoutPolicy {
    const val HIT_SLOP_DP = 14f
    const val MIN_SEEK_DISTANCE_MS = 15_000L
    const val MIN_SEEKBAR_WIDTH_RATIO = 0.35f
    const val BAR_HEIGHT_DP = 18f
    const val LANDSCAPE_BOTTOM_MARGIN_DP = 44f

    fun nearestMark(
        x: Float,
        width: Float,
        durationMs: Long,
        marks: List<Long>,
        hitSlopPx: Float
    ): Long? {
        if (durationMs <= 0L || width <= 0f || marks.isEmpty() || hitSlopPx < 0f) return null
        var best: Long? = null
        var distance = Float.MAX_VALUE
        for (mark in marks) {
            if (mark <= 0L || mark >= durationMs) continue
            val targetX = (mark.toDouble() / durationMs * width).toFloat()
            val d = abs(targetX - x)
            if (d < distance) {
                distance = d
                best = mark
            }
        }
        return best?.takeIf { distance <= hitSlopPx }
    }

    fun computePlacement(
        hasMarks: Boolean,
        containerWidth: Int,
        containerHeight: Int,
        screenWidthPx: Int,
        isLandscape: Boolean,
        barHeightPx: Int,
        landscapeBottomMarginPx: Int,
        visibleSeekBars: List<SeekBarBounds>
    ): MarkerPlacement {
        if (!hasMarks || barHeightPx <= 0) return MarkerPlacement.Hidden
        val effectiveScreenWidth = if (screenWidthPx > 0) screenWidthPx else containerWidth
        val minWidth = (effectiveScreenWidth * MIN_SEEKBAR_WIDTH_RATIO).toInt().coerceAtLeast(1)
        val candidates = if (containerWidth > 0 && containerHeight > 0) {
            visibleSeekBars.filter { bounds ->
                bounds.totalWidth > minWidth &&
                    (bounds.right - bounds.left) > 0 &&
                    bounds.top in 0 until containerHeight &&
                    bounds.bottom > 0 &&
                    bounds.right > 0 &&
                    bounds.left < containerWidth
            }
        } else {
            emptyList()
        }
        val uniqueSeekBar = candidates.singleOrNull()
        if (uniqueSeekBar != null) {
            val trackWidth = (uniqueSeekBar.right - uniqueSeekBar.left).coerceAtLeast(1)
            val leftMargin = uniqueSeekBar.left.coerceAtLeast(0)
            val topMargin = (uniqueSeekBar.top - barHeightPx).coerceAtLeast(0)
            return MarkerPlacement.AlignedToSeekBar(
                leftMargin = leftMargin,
                topMargin = topMargin,
                width = trackWidth,
                height = barHeightPx
            )
        }
        return if (isLandscape) {
            MarkerPlacement.LandscapeFallback(
                bottomMargin = landscapeBottomMarginPx.coerceAtLeast(0),
                height = barHeightPx
            )
        } else {
            MarkerPlacement.Hidden
        }
    }
}

/**
 * 刻线点击状态机：仅当 `ACTION_DOWN` 命中某条刻线附近时才消费手势，其余一律返回 `false` 放行给宿主控件；
 * `ACTION_UP` 时校验手指位移未超 `touchSlopPx` 且仍在最近刻线可点范围内才触发跳转。
 */
internal class DanmakuMarkerTouchTracker {
    private var tracking = false
    private var downX = 0f
    private var downY = 0f

    fun onTouch(
        actionMasked: Int,
        x: Float,
        y: Float,
        touchSlopPx: Float,
        nearestAtX: (Float) -> Long?,
        currentPositionMs: () -> Long,
        onJump: (Long) -> Unit
    ): Boolean {
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = nearestAtX(x)
                if (hit == null) {
                    tracking = false
                    false
                } else {
                    tracking = true
                    downX = x
                    downY = y
                    true
                }
            }
            MotionEvent.ACTION_MOVE -> tracking
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                val dx = x - downX
                val dy = y - downY
                if (dx * dx + dy * dy <= touchSlopPx * touchSlopPx) {
                    val target = nearestAtX(x)
                    if (target != null && abs(target - currentPositionMs()) >= DanmakuMarkerLayoutPolicy.MIN_SEEK_DISTANCE_MS) {
                        onJump(target)
                    }
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                false
            }
            else -> tracking
        }
    }
}

/**
 * 进度条上方的刷屏刻线。只画静态红线、不改变播放器布局、不启用周期定时器；
 * 未点中刻线时 `ACTION_DOWN` 立即返回 `false` 放行，不吞噬下层进度条与评论区手势。
 */
@SuppressLint("ViewConstructor")
internal class DanmakuMarkerView(
    context: Context,
    private val duration: () -> Long,
    private val position: () -> Long,
    private val seek: (Long) -> Boolean
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchTracker = DanmakuMarkerTouchTracker()
    private val touchSlopPx: Float by lazy(LazyThreadSafetyMode.NONE) {
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
    private val containerLocation = IntArray(2)
    private val seekLocation = IntArray(2)
    private var observedContainer: WeakReference<ViewGroup>? = null
    private var observedTreeObserver: WeakReference<ViewTreeObserver>? = null
    private var isDetached = true
    private var lastScanUptimeMs = 0L
    @Volatile private var marks: List<Long> = emptyList()

    private val realignRunnable = HostThreadGuard.runnable("danmaku_jump_markers.realign") {
        syncPlacement(force = false)
    }

    private val containerLayoutListener = OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        HostThreadGuard.run("danmaku_jump_markers.layout_change") {
            val sizeChanged = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
            val boundsChanged = left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom
            if (sizeChanged || boundsChanged) {
                syncPlacement(force = true)
            }
        }
    }

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        HostThreadGuard.run("danmaku_jump_markers.global_layout") {
            if (isDetached || marks.isEmpty()) return@run
            scheduleThrottledRealign()
        }
    }

    init {
        paint.strokeWidth = dp(1.2f)
        paint.color = Color.argb(200, 255, 70, 70)
        visibility = GONE
    }

    fun marks(points: List<Long>) {
        marks = points
        post(HostThreadGuard.runnable("danmaku_jump_markers.marks") {
            if (isDetached) return@runnable
            syncPlacement(force = true)
            invalidate()
        })
    }

    fun attach(container: ViewGroup) {
        if (parent != null) return
        isDetached = false
        val heightPx = dp(DanmakuMarkerLayoutPolicy.BAR_HEIGHT_DP).toInt().coerceAtLeast(1)
        container.addView(
            this,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx,
                Gravity.BOTTOM
            ).apply {
                bottomMargin = dp(DanmakuMarkerLayoutPolicy.LANDSCAPE_BOTTOM_MARGIN_DP).toInt()
            }
        )
        observedContainer = WeakReference(container)
        container.addOnLayoutChangeListener(containerLayoutListener)
        container.viewTreeObserver?.takeIf { it.isAlive }?.let { observer ->
            observer.addOnGlobalLayoutListener(globalLayoutListener)
            observedTreeObserver = WeakReference(observer)
        }
        syncPlacement(force = true)
    }

    fun detach() {
        isDetached = true
        removeCallbacks(realignRunnable)
        val container = observedContainer?.get()
        container?.removeOnLayoutChangeListener(containerLayoutListener)
        observedTreeObserver?.get()?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(globalLayoutListener)
        container?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(globalLayoutListener)
        observedTreeObserver = null
        observedContainer = null
        (parent as? ViewGroup)?.removeView(this)
    }

    fun syncPlacement(force: Boolean = false) {
        HostThreadGuard.run("danmaku_jump_markers.sync_placement") {
            if (isDetached) return@run
            val currentMarks = marks
            if (currentMarks.isEmpty()) {
                if (visibility != GONE) visibility = GONE
                return@run
            }
            val container = (parent as? ViewGroup) ?: return@run
            val now = SystemClock.uptimeMillis()
            if (!force && lastScanUptimeMs > 0L && now - lastScanUptimeMs < REALIGN_THROTTLE_MS) {
                return@run
            }
            lastScanUptimeMs = now

            val seekBars = collectVisibleSeekBars(container)
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val placement = DanmakuMarkerLayoutPolicy.computePlacement(
                hasMarks = currentMarks.isNotEmpty(),
                containerWidth = container.width,
                containerHeight = container.height,
                screenWidthPx = resources.displayMetrics.widthPixels,
                isLandscape = isLandscape,
                barHeightPx = dp(DanmakuMarkerLayoutPolicy.BAR_HEIGHT_DP).toInt().coerceAtLeast(1),
                landscapeBottomMarginPx = dp(DanmakuMarkerLayoutPolicy.LANDSCAPE_BOTTOM_MARGIN_DP).toInt(),
                visibleSeekBars = seekBars
            )
            applyPlacement(placement)
        }
    }

    private fun scheduleThrottledRealign() {
        val elapsed = SystemClock.uptimeMillis() - lastScanUptimeMs
        if (elapsed >= REALIGN_THROTTLE_MS) {
            syncPlacement(force = false)
        } else {
            removeCallbacks(realignRunnable)
            postDelayed(realignRunnable, (REALIGN_THROTTLE_MS - elapsed).coerceAtLeast(16L))
        }
    }

    private fun collectVisibleSeekBars(container: ViewGroup): List<SeekBarBounds> {
        if (container.width <= 0 || container.height <= 0) return emptyList()
        container.getLocationInWindow(containerLocation)
        val rootX = containerLocation[0]
        val rootY = containerLocation[1]
        val found = ArrayList<SeekBarBounds>(2)
        val queue = ArrayDeque<View>(16)
        for (i in 0 until container.childCount) {
            queue.addLast(container.getChildAt(i))
        }
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view === this) continue
            if (view.visibility != VISIBLE || view.alpha <= 0.01f) continue
            if (view is SeekBar) {
                if (view.width > 0 && view.height > 0 && view.isShown) {
                    view.getLocationInWindow(seekLocation)
                    val relLeft = seekLocation[0] - rootX
                    val relTop = seekLocation[1] - rootY
                    val trackLeft = relLeft + view.paddingLeft
                    val trackRight = relLeft + view.width - view.paddingRight
                    found += SeekBarBounds(
                        left = trackLeft,
                        top = relTop,
                        right = trackRight,
                        bottom = relTop + view.height,
                        totalWidth = view.width
                    )
                }
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    queue.addLast(view.getChildAt(i))
                }
            }
        }
        return found
    }

    private fun applyPlacement(placement: MarkerPlacement) {
        when (placement) {
            MarkerPlacement.Hidden -> {
                if (visibility != GONE) visibility = GONE
            }
            is MarkerPlacement.AlignedToSeekBar -> {
                val lp = (layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(placement.width, placement.height)
                val targetGravity = Gravity.TOP or Gravity.LEFT
                val changed = lp.gravity != targetGravity ||
                    lp.width != placement.width ||
                    lp.height != placement.height ||
                    lp.leftMargin != placement.leftMargin ||
                    lp.topMargin != placement.topMargin ||
                    lp.bottomMargin != 0
                if (changed) {
                    lp.gravity = targetGravity
                    lp.width = placement.width
                    lp.height = placement.height
                    lp.leftMargin = placement.leftMargin
                    lp.topMargin = placement.topMargin
                    lp.bottomMargin = 0
                    layoutParams = lp
                }
                if (visibility != VISIBLE) visibility = VISIBLE
                invalidate()
            }
            is MarkerPlacement.LandscapeFallback -> {
                val lp = (layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, placement.height)
                val targetGravity = Gravity.BOTTOM
                val changed = lp.gravity != targetGravity ||
                    lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                    lp.height != placement.height ||
                    lp.leftMargin != 0 ||
                    lp.topMargin != 0 ||
                    lp.bottomMargin != placement.bottomMargin
                if (changed) {
                    lp.gravity = targetGravity
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = placement.height
                    lp.leftMargin = 0
                    lp.topMargin = 0
                    lp.bottomMargin = placement.bottomMargin
                    layoutParams = lp
                }
                if (visibility != VISIBLE) visibility = VISIBLE
                invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = marks
        if (snapshot.isEmpty()) return
        val total = duration()
        if (total <= 0L) return
        val w = width.toFloat()
        if (w <= 0f) return
        val top = dp(2f)
        val bottom = height - dp(2f)
        for (mark in snapshot) {
            if (mark <= 0L || mark >= total) continue
            val x = (mark.toDouble() / total * w).toFloat()
            if (x !in 0f..w) continue
            canvas.drawLine(x, top, x, bottom, paint)
        }
    }

    private fun nearest(x: Float): Long? = DanmakuMarkerLayoutPolicy.nearestMark(
        x = x,
        width = width.toFloat(),
        durationMs = duration(),
        marks = marks,
        hitSlopPx = dp(DanmakuMarkerLayoutPolicy.HIT_SLOP_DP)
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        HostThreadGuard.call("danmaku_jump_markers.touch", false) {
            if (visibility != VISIBLE || marks.isEmpty()) return@call false
            touchTracker.onTouch(
                actionMasked = event.actionMasked,
                x = event.x,
                y = event.y,
                touchSlopPx = touchSlopPx,
                nearestAtX = ::nearest,
                currentPositionMs = position,
                onJump = { target -> seek(target) }
            )
        }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        const val REALIGN_THROTTLE_MS = 250L
    }
}
