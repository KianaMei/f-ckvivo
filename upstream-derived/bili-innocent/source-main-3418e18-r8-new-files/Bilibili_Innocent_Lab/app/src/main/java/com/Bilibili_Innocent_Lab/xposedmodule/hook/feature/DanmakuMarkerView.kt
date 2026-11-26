package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 进度条上方的刷屏刻线。只画刻度、不改变播放器布局；点击最近刻线触发跳转。
 * 触摸只在自己的窄条内消费，不接管播放器手势。
 */
@SuppressLint("ViewConstructor")
internal class DanmakuMarkerView(context: Context,
    private val duration: () -> Long,
    private val position: () -> Long,
    private val seek: (Long) -> Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 500) }
    }
    @Volatile private var marks: List<Long> = emptyList()

    init {
        paint.strokeWidth = dp(1.2f)
        paint.color = Color.argb(200, 255, 70, 70)
        isClickable = true
        visibility = GONE
    }

    fun marks(points: List<Long>) {
        marks = points
        post {
            // 没有算到刷屏点时整条隐藏：绝不能留下一条常驻底色被误认成屏幕异常。
            visibility = if (points.isEmpty()) GONE else VISIBLE
            invalidate()
        }
    }

    fun attach(container: ViewGroup) {
        if (parent != null) return
        container.addView(this, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(18f).toInt(), android.view.Gravity.BOTTOM).apply {
            bottomMargin = dp(44f).toInt()
        })
        handler.post(tick)
    }

    fun detach() {
        handler.removeCallbacks(tick)
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (marks.isEmpty()) return
        val total = duration()
        if (total <= 0) return
        val width = width.toFloat()
        val top = dp(2f)
        val bottom = height - dp(2f)
        for (mark in marks) {
            if (mark <= 0 || mark >= total) continue
            val x = (mark.toDouble() / total * width).toFloat()
            if (x !in 0f..width) continue
            canvas.drawLine(x, top, x, bottom, paint)
        }
    }

    private fun nearest(x: Float): Long? {
        val total = duration(); if (total <= 0) return null
        val width = width.toFloat(); if (width <= 0f) return null
        var best: Long? = null; var distance = Float.MAX_VALUE
        for (mark in marks) {
            val target = (mark.toDouble() / total * width).toFloat()
            val d = abs(target - x)
            if (d < distance) { distance = d; best = mark }
        }
        return best?.takeIf { distance <= dp(14f) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val target = nearest(event.x) ?: return true
        val current = position()
        // 只在离当前位置足够远时跳转，避免误触回跳。
        if (abs(target - current) >= 15_000) seek(target)
        return true
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
