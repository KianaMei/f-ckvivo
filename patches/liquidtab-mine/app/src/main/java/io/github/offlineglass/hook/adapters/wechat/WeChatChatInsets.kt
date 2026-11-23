package io.github.offlineglass.hook.adapters.wechat

import android.os.Build
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.ViewTreeObserver
import android.view.WindowInsets
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** Keep LauncherUI full height; reserve the gesture edge inside its chat sibling only. */
internal object WeChatChatInsets {
    private val immersiveRoots = WeakHashMap<View, Boolean>()
    private val observed = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val nativeNavigationColors = WeakHashMap<View, Int>()
    fun configure(decor: View, enabled: Boolean) {
        var context = decor.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window?.navigationBarColor?.let { color ->
            if (Color.alpha(color) >= 224) nativeNavigationColors[decor] = color
        }
        immersiveRoots[decor] = enabled
    }

    fun install(loader: ClassLoader) {
        // Preserve WeChat's requested colour before the immersive host clears
        // the window bar. Opaque requests also refresh the colour on theme changes.
        val windowType = XposedHelpers.findClass("com.android.internal.policy.PhoneWindow", loader)
        XposedBridge.hookAllMethods(windowType, "setNavigationBarColor", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val color = param.args.firstOrNull() as? Int ?: return
                if (Color.alpha(color) < 224) return
                val decor = (param.thisObject as? Window)?.peekDecorView() ?: return
                nativeNavigationColors[decor] = color
            }
        })
        val type = runCatching {
            XposedHelpers.findClass("com.tencent.mm.ui.tools.TestTimeForChatting", loader)
        }.getOrNull() ?: return
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                observe(param.thisObject as? View ?: return)
            }
        }
        // The local compile-only bridge stub exposes only hookAllMethods;
        // resolve the standard Xposed constructor API without changing it.
        XposedBridge::class.java.getMethod(
            "hookAllConstructors", Class::class.java, XC_MethodHook::class.java,
        ).invoke(null, type, callback)
    }

    private fun observe(chat: View) {
        if (!observed.add(chat)) return
        val listener = object : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            private var nativeBottom = chat.paddingBottom
            private var appliedBottom: Int? = null
            private var content = WeakReference<View>(null)
            private var lastContentBind = 0L
            private var lastContentLeft = Int.MIN_VALUE
            private val rootPosition = IntArray(2)
            private val chatPosition = IntArray(2)
            private val contentPosition = IntArray(2)
            private val fill = object : ColorDrawable(Color.TRANSPARENT) {
                override fun draw(canvas: android.graphics.Canvas) {
                    val page = content.get() ?: return
                    if (!page.isShown || !page.isAttachedToWindow) return
                    chat.getLocationInWindow(chatPosition)
                    page.getLocationInWindow(contentPosition)
                    val saved = canvas.save()
                    // Cancel the common ancestor's transform. The outer overlay
                    // inherits TestTimeForChatting's legacy animation itself.
                    canvas.clipRect(contentPosition[0] - chatPosition[0], bounds.top,
                        contentPosition[0] - chatPosition[0] + page.width, bounds.bottom)
                    super.draw(canvas)
                    canvas.restoreToCount(saved)
                }
            }
            private var fillAttached = false



            fun bind() {
                val next = chat.viewTreeObserver
                if (observer === next) return
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                observer = next
                next.addOnPreDrawListener(this)
            }

            override fun onPreDraw(): Boolean {
                // Preserve the native padding if WeChat has replaced it.
                if (appliedBottom == null || chat.paddingBottom != appliedBottom) {
                    nativeBottom = chat.paddingBottom
                }
                val root = chat.rootView
                var bottom = nativeBottom
                if (immersiveRoots[root] == true && chat.isShown) {
                    val insets = root.rootWindowInsets ?: return true
                    val nav = if (Build.VERSION.SDK_INT >= 30) {
                        if (insets.isVisible(WindowInsets.Type.ime())) 0
                        // HyperOS force-immersive fitting reports navigationBars
                        // and its ignoring-visibility value as zero, while the
                        // actual gesture-handle source remains on screen. Its
                        // mandatory gesture inset retains that physical height.
                        else maxOf(
                            insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                            insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        maxOf(insets.systemWindowInsetBottom, insets.mandatorySystemGestureInsets.bottom)
                    }
                    root.getLocationInWindow(rootPosition)
                    chat.getLocationInWindow(chatPosition)
                    // Account for native fitting already performed by a parent;
                    // reserve only the part of the gesture area covered by chat.
                    val overlap = (chatPosition[1] + chat.height -
                        (rootPosition[1] + root.height - nav)).coerceAtLeast(0)
                    bottom = maxOf(nativeBottom, overlap)
                } else if (immersiveRoots[root] == true) {
                    // Keep the outgoing chat's geometry through its animation.
                    // Visibility must never change the underlying pager fitting.
                    return true
                }
                appliedBottom = bottom
                val now = android.os.SystemClock.uptimeMillis()
                if (content.get()?.isAttachedToWindow != true && now - lastContentBind >= 400L) {
                    lastContentBind = now
                    // Bind only the semantic chat page, not a private controller
                    // field or the native callback's obfuscated implementation.
                    val pending = ArrayDeque<View>()
                    val candidates = ArrayList<View>()
                    pending += chat
                    var visited = 0
                    while (pending.isNotEmpty() && visited++ < 96) {
                        val view = pending.removeFirst()
                        if (view.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout") {
                            candidates += view
                            continue
                        }
                        if (view is android.widget.AbsListView) continue
                        if (view is ViewGroup) for (i in 0 until view.childCount) pending += view.getChildAt(i)
                    }
                    content = WeakReference(candidates.singleOrNull())
                }
                content.get()?.let { page ->
                    chat.getLocationInWindow(chatPosition)
                    page.getLocationInWindow(contentPosition)
                    val left = contentPosition[0] - chatPosition[0]
                    if (left != lastContentLeft) {
                        lastContentLeft = left
                        fill.invalidateSelf()
                        chat.invalidate()
                    }
                }
                val fillHeight = if (immersiveRoots[root] == true && content.get() != null) bottom else 0
                if (fillHeight > 0) {
                    val color = nativeNavigationColors[root] ?: run {
                        val value = TypedValue()
                        if (chat.context.theme.resolveAttribute(android.R.attr.navigationBarColor, value, true)) {
                            value.data
                        } else Color.TRANSPARENT
                    }
                    fill.color = color
                    fill.setBounds(0, chat.height - fillHeight, chat.width, chat.height)
                    if (!fillAttached || fill.callback == null) {
                        chat.overlay.add(fill)
                        fillAttached = true
                    }
                } else if (fillAttached) {
                    chat.overlay.remove(fill)
                    fillAttached = false
                }
                if (chat.paddingBottom == bottom) return true
                chat.setPadding(chat.paddingLeft, chat.paddingTop, chat.paddingRight, bottom)
                // Submit only after this chat subtree has its final dimensions.
                return false
            }

            override fun onViewAttachedToWindow(v: View) = bind()
            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                observer = null
            }
        }
        chat.addOnAttachStateChangeListener(listener)
        listener.bind()
    }
}
