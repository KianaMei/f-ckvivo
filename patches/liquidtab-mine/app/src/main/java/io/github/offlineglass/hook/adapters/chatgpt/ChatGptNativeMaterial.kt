package io.github.offlineglass.hook.adapters.chatgpt

import io.github.offlineglass.rendering.setFloatUniformExact

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BlendMode
import android.graphics.RectF
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.view.View
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.rendering.NativeBackdropBlur
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.BloomTiltTracker
import io.github.offlineglass.rendering.KYANT_LENS_SHADER
import io.github.offlineglass.rendering.PostureGlass
import kotlin.math.ceil
import kotlin.math.min

/** Consumer-only display list. Its children are native Haze source layers, never the View root. */
internal class ChatGptNativeMaterial(private val anchor: View, private val surface: ChatGptSurface) {
    private val sample = RenderNode("LiquidTab-ChatGPT-$surface")
    private val lens: RuntimeShader? = runCatching {
        PostureGlass.shader(if (surface == ChatGptSurface.COMPOSER)
            ChatGptComposerOptics.source
            else "uniform float4 safeSampleBounds;\n" + KYANT_LENS_SHADER)
    }.getOrElse {
        ChatGptDiagnostics.report(anchor.context, "shaderError", it.toString())
        null
    }
    private val contour = ChatGptContour()
    private val independentShadow = ChatGptControlShadow(surface.name)

    private val highlight = RuntimeShader(GlassHostLayout.BLOOM_STROKE_DUAL_SHADER).apply {
        setFloatUniform("highlightAlpha",.25f);setColorUniform("strokeColor",Color.WHITE)
        setFloatUniform("strokeAlphaMul",if(surface==ChatGptSurface.COMPOSER)0f else .12f)
        setColorUniform("lightColor1",Color.WHITE);setFloatUniform("lightIntensity1",1f)
        setFloatUniform("lightDir2",0f,.19611613f,-.9805807f)
        setColorUniform("lightColor2",Color.WHITE);setFloatUniform("lightIntensity2",.4f)
    }
    private var highlightX=Float.NaN
    private var highlightY=Float.NaN
    private val lightingPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val continuous = Path()
    private var cornerKey = ""
    private var effectKey = ""
    private var opticsKey = ""
    private var recordingKey: Long? = null
    private val recordedScreen=Rect()
    private var recordedWidth=Float.NaN;private var recordedHeight=Float.NaN;private var recordedPad=-1
    private val reportedScreen=Rect();private var reportedShape:ChatGptComposeBackground.Shape?=null
    private var reportedConfig:GlassConfig?=null;private var reportedSources=-1
    private var sourceCount = 0
    private var geometryShape:ChatGptComposeBackground.Shape?=null
    private var geometryDensity=Float.NaN
    private var effectiveRadiiCache=FloatArray(4)
    private var geometryKey=""
    private var appliedRadius=Float.NaN;private var appliedPad=-1;private var appliedLiquid:Boolean?=null
    private var cachedComposerCorner = 0f
    private val insetMatrix = android.graphics.Matrix()

    fun draw(canvas: Canvas, consumer: Any, screen: Rect, shape: ChatGptComposeBackground.Shape): Boolean {
        if (!canvas.isHardwareAccelerated || screen.isEmpty) return false
        val config = normalizedConfig(anchor)
        if (!config.enabled) { suspendRendering(); return false }
        val density = anchor.resources.displayMetrics.density
        val radius = config.blurRadius * density
        val pad = ceil(NativeBackdropBlur.padding(radius, 24f * density)).toInt()
        val renderScale = if (surface == ChatGptSurface.COMPOSER) .75f else 1f
        val width = ceil((shape.width+2*pad)*renderScale).toInt()
        val height = ceil((shape.height+2*pad)*renderScale).toInt()
        val producerKey = ChatGptHazeBackdrop.recordingKey(consumer)
        if (producerKey == null || producerKey != recordingKey || recordedScreen != screen ||
            recordedWidth != shape.width || recordedHeight != shape.height || recordedPad != pad || !sample.hasDisplayList()) {
            sample.setPosition(0, 0, width, height)
            val recording = sample.beginRecording(width, height)
            try {
                recording.scale(renderScale,renderScale)
                sourceCount = ChatGptHazeBackdrop.draw(recording, anchor, consumer, screen, shape.width, shape.height, pad)
            } finally { sample.endRecording() }
            recordingKey = if (sourceCount > 0) producerKey else null
            recordedScreen.set(screen);recordedWidth=shape.width;recordedHeight=shape.height;recordedPad=pad
        }
        val count = sourceCount
        if(reportedShape!==shape||reportedConfig!==config||reportedSources!=count||reportedScreen!=screen){
        reportedShape=shape;reportedConfig=config;reportedSources=count;reportedScreen.set(screen)
        ChatGptDiagnostics.report(anchor.context, "material.$surface",
            "nativeSources=$count; nativeSize=${shape.width}x${shape.height}; screen=$screen; blurPx=$radius; liquid=${config.liquidGlassEnabled}; lens=${lens != null}; nativeBorderSkipped=${ChatGptComposeBackground.hasSkippedBorder(consumer)}; nativeShadowSkipped=${ChatGptComposeBackground.hasSkippedShadow(consumer)}")
        }
        if (count == 0 || (config.liquidGlassEnabled && lens == null)) return false
        // Composer may expose a generic Path outline, unlike header buttons.
        // Its cornerRadii array is then all zero; that is not a rectangular input.
        val geometryChanged=geometryShape!==shape||geometryDensity!=density
        if(geometryChanged){
        geometryShape=shape;geometryDensity=density
        val nativeCorner=shape.radii.maxOrNull()?:0f
        cachedComposerCorner = if (nativeCorner > 0f) nativeCorner else {
            val points = shape.path.approximate(.25f)
            var edgeStart = Float.POSITIVE_INFINITY
            var i = 0
            while (i + 2 < points.size) {
                val x = points[i+1]; val y = points[i+2]
                if (y <= .5f && x >= 0f && x <= shape.width/2f) edgeStart = min(edgeStart,x)
                i += 3
            }
            if (edgeStart.isFinite() && edgeStart > density) edgeStart
            else min(shape.height/2f, 26f*density)
        }
        effectiveRadiiCache=if(surface==ChatGptSurface.COMPOSER)FloatArray(4){cachedComposerCorner}else shape.radii
        geometryKey="${shape.width}/${shape.height}/${effectiveRadiiCache.joinToString()}"
        ChatGptDiagnostics.report(anchor.context,"geometry.$surface","nativeRadii=${shape.radii.joinToString()}; buttonRadii=${effectiveRadiiCache.joinToString()}")
        }
        val effectiveRadii=effectiveRadiiCache
        val key=geometryKey
        if (cornerKey != key) {
            cornerKey = key
            highlight.setFloatUniform("halfView",shape.width/2f,shape.height/2f)
            val corner = effectiveRadii.maxOrNull() ?: 0f
            if (surface == ChatGptSurface.SIDEBAR || kotlin.math.abs(shape.width - shape.height) <= 3f || corner <= 0f)
                continuous.set(shape.path)
            else contour.path(continuous, shape.width, shape.height, corner)
            lightingPath.set(continuous)
            val inset = density * .5f
            insetMatrix.setScale((shape.width-2*inset)/shape.width, (shape.height-2*inset)/shape.height,
                shape.width/2f, shape.height/2f)
            lightingPath.transform(insetMatrix)
        }
        if (surface == ChatGptSurface.COMPOSER || surface == ChatGptSurface.POPUP) shape.contentClip = continuous
        if (key != effectKey || radius != appliedRadius || pad != appliedPad || config.liquidGlassEnabled != appliedLiquid) {
        effectKey = key;appliedRadius=radius;appliedPad=pad;appliedLiquid=config.liquidGlassEnabled
        val blur = if (radius > 0f) NativeBackdropBlur.effect(radius*renderScale, 1.25f) else null
        val refraction = if (config.liquidGlassEnabled) lens?.let {
            if (surface == ChatGptSurface.COMPOSER) {
                if (opticsKey != key) {
                    val opticalPath = Path(continuous).apply {
                        transform(android.graphics.Matrix().apply { setScale(renderScale,renderScale) })
                    }
                    ChatGptComposerOptics.bind(it, opticalPath, (effectiveRadii.maxOrNull() ?: 0f)*renderScale)
                    opticsKey = key
                }
                it.setFloatUniform("detachedGeometry", 0f,0f,0f)
                it.setFloatUniformExact("unifiedDropletPilot", 0f)
                it.setFloatUniformExact("directionalRimPilot", 0f)
                it.setFloatUniformExact("monotoneFlowPilot", 0f)
            }
            it.setFloatUniform("size", shape.width*renderScale, shape.height*renderScale)
            it.setFloatUniform("offset", -pad*renderScale, -pad*renderScale)
            it.setFloatUniform("cornerRadii", effectiveRadii.map { radius -> radius*renderScale }.toFloatArray())
            it.setFloatUniform("safeSampleBounds", 0f, 0f, width.toFloat(), height.toFloat())
            it.setFloatUniform("refractionHeight", min(shape.height / 3f, 12f * density)*renderScale)
            it.setFloatUniform("refractionAmount", -24f * density*renderScale)
            it.setFloatUniform("depthEffect", 0f)
            RenderEffect.createRuntimeShaderEffect(it, "content")
        } else null
        sample.setRenderEffect(if (refraction != null && blur != null)
            RenderEffect.createChainEffect(refraction, blur) else refraction ?: blur)
        }
        lens?.let { PostureGlass.sync(it, anchor, config.postureEffectsEnabled, config.liquidViscosity,
            config.flowHorizontalStrength, config.flowVerticalStrength) }
        val dark = anchor.resources.configuration.uiMode and 0x30 == 0x20

        if (!dark) independentShadow.update(anchor,consumer,continuous,shape.width,shape.height,density,key)
        else independentShadow.hide()
        val base = if (dark) Color.rgb(32, 32, 34) else Color.WHITE
        // Native Compose may clip outside this node. Keep the soft halo inside
        // the common contour instead of drawing a shadow that gets cut off.
        paint.color = base
        canvas.drawPath(continuous, paint)
        val saved = canvas.save()
        try {
            canvas.clipPath(continuous)
            canvas.translate(-pad.toFloat(), -pad.toFloat())
            canvas.scale(1f/renderScale,1f/renderScale)
            canvas.drawRenderNode(sample)
        } finally { canvas.restoreToCount(saved) }
        // Only a configured translucent tint. Foreground stays in native drawContent.
        val alpha = ((if (dark) config.darkAlpha else config.lightAlpha) * 255).toInt().coerceIn(0, 255)
        paint.color = (base and 0x00ffffff) or (alpha shl 24)
        canvas.drawPath(continuous, paint)
        if (config.outlineEnabled) {
            // XhsDetailGlass.DetailBackground.drawHighlight, using the shared
            // two-light bloom shader and the exact XHS button contour.
            var gx = if (config.postureEffectsEnabled) BloomTiltTracker.gravityX else 0f
            var gy = if (config.postureEffectsEnabled) BloomTiltTracker.gravityY else 0f
            when (anchor.display?.rotation) {
                android.view.Surface.ROTATION_90 -> { val x = gx; gx = -gy; gy = x }
                android.view.Surface.ROTATION_180 -> { gx = -gx; gy = -gy }
                android.view.Surface.ROTATION_270 -> { val x = gx; gx = gy; gy = -x }
            }
            val length = kotlin.math.sqrt(gx * gx + gy * gy)
            if (length > .1f) { gx /= length; gy /= length } else { gx = 0f; gy = -1f }
            val c = .70710677f
            if(highlightX!=gx||highlightY!=gy){
                highlightX=gx;highlightY=gy
                highlight.setFloatUniform("lightDir1",c*gx+c*gy,-c*gx+c*gy,-.05f)
            }
            // Keep the complete one-pixel stroke inside the native drawing bounds.
            paint.alpha = 255
            paint.shader = highlight
            paint.blendMode = BlendMode.SCREEN
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            canvas.drawPath(lightingPath, paint)
            paint.shader = null
            paint.blendMode = BlendMode.SRC_OVER
            paint.style = Paint.Style.FILL
        }
        return true
    }
    fun syncShadowPosition() { independentShadow.syncPosition() }
    fun suspendRendering() {
        independentShadow.close()

        sample.discardDisplayList()
        recordingKey = null
        sourceCount = 0
    }
    fun close() { suspendRendering() }
    companion object {
        private var sourceConfig:GlassConfig?=null
        private var cachedConfig:GlassConfig?=null
        private fun normalizedConfig(anchor:View):GlassConfig {
            val current=HookConfigReader.read(anchor.context,"com.openai.chatgpt")
            if(sourceConfig!==current){sourceConfig=current;cachedConfig=current.normalized()}
            return cachedConfig!!
        }
    }
}
