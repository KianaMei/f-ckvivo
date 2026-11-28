package io.github.nuke.oss.app.tiktok.injector

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.nuke.oss.app.shared.activity.ResumedActivity
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanNode
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanPlan
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanScreen
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

private const val CONSTRAINT = "androidx.constraintlayout.widget.ConstraintLayout"
private const val LONG_PRESS_LAYOUT = "com.ss.android.ugc.aweme.feed.ui.LongPressLayout"

// Carries the plan out on the main thread. Only weak references to the Views are kept, with the look to bring back.
// While controls are hidden, a PreDraw listener re-asserts alpha/GONE before every frame to prevent host fade-in
// flicker, and a ViewGroup touch guard blocks accidental ACTION_DOWN taps on non-clickable hidden controls.
internal class AndroidCleanScreen : CleanScreen {
    private val activities = ResumedActivity()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private class Faded(val view: WeakReference<View>, val alpha: Float)
    private var faded = emptyList<Faded>()
    private var gone = emptyList<WeakReference<View>>()
    private val hiddenViews: MutableSet<View> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    @Volatile private var transparent = false
    @Volatile private var hiddenActive = false
    private var preDrawTarget: WeakReference<View>? = null
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (hiddenActive) enforceHiddenNow()
        true
    }

    override fun install(scope: FeatureScope, featureId: String) {
        activities.install(scope, featureId)
        installHiddenTouchGuard(scope, featureId)
        scope.own(AutoCloseable { main.post { restoreNow() } })
    }

    override fun snapshot(): CleanNode? {
        val root = activities.get()?.window?.decorView?.rootView ?: return null
        return snapshotOf(root)
    }

    override fun hide(plan: CleanPlan, keepClickable: Boolean) {
        main.post {
            restoreNow()
            transparent = keepClickable
            val fadeViews = plan.fade.filterIsInstance<View>()
            val goneViews = plan.gone.filterIsInstance<View>()
            faded = fadeViews.map { Faded(WeakReference(it), it.alpha) }
            gone = goneViews.map { WeakReference(it) }
            hiddenViews.clear()
            hiddenViews.addAll(fadeViews)
            hiddenViews.addAll(goneViews)
            hiddenActive = faded.isNotEmpty() || gone.isNotEmpty()
            enforceHiddenNow()
            if (hiddenActive) attachPreDrawGuard()
        }
    }

    override fun restore() { main.post { restoreNow() } }

    private fun enforceHiddenNow() {
        for (entry in faded) {
            val view = entry.view.get() ?: continue
            if (transparent) {
                if (view.alpha != 0f) view.alpha = 0f
            } else {
                if (view.visibility != View.GONE) view.visibility = View.GONE
            }
        }
        for (entry in gone) {
            val view = entry.get() ?: continue
            if (view.visibility != View.GONE) view.visibility = View.GONE
        }
    }

    private fun attachPreDrawGuard() {
        detachPreDrawGuard()
        val decor = activities.get()?.window?.decorView ?: return
        val observer = decor.viewTreeObserver
        if (!observer.isAlive) return
        observer.addOnPreDrawListener(preDrawListener)
        preDrawTarget = WeakReference(decor)
    }

    private fun detachPreDrawGuard() {
        val decor = preDrawTarget?.get()
        preDrawTarget = null
        val observer = decor?.viewTreeObserver ?: return
        if (observer.isAlive) observer.removeOnPreDrawListener(preDrawListener)
    }

    private fun restoreNow() {
        hiddenActive = false
        detachPreDrawGuard()
        hiddenViews.clear()
        for (entry in faded) entry.view.get()?.let { if (transparent) it.alpha = entry.alpha else it.visibility = View.VISIBLE }
        for (entry in gone) entry.get()?.visibility = View.VISIBLE
        faded = emptyList()
        gone = emptyList()
    }

    private fun installHiddenTouchGuard(scope: FeatureScope, featureId: String) {
        val dispatch = runCatching {
            ViewGroup::class.java.getDeclaredMethod(
                "dispatchTransformedTouchEvent",
                MotionEvent::class.java,
                Boolean::class.javaPrimitiveType,
                View::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull() ?: return
        runCatching {
            scope.hooks.install(dispatch, HookSpec(
                id = "$featureId:HiddenTouchGuard",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { call ->
                    if (!hiddenActive || transparent) return@HookSpec
                    val event = call.argument(0) as? MotionEvent ?: return@HookSpec
                    val cancel = (call.argument(1) as? Boolean ?: false) || event.actionMasked == MotionEvent.ACTION_CANCEL
                    if (cancel) return@HookSpec
                    val action = event.actionMasked
                    // Only block new gesture starts; never drop MOVE/UP/CANCEL for an already active gesture.
                    if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return@HookSpec
                    val child = call.argument(2) as? View ?: return@HookSpec
                    if (isHiddenControl(child)) call.returnEarly(false)
                },
            ))
        }
    }

    private fun isHiddenControl(child: View): Boolean {
        if (child.javaClass.name == LONG_PRESS_LAYOUT) return false
        var current: View? = child
        var depth = 0
        while (current != null && depth < 4) {
            if (current.javaClass.name == LONG_PRESS_LAYOUT) return false
            if (current in hiddenViews) return true
            current = current.parent as? View
            depth++
        }
        return false
    }

}

private fun idNameOf(view: View): String? =
    if (view.id == View.NO_ID) null else try { view.resources.getResourceEntryName(view.id) } catch (_: android.content.res.Resources.NotFoundException) { null }

// Takes a snapshot of a View and everything below it, for the rules in CleanRules to run on.
internal fun snapshotOf(view: View): CleanNode {
    val kind = when (view) {
        is SeekBar -> CleanNode.Kind.SEEK_BAR
        is EditText -> CleanNode.Kind.EDIT_TEXT
        is TextView -> CleanNode.Kind.TEXT
        is LinearLayout -> CleanNode.Kind.LINEAR
        is FrameLayout -> CleanNode.Kind.FRAME
        is RelativeLayout -> CleanNode.Kind.RELATIVE
        else -> if (view.javaClass.name == CONSTRAINT) CleanNode.Kind.CONSTRAINT else CleanNode.Kind.OTHER
    }
    val node = CleanNode(
        view.javaClass.simpleName, view.javaClass.name, (view as? TextView)?.text?.toString(),
        (view as? EditText)?.hint?.toString(), kind, view, idNameOf(view),
    )
    if (view is ViewGroup) for (index in 0 until view.childCount) node.add(snapshotOf(view.getChildAt(index)))
    return node
}
