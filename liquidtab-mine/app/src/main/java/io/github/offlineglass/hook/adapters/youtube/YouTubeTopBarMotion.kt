package io.github.offlineglass.hook.adapters.youtube

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator

/** Mirrors the shared bottom-bar transition, with upward exit/downward entry. */
internal class YouTubeTopBarMotion(private val view: View, private val onFrame: () -> Unit) {
    var rendering = false
        private set
    private var initialized = false
    private var target = false
    private var generation = 0
    private var animator: ValueAnimator? = null
    private val exit = PathInterpolator(.4f, 0f, 1f, 1f)
    private val enter = PathInterpolator(.16f, .82f, .24f, 1f)
    private val settle = PathInterpolator(.22f, 0f, .18f, 1f)

    fun update(show: Boolean, distance: Float, density: Float, animate: Boolean) {
        if (!initialized || !animate) {
            initialized = true; target = show
            generation++; animator?.cancel(); animator = null
            rendering = show
            view.alpha = if (show) 1f else 0f
            view.translationY = if (show) 0f else -distance
            view.scaleX = 1f; view.scaleY = 1f
            view.visibility = if (show) View.VISIBLE else View.INVISIBLE
            return
        }
        if (show == target) return
        target = show
        val current = ++generation
        animator?.cancel(); animator = null
        rendering = true; view.visibility = View.VISIBLE
        fun phase(alpha: Float, scaleX: Float, scaleY: Float, y: Float, duration: Long,
                  interpolation: PathInterpolator, end: () -> Unit) {
            val fromAlpha = view.alpha; val fromX = view.scaleX; val fromY = view.scaleY
            val fromTranslation = view.translationY
            val next = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration; interpolator = interpolation
                addUpdateListener {
                    if (generation != current) return@addUpdateListener
                    val fraction = it.animatedValue as Float
                    view.alpha = fromAlpha + (alpha - fromAlpha) * fraction
                    view.scaleX = fromX + (scaleX - fromX) * fraction
                    view.scaleY = fromY + (scaleY - fromY) * fraction
                    view.translationY = fromTranslation + (y - fromTranslation) * fraction
                    onFrame()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (generation == current) { animator = null; end() }
                    }
                })
            }
            animator = next; next.start()
        }
        if (show) {
            phase(1f, 1.018f, 1.01f, density, 300L, enter) {
                phase(1f, 1f, 1f, 0f, 300L, settle) { onFrame() }
            }
        } else {
            phase(0f, 1f, 1f, -distance, 220L, exit) {
                rendering = false; view.visibility = View.INVISIBLE; onFrame()
            }
        }
    }

    fun dispose() {
        generation++; animator?.cancel(); animator = null
        rendering = false; target = false; initialized = false
        view.visibility = View.INVISIBLE
    }
}
