package io.github.offlineglass.hook.adapters.chatgpt

import android.graphics.*
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.ceil

/** Draw cached exterior shadows in the owning control's native page branch.
 * No ViewOverlay, view invalidation, hardware elevation or screen-space offsets. */
internal class ChatGptControlShadow(private val role: String) {
    private val node = RenderNode("ChatGPT-cached-exterior-shadow")
    private val path = Path()
    private fun newPlacement()=RenderNode("ChatGPT-shadow-placement-$role").apply {setClipToBounds(false)}
    private var placement = newPlacement()
    private val lastPlacement=FloatArray(9){Float.NaN}
    private val placementValues=FloatArray(9)
    private val relativeValues=FloatArray(16)
    private val relativeTransform=Matrix()
    private var lastAlpha=Float.NaN
    private var drawReported=false
    private var key = ""
    private var shadowDensity=Float.NaN
    private var consumer = WeakReference<Any>(null)
    private var host = WeakReference<Any>(null)
    private var pad = 0
    private var context: android.content.Context? = null
    fun update(anchor: View, owner: Any, contour: Path, width: Float, height: Float, density: Float, geometryKey: String) {
        context = anchor.context
        val ownerLayout=ChatGptBindings.layout(owner)
        val oldHost=host.get()
        val validHost=oldHost!=null&&ChatGptBindings.placed(oldHost)&&ownerLayout!=null&&ChatGptBindings.contains(oldHost,ownerLayout)
        if (consumer.get() !== owner || !validHost) {
            hide()
            // Old parent display lists can outlive their page. Never revive a
            // placement RenderNode already referenced by the old branch.
            placement=newPlacement()
            consumer = WeakReference(owner)
            var layout = ChatGptBindings.layout(owner)
            val own = ChatGptComposeBackground.screenBounds(owner,anchor)
            var selected = layout
            var done = false
            repeat(16) {
                if (done) return@repeat
                val current = layout ?: return@repeat
                val parent = ChatGptBindings.parent(current) ?: return@repeat
                selected = parent
                val coord = ChatGptBindings.read("chain.inner",ChatGptBindings.read("layout.chain",parent)!!)!!
                val rect = ChatGptComposeBackground.coordinateBounds(coord,anchor)
                if (own != null && rect != null && rect.height() >= own.height()+8f*density) done = true
                layout = parent
            }
            selected?.let { target ->
                host = WeakReference(target)
                hosts.getOrPut(target) { mutableListOf() }.add(WeakReference(this))
                context?.let {ChatGptDiagnostics.report(it,"shadowHost.$role","rebound; placed=${ChatGptBindings.placed(target)}")}
                // The host may already have a cached native display list. Make
                // its parent's next normal traversal include our exterior node.
                anchor.post {
                    if(host.get()===target&&ChatGptBindings.attached(owner)){
                        ChatGptBindings.parent(target)?.let {parent->
                            val coordinate=ChatGptBindings.read("chain.inner",ChatGptBindings.read("layout.chain",parent)!!)!!
                            ChatGptBindings.call("coordinate.invalidate",coordinate)
                        }
                        anchor.invalidate()
                    }
                }
            }
        }
        if (key == geometryKey && shadowDensity == density && node.hasDisplayList()) return
        key = geometryKey; shadowDensity = density
        path.set(contour)
        placement.discardDisplayList()
        val sigma = 6f*density
        pad = ceil(sigma*3f).toInt()
        val w = ceil(width).toInt()+pad*2
        val h = ceil(height).toInt()+pad*2
        node.setPosition(0,0,w,h)
        node.setRenderEffect(RenderEffect.createBlurEffect(sigma,sigma,Shader.TileMode.DECAL))
        val recording = node.beginRecording(w,h)
        try {
            recording.translate(pad.toFloat(),pad+2f*density)
            recording.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(56,0,0,0) })
        } finally { node.endRecording() }
    }
    fun syncPosition() {
        if (host.get() == null) return
        runCatching {
            val owner = consumer.get() ?: return
            val coord = ChatGptBindings.read("node.coordinate",owner) ?: return
            val layout = ChatGptBindings.layout(owner)
            if (ChatGptBindings.read("node.attached",owner) != true || layout == null || ChatGptBindings.read("layout.deactivated",layout) == true) {
                placement.setAlpha(0f)
                return
            }
            val parent = ChatGptBindings.parent(host.get()!!) ?: return
            val parentCoord = ChatGptBindings.read("chain.inner",ChatGptBindings.read("layout.chain",parent)!!) ?: return
            var current: Any? = coord
            var alpha = 1f
            repeat(32) {
                val c = current ?: return@repeat
                if (c === parentCoord) { current = null; return@repeat }
                ChatGptBindings.read("coordinate.layer",c)?.let { layer ->
                    alpha *= nativeAlpha(layer) ?: 0f
                }
                current = ChatGptBindings.read("coordinate.parent",c)
            }
            placement.setAlpha(alpha.coerceIn(0f,1f))
            val appliedAlpha=alpha.coerceIn(0f,1f)
            if(lastAlpha!=appliedAlpha){lastAlpha=appliedAlpha;context?.let {ChatGptDiagnostics.report(it,"shadowAlpha.$role",appliedAlpha.toString())}}
            ChatGptComposeBackground.relativeMatrix(coord,parentCoord,relativeTransform,relativeValues)
            val values=placementValues.also(relativeTransform::getValues)
            if (!values.contentEquals(lastPlacement)) {
                values.copyInto(lastPlacement)
                val sx = kotlin.math.sqrt(values[0]*values[0]+values[3]*values[3]).coerceAtLeast(.0001f)
                val sy = (values[0]*values[4]-values[1]*values[3])/sx
                placement.setPivotX(0f); placement.setPivotY(0f)
                placement.setTranslationX(values[2]); placement.setTranslationY(values[5])
                placement.setScaleX(sx); placement.setScaleY(sy)
                placement.setRotationZ((kotlin.math.atan2(values[3],values[0])*180f/kotlin.math.PI.toFloat()))
            }
        }
    }
    private var alphaVerified=false
    private fun nativeAlpha(layer:Any):Float? = runCatching {
        val graphics=ChatGptBindings.read("layer.graphics",layer)!!
        val renderer=ChatGptBindings.read("graphics.renderer",graphics)!!
        val native=ChatGptBindings.read("renderer.native",renderer) as RenderNode
        if(!alphaVerified){alphaVerified=true;context?.let {ChatGptDiagnostics.report(it,"shadowOpacity","verified RenderNode alpha chain")}}
        native.alpha
    }.onFailure {error->context?.let {ChatGptDiagnostics.report(it,"shadowOpacityFailure",error.toString())}}.getOrNull()
    private fun render(canvas: Canvas, layout: Any) {
        if (!node.hasDisplayList()) return
        if (!placement.hasDisplayList()) {
            val rect = RectF().also { path.computeBounds(it,true) }
            placement.setPosition(0,0,ceil(rect.width()).toInt(),ceil(rect.height()).toInt())
            val c = placement.beginRecording(ceil(rect.width()).toInt(),ceil(rect.height()).toInt())
            try {
                c.clipOutPath(path)
                c.translate(-pad.toFloat(),-pad.toFloat())
                c.drawRenderNode(node)
            } finally { placement.endRecording() }
        }
        syncPosition()
        canvas.drawRenderNode(placement)
        if(!drawReported){drawReported=true;context?.let {ChatGptDiagnostics.report(it,"shadowDraw.$role","recorded in current native branch")}}
    }
    fun hide() {
        placement.setAlpha(0f)
        host.get()?.let { target ->
            hosts[target]?.removeAll { it.get() == null || it.get() === this }
            if (hosts[target]?.isEmpty() == true) hosts.remove(target)
        }
        host.clear()
        lastPlacement.fill(Float.NaN)
        lastAlpha=Float.NaN
        drawReported=false
    }
    fun close() { hide(); node.discardDisplayList(); placement.discardDisplayList(); key = "" }
    companion object {
        private val hosts = WeakHashMap<Any,MutableList<WeakReference<ChatGptControlShadow>>>()
        fun drawForLayout(layout: Any, wrapper: Any) {
            val shadows = hosts[layout] ?: return
            val canvas = ChatGptBindings.read("canvas.native",wrapper) as? Canvas ?: return
            if (!canvas.isHardwareAccelerated) return
            var index=0
            while(index<shadows.size){
                val shadow=shadows[index++].get()?:continue
                runCatching { shadow.render(canvas,layout) }
            }
        }
    }
}
