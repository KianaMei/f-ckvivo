package io.github.offlineglass.hook.adapters.youtube

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/** One finite transition for labels and lens geometry; no capture or frame pump at rest. */
internal class YouTubeChannelsView(context: Context, private val more: View, private val onFrame: () -> Unit) : FrameLayout(context) {
    data class Entry(val key: String, val label: String, val selected: Boolean, val click: () -> Unit)
    private data class Pose(val center: Float, val alpha: Float, val color: Int)
    private data class Move(val view: View, val from: Pose, val to: Pose)
    private val labels = LinkedHashMap<String, TextView>()
    private var entries = emptyList<Entry>()
    private var dark = false
    private var pending = true
    private var lastWidth = 0
    private var lastHeight = 0
    private var animator: ValueAnimator? = null
    private var transitionRevision = 0L
    private var lensCenter = Float.NaN
    private var lensWidth = 0f
    private val colors = android.animation.ArgbEvaluator()

    init { clipChildren = false; clipToPadding = false; addView(more) }

    fun bind(value: List<Entry>, night: Boolean) {
        val changed = night != dark || value.map { Triple(it.key, it.label, it.selected) } != entries.map { Triple(it.key, it.label, it.selected) }
        entries = value; dark = night
        entries.forEach { entry ->
            val label = labels.getOrPut(entry.key) {
                TextView(context).apply {
                    textSize = 14f; gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    alpha = 0f; addView(this)
                }
            }
            label.text = entry.label
            label.setTypeface(label.typeface, if (entry.selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            label.contentDescription = "频道 ${entry.label}${if (entry.selected) "，已选中" else ""}"
            label.isEnabled = true
            label.setOnClickListener { entries.firstOrNull { it.key == entry.key }?.click?.invoke() }
        }
        if (changed) { pending = true; requestLayout() }
    }

    fun selectionGeometry(): Pair<Float, Float>? = if (lensCenter.isFinite() && lensWidth > 0f) lensCenter to lensWidth else null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val cell = ((measuredWidth - paddingLeft - paddingRight) / (entries.size + 1)).coerceAtLeast(1)
        val h = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(1)
        for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!pending && width == lastWidth && height == lastHeight) return
        val initial = !lensCenter.isFinite()
        pending = false; lastWidth = width; lastHeight = height
        val revision = ++transitionRevision
        animator?.cancel(); animator = null
        val from = HashMap<View, Pose>()
        for (i in 0 until childCount) {
            val view = getChildAt(i)
            from[view] = Pose(view.x + view.width / 2f, view.alpha, (view as? TextView)?.currentTextColor ?: Color.TRANSPARENT)
        }
        val cell = (width - paddingLeft - paddingRight).toFloat() / (entries.size + 1)
        val active = listOf(more) + entries.map { labels.getValue(it.key) }
        val moves = ArrayList<Move>()
        active.forEachIndexed { index, view ->
            val center = paddingLeft + (index + .5f) * cell
            val entry = entries.getOrNull(index - 1)
            val color = if (dark || entry?.selected == true) Color.WHITE else Color.rgb(30, 30, 34)
            val old = from[view]?.takeIf { view.width > 0 && it.alpha > 0f } ?: Pose(center, 0f, color)
            view.layout((center - view.measuredWidth / 2f).toInt(), paddingTop,
                (center - view.measuredWidth / 2f).toInt() + view.measuredWidth, height - paddingBottom)
            moves += Move(view, old, Pose(center, 1f, color))
        }
        val retiring = labels.filterKeys { key -> entries.none { it.key == key } }.values.toList()
        retiring.forEach { view -> view.isEnabled = false; moves += Move(view, from.getValue(view), from.getValue(view).copy(alpha = 0f)) }
        val selected = entries.indexOfFirst { it.selected }.takeIf { it >= 0 }
        val targetCenter = selected?.let { paddingLeft + (it + 1.5f) * cell } ?: lensCenter.takeIf { it.isFinite() } ?: (paddingLeft + 1.5f * cell)
        val startCenter = lensCenter.takeIf { it.isFinite() } ?: targetCenter
        val startWidth = lensWidth.takeIf { it > 0f } ?: cell
        fun frame(p: Float) {
            moves.forEach { move ->
                val center = move.from.center + (move.to.center - move.from.center) * p
                move.view.translationX = center - (move.view.left + move.view.width / 2f)
                move.view.alpha = move.from.alpha + (move.to.alpha - move.from.alpha) * p
                (move.view as? TextView)?.setTextColor(colors.evaluate(p, move.from.color, move.to.color) as Int)
            }
            lensCenter = startCenter + (targetCenter - startCenter) * p
            lensWidth = startWidth + (cell - startWidth) * p
            onFrame()
        }
        fun finish() {
            // Visibility/focus callbacks can end this animator while Android
            // is traversing our children. Never mutate that traversal inline.
            post {
                if (revision != transitionRevision) return@post
                retiring.forEach { view ->
                    val key = labels.entries.firstOrNull { it.value === view }?.key
                    if (key != null && entries.none { it.key == key } && view.parent === this) {
                        labels.remove(key); removeView(view)
                    }
                }
            }
        }
        if (initial || !isAttachedToWindow || !hasWindowFocus()) { frame(1f); finish(); return }
        frame(0f)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L; interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { frame(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) { animator = null; finish() } }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        transitionRevision++; animator?.cancel(); animator = null
        pending = true
        super.onDetachedFromWindow()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) animator?.end()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) animator?.end()
    }
}
