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
import io.github.offlineglass.hook.adapters.AppVideoBackdrop
import io.github.offlineglass.hook.GlassHostLayout
import kotlin.math.max
import kotlin.math.roundToInt

/** Homepage compositor sampling, preserving the formal release's coverage and crop rules. */
internal class TaobaoHomeSurfaceSampler {
    @Volatile private var surface: SurfaceView? = null
    private val publicationLock = Any()
    @Volatile private var retryAfter = 0L
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
    private var currentHost: View? = null
    @Volatile private var allowed = false
    private var fliggy = false
    @Volatile private var frozen = false
    @Volatile private var unchangedSamples = 0
    @Volatile private var interactionUntil = 0L
    private var copyWake: Runnable? = null
    private var copyWakeAt = 0L
    val hasSurface: Boolean get() = surface != null

    fun snapshot(): AppVideoBackdrop? = frame?.takeUnless { it.bitmap.isRecycled }

    /** Stop our own readback pump without dropping a still-visible optical frame. */
    fun pause() {
        allowed=false
        copyWake?.let { currentHost?.removeCallbacks(it) }
        copyWake=null
    }

    fun noteInteraction() {
        interactionUntil = SystemClock.uptimeMillis() + 200L
        unchangedSamples = 0
        copyWake?.let { currentHost?.removeCallbacks(it) }
        copyWake = null
        requestCurrent()
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

    fun probe(host: View, home: Boolean, routeChanged: Boolean = false, page: ViewGroup? = null) {
        if (!home) {
            changeSurface(null)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!routeChanged && now - lastProbe < 120L) return
        lastProbe = now
        val root = page ?: host.rootView as? ViewGroup ?: return
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        val hostTop = hostLocation[1]
        val hostBottom = hostTop + host.height
        fun coverage(view: SurfaceView): Long {
            val rect = Rect()
            if (!view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0 ||
                !view.holder.surface.isValid || !view.getGlobalVisibleRect(rect) ||
                rect.width() < root.width * 0.6f || rect.height() < root.height * 0.35f ||
                rect.top > hostTop || rect.bottom < hostBottom) return 0L
            return rect.width().toLong() * rect.height()
        }
        surface?.let {
            var ancestor: View?=it
            var belongs=false
            repeat(32){if(ancestor===root)belongs=true;ancestor=ancestor?.parent as? View}
            if (belongs && coverage(it) > 0L) return
        }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        var best: SurfaceView? = null
        var bestArea = 0L
        while (stack.isNotEmpty() && visited++ < 1600) {
            val (view, depth) = stack.removeLast()
            if (view.visibility != View.VISIBLE) continue
            if (view is SurfaceView) {
                val area = coverage(view)
                if (area > bestArea) { best = view; bestArea = area }
            }
            if (view is ViewGroup && depth < 32) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        changeSurface(best)
    }

    private fun changeSurface(next: SurfaceView?) {
      synchronized(publicationLock) {
        if (surface === next) return@synchronized
        generation++
        surface = next
        inFlight = false
        lastCopy = 0L
        retryAfter = 0L
        unchangedSamples = 0
        copyWake?.let { currentHost?.removeCallbacks(it) }
        copyWake = null
        // A submitted RenderNode can still retain the old bitmap. Release our
        // references; never recycle a published frame from the UI thread.
        frame = null
        scratch = null
        currentHost?.postInvalidateOnAnimation()
      }
    }

    fun request(host: View, pageAllowed: Boolean, fliggyChannel: Boolean, exitFrozen: Boolean) {
        currentHost = host
        allowed = pageAllowed
        fliggy = fliggyChannel
        frozen = exitFrozen
        requestCurrent()
    }

    private fun requestCurrent() {
        val host = currentHost ?: return
        val glass = host as? GlassHostLayout
        if (glass!=null && (!glass.config.enabled || !glass.config.backdropCapture ||
            glass.config.solidBarEnabled || (!glass.config.liquidGlassEnabled && glass.config.blurRadius<=0f))) {
            pause()
            return
        }
        if (glass != null &&
            (glass.appNavigationState as? TaobaoNavigationState)?.scene?.flashSaleChannelActive == true &&
            glass.barVisibilityAnimating && !glass.barVisibilityTarget) return
        val source = surface ?: return
        if (!allowed || frozen || inFlight || !host.isAttachedToWindow || !host.isShown ||
            !host.hasWindowFocus() || host.windowVisibility != View.VISIBLE ||
            host.width <= 0 || host.height <= 0 || !source.holder.surface.isValid) return
        val now = SystemClock.uptimeMillis()
        if (now < retryAfter) { scheduleCopy(retryAfter - now); return }
        // A moving lens does not make the page behind it move. Re-reading an
        // identical page at the spring's rate blocks the same RenderThread.
        val moving = now < interactionUntil
        val stride = if (moving || unchangedSamples < 12) 1 else if (unchangedSamples < 36) 4 else 8
        val interval = if (stride == 1) 8L else
            (1000f / (host.display?.refreshRate?.takeIf { it > 0f } ?: 60f)).roundToInt().coerceAtLeast(8).toLong() * stride
        if (now - lastCopy < interval) { scheduleCopy(interval - (now - lastCopy)); return }
        lastCopy = now
        copyWake?.let(host::removeCallbacks)
        copyWake = null
        val sourceLocation = IntArray(2).also(source::getLocationInWindow)
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        val left = hostLocation[0] - sourceLocation[0]
        val top = hostLocation[1] - sourceLocation[1]
        val pad = (24f * host.resources.displayMetrics.density).roundToInt()
        val horizontalPad = minOf(pad, left.coerceAtLeast(0),
            (source.width - left - host.width).coerceAtLeast(0))
        val cartExtra = if ((glass?.appNavigationState as? TaobaoNavigationState)?.cartPageActive() == true)
            (48f * host.resources.displayMetrics.density).roundToInt() +
                io.github.offlineglass.hook.globalGlassBottomGapPx(host) / 2 else 0
        val topPad = minOf(pad + cartExtra, top.coerceAtLeast(0))
        val bottomPad = minOf(pad, (source.height - top - host.height).coerceAtLeast(0))
        val crop = Rect(left - horizontalPad, top - topPad,
            left + host.width + horizontalPad, top + host.height + bottomPad)
        if (crop.width() <= 0 || crop.height() <= 0 || crop.left < 0 || crop.top < 0 ||
            crop.right > source.width || crop.bottom > source.height) return
        val downscale = if (fliggy) 2.5f else 2f
        val width = max(1, (crop.width() / downscale).roundToInt())
        val height = max(1, (crop.height() / downscale).roundToInt())
        val target = scratch?.takeIf { !it.isRecycled && it.width == width && it.height == height }
            // A retired published bitmap may still be held by a RenderNode.
            // Dropping a mismatched buffer is safe; recycling it here is not.
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        scratch = null
        val copyHandler = glass?.appNavigationState?.opticalSurfaceCopyHandler() ?: handler
        if (copyHandler == null) {
            worker = HandlerThread("TaobaoHomePixelCopy").apply { start() }
            handler = Handler(worker!!.looper)
        }
        val token = generation
        inFlight = true
        runCatching {
            PixelCopy.request(source, crop, target, { result ->
                try {
                  var pixelsChanged = false
                  synchronized(publicationLock) {
                    if (token != generation) {
                        target.takeUnless(Bitmap::isRecycled)?.recycle()
                        return@request
                    }
                    if (result == PixelCopy.SUCCESS && allowed && !frozen &&
                        host.isAttachedToWindow && source.isAttachedToWindow && source === surface) {
                        val previous = frame
                        val changed = previous == null || previous.bitmap.isRecycled ||
                            previous.surfaceLeft != sourceLocation[0] + crop.left ||
                            previous.surfaceTop != sourceLocation[1] + crop.top ||
                            previous.surfaceWidth != crop.width() || previous.surfaceHeight != crop.height() ||
                            !previous.bitmap.sameAs(target)
                        pixelsChanged = changed
                        unchangedSamples = if (changed) 0 else (unchangedSamples + 1).coerceAtMost(1000)
                        if (changed) {
                            // Published frames remain referenced by the optical
                            // worker, cart UI RenderNodes and RenderThread. They
                            // must NEVER become PixelCopy destinations again.
                            // Reuse only a target that was never published.
                            scratch = null
                            frame = AppVideoBackdrop(target, sourceLocation[0] + crop.left,
                                sourceLocation[1] + crop.top, crop.width(), crop.height(), ++epoch)
                            android.os.Trace.beginSection("LiquidTab.Taobao.surfaceFrame")
                            android.os.Trace.endSection()
                        } else scratch = target
                        // Submit this frame immediately through the serial
                        // output queue, without another UI traversal/vsync.
                        if (changed && glass != null) {
                            if (glass.adapterNavigationAnimationRunning || glass.barVisibilityAnimating)
                                glass.postInvalidateOnAnimation()
                            else glass.queueOpticalSurfaceRender()
                        }
                    } else {
                        scratch = target
                    }
                    inFlight = false
                  }
                    // The bottom optical Surface is queued above immediately.
                    // Cart buttons must not wait behind a second main-queue hop
                    // before they even request their next UI frame.
                    if (token == generation && allowed && !frozen && pixelsChanged)
                        TaobaoCartNativeBridge.onBackdropFramePublished()
                    main.post {
                        if (token == generation && allowed && !frozen && (glass?.appNavigationState as? TaobaoNavigationState)?.cartPageActive() == true)
                            TaobaoCartNativeBridge.invalidateFrames(now, pixelsChanged)
                        if (token == generation && allowed && !frozen &&
                            host.isAttachedToWindow && source === surface)
                            // PixelCopy already completed asynchronously. Waiting
                            // for another vsync here can turn 120 Hz content into
                            // alternate-frame glass updates. requestCurrent owns
                            // the minimum interval and the unchanged-frame backoff.
                            requestCurrent()
                    }
                } catch (error: RuntimeException) {
                    synchronized(publicationLock) {
                        if (token == generation) {
                            inFlight = false
                            scratch = null
                            retryAfter = SystemClock.uptimeMillis() + 1_000L
                        }
                    }
                    android.util.Log.w("LiquidTab-Taobao", "Surface copy callback recovered", error)
                }
            }, copyHandler ?: handler!!)
        }.onFailure {
            synchronized(publicationLock) {
                if (token == generation) {
                    inFlight = false
                    scratch = target
                    retryAfter = SystemClock.uptimeMillis() + 1_000L
                }
            }
        }
    }

    fun dispose() {
        allowed = false
        changeSurface(null)
        // Invalidate an outstanding callback even if the surface was already cleared.
        synchronized(publicationLock) {
            generation++
            currentHost = null
        }
        worker?.quitSafely()
        worker = null
        handler = null
        lastProbe = 0L
    }
}
