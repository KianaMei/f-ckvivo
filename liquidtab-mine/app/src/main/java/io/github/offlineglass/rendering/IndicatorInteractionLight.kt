package io.github.offlineglass.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import io.github.offlineglass.hook.setBottomBarSquircle

/** Local interaction light only. No application identity or navigation state. */
internal class IndicatorInteractionLight {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun outer(canvas: Canvas, bounds: RectF, radius: Float, density: Float, dark: Boolean, progress: Float) {
        if (progress <= 0.001f) return
        path.setBottomBarSquircle(bounds, radius, BottomBarIndicatorStyle.CURVE)
        paint.shader = null
        paint.color = Color.TRANSPARENT
        paint.setShadowLayer(12f * density, 0f, 0f,
            Color.argb((255f * (if (dark) 0.18f else 0.12f) * progress.coerceIn(0f, 1f)).toInt(),
                if (dark) 255 else 0, if (dark) 255 else 0, if (dark) 255 else 0))
        val save = canvas.save()
        // Never darken the interior in light mode, including the rounded edges.
        canvas.clipOutPath(path)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
        paint.clearShadowLayer()
    }
    fun inner(canvas: Canvas, bounds: RectF, progress: Float) {
        if (progress <= 0.001f) return
        paint.color = Color.WHITE
        paint.shader = RadialGradient(bounds.centerX(), bounds.centerY(),
            maxOf(bounds.width(), bounds.height()) * 0.65f,
            Color.argb((0.14f * 255f * progress.coerceIn(0f, 1f)).toInt(), 255, 255, 255),
            Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
    }
}
