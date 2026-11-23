package io.github.offlineglass.hook.adapters.bilibili_ambient

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.Configuration
import android.graphics.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.TextView
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Native render capability + Android lifecycle; no private player symbols or renderer replacement. */
internal class BilibiliVideoAmbientEngine(private val targetPackage: String, private val isEnabled: (io.github.offlineglass.config.GlassConfig) -> Boolean, private val diagnosticFile: String) {
    private val DETAILS = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
    private val sessions = WeakHashMap<Activity, Session>()
    private val foreground = WeakHashMap<Activity, Boolean>()

    fun install(loader: ClassLoader) {
        val layer = loader.loadClass("tv.danmaku.render.core.IVideoRenderLayer")
        check(layer.isInterface && layer.getMethod("getView").returnType == View::class.java &&
            layer.getMethod("getBounds").returnType == Rect::class.java &&
            layer.getMethod("getVideoWidth").returnType == Int::class.javaPrimitiveType &&
            layer.getMethod("getVideoHeight").returnType == Int::class.javaPrimitiveType)
        for (event in listOf("onPostResume", "onPause", "onDestroy")) {
            XposedBridge.hookAllMethods(Activity::class.java, event, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.packageName != targetPackage || activity.javaClass.name != DETAILS) return
                    when (event) {
                        "onPostResume" -> {
                            foreground[activity] = true
                            HookConfigReader.readFreshAsync(activity, activity.packageName) { config ->
                                if (foreground[activity] != true || activity.isDestroyed) return@readFreshAsync
                                if (config?.enabled == true && isEnabled(config)) {
                                    sessions.getOrPut(activity) { Session(activity, layer, diagnosticFile) }.resume()
                                } else sessions.remove(activity)?.dispose()
                            }
                        }
                        "onPause" -> { foreground[activity] = false; sessions[activity]?.pause() }
                        else -> { foreground.remove(activity); sessions.remove(activity)?.dispose() }
                    }
                }
            })
        }
    }

    private class Session(val activity: Activity, val layer: Class<*>, val diagnosticFile: String) : ViewTreeObserver.OnGlobalLayoutListener {
        private val decor = activity.window.decorView as ViewGroup
        private val main = Handler(Looper.getMainLooper())
        private val workerThread = HandlerThread("Bilibili-Ambient").apply { start() }
        private val worker = Handler(workerThread.looper)
        private val buffers = Array(2) { Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888) }
        private val pixels = IntArray(160 * 90)
        private val scratch = IntArray(pixels.size)
        private var source: View? = null
        private var title: TextView? = null
        private var generation = 0
        private var copying = false
        private var active = false
        private var disposed = false
        private var listening = false
        private var lastBind = -1000L
        private var failures = 0
        private var colorOwned = false
        private var originalColor = 0
        private var originalContrast = false
        private var diagnostic = ""
        private val bounds = Rect()
        private val renderBounds = layer.getMethod("getBounds").also { check(it.returnType == Rect::class.java) }
        private val surfaceWatcher = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = surfaceChanged(holder, 0, 0, 0)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                generation++; failures = 0; strip.clearFrames()
                if (active) { main.removeCallbacks(sample); main.postDelayed(sample, 250L) }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { generation++; strip.clearFrames() }
        }
        private val titleWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                generation++; failures = 0; strip.clearFrames()
                if (active) { main.removeCallbacks(sample); main.postDelayed(sample, 250L) }
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        private val strip = object : View(activity) {
            var current: Bitmap? = null
            var previous: Bitmap? = null
            var aspect = 16f/9f
            var previousAspect = aspect
            var mix = 1f
            var animation: ValueAnimator? = null
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val tone = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.65f) })
            val destination = RectF()
            init {
                isClickable = false; isFocusable = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            fun clearFrames() { animation?.cancel(); animation = null; current = null; previous = null; mix = 1f; invalidate() }
            fun present(bitmap: Bitmap, sourceAspect: Float) {
                animation?.cancel(); previous = current; previousAspect = aspect
                current = bitmap; aspect = sourceAspect
                mix = if (previous == null) 1f else 0f
                if (previous != null) animation = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1200L
                    addUpdateListener { mix = it.animatedValue as Float; invalidate() }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) { previous = null }
                    })
                    start()
                }
                invalidate()
            }
            override fun onDraw(canvas: Canvas) {
                val lightIcons = activity.window.insetsController?.systemBarsAppearance?.and(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                canvas.drawColor(if (lightIcons) 0xff202023.toInt() else Color.WHITE)
                // Match the source's aspect ratio, then crop the narrow status
                // viewport. Stretching both axes independently distorts faces.
                paint.colorFilter = tone
                fun image(bitmap: Bitmap?, alpha: Int, sourceAspect: Float) {
                    if (bitmap == null || bitmap.isRecycled) return
                    val drawnWidth = maxOf(width.toFloat(), height*sourceAspect)
                    val drawnHeight = drawnWidth/sourceAspect
                    destination.set((width-drawnWidth)/2f, (height-drawnHeight)/2f,
                        (width+drawnWidth)/2f, (height+drawnHeight)/2f)
                    paint.alpha = alpha; canvas.drawBitmap(bitmap, null, destination, paint)
                }
                image(previous, 255, previousAspect); image(current, (mix * 255).toInt(), aspect)
                canvas.drawColor(if (lightIcons) 0x66000000 else 0x99ffffff.toInt())
                paint.alpha = 255
            }
        }
        private val sample = Runnable { capture() }

        fun resume() {
            if (disposed || active) return
            active = true; generation++; failures = 0
            decor.overlay.add(strip)
            if (!listening) { decor.viewTreeObserver.addOnGlobalLayoutListener(this); listening = true }
            onGlobalLayout(); main.postDelayed(sample, 250L)
        }
        override fun onGlobalLayout() {
            if (!active) return
            val top = decor.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
            val normal = top > 0 && activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                !io.github.offlineglass.hook.adapters.bilibili.BilibiliDetailWindowMode.windowed(activity) &&
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0
            strip.visibility = if (normal) View.VISIBLE else View.GONE
            if (!normal) { releaseColor(); return }
            if (!colorOwned) {
                originalColor = activity.window.statusBarColor
                originalContrast = activity.window.isStatusBarContrastEnforced
                colorOwned = true
            }
            activity.window.statusBarColor = Color.TRANSPARENT
            activity.window.isStatusBarContrastEnforced = false
            if (strip.width != decor.width || strip.height != top) strip.layout(0, 0, decor.width, top)
            if (source?.let { it.isAttachedToWindow && it.isShown && layer.isInstance(it) } != true) bindSource()
        }
        private fun bindSource() {
            val now = SystemClock.uptimeMillis()
            if (now - lastBind < 500L) return
            lastBind = now
            val container = decor.findViewById<View>(activity.resources.getIdentifier("video_container", "id", activity.packageName)) ?: return
            val candidates = ArrayList<View>()
            fun walk(view: View) {
                if ((view is SurfaceView || view is TextureView) && view.isShown && view.getGlobalVisibleRect(bounds)) candidates += view
                else if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(container)
            val next = BilibiliAmbientSources.unique(candidates, layer)
            if (next !== source) {
                (source as? SurfaceView)?.holder?.removeCallback(surfaceWatcher)
                source = next; generation++; failures = 0; strip.clearFrames()
                (source as? SurfaceView)?.holder?.addCallback(surfaceWatcher)
                report(if (next == null) "source unavailable or ambiguous: candidates=${candidates.size}" else "bound native render surface ${next.javaClass.name}")
            }
            val nextTitle = decor.findViewById<TextView>(activity.resources.getIdentifier("title", "id", activity.packageName))
            if (title !== nextTitle) {
                title?.removeTextChangedListener(titleWatcher); title = nextTitle; title?.addTextChangedListener(titleWatcher)
            }
        }
        private fun capture() {
            if (!active || disposed) return
            onGlobalLayout()
            if (strip.visibility != View.VISIBLE || copying || failures >= 3) return schedule()
            val view = source ?: return schedule()
            if (!view.isAttachedToWindow || !view.isShown) return schedule()
            val bitmap = buffers.first { it !== strip.current }
            val token = generation
            val texture = (view as? TextureView)?.takeIf { it.isAvailable }?.surfaceTexture
            val owned = texture?.let { Surface(it) }
            val surface = (view as? SurfaceView)?.holder?.surface ?: owned
            if (surface?.isValid != true) { owned?.release(); return schedule() }
            // External-render Surface covers the page. Its native fitted video
            // rectangle excludes the unused buffer underneath the player.
            val crop = runCatching { Rect(renderBounds.invoke(view) as Rect) }.getOrNull()
            val frame = (view as? SurfaceView)?.holder?.surfaceFrame
            if (crop == null || crop.isEmpty || view.width <= 0 || view.height <= 0) {
                owned?.release(); report("native video bounds unavailable; color fallback"); return schedule()
            }
            if (frame != null && !frame.isEmpty) {
                crop.set((crop.left.toLong()*frame.width()/view.width).toInt(),
                    (crop.top.toLong()*frame.height()/view.height).toInt(),
                    (crop.right.toLong()*frame.width()/view.width).toInt(),
                    (crop.bottom.toLong()*frame.height()/view.height).toInt())
                if (!crop.intersect(0,0,frame.width(),frame.height())) { owned?.release(); return schedule() }
            }
            copying = true
            try {
                PixelCopy.request(surface, crop, bitmap, { result ->
                    val processed = result == PixelCopy.SUCCESS && runCatching {
                        bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90)
                        BilibiliAmbientBlur.blur(pixels, scratch, 160, 90, 10)
                        bitmap.setPixels(pixels, 0, 160, 0, 0, 160, 90)
                    }.isSuccess
                    owned?.release()
                    main.post {
                        copying = false
                        if (disposed) { free(); return@post }
                        val textureCurrent = view !is TextureView || view.surfaceTexture === texture
                        if (active && token == generation && source === view && textureCurrent) {
                            if (processed) {
                                failures = 0; strip.present(bitmap, crop.width().toFloat()/crop.height())
                                report("sample success strip=${strip.width}x${strip.height} source=${view.width}x${view.height} frame=$frame crop=$crop")
                            }
                            else { failures++; report("sample unavailable code=$result; fallback color") }
                        }
                        schedule()
                    }
                }, worker)
            } catch (error: Exception) {
                copying = false; owned?.release(); failures++
                XposedBridge.log("LiquidTab Bilibili ambient capture unavailable: ${error.javaClass.simpleName}")
                schedule()
            }
        }
        private fun schedule() { if (active && !disposed) { main.removeCallbacks(sample); main.postDelayed(sample, 4500L) } }
        private fun report(value: String) {
            if (diagnostic == value) return
            diagnostic = value
            runCatching { java.io.File(activity.getExternalFilesDir(null), diagnosticFile).writeText(value) }
        }
        private fun releaseColor() {
            if (!colorOwned) return
            if (activity.window.statusBarColor == Color.TRANSPARENT) activity.window.statusBarColor = originalColor
            activity.window.isStatusBarContrastEnforced = originalContrast
            colorOwned = false
        }
        fun pause() {
            active = false; generation++; main.removeCallbacks(sample)
            if (listening && decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalLayoutListener(this)
            listening = false; decor.overlay.remove(strip); strip.clearFrames(); releaseColor()
            title?.removeTextChangedListener(titleWatcher); title = null
            (source as? SurfaceView)?.holder?.removeCallback(surfaceWatcher); source = null
        }
        fun dispose() { pause(); disposed = true; if (!copying) free() }
        private fun free() { buffers.forEach { if (!it.isRecycled) it.recycle() }; workerThread.quitSafely() }
    }
}
