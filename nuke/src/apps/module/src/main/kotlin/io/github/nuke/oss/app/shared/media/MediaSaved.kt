package io.github.nuke.oss.app.shared.media

import io.github.nuke.oss.app.tiktok.sdk.media.MediaFileType
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// Every file the module itself saves, announced while the file still exists. `AndroidMediaFiles.save` deletes its
// temporary download as soon as the save returns, so a listener that needs the bytes must copy them now; the WebDAV
// upload does exactly that and does the network work afterwards. Host process only, like everything else here.
internal object MediaSaved {
    fun interface Listener {
        // context is the host Context the save used, so a listener that writes afterwards can use it too.
        fun saved(context: Any, file: File, type: MediaFileType)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun add(listener: Listener) {
        listeners += listener
    }

    fun remove(listener: Listener) {
        listeners -= listener
    }

    // A listener that fails costs the announcement, not the save.
    fun publish(context: Any, file: File, type: MediaFileType) {
        for (listener in listeners) {
            try {
                listener.saved(context, file, type)
            } catch (_: RuntimeException) {
            }
        }
    }
}
