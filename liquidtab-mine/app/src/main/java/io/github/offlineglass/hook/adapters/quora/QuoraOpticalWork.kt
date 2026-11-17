package io.github.offlineglass.hook.adapters.quora

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RenderEffect
import io.github.offlineglass.hook.adapters.AppNavigationState
import java.util.concurrent.atomic.AtomicLong

/** Each bar/button owns one instance. Shared queue keeps buffer waits off UI. */
internal class QuoraOpticalWork : AppNavigationState {
    private val revision = AtomicLong()
    private val blur = QuoraBlurReuse()
    override fun deferOpticalCopyRenderToUi() = true
    override fun shouldQueueOpticalRenderFromUi(animating: Boolean) = true
    override fun onOpticalBackdropCaptured(barSource: Rect?) { revision.incrementAndGet() }
    override fun opticalBlurNodeNeedsRecord(prepared: Boolean, backdrop: Bitmap?, width: Int,
        height: Int, effect: RenderEffect?): Boolean = blur.needsRecord(prepared, backdrop,
            backdrop?.generationId ?: 0, revision.get(), width, height, effect)
    override fun onOpticalBlurNodeRecorded(backdrop: Bitmap?, width: Int, height: Int,
        effect: RenderEffect?) { blur.recorded() }
    override fun dispose() { blur.clear() }
}
