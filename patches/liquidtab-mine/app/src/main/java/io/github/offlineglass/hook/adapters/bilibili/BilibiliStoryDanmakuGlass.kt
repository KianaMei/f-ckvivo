package io.github.offlineglass.hook.adapters.bilibili

import io.github.offlineglass.rendering.setFloatUniformExact

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Choreographer
import android.app.Activity
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.NativeBackdropBlur
import io.github.offlineglass.rendering.PostureGlass
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.HookConfigReader

/** Native capsule background only; video pixels come from its Surface, never the window. */
internal class BilibiliStoryDanmakuGlass(private val activity: Activity, private val root: ViewGroup) {
    private val skins = LinkedHashMap<View, Material>()
    private var surface: SurfaceView? = null
    private var texture: TextureView? = null
    private var videoLayer: View? = null
    private val posterLayers = ArrayList<View>()
    private val handler = Handler(Looper.getMainLooper())
    private val visible = Rect()
    private var framePosted = false
    private var active = false
    private var playerField: java.lang.reflect.Field? = null
    private var playerBindingAttempted = false
    private var playerState: java.lang.reflect.Method? = null
    private var lastPlayerState = -1
    private fun playing(): Boolean {
        val host = activity
        return runCatching {
            if (!playerBindingAttempted) {
                playerBindingAttempted = true
                playerField = generateSequence(host.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }.singleOrNull {
                    it.type.name == "com.bilibili.video.story.player.StoryPlayer" &&
                        it.type.methods.any { method -> method.name == "getState" &&
                            method.parameterTypes.isEmpty() && method.returnType == Int::class.javaPrimitiveType }
                }?.apply { isAccessible = true }
            }
            val player = playerField?.get(host) ?: return false
            val state = playerState ?: player.javaClass.getMethod("getState").also { playerState = it }
            lastPlayerState = (state.invoke(player) as? Number)?.toInt() ?: -1
            lastPlayerState == 4
        }.getOrDefault(false)
    }
    private fun visibleMaterial(): Material? = skins.values.filter {
        it.owner.isShown && it.owner.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() >= it.owner.height / 2
    }.maxByOrNull { it.owner.getGlobalVisibleRect(visible); visible.width().toLong() * visible.height() }
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            if (!active || !root.isAttachedToWindow || !config.enabled || config.solidBarEnabled) return
            val material = visibleMaterial() ?: return
            if (texture != null) {
                // Both outgoing and incoming controls can be visible during a
                // swipe. Each needs the current composite, not a retained frame.
                skins.values.forEach {
                    if (it.owner.isShown && it.owner.getGlobalVisibleRect(visible)) it.sample()
                }
            } else material.sample()
            if (playing()) scheduleFrame()
        }
    }
    private fun scheduleFrame() {
        if (framePosted || !active) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frame)
    }
    private val shadow = object : Drawable() {
        override fun draw(canvas: Canvas) {
            val host = shadowHost ?: return
            host.getLocationInWindow(shadowOrigin)
            val saved = canvas.save()
            skins.values.forEach { it.exclude(canvas, shadowOrigin) }
            skins.values.forEach { it.shadow(canvas, shadowOrigin) }
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Deprecated("Deprecated") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    private val pagerId = root.resources.getIdentifier("story_view_pager", "id", activity.packageName)
    private var shadowHost: View? = null
    private var shadowGeometry = Long.MIN_VALUE
    private val shadowOrigin = IntArray(2)
    private val shadowPosition = IntArray(2)
    fun scan(page: View, player: View?) {
        fun findTexture(view: View): TextureView? {
            if (view is TextureView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findTexture(view.getChildAt(i))?.let { return it }
            return null
        }
        fun findSurface(view: View): SurfaceView? {
            if (view is SurfaceView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
            return null
        }
        surface = player?.let(::findSurface)
        texture = player?.let(::findTexture)
        videoLayer = player
        posterLayers.clear()
        val id = root.resources.getIdentifier("story_ctrl_layer_danmaku", "id", root.context.packageName)
        fun visit(view: View) {
            if (view !== player && view.javaClass.name == "com.bilibili.video.story.view.StoryVideoFrameLayout") {
                val parent = view.parent as? ViewGroup
                val controllerId = root.resources.getIdentifier("video_controller", "id", activity.packageName)
                if (view.id == View.NO_ID && parent != null && (0 until parent.childCount).any {
                        parent.getChildAt(it).id == controllerId
                    }) posterLayers.add(view)
                return
            }
            if (view.id == id) {
                skins.getOrPut(view) { Material(view, view.background) }
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(page)
        skins.keys.toList().filter { !it.isAttachedToWindow }.forEach { skins.remove(it)?.release() }
        val host = page.findViewById<View>(pagerId)?.takeIf { it.parent === page } ?: page
        if (shadowHost !== host) {
            shadowHost?.overlay?.remove(shadow)
            shadowHost = host
            host.overlay.add(shadow)
            shadowGeometry = Long.MIN_VALUE
        }
    }
    fun update() {
        refreshConfig(root)
        if (!config.enabled) { restore(); return }
        val host = shadowHost ?: return
        shadow.setBounds(0, 0, host.width, host.height)
        host.getLocationInWindow(shadowOrigin)
        active = true
        var nextShadowGeometry = host.width.toLong() * 31 + host.height
        for ((view, material) in skins) {
            nextShadowGeometry = nextShadowGeometry * 31 + System.identityHashCode(view)
            nextShadowGeometry = nextShadowGeometry * 31 + if (view.isShown) 1 else 0
            if (!view.isShown) continue
            view.getLocationInWindow(shadowPosition)
            nextShadowGeometry = nextShadowGeometry * 31 + shadowPosition[0] - shadowOrigin[0]
            nextShadowGeometry = nextShadowGeometry * 31 + shadowPosition[1] - shadowOrigin[1]
            nextShadowGeometry = nextShadowGeometry * 31 + view.width
            nextShadowGeometry = nextShadowGeometry * 31 + view.height
            if (view.background !== material) view.background = material
        }
        // The overlay has its own cached display list. Moving native children
        // does not invalidate it, even while their glass is redrawn correctly.
        if (nextShadowGeometry != shadowGeometry) {
            shadowGeometry = nextShadowGeometry
            shadow.invalidateSelf()
        }
        val material = visibleMaterial()
        if (!config.solidBarEnabled && material != null && (playing() || material.needsSample())) scheduleFrame()
    }
    fun restore() {
        active = false
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frame)
        framePosted = false
        shadowGeometry = Long.MIN_VALUE
        skins.values.forEach { it.release() }; skins.clear()
        shadowHost?.overlay?.remove(shadow)
        shadowHost = null; surface = null; texture = null; videoLayer = null; posterLayers.clear()
    }
    private inner class Material(val owner: View, val original: Drawable?) : Drawable() {
        private val path = Path()
        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val node = RenderNode("Bilibili-story-danmaku-glass")
        private val shade = RenderNode("Bilibili-story-danmaku-shadow")
        private val lens = PostureGlass.shader(GlassHostLayout.ROUNDED_RECT_REFRACTION_SHADER)
        private var front: Bitmap? = null
        private var back: Bitmap? = null
        private var pending = false
        private var released = false
        private val position = IntArray(2)
        private val sourcePosition = IntArray(2)
        private var pad = 0
        private var geometry = ""
        private var frontX = 0f
        private var frontY = 0f
        private var frontScaleX = 1f
        private var frontScaleY = 1f
        private val sampling = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val sampleMatrix = Matrix()
        private var samplingShader: BitmapShader? = null
        private val sourceToWindow = Matrix()
        private val ownerToWindow = Matrix()
        private val windowToOwner = Matrix()
        private val localSourceClip = Rect()
        private var rim: Shader? = null
        private var sampledSurface: SurfaceView? = null
        private var sampledTexture: TextureView? = null
        private var sampledX = Int.MIN_VALUE
        private var sampledY = Int.MIN_VALUE
        private var reportAt = 0L
        private var copied = 0
        private var latency = 0L
        fun needsSample(): Boolean {
            owner.getLocationInWindow(position)
            return (if (texture != null) sampledTexture !== texture else front == null || sampledSurface !== surface) ||
                position[0] != sampledX || position[1] != sampledY
        }
        private fun shape() {
            rect.set(0f, 0f, owner.width.toFloat(), owner.height.toFloat())
            path.setBottomBarSquircle(rect, owner.height / 2f, -1f)
        }
        fun sample() {
            if (released) return
            texture?.takeIf { it.isAvailable }?.let {
                sampledTexture = it
                owner.getLocationInWindow(position)
                sampledX = position[0]; sampledY = position[1]
                invalidateSelf()
                return
            }
            val source = surface ?: return
            if (released || pending || !source.holder.surface.isValid || owner.width <= 0 || owner.height <= 0) return
            val dp = owner.resources.displayMetrics.density
            pad = NativeBackdropBlur.padding(minOf(config.blurRadius, 6f) * dp, 24f * dp).toInt()
            owner.getLocationInWindow(position); source.getLocationInWindow(sourcePosition)
            val crop = Rect(position[0] - sourcePosition[0] - pad, position[1] - sourcePosition[1] - pad,
                position[0] - sourcePosition[0] + owner.width + pad, position[1] - sourcePosition[1] + owner.height + pad)
            val desiredX = crop.left; val desiredY = crop.top
            if (!crop.intersect(0, 0, source.width, source.height)) return
            // Keep a separate write buffer; RenderThread may still read front.
            // The background is blurred anyway: half-resolution copy reduces
            // readback/upload area by four while retaining full-size optics.
            val copyWidth = (crop.width() + 1) / 2
            val copyHeight = (crop.height() + 1) / 2
            val target = back?.takeIf { it.width == copyWidth && it.height == copyHeight }
                ?: Bitmap.createBitmap(copyWidth, copyHeight, Bitmap.Config.ARGB_8888)
            pending = true
            val beganAt = SystemClock.uptimeMillis()
            val copyX = position[0]; val copyY = position[1]
            PixelCopy.request(source, crop, target, { result ->
                pending = false
                owner.getLocationInWindow(position)
                if (!released && texture == null && source === surface && visibleMaterial() === this &&
                    position[0] == copyX && position[1] == copyY && result == PixelCopy.SUCCESS) {
                    back = front; front = target
                    frontX = (crop.left - desiredX).toFloat(); frontY = (crop.top - desiredY).toFloat()
                    frontScaleX = crop.width().toFloat() / target.width
                    frontScaleY = crop.height().toFloat() / target.height
                    samplingShader = BitmapShader(target, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    sampledSurface = source; sampledX = copyX; sampledY = copyY
                    val now = SystemClock.uptimeMillis()
                    if (reportAt == 0L) reportAt = now
                    copied++; latency += now - beganAt
                    if (now - reportAt >= 3000L) {
                        val report = "state=$lastPlayerState copyFps=${copied * 1000L / (now - reportAt)} meanCopyMs=${latency / copied} roi=${target.width}x${target.height}"
                        android.util.Log.i("LiquidTabBiliGlass", report)
                        val context = activity.applicationContext
                        worker.execute { context.getExternalFilesDir(null)?.resolve("liquidtab-story-glass-performance.txt")?.writeText(report) }
                        reportAt = now; copied = 0; latency = 0L
                    }
                    invalidateSelf()
                }
            }, handler)
        }
        override fun draw(canvas: Canvas) {
            if (!canvas.isHardwareAccelerated || owner.width <= 0 || owner.height <= 0) return
            shape()
            val dp = owner.resources.displayMetrics.density
            pad = NativeBackdropBlur.padding(minOf(config.blurRadius, 6f) * dp, 24f * dp).toInt()
            val width = owner.width; val height = owner.height
            val w = width + pad * 2; val h = height + pad * 2
            PostureGlass.sync(lens, owner, config.postureEffectsEnabled && config.liquidGlassEnabled && !config.solidBarEnabled,
                config.liquidViscosity, config.flowHorizontalStrength, config.flowVerticalStrength)
            val key = "$w:$h:$pad:${config.hashCode()}"
            // Uses the shared optical shader and continuous native glass outline.
            if (geometry != key || PostureGlass.effectChanged(lens)) {
                geometry = key
                rim = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), intArrayOf(0x90FFFFFF.toInt(), 0x12FFFFFF, 0x48FFFFFF), null, Shader.TileMode.CLAMP)
                lens.setFloatUniform("size", width.toFloat(), height.toFloat())
                lens.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
                lens.setFloatUniform("cornerRadii", height / 2f, height / 2f, height / 2f, height / 2f)
                lens.setFloatUniform("safeSampleBounds", .5f, .5f, w - .5f, h - .5f)
                lens.setFloatUniform("refractionHeight", 12f * dp)
                lens.setFloatUniform("refractionAmount", -24f * dp)
                lens.setFloatUniform("depthEffect", 0f)
                lens.setFloatUniform("detachedGeometry", 0f, 0f, 0f)
                lens.setFloatUniformExact("flowingLiquidEnabled", 1f)
                lens.setFloatUniformExact("unifiedDropletPilot", 0f)
                lens.setFloatUniformExact("directionalRimPilot", 0f)
                lens.setFloatUniformExact("monotoneFlowPilot", 0f)
                lens.setFloatUniformExact("flowMotionEnabled", 1f)
                lens.setFloatUniform("continuousShape", 0f)
                val blur = NativeBackdropBlur.effect(minOf(config.blurRadius, 6f) * dp)
                node.setRenderEffect(if (config.liquidGlassEnabled && !config.solidBarEnabled)
                    RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), blur) else blur)
                shade.setPosition(0, 0, width + 160, height + 160)
                shade.setRenderEffect(RenderEffect.createBlurEffect(7f * dp, 7f * dp, Shader.TileMode.DECAL))
                val sc = shade.beginRecording(); sc.translate(80f, 80f)
                paint.color = 0x60000000; paint.style = Paint.Style.FILL; sc.drawPath(path, paint); shade.endRecording()
                shadow.invalidateSelf()
            }
            val liveTexture = texture?.takeIf { it.isAvailable && !config.solidBarEnabled }
            val liveLayer = videoLayer?.takeIf { liveTexture != null }
            if (liveLayer != null) {
                node.setPosition(0, 0, w, h)
                val recording = node.beginRecording()
                // A raw TextureView.draw() omits transforms/alpha/clips applied
                // by its native parent. Record only the verified video branch,
                // whose dispatchDraw preserves the real letterbox and renderer
                // layer ordering. Native story controls are sibling branches.
                recording.drawColor(Color.BLACK)
                ownerToWindow.reset()
                owner.transformMatrixToGlobal(ownerToWindow)
                if (ownerToWindow.invert(windowToOwner)) {
                    fun drawSource(source: View) {
                        if (!source.isShown || !source.getLocalVisibleRect(localSourceClip)) return
                        var opacity = source.alpha
                        var ancestor = source.parent as? View
                        while (ancestor != null && ancestor !== root) {
                            opacity *= ancestor.alpha
                            ancestor = ancestor.parent as? View
                        }
                        if (opacity <= 0f) return
                        sourceToWindow.reset()
                        source.transformMatrixToGlobal(sourceToWindow)
                        sourceToWindow.postConcat(windowToOwner)
                        sourceToWindow.postTranslate(pad.toFloat(), pad.toFloat())
                        val sourceSave = recording.save()
                        recording.concat(sourceToWindow)
                        recording.clipRect(localSourceClip)
                        val alphaSave = if (opacity < .999f) recording.saveLayerAlpha(
                            0f, 0f, source.width.toFloat(), source.height.toFloat(), (opacity * 255).toInt()) else -1
                        source.draw(recording)
                        if (alphaSave >= 0) recording.restoreToCount(alphaSave)
                        recording.restoreToCount(sourceSave)
                    }
                    drawSource(liveLayer)
                    // The visible page shows posters above the shared player
                    // until the new decoder frame arrives. Reproduce that same
                    // ordering, using only video branches (never controls).
                    posterLayers.forEach(::drawSource)
                }
                node.endRecording()
                val saved = canvas.save(); canvas.clipPath(path); canvas.translate(-pad.toFloat(), -pad.toFloat())
                canvas.drawRenderNode(node); canvas.restoreToCount(saved)
            } else front?.takeIf { !config.solidBarEnabled }?.let { bitmap ->
                node.setPosition(0, 0, w, h)
                val recording = node.beginRecording()
                sampleMatrix.setScale(frontScaleX, frontScaleY)
                sampleMatrix.postTranslate(frontX, frontY)
                samplingShader?.setLocalMatrix(sampleMatrix)
                sampling.shader = samplingShader
                recording.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sampling); node.endRecording()
                val saved = canvas.save(); canvas.clipPath(path); canvas.translate(-pad.toFloat(), -pad.toFloat())
                canvas.drawRenderNode(node); canvas.restoreToCount(saved)
            }
            paint.style = Paint.Style.FILL; paint.color = if (config.solidBarEnabled) 0xFF1C1C20.toInt() else 0x251C1C20; canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = .7f * dp
            paint.shader = rim
            canvas.drawPath(path, paint); paint.shader = null; paint.style = Paint.Style.FILL
        }
        fun exclude(canvas: Canvas, origin: IntArray) {
            if (!owner.isShown) return
            shape(); owner.getLocationInWindow(position)
            val clip = Path(path); clip.offset((position[0] - origin[0]).toFloat(), (position[1] - origin[1]).toFloat())
            canvas.clipOutPath(clip)
        }
        fun shadow(canvas: Canvas, origin: IntArray) {
            if (!owner.isShown || !shade.hasDisplayList()) return
            owner.getLocationInWindow(position)
            val saved = canvas.save(); canvas.translate((position[0] - origin[0] - 80).toFloat(), (position[1] - origin[1] - 80).toFloat())
            canvas.drawRenderNode(shade); canvas.restoreToCount(saved)
        }
        fun release() {
            released = true
            PostureGlass.track(owner, false)
            if (owner.background === this) owner.background = original
            node.discardDisplayList(); shade.discardDisplayList(); front = null; back = null
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Deprecated("Deprecated") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    companion object {
        @Volatile private var config = GlassConfig()
        private var readAt = 0L
        private val reading = java.util.concurrent.atomic.AtomicBoolean(false)
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "LiquidTab-BiliStoryConfig").apply { isDaemon = true }
        }
        private fun refreshConfig(root: View) {
            val now = SystemClock.uptimeMillis()
            if (now - readAt < 1000L || !reading.compareAndSet(false, true)) return
            readAt = now
            val context = root.context.applicationContext
            worker.execute {
                try { config = HookConfigReader.read(context, "tv.danmaku.bili").normalized() }
                finally { reading.set(false) }
            }
        }
    }
}
