package io.github.offlineglass.hook.adapters.xhs

import android.graphics.Canvas
import android.graphics.RenderNode
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** One GPU recording per native frame, shared by the video-page controls. */
internal class XhsVideoBackdrop(private val changed: () -> Unit) {
    private val scene = RenderNode("XHS-shared-video-backdrop")
    private var sources = emptyList<View>()
    private var textures = emptyList<TextureView>()
    private var recordedAt = Long.MIN_VALUE
    private var generation = 0L
    private var recordedGeneration = -1L
    private val position = IntArray(2)
    private val origin = IntArray(2)
    private var scanAt = 0L
    private val timestamps = WeakHashMap<SurfaceTexture, Long>()
    private val self = WeakReference(this)
    var observesFrames = false
        private set
    val nativeContentDirty: Boolean get() = sources.any { it.isDirty }

    fun bind(players: List<View>) {
        val now = android.os.SystemClock.uptimeMillis()
        if (sources != players || now - scanAt >= 500L) {
            val sourceChanged = sources != players
            if (sourceChanged) sources = players.toList()
            scanAt = now
            val found = ArrayList<TextureView>()
            fun walk(view: View) {
                if (view is TextureView) { found.add(view); return }
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            sources.forEach(::walk)
            if (sourceChanged || textures != found) {
                textures.forEach { it.surfaceTexture?.let { surface ->
                    if (targets[surface]?.get() === this) targets.remove(surface)
                } }
                textures = found; generation++
            }
        }
        observesFrames = textures.isNotEmpty()
        for (texture in textures) {
            val surface = texture.surfaceTexture
            if (surface == null || !texture.isAvailable) { observesFrames = false; continue }
            if (targets[surface] !== self) targets[surface] = self
            val listener = texture.surfaceTextureListener
            if (listener == null || !observe(listener.javaClass)) observesFrames = false
        }
    }

    private fun frame(surface: SurfaceTexture) {
        if (textures.none { it.isAttachedToWindow && it.isShown && it.surfaceTexture === surface }) return
        val timestamp = runCatching { surface.timestamp }.getOrNull() ?: return
        if (timestamps[surface] == timestamp) return
        timestamps[surface] = timestamp
        generation++
        changed()
    }

    fun draw(canvas: Canvas, root: View) {
        // drawingTime identifies the current UI traversal. Both buttons use the
        // same recording, including while native outgoing/incoming pages move.
        val time = root.drawingTime
        if (recordedAt != time || recordedGeneration != generation || !scene.hasDisplayList()) {
            root.getLocationInWindow(origin)
            scene.setPosition(0, 0, root.width, root.height)
            val recording = scene.beginRecording(root.width, root.height)
            try {
                for (source in sources) {
                    if (!source.isAttachedToWindow || !source.isShown) continue
                    source.getLocationInWindow(position)
                    val saved = recording.save()
                    recording.translate((position[0] - origin[0]).toFloat(), (position[1] - origin[1]).toFloat())
                    recording.clipRect(0, 0, source.width, source.height)
                    source.draw(recording)
                    recording.restoreToCount(saved)
                }
            } finally { scene.endRecording() }
            recordedAt = time; recordedGeneration = generation
        }
        canvas.drawRenderNode(scene)
    }

    fun release() {
        targets.entries.removeAll { it.value.get() == null || it.value.get() === this }
        textures = emptyList(); sources = emptyList(); scanAt = 0L; timestamps.clear(); observesFrames = false
        scene.discardDisplayList(); recordedAt = Long.MIN_VALUE
    }

    companion object {
        private val targets = WeakHashMap<SurfaceTexture, WeakReference<XhsVideoBackdrop>>()
        private val observed = WeakHashMap<Class<*>, Boolean>()
        private fun observe(type: Class<*>): Boolean {
            observed[type]?.let { return it }
            val success = runCatching {
                // Preserve the player's listener; observe its platform callback
                // signature rather than its obfuscated implementation name.
                XposedBridge.hookMethod(type.getMethod("onSurfaceTextureUpdated", SurfaceTexture::class.java), object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val surface = param.args.firstOrNull() as? SurfaceTexture ?: return
                        targets[surface]?.get()?.frame(surface)
                    }
                })
            }.onFailure { android.util.Log.w("LiquidTabInject", "XHS native frame callback unavailable; native redraw retained", it) }.isSuccess
            observed[type] = success
            return success
        }
    }
}
