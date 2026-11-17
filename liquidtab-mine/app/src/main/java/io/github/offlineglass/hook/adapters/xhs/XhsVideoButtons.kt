package io.github.offlineglass.hook.adapters.xhs

import io.github.offlineglass.rendering.setFloatUniformExact

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.content.res.ColorStateList
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.NativeBackdropBlur
import io.github.offlineglass.rendering.PostureGlass

/** Video-feed background views only. Native text, artwork and hit targets stay native. */
internal class XhsVideoButtons {
    private val skins = LinkedHashMap<View, Material>()
    private var scanAt = 0L
    private val backdrop = XhsVideoBackdrop {
        // Invalidate only the controls whose backdrop actually changed. Native
        // TextureView already schedules playback frames; do not pump DecorView.
        skins.values.forEach { it.refreshPixels() }
    }
    private val foregrounds = LinkedHashMap<View, WhiteForeground>()
    private val dividers = LinkedHashMap<View, Float>()
    private var overlayRoot: View? = null
    private var shadowState = Long.MIN_VALUE
    private val shadowPosition = IntArray(2)
    private val shadowOverlay = object : Drawable() {
        private val rootPosition = IntArray(2)
        override fun draw(canvas: Canvas) {
            val root = overlayRoot ?: return
            root.getLocationInWindow(rootPosition)
            val saved = canvas.save()
            // No button's shadow may darken either glass interior or its text.
            for (material in skins.values) material.excludeInterior(canvas, rootPosition)
            for (material in skins.values) material.drawShadow(canvas, rootPosition)
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    fun updateComment(root: View, button: View, source: View) {
        refreshConfig(root, SystemClock.uptimeMillis())
        if (!latestConfig.enabled) { restore(); return }
        if (overlayRoot !== root) {
            overlayRoot?.overlay?.remove(shadowOverlay)
            overlayRoot = root; root.overlay.add(shadowOverlay)
        }
        shadowOverlay.setBounds(0, 0, root.width, root.height)
        val material = skins.getOrPut(button) { Material(button, button.background) }
        if (latestConfig.solidBarEnabled) backdrop.release() else backdrop.bind(listOf(source))
        material.bind(listOf(source), latestConfig)
        if (button.background !== material) button.background = material
        shadowOverlay.invalidateSelf()
    }
    fun update(root: View, bars: List<View>, player: View?, visiblePlayers: List<View>) {
        val now = SystemClock.uptimeMillis()
        refreshConfig(root, now)
        val config = latestConfig
        if (player == null || !config.enabled) { restore(); return }
        if (overlayRoot !== root) {
            overlayRoot?.overlay?.remove(shadowOverlay)
            overlayRoot = root
            root.overlay.add(shadowOverlay)
        }
        shadowOverlay.setBounds(0, 0, root.width, root.height)
        if (scanAt == 0L || now - scanAt >= 500L) {
            scanAt = now
            val wanted = ArrayList<View>()
            for (bar in bars) {
                // Verified 9.51.0: InnerStreamBottomCommentBarView replaces the
                // background on this LinearLayout; the fullscreen icon is a
                // separate Lottie sibling over its square background View.
                bar.findViewById<View>(0x7f090b21)?.takeIf {
                    it.isShown && it.width > bar.width * .6f && it.height > 0
                }?.let { wanted.add(it) }
                bar.findViewById<View>(0x7f09560f)?.takeIf {
                    it.isShown && it.javaClass == View::class.java && it.height > 0 &&
                        kotlin.math.abs(it.width - it.height) <= 2
                }?.let { wanted.add(it) }
                // Only these native foreground branches, never the glass
                // background Views or video, are recolored.
                fun whiten(view: View) {
                    if (!view.isShown) return
                    if (view is TextView || view is ImageView)
                        foregrounds.getOrPut(view) { WhiteForeground(view) }
                    if (view is ViewGroup) for (i in 0 until view.childCount) whiten(view.getChildAt(i))
                }
                bar.findViewById<View>(0x7f090b23)?.let(::whiten)
                bar.findViewById<View>(0x7f0955cb)?.let(::whiten)
            }
            // The runtime tree identifies a separate 6px full-width strip
            // beneath the native seekbar. Preserve the seekbar and its listener.
            val item = (player.parent as? View)?.parent as? View
            val progress = item?.findViewById<ViewGroup>(0x7f094e8a)
            if (progress?.findViewById<View>(0x7f092f32)?.javaClass?.name ==
                "com.xingin.video.feed.videoitem.progress.VideoSeekBar") {
                for (i in 0 until progress.childCount) {
                    val child = progress.getChildAt(i)
                    if (child.javaClass == View::class.java && !child.isClickable &&
                        child.width >= progress.width * .95f && child.height in
                        1..(3f * root.resources.displayMetrics.density).toInt())
                        dividers.putIfAbsent(child, child.alpha)
                }
            }
            skins.keys.toList().filter { it !in wanted }.forEach { view -> skins.remove(view)?.release() }
            for (view in wanted) skins.getOrPut(view) { Material(view, view.background) }
        }
        if (config.solidBarEnabled || skins.isEmpty()) backdrop.release() else backdrop.bind(visiblePlayers)
        for ((view, material) in skins) {
            material.bind(visiblePlayers, config)
            if (view.background !== material) view.background = material
        }
        foregrounds.values.forEach { it.apply() }
        dividers.keys.forEach { if (it.alpha != 0f) it.alpha = 0f }
        var state = config.hashCode().toLong()
        for (view in skins.keys) {
            view.getLocationInWindow(shadowPosition)
            state = state * 31 + shadowPosition[0]
            state = state * 31 + shadowPosition[1]
            state = state * 31 + view.width * 31 + view.height
            state = state * 31 + PostureGlass.revision(view,
                config.postureEffectsEnabled && config.liquidGlassEnabled && !config.solidBarEnabled)
        }
        if (state != shadowState) { shadowState = state; shadowOverlay.invalidateSelf() }
    }
    fun restore() {
        skins.values.forEach { it.release() }
        skins.clear(); scanAt = 0L
        foregrounds.values.forEach { it.restore() }; foregrounds.clear()
        dividers.forEach { (view, alpha) -> view.alpha = alpha }; dividers.clear()
        overlayRoot?.overlay?.remove(shadowOverlay); overlayRoot = null
        shadowState = Long.MIN_VALUE
        backdrop.release()
    }
    private class WhiteForeground(private val view: View) {
        private val colors: ColorStateList? = (view as? TextView)?.textColors
        private val hints: ColorStateList? = (view as? TextView)?.hintTextColors
        private val originalFilter = (view as? ImageView)?.colorFilter
        private val originalTint = (view as? ImageView)?.imageTintList
        private val white = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        private val whiteTint = ColorStateList.valueOf(Color.WHITE)
        private val lottie = view.javaClass.name.contains("LottieAnimationView")
        private var callbackMethod: java.lang.reflect.Method? = null
        private var keyPath: Any? = null
        private var property: Any? = null
        private var callback: Any? = null
        private var lastDrawable: Drawable? = null
        private var lastComposition: Any? = null
        private val compositionGetter = if (lottie) runCatching { view.javaClass.getMethod("getComposition") }.getOrNull() else null
        private var tintAttempted = false
        private fun bindTint() {
            if (!lottie || tintAttempted || !XhsNativeBindings.ready) return
            tintAttempted = true
            runCatching {
                val filter = XhsNativeBindings.field("lottieFilter") ?: return@runCatching
                // The native XML color-filter call identifies the property;
                // KeyPath and ValueCallback are validated by their constructors.
                val method = view.javaClass.methods.single { method ->
                    val types = method.parameterTypes
                    types.size == 3 && types[1] == Any::class.java && method.returnType == Void.TYPE &&
                        types[0].constructors.any { it.parameterTypes.contentEquals(arrayOf(Array<String>::class.java)) } &&
                        types[2].constructors.any { it.parameterTypes.contentEquals(arrayOf(Any::class.java)) }
                }
                val keyType = method.parameterTypes[0]
                val valueType = method.parameterTypes[2]
                keyPath = keyType.getConstructor(Array<String>::class.java).newInstance(arrayOf("**") as Any)
                property = filter.get(null)
                callback = valueType.getConstructor(Any::class.java).newInstance(white)
                callbackMethod = method
            }.onFailure { android.util.Log.w("LiquidTabInject", "XHS fullscreen stroke tint unavailable", it) }
        }
        fun apply() {
            bindTint()
            // Native alpha controls send/input transitions; tint must never reveal hidden actions.
            if (view is TextView) {
                if (view.currentTextColor != Color.WHITE) view.setTextColor(Color.WHITE)
                if (view.currentHintTextColor != Color.WHITE) view.setHintTextColor(Color.WHITE)
            }
            if (view is ImageView) {
                if (lottie) {
                    if (callbackMethod == null) return
                    val composition = runCatching { compositionGetter?.invoke(view) }.getOrNull()
                    if (view.drawable !== lastDrawable || composition !== lastComposition) {
                        runCatching { callbackMethod!!.invoke(view, keyPath, property, callback) }
                        lastDrawable = view.drawable; lastComposition = composition
                    }
                } else {
                    if (view.imageTintList !== whiteTint) view.imageTintList = whiteTint
                    if (view.colorFilter !== white) view.colorFilter = white
                }
            }
        }
        fun restore() {
            if (view is TextView) { colors?.let(view::setTextColor); hints?.let(view::setHintTextColor) }
            if (view is ImageView) {
                if (lottie && callbackMethod != null) runCatching {
                    callbackMethod!!.invoke(view, keyPath, property, null)
                } else { view.imageTintList = originalTint; view.colorFilter = originalFilter }
            }
        }
    }
    private inner class Material(private val owner: View, private val original: Drawable?) : Drawable() {
        private var sources: List<View> = emptyList()
        private var sourceState = Long.MIN_VALUE
        private var config = GlassConfig()
        fun refreshPixels() { if (owner.isShown && !config.solidBarEnabled) invalidateSelf() }
        fun bind(players: List<View>, value: GlassConfig) {
            var state = 0L
            for (player in players) {
                player.getLocationInWindow(sourcePosition)
                state = state * 31 + System.identityHashCode(player)
                state = state * 31 + sourcePosition[0]
                state = state * 31 + sourcePosition[1]
                state = state * 31 + player.width * 31 + player.height
            }
            val changed = state != sourceState || config != value
            sources = players
            sourceState = state
            config = value
            // If a target update removes/replaces the native listener, retain
            // ordinary native-traversal refresh without adding a polling loop.
            if (changed || (!backdrop.observesFrames && backdrop.nativeContentDirty)) invalidateSelf()
        }
        private val node = RenderNode("XHS-video-button")
        private val lens = PostureGlass.shader(GlassHostLayout.ROUNDED_RECT_REFRACTION_SHADER)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadowNode = RenderNode("XHS-video-button-independent-shadow")
        private var shadowKey = ""
        private val path = Path()
        private val rect = RectF()
        private val sourcePosition = IntArray(2)
        private val ownerPosition = IntArray(2)
        private var effectKey = ""
        private var rim: Shader? = null
        override fun draw(canvas: Canvas) {
            if (!canvas.isHardwareAccelerated || sources.isEmpty()) return
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            if (width <= 0 || height <= 0) return
            val dp = owner.resources.displayMetrics.density
            val blur = minOf(config.blurRadius, 8f) * dp
            val pad = NativeBackdropBlur.padding(blur, 24f * dp).toInt()
            val w = bounds.width() + pad * 2
            val h = bounds.height() + pad * 2
            val radius = minOf(width, height) / 2f
            rect.set(0f, 0f, width, height)
            // Canonical inward continuous corners, independent of old per-app
            // round-rect settings. No layout or hit-region changes.
            path.setBottomBarSquircle(rect, radius, -1f)
            val dynamic = config.postureEffectsEnabled && config.liquidGlassEnabled && !config.solidBarEnabled
            PostureGlass.sync(lens, owner, dynamic, config.liquidViscosity,
                config.flowHorizontalStrength, config.flowVerticalStrength)
            owner.getLocationInWindow(ownerPosition)
            val root = owner.rootView
            root.getLocationInWindow(sourcePosition)
            val sx = sourcePosition[0] - ownerPosition[0] + pad
            val sy = sourcePosition[1] - ownerPosition[1] + pad
            val key = "$w:$h:$blur:${config.liquidGlassEnabled}:${config.solidBarEnabled}:$sx:$sy:${root.width}:${root.height}"
            val poseChanged = PostureGlass.effectChanged(lens)
            if (key != effectKey || poseChanged) {
                effectKey = key
                rim = LinearGradient(0f, 0f, width, height,
                    intArrayOf(0x90FFFFFF.toInt(), 0x12FFFFFF, 0x48FFFFFF), null, Shader.TileMode.CLAMP)
                lens.setFloatUniform("size", width, height)
                lens.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
                lens.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                lens.setFloatUniform("safeSampleBounds", maxOf(.5f, sx + .5f), maxOf(.5f, sy + .5f),
                    minOf(w - .5f, sx + root.width - .5f), minOf(h - .5f, sy + root.height - .5f))
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
                val background = NativeBackdropBlur.effect(blur)
                node.setRenderEffect(if (config.liquidGlassEnabled && !config.solidBarEnabled)
                    RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), background)
                    else background)
            }
            if (!config.solidBarEnabled) {
                node.setPosition(0, 0, w, h)
                val recording = node.beginRecording(w, h)
                try {
                    recording.drawColor(Color.BLACK)
                    // Composite every visible player in screen coordinates.
                    // During a swipe both outgoing/incoming layers belong to
                    // the backdrop. Never capture the page/control ancestor.
                    recording.translate(sx.toFloat(), sy.toFloat())
                    backdrop.draw(recording, root)
                } finally { node.endRecording() }
                val save = canvas.save()
                canvas.clipPath(path)
                canvas.translate(-pad.toFloat(), -pad.toFloat())
                canvas.drawRenderNode(node)
                canvas.restoreToCount(save)
            }
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = if (config.solidBarEnabled) Color.rgb(30, 30, 34) else Color.argb(32, 28, 28, 32)
            canvas.drawPath(path, paint)
            if (config.outlineEnabled) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = .8f * dp
                paint.shader = rim
                paint.blendMode = BlendMode.SCREEN
                canvas.drawPath(path, paint)
                paint.shader = null; paint.blendMode = BlendMode.SRC_OVER
            }
        }
        fun excludeInterior(canvas: Canvas, rootPosition: IntArray) {
            if (!owner.isShown || path.isEmpty) return
            owner.getLocationInWindow(ownerPosition)
            val shape = Path(path)
            shape.offset((ownerPosition[0] - rootPosition[0]).toFloat(),
                (ownerPosition[1] - rootPosition[1]).toFloat())
            canvas.clipOutPath(shape)
        }
        fun drawShadow(canvas: Canvas, rootPosition: IntArray) {
            if (!owner.isShown || path.isEmpty || !canvas.isHardwareAccelerated) return
            val dp = owner.resources.displayMetrics.density
            val pad = kotlin.math.ceil(48f * dp).toInt()
            val w = owner.width + pad * 2
            val h = owner.height + pad * 2
            val key = "$w:$h"
            if (shadowKey != key || !shadowNode.hasDisplayList()) {
                shadowKey = key
                shadowNode.setPosition(0, 0, w, h)
                shadowNode.setRenderEffect(RenderEffect.createBlurEffect(8f * dp, 8f * dp, Shader.TileMode.DECAL))
                val recording = shadowNode.beginRecording(w, h)
                try {
                    recording.translate(pad.toFloat(), pad.toFloat())
                    shadow.color = Color.argb(88, 0, 0, 0)
                    recording.drawPath(path, shadow)
                } finally { shadowNode.endRecording() }
            }
            owner.getLocationInWindow(ownerPosition)
            val save = canvas.save()
            canvas.translate((ownerPosition[0] - rootPosition[0]).toFloat(),
                (ownerPosition[1] - rootPosition[1]).toFloat())
            PostureGlass.shadow(canvas, owner, rect, pad.toFloat()) {
                val nodeSave = canvas.save()
                canvas.translate(-pad.toFloat(), -pad.toFloat())
                canvas.drawRenderNode(shadowNode)
                canvas.restoreToCount(nodeSave)
            }
            canvas.restoreToCount(save)
        }
        fun release() {
            PostureGlass.track(owner, false)
            if (owner.background === this) owner.background = original
            node.discardDisplayList()
            shadowNode.discardDisplayList()
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    companion object {
        fun needsVideoPixels() = latestConfig.enabled && !latestConfig.solidBarEnabled
        @Volatile private var latestConfig = GlassConfig()
        private val reading = java.util.concurrent.atomic.AtomicBoolean(false)
        private var readAt = 0L
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "LiquidTab-XhsVideoConfig").apply { isDaemon = true }
        }
        private fun refreshConfig(root: View, now: Long) {
            if (now - readAt < 600L || !reading.compareAndSet(false, true)) return
            readAt = now
            val context = root.context.applicationContext
            worker.execute {
                try { latestConfig = HookConfigReader.read(context, "com.xingin.xhs").normalized() }
                finally { reading.set(false) }
            }
        }
    }
}
