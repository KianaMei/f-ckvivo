package io.github.offlineglass.hook.adapters.youtube

import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import io.github.offlineglass.BuildConfig
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap

/** Owns native header action discovery and dispatch; never restores native ink. */
internal class YouTubeTopActions {
    private val getters = HashMap<Class<*>, Method?>()

    private fun nodes(scopes: List<ViewGroup>): List<View> {
        val seen = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        val result = ArrayList<View>()
        val queue = ArrayDeque<Pair<View, Int>>()
        scopes.filter { it.isAttachedToWindow }.forEach { queue += it to 0 }
        while (queue.isNotEmpty()) {
            val (view, depth) = queue.removeFirst()
            if (!seen.add(view)) continue
            check(seen.size <= 512) { "Native header discovery budget exceeded" }
            result += view
            if (view is ViewGroup && view.childCount > 0) {
                check(depth < 16) { "Native header discovery depth exceeded" }
                for (i in 0 until view.childCount) queue += view.getChildAt(i) to depth + 1
            }
        }
        return result
    }

    private fun nativeToolbar(view: View): Boolean = generateSequence(view.javaClass as Class<*>?) { it.superclass }.any {
        // These are stable Android/support APIs, not target-app implementation names.
        it.name == "android.widget.Toolbar" || it.name == "android.support.v7.widget.Toolbar" ||
            it.name == "androidx.appcompat.widget.Toolbar"
    }

    private fun menus(views: List<View>): List<Menu> {
        val found = ArrayList<Menu>()
        for (view in views) {
            if (!nativeToolbar(view)) continue
            val type = view.javaClass
            val getter = if (getters.containsKey(type)) getters[type] else {
                runCatching { YouTubeExploreContract.menuGetter(type, Menu::class.java) }.getOrNull().also { getters[type] = it }
            }
            val menu = getter?.invoke(view) as? Menu ?: continue
            if (found.none { it === menu }) found += menu
        }
        return found
    }

    fun perform(role: YouTubeExploreContract.Role, scopes: List<ViewGroup>): Boolean {
        val diagnostic = StringBuilder("role=$role received=true\n")
        return runCatching {
            val views = nodes(scopes)
            val menuItems = ArrayList<YouTubeExploreContract.Candidate<MenuItem>>()
            val owners = IdentityHashMap<MenuItem, Menu>()
            fun collect(menu: Menu, depth: Int = 0) {
                check(depth < 5 && menu.size() <= 64) { "Native menu budget exceeded" }
                for (i in 0 until menu.size()) {
                    val item = menu.getItem(i)
                    diagnostic.append("menu id=${item.itemId} title=${item.title} description=${item.contentDescription} enabled=${item.isEnabled}\n")
                    val label = listOf(item.title?.toString().orEmpty(), item.contentDescription?.toString().orEmpty())
                        .firstOrNull { YouTubeExploreContract.matches(role, it) }
                    if (label != null && item.itemId != 0) {
                        owners[item] = menu
                        menuItems += YouTubeExploreContract.Candidate(item, label, true, item.isEnabled, true)
                    }
                    item.subMenu?.let { collect(it, depth + 1) }
                }
            }
            menus(views).forEach { collect(it) }
            if (menuItems.isNotEmpty()) {
                val item = YouTubeExploreContract.unique(role, menuItems)
                val actionView = item.actionView
                if (actionView != null) {
                    // Verified native registration: mobile_topbar_button_item
                    // installs its controller on menu_item_view, then hands the
                    // containing ActionView to MenuItem.setActionView(). Its
                    // MenuItem callback deliberately returns false. Dispatch
                    // that actual native listener, not Menu's callback path.
                    val iconId = actionView.resources.getIdentifier("menu_item_view", "id", "com.google.android.youtube")
                    val targets = nodes(listOfNotNull(actionView as? ViewGroup)).filter {
                        iconId != 0 && it.id == iconId && it.isAttachedToWindow && it.isEnabled && it.hasOnClickListeners()
                    }
                    val target = targets.singleOrNull() ?: error("Unique native ActionView menu_item_view missing: $role")
                    check(target.performClick()) { "Native ActionView rejected $role" }
                    diagnostic.append("dispatched=MenuItem.ActionView/menu_item_view.performClick\n")
                    return@runCatching true
                }
                val handled = owners[item]?.performIdentifierAction(item.itemId, 0) == true
                check(handled) { "Native menu rejected $role" }
                diagnostic.append("dispatched=Menu.performIdentifierAction\n")
                return@runCatching true
            }
            val candidates = ArrayList<YouTubeExploreContract.Candidate<View>>()
            val accessibility = IdentityHashMap<View, Boolean>()
            for (view in views) {
                val info = runCatching { view.createAccessibilityNodeInfo() }.getOrNull()
                val labels = listOf(view.contentDescription?.toString().orEmpty(),
                    info?.contentDescription?.toString().orEmpty(), info?.text?.toString().orEmpty())
                val label = labels.firstOrNull { YouTubeExploreContract.matches(role, it) }
                val accessible = info?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true
                @Suppress("DEPRECATION")
                info?.recycle()
                val resource = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("-")
                diagnostic.append("view=$resource class=${view.javaClass.name} labels=$labels click=${view.hasOnClickListeners()} a11y=$accessible size=${view.width}x${view.height}\n")
                if (label != null) {
                    candidates += YouTubeExploreContract.Candidate(view, label, view.isAttachedToWindow,
                        view.isEnabled, view.hasOnClickListeners() || accessible)
                    accessibility[view] = !view.hasOnClickListeners() && accessible
                }
            }
            val view = YouTubeExploreContract.unique(role, candidates)
            val handled = if (accessibility[view] == true)
                view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) else view.performClick()
            check(handled) { "Native view rejected $role" }
            diagnostic.append("dispatched=native view\n")
            true
        }.onFailure {
            diagnostic.append("failure=${it.message}\n")
            android.util.Log.w("YouTubeGlassDiag", "Native top action contract failed: $role", it)
        }.also {
            if (BuildConfig.DEBUG) scopes.firstOrNull()?.context?.let { context ->
                runCatching { java.io.File(context.getExternalFilesDir(null), "youtube_top_action.txt").writeText(diagnostic.toString()) }
            }
        }.getOrDefault(false)
    }
}
