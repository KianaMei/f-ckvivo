package io.github.offlineglass.hook.adapters.youtube

import android.os.Bundle
import android.view.View
import android.view.ViewGroup

/** Native Fragment view-tag + argument role, independent of implementation names. */
internal class YouTubeNotificationPage {
    private var owner: Any? = null
    private var boundView: View? = null
    private var notification = false
    private var marginOwner: View? = null
    private var originalTop = 0
    private var tagId: Int? = null

    fun active(view: View?): Boolean {
        if (view == null || !view.isAttachedToWindow) return false
        val id = tagId ?: view.resources.getIdentifier("fragment_container_view_tag", "id", "com.google.android.youtube")
            .also { tagId = it }
        val tag = if (id != 0) view.getTag(id) else null
        if (tag === owner && view === boundView) return notification
        owner = tag; boundView = view; notification = false
        if (tag == null) return false
        runCatching {
            val arguments = YouTubeFragmentArguments.resolve(tag, view, View::class.java, Bundle::class.java) {
                it.containsKey("navigation_endpoint") && it.containsKey("top_level_browse_pane")
            }
            val id = YouTubeBrowseEndpoint.browseId(arguments.getByteArray("navigation_endpoint"))
            notification = id == "FEnotifications_inbox" || id == "FEactivity"
        }.onFailure { android.util.Log.w("YouTubeGlassDiag", "Browse endpoint contract: ${it.message}") }
        return notification
    }

    fun updateInset(view: View?, enabled: Boolean) {
        if (!enabled || view !== marginOwner) restoreInset()
        if (!enabled || view == null) return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (marginOwner == null) { marginOwner = view; originalTop = params.topMargin }
        // Native browse layout reserves the status inset as a top margin.
        // Change only this page's reservation, retaining native concrete LP.
        if (params.topMargin != 0) { params.topMargin = 0; view.layoutParams = params }
    }
    fun restoreInset() {
        marginOwner?.let { view ->
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && params.topMargin == 0) { params.topMargin = originalTop; view.layoutParams = params }
        }
        marginOwner = null
    }
}
