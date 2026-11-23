package io.github.offlineglass.hook.adapters.chatgpt

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap

/** ChatGPT-specific binding. Native Compose owns geometry and foreground drawing. */
internal enum class ChatGptSurface { COMPOSER, SIDEBAR, ACTIONS, POPUP }

internal object ChatGptComposerGlass {
    private const val PACKAGE = "com.openai.chatgpt"
    private val attached = WeakHashMap<Activity, MutableMap<View, Controller>>()
    private val pending = WeakHashMap<Activity, Boolean>()
    private var lastDiag = 0L

    fun install(loader: ClassLoader) {

        // Only window-owner lifecycle can introduce/remove a glass target.
        // Chat content creates ordinary Views while scrolling; scanning the
        // full semantics tree for each child attachment is quadratic work.
        val composeOwner = loader.loadClass("androidx.compose.ui.platform.AndroidComposeView")
        for (method in listOf("onAttachedToWindow", "onDetachedFromWindow")) XposedBridge.hookAllMethods(composeOwner, method, object : io.github.offlineglass.hook.compat.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.context.packageName != PACKAGE) return
                val activity = ChatGptWindows.activity(view.context) ?: return
                if (pending.put(activity, true) == true) return
                val observer = view.viewTreeObserver
                if (view.isAttachedToWindow && observer.isAlive) observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
                        pending.remove(activity)
                        if (!activity.isDestroyed) scan(activity)
                        return true
                    }
                }) else activity.window?.decorView?.post { pending.remove(activity); if (!activity.isDestroyed) scan(activity) }
            }
        })
        val activityClass = runCatching { loader.loadClass("android.app.Activity") }.getOrNull() ?: return
        XposedBridge.hookAllMethods(activityClass, "onPostResume", object : io.github.offlineglass.hook.compat.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName == PACKAGE && !activity.isFinishing && !activity.isDestroyed) {
                    activity.window?.decorView?.post { scan(activity) }
                }
            }
        })
        XposedBridge.hookAllMethods(activityClass, "onDestroy", object : io.github.offlineglass.hook.compat.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Activity)?.let { attached.remove(it)?.values?.forEach { controller -> controller.dispose() } }
            }
        })
    }

    fun scan(activity: Activity) {
        if (!ChatGptBindings.ensure(activity)) return
        ChatGptLiveConfig.install(activity)
        val windows = ChatGptWindows.roots(activity)
        val all = windows.flatMap { root -> findControls(root, root !== activity.window.decorView) }
        val groups = all.groupBy { it.anchor }
        val controllers = attached.getOrPut(activity) { linkedMapOf() }
        val extraWindow = windows.any { it !== activity.window.decorView }
        (controllers.keys - groups.keys).forEach { anchor ->
            // Popup semantics can temporarily hide the main window's controls.
            // Keep its producer alive while a separate owned window consumes it.
            val live = controllers[anchor]?.hasLiveBindings() == true
            if (!(anchor.isAttachedToWindow && windows.any { it === anchor.rootView } && (extraWindow || live)))
                controllers.remove(anchor)?.dispose()
        }
        groups.forEach { (anchor, candidates) ->
            val old = controllers[anchor]
            if (old != null) old.update(candidates)
            else controllers[anchor] = Controller(anchor, candidates).also { it.install() }
        }
        ChatGptDiagnostics.report(activity, "binding", "continuous-contour-v1; " + all.joinToString { "${it.surface}:${it.bounds}" })
    }

    private data class ComposerCandidate(val anchor: View, val bounds: Rect, val semanticsId: Int, val surface: ChatGptSurface = ChatGptSurface.COMPOSER)

    /** Android Compose exposes virtual semantics through AccessibilityNodeProvider, not View children. */
    private fun findControls(root: View, popup: Boolean = false): List<ComposerCandidate> {
        val header = linkedMapOf<ChatGptSurface, ComposerCandidate>()
        var best: ComposerCandidate? = null
        var bestArea = Int.MAX_VALUE
        fun walk(view: View, depth: Int) {
            if (depth > 24 || !view.isShown || view.width <= 0 || view.height <= 0) return
            val provider = runCatching { view.accessibilityNodeProvider }.getOrNull()
            if (provider != null) {
                data class Entry(val id: Int, val node: AccessibilityNodeInfo, val parent: Entry?)
                val entries = mutableListOf<Entry>()
                val pending = ArrayDeque<Entry>()
                runCatching { provider.createAccessibilityNodeInfo(View.NO_ID) }
                    .getOrNull()?.let { pending.add(Entry(View.NO_ID, it, null)) }
                val visited = HashSet<Int>()
                var count = 0
                while (pending.isNotEmpty() && count++ < 1000) {
                    val entry = pending.removeFirst()
                    entries.add(entry)
                    val node = entry.node
                    try {
                        val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                            .joinToString(" ").lowercase()
                        if (label.contains("回复 chatgpt") || label.contains("reply to chatgpt") ||
                            label.contains("ask anything") || label.contains("有问题，随便问") || label.contains("询问 chatgpt") || label.contains("ask chatgpt") || node.isEditable) {
                            val inner = Rect().also(node::getBoundsInScreen)
                            var ancestor: Entry? = entry
                            while (ancestor != null) {
                                val outer = Rect().also(ancestor.node::getBoundsInScreen)
                                // First wider branching ancestor owns the native controls.
                                if (ancestor.node.childCount > 1 && outer.contains(inner) &&
                                    (outer.width() > inner.width() || ancestor.node.childCount >= 3) && outer.height() >= inner.height() &&
                                    outer.width() >= view.width * .60f && outer.width() <= view.width && outer.height() < view.height / 2) {
                                    val area = outer.width() * outer.height()
                                    if (area < bestArea) {
                                        bestArea = area
                                        best = ComposerCandidate(view, outer, ancestor.id)
                                    }
                                    break
                                }
                                ancestor = ancestor.parent
                            }
                        }
                        for (i in 0 until node.childCount) {
                            // Provider nodes have no service connection: getChild() is null.
                            val id = runCatching {
                                val childId = XposedHelpers.callMethod(node, "getChildId", i) as Long
                                (childId ushr 32).toInt()
                            }.getOrNull() ?: continue
                            if (visited.add(id)) runCatching { provider.createAccessibilityNodeInfo(id) }
                                .getOrNull()?.let { pending.add(Entry(id, it, entry)) }
                        }
                    } catch (_: Throwable) { /* Semantics may change during traversal. */ }
                }
                fun label(e: Entry) = (e.node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: e.node.text?.toString().orEmpty()).trim().lowercase()
                fun bounds(e: Entry) = Rect().also(e.node::getBoundsInScreen)
                fun ancestry(e: Entry): List<Entry> {
                    val result = mutableListOf<Entry>()
                    var current: Entry? = e
                    while (current != null) { result.add(current); current = current.parent }
                    return result
                }
                if (popup) {
                    val named = setOf("分享", "置顶", "添加到项目", "已上传文件", "在聊天中查找", "添加到主屏幕", "归档", "删除",
                        "share", "pin", "add to project", "uploaded files", "search in chat", "archive", "delete")
                    val rows = entries.filter { label(it) in named }
                        .ifEmpty { entries.filter { it.node.isClickable && !bounds(it).isEmpty } }
                    if (rows.size >= 2) {
                        val sets = rows.map { ancestry(it).map { ancestor -> ancestor.id }.toSet() }
                        ancestry(rows.first()).firstOrNull { e ->
                            val r = bounds(e)
                            sets.all { e.id in it } && !r.isEmpty && r.width() > 100 * view.resources.displayMetrics.density &&
                                r.height() > 80 * view.resources.displayMetrics.density
                        }?.let { e -> header[ChatGptSurface.POPUP] = ComposerCandidate(view, bounds(e), e.id, ChatGptSurface.POPUP) }
                    }
                }
                val topEntries = entries.filter { val r = bounds(it); !r.isEmpty && r.top < view.height * .25f }
                topEntries.firstOrNull { label(it) in setOf("菜单", "menu", "打开侧边栏", "open sidebar") }?.let { menu ->
                    ancestry(menu).filter {
                        val r = bounds(it)
                        r.width() >= 32 * view.resources.displayMetrics.density &&
                            kotlin.math.abs(r.width() - r.height()) <= 3 && r.height() < view.height * .12f
                    }.minByOrNull { val r = bounds(it); r.width() * r.height() }?.let { e -> header[ChatGptSurface.SIDEBAR] = ComposerCandidate(view, bounds(e), e.id, ChatGptSurface.SIDEBAR) }
                }
                val newChat = topEntries.firstOrNull { label(it) in setOf("新聊天", "新话题", "new chat", "new conversation") }
                val edit = topEntries.firstOrNull { label(it) in setOf("编辑菜单", "edit menu", "更多选项", "more options", "设置", "settings") }
                if (newChat != null && edit != null) {
                    val editAncestors = ancestry(edit).map { it.id }.toSet()
                    ancestry(newChat).firstOrNull {
                        val r = bounds(it)
                        it.id in editAncestors && r.width() > r.height() * 1.3f &&
                            r.width() < view.width * .6f && r.height() < view.height * .12f
                    }?.let { e -> header[ChatGptSurface.ACTIONS] = ComposerCandidate(view, bounds(e), e.id, ChatGptSurface.ACTIONS) }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return listOfNotNull(best) + header.values
    }

    fun refreshConfig() {
        ChatGptComposeBackground.clearGeometryCache()
        attached.values.toList().flatMap { it.values.toList() }.forEach { it.refreshConfig() }
    }

    private class Controller(val anchor: View, private val initial: List<ComposerCandidate>) {
        private val materials = ChatGptSurface.entries.associateWith { ChatGptNativeMaterial(anchor, it) }
        private var backgrounds: Set<Any> = emptySet()
        private var boundRoles: Map<Any,ChatGptSurface> = emptyMap()
        private val lastBounds = java.util.WeakHashMap<Any,Rect>()
        private fun attached(node: Any) = ChatGptBindings.attached(node)
        fun hasLiveBindings() = backgrounds.any(::attached)
        private var lastPose = Long.MIN_VALUE
        private var moving = false
        private var candidateKey = ""
        private var wasEnabled = true
        private var lastSourceCheck = 0L
        private val frameListener = android.view.ViewTreeObserver.OnPreDrawListener {
            val config = io.github.offlineglass.hook.HookConfigReader.read(anchor.context, PACKAGE)
            if (config.enabled != wasEnabled) refreshConfig()
            if (config.enabled && backgrounds.any { !attached(it) }) {
                candidateKey = ""
                update(findControls(anchor.rootView, initial.any { it.surface == ChatGptSurface.POPUP }))
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (config.enabled && now-lastSourceCheck >= 250L) {
                lastSourceCheck = now
                if (backgrounds.isNotEmpty() && backgrounds.all(::attached) &&
                    !ChatGptPageSource.bindingsHealthy(backgrounds,anchor)) {
                    if (ChatGptPageSource.bindAll(backgrounds,anchor))
                        ChatGptComposeBackground.invalidate(backgrounds)
                }
            }
            materials.values.forEach { it.syncShadowPosition() }
            val moved = backgrounds.filter { node ->
                val bounds = ChatGptComposeBackground.screenBounds(node,anchor) ?: return@filter false
                val old = lastBounds.put(node,bounds)
                old != bounds
            }.toSet()
            if (moved.isNotEmpty()) ChatGptComposeBackground.invalidate(moved)
            val enabledPose = config.enabled && config.postureEffectsEnabled
            val revision = io.github.offlineglass.rendering.PostureGlass.revision(anchor, enabledPose)
            val motion = io.github.offlineglass.rendering.LiquidMotion.sample(anchor, config.liquidViscosity, enabledPose)
            if (revision != lastPose || motion.active || moving) ChatGptComposeBackground.invalidate(backgrounds)
            lastPose = revision
            moving = motion.active
            true
        }
        private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            // IME and typing change placement/size, not target identity. Native
            // draw geometry follows those changes without a full semantics walk.
            if (backgrounds.isEmpty() || backgrounds.any { !attached(it) })
                update(findControls(anchor.rootView, initial.any { it.surface == ChatGptSurface.POPUP }))
        }
        fun install() {
            anchor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            anchor.viewTreeObserver.addOnPreDrawListener(frameListener)
            update(initial)
        }
        fun update(candidates: List<ComposerCandidate>) {
            val enabled = io.github.offlineglass.hook.HookConfigReader.read(anchor.context,PACKAGE).enabled
            wasEnabled = enabled
            if (!enabled) {
                ChatGptComposeBackground.replace(backgrounds,emptySet())
                backgrounds = emptySet()
                boundRoles = emptyMap()
                lastBounds.clear()
                candidateKey = ""
                materials.values.forEach { it.suspendRendering() }
                io.github.offlineglass.rendering.PostureGlass.track(anchor,false)
                return
            }
            val key = candidates.joinToString { "${it.semanticsId}/${it.surface}" }
            if (key == candidateKey && backgrounds.isNotEmpty() && backgrounds.all(::attached)) return
            candidateKey = key
            val bindings = linkedMapOf<Any, ChatGptSurface>()
            candidates.filter { it.anchor === anchor }.forEach { candidate ->
                ChatGptComposeBackground.find(anchor, candidate.semanticsId, candidate.bounds, candidate.surface == ChatGptSurface.COMPOSER || candidate.surface == ChatGptSurface.POPUP)
                    .forEach { bindings[it] = candidate.surface }
            }
            // Accessibility can temporarily omit the editor during IME focus
            // handoff. Keep attached native nodes instead of reverting to solid.
            boundRoles.forEach { (node,role) ->
                if (attached(node) && role !in bindings.values) bindings[node] = role
            }
            ChatGptDiagnostics.report(anchor.context, "candidates", candidates.joinToString { "${it.surface}:${it.bounds}" })
            val next = bindings.keys.toSet()
            ChatGptComposeBackground.replace(backgrounds, next) { node, canvas, shape ->
                val screen = ChatGptComposeBackground.screenBounds(node, anchor)
                val material = bindings[node]?.let(materials::get)
                screen != null && material != null && material.draw(canvas, node, screen, shape)
            }
            val removedRoles = boundRoles.values.toSet() - bindings.values.toSet()
            removedRoles.forEach { materials[it]?.suspendRendering() }
            backgrounds = next
            boundRoles = bindings.toMap()
            ChatGptPageSource.bindAll(next, anchor)
            ChatGptDiagnostics.report(anchor.context, "controls", bindings.values.joinToString())
        }
        fun refreshConfig() {
            candidateKey = ""
            update(findControls(anchor.rootView, initial.any { it.surface == ChatGptSurface.POPUP }))
            ChatGptComposeBackground.invalidate(backgrounds)
            anchor.invalidate()
        }
        fun dispose() {
            ChatGptComposeBackground.replace(backgrounds, emptySet())
            backgrounds = emptySet()
            materials.values.forEach { it.close() }
            if (anchor.viewTreeObserver.isAlive) {
                anchor.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                anchor.viewTreeObserver.removeOnPreDrawListener(frameListener)
            }
        }
    }
}
