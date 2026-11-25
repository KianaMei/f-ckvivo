package io.github.offlineglass.hook.adapters.xhs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.localizeNavigationLabel

/** Immutable snapshot of a native Xiaohongshu tab badge read on the UI thread. */
internal data class XhsTabBadge(val text: String?, val dotOnly: Boolean)

/** Artwork state belongs to the optical pass, never to the current page index. */
internal class XhsNavigationRenderer {
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BRAND_RED
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val badgeRect = RectF()

    fun draw(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        messageBadge: XhsTabBadge? = null,
        highlightSelected: Boolean = false,
    ): Unit = with(frame) {
        if (slotCount != 5 || width <= 0 || height <= 0) return
        val normal = if (darkGlass) Color.rgb(232, 230, 235) else Color.rgb(40, 40, 40)
        labelPaint.textSize = 15f * 1.2f * scaledDensity
        val metrics = labelPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        val labelVisualTop = height / 2f - (metrics.descent - metrics.ascent) * 0.36f
        barLayout?.let { layout ->
            // The shared host already draws this action's own glass and shadow.
            // Match the glass radius so the untinted face does not form a ring.
            if (!hideSelectedForOptics) {
                val cx = layout.actionLeft + layout.actionSize / 2f
                actionPaint.style = Paint.Style.FILL
                // White backdrops wash a low-alpha red into pastel pink. Keep
                // the same brand hue but increase coverage in light mode;
                // the remaining transparency still exposes the live optics.
                val tintAlpha = if (darkGlass) 70 else 190
                actionPaint.color = (BRAND_RED and 0x00FFFFFF) or (tintAlpha shl 24)
                canvas.drawCircle(cx, height / 2f, layout.actionSize / 2f, actionPaint)
                drawPlus(canvas, cx, height / 2f, 9f * density, density)
            }
        }
        for ((visualIndex, index) in enabledIndices.withIndex()) {
            val cx = 4f * density + (visualIndex + .5f) * slotWidth
            val saved = canvas.save()
            canvas.scale(extraScale, extraScale, cx, height / 2f)
            if (index == 2) {
                val half = 20f * density
                rect.set(cx - half, height / 2f - half, cx + half, height / 2f + half)
                actionPaint.style = Paint.Style.FILL
                actionPaint.color = BRAND_RED
                canvas.drawRoundRect(rect, 12f * density, 12f * density, actionPaint)
                drawPlus(canvas, cx, height / 2f, 7f * density, density)
            } else {
                val label = localizeNavigationLabel(LABELS[index])
                labelPaint.color = XhsNavigationLogic.labelColor(
                    hideSelectedForOptics, highlightSelected, index, selectedIndex, accentColor, normal,
                )
                canvas.drawText(label, cx, baseline, labelPaint)
                if (index == MESSAGE_INDEX && messageBadge != null) {
                    drawMessageBadge(canvas, cx, slotWidth, height.toFloat(), label, labelVisualTop,
                        messageBadge, density, scaledDensity)
                }
            }
            canvas.restoreToCount(saved)
        }
    }

    private fun drawMessageBadge(
        canvas: Canvas,
        cx: Float,
        slotWidth: Float,
        barHeight: Float,
        label: String,
        labelVisualTop: Float,
        badge: XhsTabBadge,
        density: Float,
        scaledDensity: Float,
    ) {
        val labelWidth = labelPaint.measureText(label)
        val badgeText = badge.text
        val dotOnly = badge.dotOnly || badgeText.isNullOrEmpty()
        val numberWidth = if (!dotOnly) {
            badgeTextPaint.textSize = 9.5f * scaledDensity
            badgeTextPaint.measureText(badgeText)
        } else 0f
        val bounds = XhsNavigationLogic.computeBadgeBounds(
            cx, slotWidth, barHeight, labelWidth, labelVisualTop, numberWidth, dotOnly, density,
        )
        badgeRect.set(bounds[0], bounds[1], bounds[2], bounds[3])
        val radius = badgeRect.height() / 2f
        canvas.drawRoundRect(badgeRect, radius, radius, badgeFillPaint)
        if (!dotOnly && badgeText != null) {
            val fm = badgeTextPaint.fontMetrics
            val textBaseline = badgeRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(badgeText, badgeRect.centerX(), textBaseline, badgeTextPaint)
        }
    }

    private fun drawPlus(canvas: Canvas, x: Float, y: Float, half: Float, density: Float) {
        actionPaint.style = Paint.Style.STROKE
        actionPaint.strokeWidth = 3.2f * density
        actionPaint.color = Color.WHITE
        canvas.drawLine(x - half, y, x + half, y, actionPaint)
        canvas.drawLine(x, y - half, x, y + half, actionPaint)
        actionPaint.style = Paint.Style.FILL
    }
    companion object {
        const val BRAND_RED = 0xFFFF2442.toInt()
        const val MESSAGE_INDEX = 3
        private val LABELS = arrayOf("首页", "市集", "", "消息", "我")
    }
}
