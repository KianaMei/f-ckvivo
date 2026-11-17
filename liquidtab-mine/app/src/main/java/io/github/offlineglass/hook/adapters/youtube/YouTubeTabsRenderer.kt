package io.github.offlineglass.hook.adapters.youtube

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.config.GlassConfig

/** Retains native artwork, without replaying recycled/hidden native Views. */
internal class YouTubeTabsRenderer {
    var opticalSelected: Boolean? = null
    private data class Artwork(val drawable: Drawable, val label: String, val typeface: Typeface?, val selected: Boolean, val badge: String?)
    private val artwork = HashMap<Int, Artwork>()
    private var nextRefresh = 0L
    private var sourceIdentity: ViewGroup? = null
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val avatarClip = android.graphics.Path()

    fun clear() { artwork.clear(); nextRefresh = 0L; sourceIdentity = null }
    private fun name(view: View) = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    private fun find(root: View, id: String): View? {
        if (name(root) == id) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), id)?.let { return it }
        return null
    }
    private fun image(root: View?): ImageView? {
        if (root is ImageView && root.drawable != null) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) image(root.getChildAt(i))?.let { return it }
        return null
    }
    private fun refresh(source: ViewGroup, count: Int) {
        val now = SystemClock.uptimeMillis()
        if (sourceIdentity === source && now < nextRefresh) return
        sourceIdentity = source; nextRefresh = now + 250L
        val queue = ArrayDeque<ViewGroup>(); queue += source
        while (queue.isNotEmpty()) {
            val group = queue.removeFirst()
            val children = (0 until group.childCount).map(group::getChildAt)
            if (children.size == count && children.all { it is ViewGroup &&
                (find(it, "thumbnail_layout") != null || find(it, "image") != null) }) {
                children.forEachIndexed { index, cell ->
                    val icon = image(find(cell, "thumbnail_layout") ?: find(cell, "image")) ?: return@forEachIndexed
                    val native = icon.drawable ?: return@forEachIndexed
                    val copy = native.constantState?.newDrawable(source.resources)?.mutate() ?: return@forEachIndexed
                    icon.imageTintList?.let(copy::setTintList)
                    val label = find(cell, "text") as? TextView
                    val countView = find(cell, "new_content_count") as? TextView
                    val dot = find(cell, "new_content_dot")
                    val badge = countView?.takeIf { it.visibility == View.VISIBLE && it.text.isNotBlank() }
                        ?.text?.toString() ?: dot?.takeIf { it.visibility == View.VISIBLE }?.let { "" }
                    artwork[index] = Artwork(copy, label?.text?.toString().orEmpty(), label?.typeface,
                        icon.isSelected || cell.isSelected, badge)
                }
                return
            }
            children.filterIsInstance<ViewGroup>().forEach(queue::addLast)
        }
        // Missing native cells are a transient rebind. Retain the last valid
        // drawables and text rather than replacing them with an empty frame.
    }
    fun drawSlot(canvas: Canvas, source: ViewGroup, count: Int, index: Int,
                 centerX: Float, centerY: Float, iconScale: Float, config: GlassConfig,
                 opticalSelected: Boolean? = null, dark: Boolean = true,
                 localizeLabel: (String) -> String = { it }): Boolean {
        refresh(source, count)
        val item = artwork[index] ?: return false
        val d = source.resources.displayMetrics.density
        val sd = source.resources.displayMetrics.scaledDensity
        val size = 24f * d * 1.08f * iconScale
        val labelSize = config.textSize * sd * 1.08f * iconScale / config.iconScale.coerceAtLeast(.01f)
        textPaint.textSize = labelSize; textPaint.typeface = item.typeface
        textPaint.color = if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val fm = textPaint.fontMetrics
        val labelHeight = if (config.iconOnly) 0f else fm.descent - fm.ascent
        val gap = if (config.iconOnly) 0f else 2f*d
        val top = centerY - (size + gap + labelHeight)/2f
        val drawable = item.drawable
        drawable.state = if (opticalSelected ?: item.selected)
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected)
        else intArrayOf(android.R.attr.state_enabled)
        drawable.alpha = 255
        drawable.setBounds((centerX-size/2f).toInt(), top.toInt(), (centerX+size/2f).toInt(), (top+size).toInt())
        if (index == 4) {
            // The native profile artwork is a square bitmap. Crop only its
            // icon pixels to a circular viewport, without changing the label,
            // badge, or other tab artwork.
            val save = canvas.save()
            avatarClip.rewind()
            avatarClip.addCircle(centerX, top + size / 2f, size / 2f, android.graphics.Path.Direction.CW)
            canvas.clipPath(avatarClip)
            drawable.draw(canvas)
            canvas.restoreToCount(save)
        } else {
            drawable.draw(canvas)
        }
        if (!config.iconOnly) canvas.drawText(localizeLabel(item.label), centerX, top+size+gap-fm.ascent, textPaint)
        item.badge?.let { badge ->
            val x = centerX + size*.42f; val y = top + size*.08f
            textPaint.color = 0xFFFF0033.toInt()
            canvas.drawCircle(x, y, if (badge.isEmpty()) 3f*d else 5.5f*d, textPaint)
            if (badge.isNotEmpty()) {
                textPaint.color = android.graphics.Color.WHITE; textPaint.textSize = 7f*sd
                canvas.drawText(badge.take(2), x, y-(textPaint.fontMetrics.ascent+textPaint.fontMetrics.descent)/2f, textPaint)
            }
        }
        return true
    }
}
