package io.github.offlineglass.hook.adapters.x

import android.graphics.Canvas
import android.graphics.RenderNode
import java.lang.reflect.Field

/** Reuse X's selected Haze sources. Never redraw or intercept recording of the page. */
internal class XReplyPageSource(private val bridge: XComposeBridge) {
    private val selection=requireNotNull(bridge.bindings.fields["hazeAreas"])
    private val recording=requireNotNull(bridge.bindings.fields["hazeAreaRecording"])
    private val layer=requireNotNull(bridge.bindings.methods["hazeAreaLayer"])
    private val areaType=layer.declaringClass
    private val coordinates=bridge.fields(areaType).single { it.type==bridge.coordinateType }
    private val nativePaths=mutableMapOf<Class<*>,List<Field>?>()
    var diagnostic="awaiting native Haze areas"
        private set
    init {
        require(selection.declaringClass==bridge.drawMethod.declaringClass && selection.type==List::class.java)
        require(recording.declaringClass==areaType && recording.type==Boolean::class.javaPrimitiveType)
        require(layer.parameterCount==0 && layer.returnType.name.startsWith("androidx.compose.ui.graphics.layer."))
    }

    private fun nativeNode(graphicsLayer: Any): RenderNode? {
        val path=nativePaths.getOrPut(graphicsLayer.javaClass) {
            val found=mutableListOf<List<Field>>()
            // GraphicsLayer -> platform implementation -> hardware RenderNode.
            for(backend in bridge.fields(graphicsLayer.javaClass)) {
                if(!backend.type.name.startsWith("androidx.compose.ui.graphics.layer.")) continue
                val value=backend.get(graphicsLayer) ?: continue
                for(node in bridge.fields(value.javaClass)) if(node.type==RenderNode::class.java) found+=listOf(backend,node)
            }
            found.singleOrNull()
        } ?: return null
        val backend=path[0].get(graphicsLayer) ?: return null
        return path[1].get(backend) as? RenderNode
    }
    private data class Input(val node: RenderNode,val coordinate: Any)
    private fun inputs(consumer: Any): List<Input>? {
        if(!selection.declaringClass.isInstance(consumer)) return null
        val selected=selection.get(consumer) as? List<*> ?: return null
        if(selected.isEmpty() || selected.size>32) return null
        return selected.map { area ->
            if(area==null || !areaType.isInstance(area) || recording.getBoolean(area)) return null
            val position=coordinates.get(area) ?: return null
            if(bridge.bounds(position)==null) return null
            val graphicsLayer=layer.invoke(area) ?: return null
            val node=nativeNode(graphicsLayer) ?: return null
            if(!node.hasDisplayList()) return null
            Input(node,position)
        }
    }
    fun bind(consumer: Any,coordinate: Any): Boolean {
        val count=inputs(consumer)?.size ?: 0
        diagnostic=if(count>0) "native Haze source layers=$count" else "native Haze source not ready"
        return count>0
    }
    fun draw(canvas: Canvas,consumer: Any,coordinate: Any,padding: Float): Boolean {
        if(!canvas.isHardwareAccelerated) return false
        // Validate all inputs before drawing. Never show a partially valid source.
        val inputs=inputs(consumer) ?: return false
        val transforms=inputs.map { bridge.transform(it.coordinate,coordinate) }
        inputs.forEachIndexed { index,input ->
            val save=canvas.save()
            try {
                canvas.translate(padding,padding)
                canvas.concat(transforms[index])
                canvas.drawRenderNode(input.node)
            } finally { canvas.restoreToCount(save) }
        }
        diagnostic="native Haze source layers=${inputs.size}"
        return true
    }
    fun clear() { nativePaths.clear() }
}
