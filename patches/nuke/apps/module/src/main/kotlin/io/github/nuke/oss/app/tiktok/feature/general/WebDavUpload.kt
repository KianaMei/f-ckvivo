package io.github.nuke.oss.app.tiktok.feature.general

import io.github.nuke.oss.app.shared.media.MediaSaved
import io.github.nuke.oss.app.shared.net.WebDavClient
import io.github.nuke.oss.app.shared.net.WebDavResult
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.TextOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFileType
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// Uploads files the module itself saves (comment images, voice messages and the like) to a WebDAV collection. The host's
// own downloads are not included: they never pass through the module, and there is no completion callback to hook. The
// bytes are copied while the save still has them and uploaded later, so a slow server never delays the save.
@HookEntry
internal class WebDavUpload(
    private val options: OptionValues,
    private val directory: File,
    private val logger: Logger,
) : RuntimeFeature {
    override val id = ID

    override fun install(scope: FeatureScope) {
        val address = options.text(ADDRESS)
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            logger.log("feature=$id skipped address_not_http")
            return
        }
        val client = WebDavClient(address, options.text(USER), options.text(PASSWORD), options.text(EXTRA_DIRECTORY))
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "nuke-webdav").apply { isDaemon = true }
        }
        val listener = MediaSaved.Listener { _, file, type -> enqueue(file, type, worker, client) }
        MediaSaved.add(listener)
        scope.own(AutoCloseable {
            MediaSaved.remove(listener)
            worker.shutdownNow()
        })
    }

    // Runs on the thread that saved the file, which deletes it as soon as it returns: copy first, upload afterwards.
    private fun enqueue(file: File, type: MediaFileType, worker: Executor, client: WebDavClient) {
        val size = file.length()
        if (size <= 0 || size > MAX_BYTES) {
            logger.log("feature=$id skipped size=$size")
            return
        }
        directory.mkdirs()
        val copy = File(directory, "upload-${System.currentTimeMillis()}.${type.extension}")
        try {
            file.copyTo(copy, overwrite = true)
        } catch (failure: IOException) {
            logger.log("feature=$id copy_failed ${failure.javaClass.simpleName}")
            return
        }
        worker.execute {
            val result = client.upload(copy, copy.name)
            val outcome = when (result) {
                is WebDavResult.Uploaded -> "uploaded status=${result.status} name=${copy.name}"
                is WebDavResult.Failed -> "upload_failed status=${result.status} reason=${result.reason}"
            }
            logger.log("feature=$id $outcome")
            copy.delete()
        }
    }

    companion object : TikTokFeature {
        const val ID = "WebDavUpload"
        const val ADDRESS = "address"
        const val USER = "user"
        const val PASSWORD = "password"
        const val EXTRA_DIRECTORY = "directory"
        private const val MAX_BYTES = 32L * 1024 * 1024

        val OPTIONS = listOf(
            TextOption(ADDRESS, maxLength = 300),
            TextOption(USER, maxLength = 100),
            // Stored in the host's settings like every other option; the log never prints it.
            TextOption(PASSWORD, maxLength = 200),
            TextOption(EXTRA_DIRECTORY, maxLength = 200),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(WebDavUpload(options(ID, OPTIONS), File(featureFiles, ID), logger))
        }
    }
}
