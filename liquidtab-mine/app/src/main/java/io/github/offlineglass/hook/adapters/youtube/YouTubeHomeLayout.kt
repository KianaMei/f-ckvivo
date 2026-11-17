package io.github.offlineglass.hook.adapters.youtube

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup

/** One verified binding owns native header layout and nested-scroll behavior. */
internal class YouTubeHomeLayout {
    private data class NativeBehavior(
        val view: View,
        val params: ViewGroup.LayoutParams,
        val behavior: Any,
        val access: YouTubeBehaviorAccess.Access,
    ) {
        fun detach() {
            val current = access.get(params)
            check(current == null || current === behavior) { "Native Behavior replaced; rebind required" }
            if (current != null) { access.set(params, null); view.requestLayout() }
        }
        fun restore() {
            if (view.layoutParams === params && access.get(params) == null) {
                access.set(params, behavior)
                view.requestLayout()
            }
        }
    }
    private data class Binding(
        val root: View,
        val content: NativeBehavior,
        val appBar: NativeBehavior,
        val results: ViewGroup,
        val paddingTop: Int,
        val clipToPadding: Boolean,
        var appliedPadding: Int,
    )
    private var binding: Binding? = null
    private var retryAt = 0L
    private var lastFailure: String? = null

    fun update(root: View, headerSpace: Int) {
        binding?.let { old ->
            if (old.root === root && old.content.view.isAttachedToWindow && old.appBar.view.isAttachedToWindow &&
                old.results.isAttachedToWindow && old.results.isShown && old.content.view.layoutParams === old.content.params &&
                old.appBar.view.layoutParams === old.appBar.params) {
                runCatching { old.appBar.detach(); old.content.detach() }.onFailure { report(it) }
                val wanted = old.paddingTop + headerSpace
                if (old.appliedPadding != wanted) {
                    old.results.setPadding(old.results.paddingLeft, wanted, old.results.paddingRight, old.results.paddingBottom)
                    old.appliedPadding = wanted
                }
                return
            }
            restore()
        }
        val now = SystemClock.uptimeMillis()
        if (now < retryAt) return
        retryAt = now + 500L
        fun find(name: String): View? {
            val id = root.resources.getIdentifier(name, "id", "com.google.android.youtube")
            return if (id != 0) root.findViewById(id) else null
        }
        runCatching {
            val contents = checkNotNull(find("pane_fragment_contents")) { "Home content missing" }
            val appBar = checkNotNull(find("appbar_layout")) { "Home AppBar missing" }
            val results = checkNotNull(find("results") as? ViewGroup) { "Home results missing" }
            val parent = contents.parent
            check(parent === appBar.parent && parent is ViewGroup &&
                generateSequence(parent.javaClass as Class<*>?) { it.superclass }.any {
                    it.name == "androidx.coordinatorlayout.widget.CoordinatorLayout"
                }) { "Header/content must share CoordinatorLayout" }
            val params = contents.layoutParams
            check(params.javaClass === appBar.layoutParams.javaClass) { "Coordinator LP types differ" }
            fun role(view: View, accepts: (Class<*>) -> Boolean): Any {
                val candidates = view.layoutParams.javaClass.fields.filter {
                    !java.lang.reflect.Modifier.isStatic(it.modifiers)
                }.mapNotNull { it.get(view.layoutParams) }.filter { value ->
                    generateSequence(value.javaClass as Class<*>?) { it.superclass }.any(accepts)
                }
                return candidates.singleOrNull() ?: error("Native behavior role absent or ambiguous")
            }
            val scrolling = role(contents) { it.name == "com.google.android.apps.youtube.app.ui.actionbar.MainScrollingViewBehavior" }
            val header = role(appBar) { it.name == "com.google.android.material.appbar.AppBarLayout\$BaseBehavior" }
            val access = YouTubeBehaviorAccess.resolve(params.javaClass, scrolling, header)
            val contentBehavior = NativeBehavior(contents, params, scrolling, access)
            val headerBehavior = NativeBehavior(appBar, appBar.layoutParams, header, access)
            val top = results.paddingTop
            val saved = Binding(root, contentBehavior, headerBehavior, results, top, results.clipToPadding, top + headerSpace)
            try {
                headerBehavior.detach()
                contentBehavior.detach()
            } catch (error: Throwable) {
                runCatching { headerBehavior.restore() }
                runCatching { contentBehavior.restore() }
                throw error
            }
            binding = saved
            results.clipToPadding = false
            results.setPadding(results.paddingLeft, saved.appliedPadding, results.paddingRight, results.paddingBottom)
            lastFailure = null
            Log.i("YouTubeGlassDiag", "Home Coordinator behaviors detached through verified shared binding")
        }.onFailure(::report)
    }

    private fun report(error: Throwable) {
        val reason = error.message ?: error.javaClass.name
        if (reason != lastFailure) Log.w("YouTubeGlassDiag", "Home layout contract: $reason", error)
        lastFailure = reason
    }

    fun restore() {
        val old = binding ?: return
        binding = null
        runCatching { old.appBar.restore() }.onFailure(::report)
        runCatching { old.content.restore() }.onFailure(::report)
        if (old.results.paddingTop == old.appliedPadding) {
            old.results.setPadding(old.results.paddingLeft, old.paddingTop, old.results.paddingRight, old.results.paddingBottom)
        }
        old.results.clipToPadding = old.clipToPadding
        retryAt = 0L
    }
}
