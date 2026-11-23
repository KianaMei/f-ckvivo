package io.github.offlineglass.hook.adapters.bilibili

import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.home_glass.ComposeSemanticsReader
import java.lang.reflect.Modifier

internal class BilibiliComposeBottomArtwork(private val source: ViewGroup) {
    private val reader = ComposeSemanticsReader(source.javaClass.classLoader)
    private val access = runCatching { Access(source.javaClass.classLoader) }.getOrNull()
    private var slots: List<Slot> = emptyList()
    private var owner: View? = null
    private var needsRefresh = true

    fun needsBinding() = access != null && (needsRefresh || slots.isEmpty() || owner?.isAttachedToWindow != true)

    fun consumeInvalidation(): Boolean {
        val changed = owner?.let(BilibiliBottomArtwork::invalidateFrom) == true
        if (changed) needsRefresh = true
        return changed
    }

    fun bind(): Boolean {
        val bridge = access ?: return false
        val next = runCatching { bindSlots(bridge) }.getOrNull() ?: return false
        val changed = next != slots
        slots = next
        needsRefresh = false
        return changed
    }

    fun slot(index: Int): Slot? = slots.getOrNull(index)?.takeIf { owner?.isAttachedToWindow == true }

    private fun bindSlots(bridge: Access): List<Slot>? {
        reader.clearDiagnostic()
        val nodes = reader.nodes(source)
        owner = nodes.firstOrNull()?.source
        val nodesByTarget = nodes.mapNotNull { node -> node.clickTarget()?.let { it to node } }
            .groupBy({ it.first }, { it.second })
        val rows = nodesByTarget.keys.mapNotNull { target ->
            val layout = bridge.layout(target.nativeNode) ?: return@mapNotNull null
            val parent = bridge.parent(layout) ?: return@mapNotNull null
            Cell(target, layout, parent)
        }.groupBy { it.parent }
        val cells = rows.values.singleOrNull { it.size == BilibiliBottomArtwork.TAB_COUNT }
            ?.sortedBy { it.target.bounds.centerX() } ?: return null
        val tabBounds = cells.map { it.target.bounds }
        val height = source.height.toFloat()
        if (tabBounds.any { it.isEmpty || it.left < -1f || it.right > source.width + 1f ||
                it.top < -1f || it.bottom > height + 1f } ||
            tabBounds.zipWithNext().any { (left, right) -> left.right > right.left + 1f }) return null
        return cells.mapIndexed { index, cell ->
            val related = nodesByTarget.getValue(cell.target)
            if (related.any { node ->
                    val textSlot = BilibiliComposeTabContract.slot(node.text)
                    val descriptionSlot = BilibiliComposeTabContract.slot(node.description)
                    (textSlot >= 0 && textSlot != index) || (descriptionSlot >= 0 && descriptionSlot != index)
                }) return null
            val bounds = tabBounds[index]
            val label = related.filter { BilibiliComposeTabContract.slot(it.text) == index && it !== cell.target }
                .filter { it.bounds.top - bounds.top in (bounds.height() / 2f)..<bounds.height() }
                .minByOrNull { it.bounds.top }
            val position = bridge.positionInParent(cell.layout) ?: return null
            val crop = RectF(bounds).apply { if (label != null) bottom = label.bounds.top }
            val measured = runCatching {
                bridge.artworkBounds(cell.layout, bounds, label?.nativeNode)
            }.getOrNull()
            if (measured != null && measured.intersect(crop)) crop.set(measured)
            crop.offset(-bounds.left, -bounds.top)
            Slot(bridge, cell.layout, crop, label?.text, position)
        }
    }

    private data class Cell(val target: ComposeSemanticsReader.Node, val layout: Any, val parent: Any)

    internal data class Slot(val access: Access, val layout: Any, val bounds: RectF,
                             val label: String?, val position: Long) {
        fun draw(canvas: Canvas): Boolean = runCatching {
            if (!access.attached(layout)) return false
            val offsetX = Float.fromBits((position shr 32).toInt())
            val offsetY = Float.fromBits(position.toInt())
            canvas.translate(-offsetX, -offsetY)
            access.draw(layout, canvas)
            true
        }.getOrDefault(false)
    }

    internal class Access(private val loader: ClassLoader?) {
        private fun load(name: String) = Class.forName(name, false, loader)

        private val layoutType = load("androidx.compose.ui.node.LayoutNode")
        private val coordinatesType = load("androidx.compose.ui.layout.LayoutCoordinates")
        private val canvasType = load("androidx.compose.ui.graphics.Canvas")
        private val layerType = load("androidx.compose.ui.graphics.layer.GraphicsLayer")
        private val nodeLayoutFields = HashMap<Class<*>, java.lang.reflect.Field?>()
        private val nodeParent = layoutType.getMethod("C").also { check(it.returnType == layoutType) }
        private val nodeChildren = runCatching { layoutType.getMethod("w") }.getOrNull()
        private val attached = layoutType.getMethod("isAttached")
        private val nodes = layoutType.getField("G")
        private val outerCoordinator = nodes.type.getField("d").also { check(coordinatesType.isAssignableFrom(it.type)) }
        private val coordinates = load("androidx.compose.ui.layout.LayoutCoordinatesKt")
        private val position = coordinates.getMethod("positionInParent", coordinatesType)
        private val boundsInRoot = runCatching { coordinates.getMethod("boundsInRoot", coordinatesType) }.getOrNull()
        private val edges by lazy(LazyThreadSafetyMode.NONE) {
            val type = boundsInRoot?.returnType ?: return@lazy emptyList()
            listOf("getLeft", "getTop", "getRight", "getBottom").map { type.getMethod(it) }
        }
        private val wrap = load("androidx.compose.ui.graphics.AndroidCanvas_androidKt")
            .getMethod("Canvas", Canvas::class.java).also { check(it.returnType == canvasType) }
        private val draw = layoutType.methods.single {
            !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(canvasType, layerType))
        }

        fun layout(node: Any): Any? = nodeLayoutFields.getOrPut(node.javaClass) {
            node.javaClass.fields.singleOrNull { it.type == layoutType && !Modifier.isStatic(it.modifiers) }
        }?.get(node)

        fun parent(layout: Any): Any? = nodeParent.invoke(layout)

        fun attached(layout: Any) = attached.invoke(layout) == true

        fun artworkBounds(layout: Any, cellBounds: RectF, caption: Any?): RectF? {
            val cellChildren = children(layout)
            val captionLayout = caption?.let(::layout)
            val contents = if (captionLayout != null) parent(captionLayout) else cellChildren.firstOrNull()
            if (contents == null || cellChildren.none { it === contents }) return null
            val primary = children(contents).filter { it !== captionLayout }
            if (primary.isEmpty()) return null
            val box = RectF()
            for (part in primary + cellChildren.filter { it !== contents }) {
                val partBounds = bounds(part) ?: continue
                if (partBounds.intersect(cellBounds)) box.union(partBounds)
            }
            return box.takeUnless { it.isEmpty }
        }

        private fun children(layout: Any): List<Any> =
            (nodeChildren?.invoke(layout) as? List<*>)?.filterNotNull().orEmpty()

        private fun bounds(layout: Any): RectF? {
            if (!attached(layout) || edges.size != 4) return null
            val bounds = boundsInRoot?.invoke(null, outerCoordinator.get(nodes.get(layout))) ?: return null
            val dimensions = edges.map { (it.invoke(bounds) as Number).toFloat() }
            if (dimensions.any { !it.isFinite() }) return null
            return RectF(dimensions[0], dimensions[1], dimensions[2], dimensions[3])
        }

        fun positionInParent(layout: Any): Long? {
            if (!attached(layout)) return null
            val packed = position.invoke(null, outerCoordinator.get(nodes.get(layout))) as? Long ?: return null
            return packed.takeIf { Float.fromBits((it shr 32).toInt()).isFinite() && Float.fromBits(it.toInt()).isFinite() }
        }

        fun draw(layout: Any, canvas: Canvas) {
            draw.invoke(layout, wrap.invoke(null, canvas), null)
        }
    }
}
