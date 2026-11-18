package io.github.nuke.oss.app.tiktok.feature.general

import io.github.nuke.oss.app.shared.media.AudioExport
import io.github.nuke.oss.app.shared.media.MediaSaved
import io.github.nuke.oss.app.shared.media.VideoAudio
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFileType
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// Keeps the sound of a video the module saved as an MP3 next to it: the audio track is decoded with MediaCodec, encoded
// with the pure-Java LAME port, and written to Music/douyin/audio. Only files this module saved are handled; the host's
// own downloads never pass through here. The conversion runs on its own thread, so a save is never delayed by it.
@HookEntry
internal class SaveVideoAudio(
    private val files: () -> MediaFiles,
    private val logger: Logger,
    private val directory: File,
) : RuntimeFeature {
    override val id = ID

    override fun install(scope: FeatureScope) {
        directory.mkdirs()
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "nuke-audio").apply { isDaemon = true }
        }
        val listener = MediaSaved.Listener { context, file, type -> enqueue(context, file, type, worker) }
        MediaSaved.add(listener)
        scope.own(AutoCloseable {
            MediaSaved.remove(listener)
            worker.shutdownNow()
        })
    }

    // Runs on the thread that saved the video, which deletes it as soon as it returns: copy first, convert afterwards.
    private fun enqueue(context: Any, file: File, type: MediaFileType, worker: Executor) {
        if (!type.mime.startsWith("video/")) return
        val size = file.length()
        if (size <= 0) {
            logger.log("feature=$id skipped size=$size")
            return
        }
        val stamp = System.currentTimeMillis()
        val source = File(directory, "audio-source-$stamp.${type.extension}")
        try {
            file.copyTo(source, overwrite = true)
        } catch (failure: IOException) {
            logger.log("feature=$id copy_failed ${failure.javaClass.simpleName}")
            return
        }
        worker.execute {
            val result = VideoAudio.toMp3(source, File(directory, "audio-$stamp.mp3"))
            source.delete()
            when (result) {
                is AudioExport.Converted -> {
                    try {
                        files().saveFile(context, result.file, audio = true, name = "audio-$stamp")
                        logger.log("feature=$id saved duration=${result.durationMillis}")
                    } catch (failure: Exception) {
                        logger.log("feature=$id save_failed ${failure.javaClass.simpleName}: ${failure.message}")
                    } finally {
                        result.file.delete()
                    }
                }
                is AudioExport.Failed -> logger.log("feature=$id convert_failed ${result.reason}")
            }
        }
    }

    companion object : TikTokFeature {
        const val ID = "SaveVideoAudio"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(SaveVideoAudio(mediaFiles, logger, File(featureFiles, ID)))
        }
    }
}
