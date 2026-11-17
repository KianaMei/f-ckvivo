package io.github.offlineglass.hook.adapters.youtube

import android.view.View
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppVideoBackdrop
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** One decoder sampler per window; top controls only consume its existing frame. */
internal object YouTubeBackdropRegistry {
    private class Entry {
        var sampler: WeakReference<YouTubeVideoSampler>? = null
        var samplingHost: WeakReference<GlassHostLayout>? = null
        val consumers = WeakHashMap<GlassHostLayout, Boolean>()
    }
    private val windows = WeakHashMap<View, Entry>()

    fun bind(root: View, sampler: YouTubeVideoSampler, host: GlassHostLayout) {
        val entry = windows.getOrPut(root) { Entry() }
        if (entry.sampler?.get() !== sampler) entry.sampler = WeakReference(sampler)
        if (entry.samplingHost?.get() !== host) entry.samplingHost = WeakReference(host)
    }
    fun unbind(root: View, sampler: YouTubeVideoSampler) {
        val entry = windows[root] ?: return
        if (entry.sampler?.get() === sampler) { entry.sampler = null; entry.samplingHost = null }
        invalidate(root)
    }
    fun add(root: View, host: GlassHostLayout) { windows.getOrPut(root) { Entry() }.consumers[host] = false }
    fun remove(root: View, host: GlassHostLayout) { windows[root]?.consumers?.remove(host) }
    fun request(host: GlassHostLayout) {
        val entry = windows[host.rootView] ?: return
        entry.samplingHost?.get()?.let { entry.sampler?.get()?.request(it, true) }
    }
    fun frame(root: View, host: GlassHostLayout?): AppVideoBackdrop? {
        val entry = windows[root] ?: return null
        return host?.let { entry.sampler?.get()?.frame(it) }
    }
    fun forEachVisible(root: View, action: (GlassHostLayout) -> Unit) {
        windows[root]?.consumers?.keys?.forEach { host ->
            if (host.isAttachedToWindow && host.isShown && host.alpha > .001f && host.width > 0 && host.height > 0) action(host)
        }
    }
    fun hasVisible(root: View): Boolean {
        var visible = false
        forEachVisible(root) { visible = true }
        return visible
    }
    fun invalidate(root: View) {
        val entry = windows[root] ?: return
        val sampler = entry.sampler?.get()
        forEachVisible(root) {
            val overlap = sampler?.frame(it) != null
            if (sampler?.hasSnapshot != true || overlap || entry.consumers[it] == true) it.postInvalidateOnAnimation()
            entry.consumers[it] = overlap
        }
    }
}
