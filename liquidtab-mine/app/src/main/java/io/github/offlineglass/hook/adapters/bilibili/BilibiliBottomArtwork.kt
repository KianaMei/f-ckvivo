package io.github.offlineglass.hook.adapters.bilibili

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame

internal class BilibiliBottomArtwork(private val findViewCell: (ViewGroup, Int, Int) -> View?) {
    private var source: ViewGroup? = null
    private var compose: BilibiliComposeBottomArtwork? = null
    private var viewSlots: List<ViewSlot?> = emptyList()
    private var probeAfter = 0L
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var logged = false
    private val partBounds = Rect()
    private val artworkBounds = RectF()

    fun clear() {
        source = null
        compose = null
        viewSlots = emptyList()
        probeAfter = 0L
        sourceWidth = 0
        sourceHeight = 0
        logged = false
    }

    fun update(current: ViewGroup?, host: View) {
        if (current == null || !current.isAttachedToWindow || current.width <= 0 || current.height <= 0) {
            if (source != null) clear()
            return
        }
        if (source !== current) {
            clear()
            source = current
            if (BilibiliAdapter.isComposeNavigation(current)) {
                compose = BilibiliComposeBottomArtwork(current)
            }
        }
        val now = SystemClock.uptimeMillis()
        val geometryChanged = current.width != sourceWidth || current.height != sourceHeight
        val nativeCompose = compose
        var rebound = false
        val needsBinding = nativeCompose?.needsBinding()
            ?: (current.isDirty || viewSlots.isEmpty() || viewSlots.any { it?.cell?.isAttachedToWindow == false })
        if (geometryChanged || (now >= probeAfter && needsBinding)) {
            sourceWidth = current.width
            sourceHeight = current.height
            probeAfter = now + 200L
            rebound = if (nativeCompose != null) nativeCompose.bind() else bindViewSlots(current)
        }
        val dirty = if (nativeCompose != null) nativeCompose.consumeInvalidation()
        else invalidateFrom(current)
        if (rebound || dirty) host.invalidate()
    }

    private fun bindViewSlots(row: ViewGroup): Boolean {
        val resources = row.resources
        val packageName = row.context.packageName
        val ids = listOf("tab_icon", "tab_svg", "tab_icon_cover", "tab_image", "home_publish_icon",
            "bili_badge_view", "tab_badge_text", "tab_text")
            .map { resources.getIdentifier(it, "id", packageName) }
        val previous = viewSlots
        viewSlots = List(TAB_COUNT) { index ->
            val cell = findViewCell(row, index, TAB_COUNT) as? ViewGroup ?: return@List null
            val parts = ids.map { id -> if (id != 0) cell.findViewById<View>(id) else null }
            val icons = if (index == BilibiliAdapter.PUBLISH_INDEX) listOfNotNull(parts[4])
            else parts.take(4).filterNotNull().distinct()
            if (icons.isEmpty()) null else ViewSlot(cell, icons, parts[5] ?: parts[6], parts[7])
        }.takeIf { slots -> slots.any { it != null } }.orEmpty()
        return previous != viewSlots
    }

    fun drawTab(canvas: Canvas, frame: AdapterNavigationFrame, index: Int, centerX: Float, slotWidth: Float,
                captionTop: Float = frame.height.toFloat(), drawCaption: ((String) -> Unit)? = null): Boolean {
        val row = source ?: return false
        if (!row.isAttachedToWindow || !row.isShown || slotWidth <= 0f) return false
        val composeSlot = compose?.slot(index)
        val viewSlot = viewSlots.getOrNull(index)
        val caption = if (drawCaption != null) {
            composeSlot?.label ?: (viewSlot?.label as? TextView)?.takeIf(::ready)?.text?.toString()
        } else null
        val iconBottom = if (caption.isNullOrBlank()) frame.height.toFloat() else captionTop
        val drawn = when {
            composeSlot != null -> project(canvas, frame, centerX, slotWidth, iconBottom, composeSlot.bounds) {
                composeSlot.draw(canvas)
            }
            viewSlot != null -> drawViewSlot(canvas, frame, viewSlot, centerX, slotWidth, iconBottom)
            else -> false
        }
        if (drawn && !caption.isNullOrBlank()) drawCaption?.invoke(caption)
        if (drawn && !logged) {
            logged = true
            Log.i("LiquidTabBiliNav", "bottomArtwork=${if (composeSlot != null) "compose" else "view"}")
        }
        return drawn
    }

    private fun drawViewSlot(canvas: Canvas, frame: AdapterNavigationFrame, slot: ViewSlot, centerX: Float,
                             slotWidth: Float, iconBottom: Float): Boolean = runCatching {
        if (!ready(slot.cell)) return@runCatching false
        val icons = slot.icons.filter { ready(it) && (it !is ImageView || it.drawable != null) }
        if (icons.isEmpty()) return@runCatching false
        val parts = icons + listOfNotNull(slot.badge?.takeIf(::ready))
        artworkBounds.setEmpty()
        val placements = parts.map { view ->
            partBounds.set(0, 0, view.width, view.height)
            slot.cell.offsetDescendantRectToMyCoords(view, partBounds)
            artworkBounds.union(partBounds.left.toFloat(), partBounds.top.toFloat(),
                partBounds.right.toFloat(), partBounds.bottom.toFloat())
            view to Rect(partBounds)
        }
        project(canvas, frame, centerX, slotWidth, iconBottom, artworkBounds) {
            placements.forEach { (view, bounds) ->
                val saved = canvas.save()
                try {
                    canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                    val recorded = if (canvas.isHardwareAccelerated) displayList(view) else null
                    if (recorded?.hasDisplayList() == true) {
                        canvas.translate(-view.left.toFloat(), -view.top.toFloat())
                        canvas.drawRenderNode(recorded)
                    } else {
                        view.draw(canvas)
                    }
                } finally {
                    canvas.restoreToCount(saved)
                }
            }
            true
        }
    }.getOrDefault(false)

    private inline fun project(canvas: Canvas, frame: AdapterNavigationFrame, centerX: Float, slotWidth: Float,
                               iconBottom: Float, bounds: RectF, draw: () -> Boolean): Boolean {
        val top = 2f * frame.density
        val bottom = minOf(iconBottom, frame.height - top)
        val scale = BilibiliBottomIconFit.scale(
            bounds.width(), bounds.height(), slotWidth, bottom - top, frame.density, frame.iconScale,
        )
        if (scale <= 0f) return false
        val saved = canvas.save()
        return try {
            canvas.translate(centerX, (top + bottom) / 2f)
            canvas.scale(scale, scale)
            canvas.translate(-bounds.centerX(), -bounds.centerY())
            canvas.clipRect(bounds)
            draw()
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun ready(view: View) = view.isAttachedToWindow && view.isShown && view.alpha > 0f &&
        view.width > 0 && view.height > 0

    private data class ViewSlot(val cell: ViewGroup, val icons: List<View>, val badge: View?, val label: View?)

    companion object {
        const val TAB_COUNT = 5
        private val updateDisplayList = runCatching {
            View::class.java.getDeclaredMethod("updateDisplayListIfDirty").apply { isAccessible = true }
        }.getOrNull()

        private fun displayList(view: View): RenderNode? = runCatching {
            updateDisplayList?.invoke(view) as? RenderNode
        }.getOrNull()

        fun invalidateFrom(view: View): Boolean {
            if (!view.isDirty || view.width <= 0 || view.height <= 0) return false
            return displayList(view) != null && !view.isDirty
        }
    }
}
