package io.github.offlineglass.hook.adapters.taobao

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppVideoBackdrop
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Samples an overlapping SurfaceView (e.g. Taobao Flash Sale UnicornSurfaceView,
 * or Cart before UnicornView converts to TextureView) via PixelCopy so the glass
 * bottom bar retains live refraction instead of seeing a transparent hole / white panel.
 */
internal class TaobaoSurfaceBackdropSampler {
    @Volatile private var surface: SurfaceView? = null
    private val lock = Any()
    @Volatile private var frame: AppVideoBackdrop? = null
    @Volatile private var scratch: Bitmap? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastProbe = 0L
    private var lastCopy = 0L
    @Volatile private var generation = 0
    @Volatile private var inFlight = false
    private var epoch = 0
    private var currentHost: GlassHostLayout? = null
    @Volatile private var allowed = false
    @Volatile private var unchangedSamples = 0
    @Volatile private var interactionUntil = 0L
    private var copyWake: Runnable? = null
    private var copyWakeAt = 0L
    private val hostLocation = IntArray(2)
    private val surfaceLocation = IntArray(2)
    private val probeRect = Rect()

    fun snapshot(): AppVideoBackdrop? = frame?.takeUnless { it.bitmap.isRecycled }

    fun hasActiveSurface(): Boolean = surface != null

    fun noteInteraction() {
        interactionUntil = SystemClock.uptimeMillis() + 300L
        unchangedSamples = 0
        copyWake?.let { currentHost?.removeCallbacks(it) }
        copyWake = null
        requestCurrent()
    }

    fun pause() {
        allowed = false
        copyWake?.let { currentHost?.removeCallbacks(it) }
        copyWake = null
    }

    fun probe(host: GlassHostLayout, routeChanged: Boolean, page: ViewGroup?) {
        val now = SystemClock.uptimeMillis()
        if (!routeChanged && now - lastProbe < 120L) return
        lastProbe = now
        val root = page ?: host.rootView as? ViewGroup ?: return
        if (!host.isAttachedToWindow || host.width <= 0 || host.height <= 0) {
            changeSurface(null)
            return
        }
        host.getLocationInWindow(hostLocation)
        val hostLeft = hostLocation[0]
        val hostTop = hostLocation[1]
        val hostRight = hostLeft + host.width
        val hostBottom = hostTop + host.height

        fun overlapArea(view: SurfaceView): Long {
            if (!view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0 ||
                !view.holder.surface.isValid || !view.getGlobalVisibleRect(probeRect)) return 0L
            // Require substantial overlap with the glass bar rectangle.
            val overlapW = (minOf(probeRect.right, hostRight) - maxOf(probeRect.left, hostLeft)).coerceAtLeast(0)
            val overlapH = (minOf(probeRect.bottom, hostBottom) - maxOf(probeRect.top, hostTop)).coerceAtLeast(0)
            if (overlapW < host.width * 0.4f || overlapH < host.height * 0.25f) return 0L
            return overlapW.toLong() * overlapH.toLong()
        }

        surface?.let { existing ->
            var ancestor: View? = existing
            var belongs = false
            repeat(32) {
                if (ancestor === root) belongs = true
                ancestor = ancestor?.parent as? View
            }
            if (belongs && overlapArea(existing) > 0L) return
        }

        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        var best: SurfaceView? = null
        var bestArea = 0L
        while (stack.isNotEmpty() && visited++ < 1600) {
            val (view, depth) = stack.removeLast()
            if (view === host || view.visibility != View.VISIBLE || !view.isShown) continue
            if (view is SurfaceView) {
                val area = overlapArea(view)
                if (area > bestArea) {
                    best = view
                    bestArea = area
                }
            }
            if (view is ViewGroup && depth < 32) {
                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)
            }
        }
        changeSurface(best)
    }

    private fun changeSurface(next: SurfaceView?) {
        synchronized(lock) {
            if (surface === next) return
            generation++
            surface = next
            inFlight = false
            lastCopy = 0L
            unchangedSamples = 0
            copyWake?.let { currentHost?.removeCallbacks(it) }
            copyWake = null
            frame = null
            scratch = null
            currentHost?.postInvalidateOnAnimation()
        }
    }

    fun request(host: GlassHostLayout, pageAllowed: Boolean) {
        currentHost = host
        allowed = pageAllowed
        if (surface != null) requestCurrent()
    }

    private fun scheduleCopy(delay: Long) {
        val host = currentHost ?: return
        val at = SystemClock.uptimeMillis() + delay
        if (copyWake != null && copyWakeAt <= at) return
        copyWake?.let(host::removeCallbacks)
        val token = generation
        copyWakeAt = at
        copyWake = Runnable {
            copyWake = null
            if (token == generation) requestCurrent()
        }.also { host.postDelayed(it, delay.coerceAtLeast(1L)) }
    }

    private fun requestCurrent() {
        val host = currentHost ?: return
        val cfg = host.config
        if (!cfg.enabled || !cfg.backdropCapture || cfg.solidBarEnabled ||
            (!cfg.liquidGlassEnabled && cfg.blurRadius <= 0f)) {
            pause()
            return
        }
        val source = surface ?: return
        if (!allowed || inFlight || !host.isAttachedToWindow || !host.isShown ||
            !host.hasWindowFocus() || host.windowVisibility != View.VISIBLE ||
            host.width <= 0 || host.height <= 0 || !source.holder.surface.isValid) return

        val now = SystemClock.uptimeMillis()
        val moving = now < interactionUntil
        val stride = if (moving || unchangedSamples < 12) 1 else if (unchangedSamples < 36) 4 else 8
        val interval = if (stride == 1) 12L else
            (1000f / (host.display?.refreshRate?.takeIf { it > 0f } ?: 60f)).roundToInt().coerceAtLeast(8).toLong() * stride
        if (now - lastCopy < interval) {
            scheduleCopy(interval - (now - lastCopy))
            return
        }
        lastCopy = now
        copyWake?.let(host::removeCallbacks)
        copyWake = null

        source.getLocationInWindow(surfaceLocation)
        host.getLocationInWindow(hostLocation)
        val left = hostLocation[0] - surfaceLocation[0]
        val top = hostLocation[1] - surfaceLocation[1]
        val pad = (24f * host.resources.displayMetrics.density).roundToInt()
        val cropLeft = (left - pad).coerceIn(0, source.width)
        val cropTop = (top - pad).coerceIn(0, source.height)
        val cropRight = (left + host.width + pad).coerceIn(cropLeft, source.width)
        val cropBottom = (top + host.height + pad).coerceIn(cropTop, source.height)
        val crop = Rect(cropLeft, cropTop, cropRight, cropBottom)
        if (crop.width() <= 0 || crop.height() <= 0) return

        val downscale = 2f
        val targetW = max(1, (crop.width() / downscale).roundToInt())
        val targetH = max(1, (crop.height() / downscale).roundToInt())
        val target = scratch?.takeIf { !it.isRecycled && it.width == targetW && it.height == targetH }
            ?: Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        scratch = null

        val copyHandler = synchronized(this) {
            handler ?: HandlerThread("Taobao-SurfaceCopy").let { thread ->
                thread.start()
                worker = thread
                Handler(thread.looper).also { handler = it }
            }
        }
        val token = generation
        val absLeft = surfaceLocation[0] + crop.left
        val absTop = surfaceLocation[1] + crop.top
        inFlight = true
        runCatching {
            PixelCopy.request(source, crop, target, { result ->
                var changed = false
                synchronized(lock) {
                    if (token != generation) {
                        target.takeUnless(Bitmap::isRecycled)?.recycle()
                        return@request
                    }
                    if (result == PixelCopy.SUCCESS && allowed && host.isAttachedToWindow &&
                        source.isAttachedToWindow && source === surface) {
                        val previous = frame
                        changed = previous == null || previous.bitmap.isRecycled ||
                            previous.surfaceLeft != absLeft || previous.surfaceTop != absTop ||
                            previous.surfaceWidth != crop.width() || previous.surfaceHeight != crop.height() ||
                            !previous.bitmap.sameAs(target)
                        unchangedSamples = if (changed) 0 else (unchangedSamples + 1).coerceAtMost(1000)
                        if (changed) {
                            scratch = null
                            frame = AppVideoBackdrop(target, absLeft, absTop, crop.width(), crop.height(), ++epoch)
                        } else {
                            scratch = target
                        }
                    } else {
                        scratch = target
                    }
                    inFlight = false
                }
                if (changed && token == generation) {
                    host.postInvalidateOnAnimation()
                }
                main.post {
                    if (token == generation && allowed && host.isAttachedToWindow && source === surface) {
                        requestCurrent()
                    }
                }
            }, copyHandler)
        }.onFailure {
            synchronized(lock) {
                inFlight = false
                scratch = target
            }
        }
    }

    fun dispose() {
        changeSurface(null)
        currentHost = null
        synchronized(this) {
            worker?.quitSafely()
            worker = null
            handler = null
        }
    }
}
