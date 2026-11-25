package io.github.offlineglass.hook.adapters.xhs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.WindowInsets
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.rendering.PostureGlass
import kotlin.math.roundToInt

/** The feed is a disjoint native sibling. Replaying its live RenderNode also includes text. */
internal class XhsNoteTopPlates(private val binding: XhsNoteTopBinding) :
    View(binding.title.context), AdapterOwnedOverlay {
    var feedback: XhsPressFeedback? = null
    private val capsules = List(4) { XhsNoteCapsule(this, "top-$it") }
    private val buttons = List(4) { RectF() }
    private val feedLocation = IntArray(2)
    private val ownLocation = IntArray(2)
    private var config = GlassConfig()
    private var sceneColor: Int? = null
    private var leftSceneColor: Int? = null
    private var rightSource: RenderNode? = null
    private var rightOffsetX = 0f
    private var rightOffsetY = 0f
    private var rightBaseColor = Color.TRANSPARENT
    private var leftSource: RenderNode? = null
    private var leftOffsetX = 0f
    private var leftOffsetY = 0f
    private var leftBaseColor = Color.TRANSPARENT
    private var retried = false
    private var postureWanted = false
    private var gradient: GradientDrawable? = null
    private var gradientColor = 0
    private var leftGradient: GradientDrawable? = null
    private var leftGradientColor = 0

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        XhsTopTheme.watch(this)
    }

    override fun onDetachedFromWindow() {
        XhsTopTheme.unwatch(this)
        super.onDetachedFromWindow()
    }

    fun update(value: GlassConfig) {
        val nativeColor = (binding.feed.background as? ColorDrawable)?.color
            ?: (binding.page.background as? ColorDrawable)?.color
        val nativeLeftColor = (binding.leftPane?.background as? ColorDrawable)?.color
        if (config != value || sceneColor != nativeColor || leftSceneColor != nativeLeftColor) {
            config = value
            sceneColor = nativeColor
            leftSceneColor = nativeLeftColor
            invalidate()
        }
        val wantsPosture = value.liquidGlassEnabled && !value.solidBarEnabled && value.postureEffectsEnabled && isShown
        if (postureWanted != wantsPosture) {
            postureWanted = wantsPosture
            PostureGlass.track(this, wantsPosture)
        }
    }

    fun place(index: Int, left: Int, top: Int, width: Int, height: Int) {
        val rect = buttons[index]
        val right = (left + width).toFloat()
        val bottom = (top + height).toFloat()
        if (rect.left == left.toFloat() && rect.top == top.toFloat() && rect.right == right && rect.bottom == bottom) return
        rect.set(left.toFloat(), top.toFloat(), right, bottom)
        capsules[index].resetBackdrop()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val feed = binding.feed
        val dark = XhsTopTheme.dark(context, config.themeMode)
        val rightBackground = sceneColor?.takeIf { Color.alpha(it) == 255 }
            ?: if (dark) Color.rgb(25, 25, 31) else Color.WHITE
        val splitLeftPane = binding.leftPane?.takeIf { it.isShown && it.width > 0 }
        val leftBackground = if (splitLeftPane != null) {
            leftSceneColor?.takeIf { Color.alpha(it) == 255 } ?: rightBackground
        } else rightBackground

        drawStatusGradient(canvas, dark, splitLeftPane, leftBackground, rightBackground)

        val feedNode = if (!config.solidBarEnabled && canvas.isHardwareAccelerated &&
            feed.parent === binding.feedParent && feed.isShown) XhsNoteCapsule.renderNode(feed)?.takeIf { it.hasDisplayList() }
            else null
        val leftPaneNode = if (splitLeftPane != null && !config.solidBarEnabled && canvas.isHardwareAccelerated &&
            splitLeftPane.parent === binding.splitBody) XhsNoteCapsule.renderNode(splitLeftPane)?.takeIf { it.hasDisplayList() }
            else null

        val missingNode = feedNode == null || (splitLeftPane != null && leftPaneNode == null)
        if (missingNode && !retried && !config.solidBarEnabled && canvas.isHardwareAccelerated) {
            retried = true
            postInvalidateOnAnimation()
        } else if (!missingNode) retried = false

        getLocationInWindow(ownLocation)
        feed.getLocationInWindow(feedLocation)
        val rx = feedLocation[0] - ownLocation[0] - feed.x
        val ry = feedLocation[1] - ownLocation[1] - feed.y
        if (rightSource !== feedNode || rightOffsetX != rx || rightOffsetY != ry || rightBaseColor != rightBackground) {
            for (i in 1..3) capsules[i].resetBackdrop()
            if (splitLeftPane == null) capsules[0].resetBackdrop()
            rightSource = feedNode
            rightOffsetX = rx
            rightOffsetY = ry
            rightBaseColor = rightBackground
        }

        val lx: Float
        val ly: Float
        val backNode: RenderNode?
        if (splitLeftPane != null) {
            splitLeftPane.getLocationInWindow(feedLocation)
            lx = feedLocation[0] - ownLocation[0] - splitLeftPane.x
            ly = feedLocation[1] - ownLocation[1] - splitLeftPane.y
            backNode = leftPaneNode
        } else {
            lx = rx
            ly = ry
            backNode = feedNode
        }
        if (leftSource !== backNode || leftOffsetX != lx || leftOffsetY != ly || leftBaseColor != leftBackground) {
            capsules[0].resetBackdrop()
            leftSource = backNode
            leftOffsetX = lx
            leftOffsetY = ly
            leftBaseColor = leftBackground
        }

        val alpha = if (config.solidBarEnabled) 255 else (config.lightAlpha * 255).toInt()
        val rightTint = (rightBackground and 0x00ffffff) or (alpha shl 24)
        val leftTint = (leftBackground and 0x00ffffff) or (alpha shl 24)
        buttons.forEachIndexed { index, rect ->
            if (rect.isEmpty) return@forEachIndexed
            val isBack = index == 0
            val follow = index == 2
            val bg = if (isBack) leftBackground else rightBackground
            val tint = if (follow) 0x33FF2442 else if (isBack) leftTint else rightTint
            val node = if (isBack) backNode else feedNode
            val tx = if (isBack) lx else rx
            val ty = if (isBack) ly else ry
            val save = canvas.save()
            val scale = feedback?.scale(index) ?: 1f
            canvas.scale(scale, scale, rect.centerX(), rect.centerY())
            capsules[index].draw(canvas, rect, config, dark,
                tint, bg,
                shadowColor = if (follow) 0x4DFF2442 else XhsNoteCapsule.DEFAULT_SHADOW,
                highlightStrength = if (follow) 0.6f else 1f, reuseBackdrop = true,
                recordBackdrop = { recording ->
                    if (node != null) {
                        recording.translate(tx, ty)
                        recording.drawRenderNode(node)
                    }
                }, drawPress = { feedback?.drawLight(it, rect, index) })
            canvas.restoreToCount(save)
        }
    }

    private fun statusGradientFor(rgbColor: Int): GradientDrawable {
        val rgb = rgbColor and 0x00FFFFFF
        val colors = IntArray(17) { index ->
            val t = index / 16f
            val alpha = (160f * (1f - t * t * (3f - 2f * t))).toInt().coerceIn(0, 160)
            (alpha shl 24) or rgb
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
    }

    private fun drawStatusGradient(
        canvas: Canvas,
        dark: Boolean,
        splitLeftPane: View?,
        leftBackground: Int,
        rightBackground: Int,
    ) {
        val statusHeight = rootWindowInsets?.getInsets(
            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top
            ?: binding.status?.height
            ?: binding.topBarRow?.top
            ?: 0
        if (statusHeight <= 0) return
        val fade = (6 * resources.displayMetrics.density).roundToInt()
        val bottom = minOf(height, statusHeight + fade)
        if (splitLeftPane != null && splitLeftPane.right in 1 until width) {
            val splitX = splitLeftPane.right
            val leftRgb = leftBackground and 0x00FFFFFF
            if (leftGradient == null || leftGradientColor != leftRgb) {
                leftGradient = statusGradientFor(leftRgb)
                leftGradientColor = leftRgb
            }
            val rightRgb = rightBackground and 0x00FFFFFF
            if (gradient == null || gradientColor != rightRgb) {
                gradient = statusGradientFor(rightRgb)
                gradientColor = rightRgb
            }
            leftGradient?.let {
                it.setBounds(0, 0, splitX, bottom)
                it.draw(canvas)
            }
            gradient?.let {
                it.setBounds(splitX, 0, width, bottom)
                it.draw(canvas)
            }
            return
        }
        val singleRgb = if (dark) 0x0018181B else 0x00FFFFFF
        if (gradient == null || gradientColor != singleRgb) {
            gradient = XhsTopTheme.statusGradient(dark)
            gradientColor = singleRgb
        }
        gradient?.let {
            it.setBounds(0, 0, width, bottom)
            it.draw(canvas)
        }
    }

    fun pause() {
        if (!postureWanted) return
        postureWanted = false
        PostureGlass.track(this, false)
    }

    fun release() {
        pause()
        capsules.forEach { it.release() }
        rightSource = null
        leftSource = null
    }
}
