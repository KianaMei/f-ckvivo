package io.github.offlineglass.hook.adapters.youtube

import android.view.View
import android.graphics.Rect
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch

/** Owns the same verified native cells for artwork, selection and dispatch. */
internal class YouTubePivotBinding private constructor(
    val source: ViewGroup,
    private val row: ViewGroup,
    private val entries: List<Entry>,
) {
    data class Entry(val slot: Int, val cell: ViewGroup, val icon: ImageView, val label: String, val text: TextView?)

    fun matches(source: ViewGroup): Boolean = this.source === source && source.isAttachedToWindow &&
        row.parent === source && entries.size == row.childCount && entries.all { it.cell.parent === row }

    fun entry(index: Int): Entry? = entries.firstOrNull { it.slot == index }
    fun hasVisibleCell(bounds: Rect): Boolean = source.isShown && row.height > 0 && entries.any {
        it.cell.height > 0 && it.cell.width > 0 && it.cell.isShown && it.cell.getGlobalVisibleRect(bounds)
    }
    fun selected(): Int? = entries.singleOrNull {
        it.slot != 2 && (it.cell.isSelected || it.cell.isActivated || it.icon.isSelected || it.icon.isActivated)
    }?.slot

    companion object {
        private fun name(view: View) = AdapterNavigationSearch.resourceEntryName(view)

        fun resolve(source: ViewGroup): YouTubePivotBinding? {
            if (!source.isAttachedToWindow || name(source) != "pivot_bar" ||
                (source.parent as? View)?.let(::name) != "bottom_bar_container") return null
            var match: YouTubePivotBinding? = null
            for (index in 0 until source.childCount) {
                val row = source.getChildAt(index) as? ViewGroup ?: continue
                val entries = readRow(row) ?: continue
                if (match != null) return null
                match = YouTubePivotBinding(source, row, entries)
            }
            return match
        }

        private fun readRow(row: ViewGroup): List<Entry>? {
            if (row.childCount !in 4..5) return null
            val present = BooleanArray(5)
            val entries = ArrayList<Entry>(row.childCount)
            for (index in 0 until row.childCount) {
                val cell = row.getChildAt(index) as? ViewGroup ?: return null
                if (!cell.hasOnClickListeners()) return null
                val text = child(cell, "text") as? TextView
                val label = text?.text?.toString()?.takeIf(String::isNotBlank)
                    ?: cell.contentDescription?.toString()?.takeIf(String::isNotBlank)
                    ?: return null
                val slot = YouTubePivotRoles.slot(label)
                if (slot < 0 || present[slot]) return null
                // YouTube 21.46+ wraps id/image in FrameLayout(id/thumbnail_layout) inside
                // image_with_text_tab, and avatar_image_with_text_tab_animation nests
                // id/pivot_bar_thumbnail one extra FrameLayout deeper inside thumbnail_layout.
                val thumbnail = child(cell, "thumbnail_layout", maxDepth = 2) as? ViewGroup
                val image = (thumbnail?.let { child(it, "image", maxDepth = 3) }
                    ?: child(cell, "image")) as? ImageView
                val avatar = (thumbnail?.let { child(it, "pivot_bar_thumbnail", maxDepth = 3) }
                    ?: child(cell, "pivot_bar_thumbnail")) as? ImageView
                val icon = (if (slot == 4) avatar ?: image else image) ?: return null
                present[slot] = true
                entries += Entry(slot, cell, icon, label, text)
            }
            return entries.takeIf { YouTubePivotRoles.complete(present) }
        }

        fun child(group: ViewGroup, resource: String, maxDepth: Int = 4): View? {
            var direct: View? = null
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (name(child) != resource) continue
                if (direct != null) return null
                direct = child
            }
            if (direct != null || maxDepth <= 1) return direct
            var match: View? = null
            val stack = ArrayDeque<Pair<ViewGroup, Int>>()
            for (index in 0 until group.childCount) {
                val nested = group.getChildAt(index) as? ViewGroup ?: continue
                stack += nested to 2
            }
            while (stack.isNotEmpty()) {
                val (current, depth) = stack.removeLast()
                for (index in 0 until current.childCount) {
                    val item = current.getChildAt(index)
                    if (name(item) == resource) {
                        if (match != null) return null
                        match = item
                    }
                    if (item is ViewGroup && depth < maxDepth) {
                        stack += item to (depth + 1)
                    }
                }
            }
            return match
        }

        fun diagnose(source: ViewGroup): String {
            val parentName = (source.parent as? View)?.let(::name) ?: "null"
            val rows = (0 until source.childCount.coerceAtMost(3)).joinToString("; ") { rowIdx ->
                val row = source.getChildAt(rowIdx) as? ViewGroup
                    ?: return@joinToString "child[$rowIdx]=${source.getChildAt(rowIdx).javaClass.simpleName}"
                val cells = (0 until row.childCount.coerceAtMost(6)).joinToString(", ") { i ->
                    val cell = row.getChildAt(i) as? ViewGroup
                        ?: return@joinToString row.getChildAt(i).javaClass.simpleName
                    val text = (child(cell, "text") as? TextView)?.text?.toString()
                    val desc = cell.contentDescription?.toString()
                    val label = text?.takeIf(String::isNotBlank) ?: desc?.takeIf(String::isNotBlank)
                    val slot = label?.let(YouTubePivotRoles::slot) ?: -1
                    val hasImg = child(cell, "image") is ImageView
                    val hasThumb = child(cell, "pivot_bar_thumbnail") is ImageView
                    "[$i:click=${cell.hasOnClickListeners()},label=${label ?: "null"},slot=$slot,img=$hasImg,thumb=$hasThumb]"
                }
                "row[$rowIdx](n=${row.childCount}):$cells"
            }
            return "attached=${source.isAttachedToWindow} src=${name(source)} parent=$parentName children=${source.childCount} $rows"
        }
    }
}
