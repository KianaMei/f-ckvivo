package io.github.nuke.oss.app.tiktok.injector

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import com.shakster.gifkt.GifEncoder
import io.github.nuke.oss.app.shared.media.MediaNaming
import io.github.nuke.oss.app.shared.media.DownloadCancelled
import io.github.nuke.oss.app.shared.media.MediaSaved
import io.github.nuke.oss.app.shared.media.MotionPhoto
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFileType
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

// Downloads, converts and saves media with the host's own storage rights, the way the legacy module did. Sizes are
// capped (128 MiB per download, 3000 frames and 16 million pixels per frame for a sticker), and every temporary file
// is removed whether or not the save worked.
internal class AndroidMediaFiles(private val hostClassLoader: ClassLoader) : MediaFiles {
    override fun requestStoragePermission(activity: Any): Boolean {
        val host = activity as? Activity ?: return false
        if (Build.VERSION.SDK_INT >= 29 || hasLegacyStorage(host)) return false
        host.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE)
        return true
    }

    private fun hasLegacyStorage(host: Context) =
        host.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    override fun save(
        context: Any,
        urls: List<String>,
        audio: Boolean,
        folder: ImageFolder,
        name: String?,
        progress: (bytes: Long, total: Long) -> Unit,
        cancelled: () -> Boolean,
        tree: String?,
    ) {
        val host = (context as? Context)?.applicationContext ?: error("No host Context to save with")
        val source = File.createTempFile("nuke-media-", ".media", host.cacheDir)
        var converted: File? = null
        try {
            var downloaded = false
            var lastError: Exception? = null
            for (url in urls.distinct()) {
                try {
                    download(url, source, progress, cancelled)
                    downloaded = true
                    break
                } catch (error: java.io.IOException) {
                    lastError = error
                } catch (error: IllegalArgumentException) {
                    lastError = error
                }
            }
            check(downloaded) { "No media URL succeeded: ${lastError?.message}" }
            var type = source.inputStream().use { MediaFileType.detect(header(it), audio) }
            // A sticker served as HEIF loses its animation, so the address and the type it answered with are logged
            // together; the log is the only place that shows which of the two addresses the CDN turned into a still.
            android.util.Log.i("NukeApp-OSS", "media_saved source=$type.mime url=${urls.firstOrNull().orEmpty().take(160)}")
            require(if (audio) type.mime.startsWith("audio/") else type.mime.startsWith("image/") || type.mime.startsWith("video/")) {
                "Unexpected downloaded MIME ${type.mime}"
            }
            var output = source
            if (!audio && (type.mime.startsWith("video/") || type.mime == "image/heif-sequence")) {
                // An animated sticker arrives as a video or as a HEIF image sequence; both become a GIF the gallery can
                // play. A sequence the extractor cannot read is kept as the host served it rather than failing the save.
                val animation = File.createTempFile("nuke-sticker-", ".gif", host.cacheDir)
                val convertedToGif = runCatching { toGif(source, animation) }.isSuccess
                if (convertedToGif) {
                    converted = animation
                    output = animation
                    type = MediaFileType("image/gif", "gif")
                } else {
                    animation.delete()
                    android.util.Log.i("NukeApp-OSS", "media_saved sticker_sequence_unreadable url=${urls.firstOrNull().orEmpty().take(160)}")
                }
            } else if (type.mime == "image/vvic") {
                convertVvic(source)
                type = MediaFileType("image/png", "png")
            }
            // The file is deleted in the finally block below, so whoever needs the bytes takes them here.
            MediaSaved.publish(host, output, type)
            if (Build.VERSION.SDK_INT < 29) {
                require(hasLegacyStorage(host)) { "Storage permission is required on Android 8–9" }
                saveLegacy(host, output, type, audio, folder, name)
            } else {
                saveScoped(host, output, type, audio, folder, name)
            }
        } finally {
            source.delete()
            converted?.delete()
        }
    }

    override fun saveMotionPhoto(
        context: Any,
        imageUrls: List<String>,
        videoUrls: List<String>,
        folder: ImageFolder,
        name: String?,
        progress: (bytes: Long, total: Long) -> Unit,
        cancelled: () -> Boolean,
        tree: String?,
    ) {
        val host = (context as? Context)?.applicationContext ?: error("No host Context to save with")
        val still = File.createTempFile("nuke-live-still-", ".jpg", host.cacheDir)
        val clip = File.createTempFile("nuke-live-video-", ".mp4", host.cacheDir)
        val packed = File.createTempFile("nuke-live-", ".jpg", host.cacheDir)
        try {
            downloadFirst(imageUrls, still, progress, cancelled)
            downloadFirst(videoUrls, clip, { _, _ -> }, cancelled)
            require(still.length() <= MAX_LIVE_BYTES && clip.length() <= MAX_LIVE_BYTES) { "The live photo is too large" }
            packed.writeBytes(MotionPhoto.wrap(still.readBytes(), clip.readBytes()))
            val type = MediaFileType("image/jpeg", "jpg")
            MediaSaved.publish(host, packed, type)
            if (tree != null) {
                saveTree(host, packed, type, tree, name)
            } else if (Build.VERSION.SDK_INT < 29) {
                require(hasLegacyStorage(host)) { "Storage permission is required on Android 8–9" }
                saveLegacy(host, packed, type, audio = false, folder, name)
            } else {
                saveScoped(host, packed, type, audio = false, folder, name)
            }
        } finally {
            still.delete()
            clip.delete()
            packed.delete()
        }
    }

    // The first address that answers, written to target; the same choice save makes, for a second file.
    private fun downloadFirst(urls: List<String>, target: File, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        var lastError: Exception? = null
        for (url in urls.distinct()) {
            try {
                download(url, target, progress, cancelled)
                return
            } catch (error: java.io.IOException) {
                lastError = error
            } catch (error: IllegalArgumentException) {
                lastError = error
            }
        }
        error("No media URL succeeded: ${lastError?.message}")
    }

    override fun saveFile(context: Any, file: File, audio: Boolean, folder: ImageFolder, name: String?) {
        val host = (context as? Context)?.applicationContext ?: error("No host Context to save with")
        check(file.isFile) { "No file to save" }
        val type = file.inputStream().use { MediaFileType.detect(header(it), audio) }
        require(if (audio) type.mime.startsWith("audio/") else type.mime.startsWith("image/") || type.mime.startsWith("video/")) {
            "Unexpected saved MIME ${type.mime}"
        }
        MediaSaved.publish(host, file, type)
        if (Build.VERSION.SDK_INT < 29) {
            require(hasLegacyStorage(host)) { "Storage permission is required on Android 8–9" }
            saveLegacy(host, file, type, audio, folder, name)
        } else {
            saveScoped(host, file, type, audio, folder, name)
        }
    }

    override fun saveText(context: Any, name: String, text: String): String {
        val host = (context as? Context)?.applicationContext ?: error("No host Context to save with")
        val filename = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.take(100).ifEmpty { "details.txt" }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = host.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Nuke")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Unable to create the file" }
            try {
                checkNotNull(resolver.openOutputStream(uri)) { "Unable to open the file" }.writer(Charsets.UTF_8).use { it.write(text) }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) { "Unable to publish the file" }
                return "${Environment.DIRECTORY_DOWNLOADS}/Nuke/$filename"
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
        val directory = File(checkNotNull(host.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)) { "No documents folder" }, "Nuke")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create ${directory.name}" }
        val file = File(directory, filename)
        try {
            file.writeText(text, Charsets.UTF_8)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return file.absolutePath
    }

    private fun saveLegacy(host: Context, output: File, type: MediaFileType, audio: Boolean, folder: ImageFolder, name: String?) {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(
                when {
                    audio -> Environment.DIRECTORY_MUSIC
                    isVideo(type, audio) -> Environment.DIRECTORY_MOVIES
                    else -> Environment.DIRECTORY_PICTURES
                },
            ),
            if (audio) "douyin/audio" else "douyin/${folder.directory}",
        )
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create ${directory.name}" }
        val destination = freeFile(directory, fileName(name, folder.directory, type.extension))
        try {
            output.inputStream().use { input -> destination.outputStream().use { target -> input.copyTo(target) } }
            MediaScannerConnection.scanFile(host, arrayOf(destination.path), arrayOf(type.mime), null)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    // The user picked a SAF folder: the file goes there under the template name, whatever the gallery would have done.
    private fun saveTree(host: Context, output: File, type: MediaFileType, tree: String, name: String?) {
        val treeUri = Uri.parse(tree)
        val resolver = host.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val created = checkNotNull(DocumentsContract.createDocument(resolver, parent, type.mime, fileName(name, "saf", type.extension))) {
            "Unable to create the document"
        }
        try {
            checkNotNull(resolver.openOutputStream(created)) { "Unable to open the document" }.use { target ->
                output.inputStream().use { input -> input.copyTo(target) }
            }
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw error
        }
    }

    private fun saveScoped(host: Context, output: File, type: MediaFileType, audio: Boolean, folder: ImageFolder, name: String?) {
        val collection = when {
            audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            isVideo(type, audio) -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(name, folder.directory, type.extension))
            put(MediaStore.MediaColumns.MIME_TYPE, type.mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                when {
                    audio -> "${Environment.DIRECTORY_MUSIC}/douyin/audio"
                    isVideo(type, audio) -> "${Environment.DIRECTORY_MOVIES}/douyin/${folder.directory}"
                    else -> "${Environment.DIRECTORY_PICTURES}/douyin/${folder.directory}"
                },
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = host.contentResolver
        val uri = checkNotNull(resolver.insert(collection, values)) { "Unable to create the media entry" }
        try {
            checkNotNull(resolver.openOutputStream(uri)) { "Unable to open the media output" }.use { target ->
                output.inputStream().use { input -> input.copyTo(target) }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "Unable to publish the media" }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun isVideo(type: MediaFileType, audio: Boolean) = !audio && type.mime.startsWith("video/")

    // What the gallery shows: the caller's name, or the folder and a random tail. The extension is always the one of
    // the file that was actually saved, since a sticker becomes a GIF and a VVIC image becomes a PNG.
    private fun fileName(name: String?, prefix: String, extension: String): String {
        val base = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MediaNaming.MAX_BYTES) ?: "${prefix}_${UUID.randomUUID()}"
        return if (base.endsWith(".$extension", ignoreCase = true)) base else "$base.$extension"
    }

    // A legacy save writes straight into the public folder, so a name already there must not be overwritten.
    private fun freeFile(directory: File, name: String): File {
        val candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val next = File(directory, "${stem}_$index$extension")
            if (!next.exists()) return next
            index++
        }
    }

    private fun download(
        url: String,
        target: File,
        progress: (bytes: Long, total: Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val parsed = URL(if (url.startsWith("//")) "https:$url" else url)
        require(parsed.protocol == "https" || parsed.protocol == "http") { "Unsupported media URL" }
        val connection = parsed.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val announced = connection.contentLengthLong.coerceAtLeast(0)
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    var total = 0L
                    while (true) {
                        if (cancelled()) throw DownloadCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BYTES) { "The media exceeds 128 MiB" }
                        output.write(buffer, 0, count)
                        progress(total, announced)
                    }
                    require(total > 0) { "Empty download" }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    // Frames are read one at a time so a long sticker never sits in memory whole.
    private fun toGif(source: File, destination: File) {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                val mime = extractor.getTrackFormat(it).getString("mime").orEmpty()
                mime.startsWith("video/") || mime.startsWith("image/")
            } ?: error("No visual track")
            extractor.selectTrack(track)
            val timestamps = ArrayList<Long>()
            while (extractor.sampleTime >= 0) {
                require(timestamps.size < MAX_FRAMES) { "The sticker has too many frames" }
                timestamps += extractor.sampleTime
                extractor.advance()
            }
            require(timestamps.isNotEmpty()) { "No video frames" }
            retriever.setDataSource(source.path)
            SystemFileSystem.sink(Path(destination.path)).buffered().use { sink ->
                GifEncoder(sink).use { encoder ->
                    timestamps.forEachIndexed { index, time ->
                        val bitmap = (if (Build.VERSION.SDK_INT >= 28) runCatching { retriever.getFrameAtIndex(index) }.getOrNull() else null)
                            ?: retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
                            ?: error("Unable to decode sticker frame $index")
                        try {
                            require(bitmap.width.toLong() * bitmap.height <= MAX_PIXELS) { "A sticker frame is too large" }
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            val next = timestamps.getOrNull(index + 1) ?: (time + if (index > 0) time - timestamps[index - 1] else DEFAULT_FRAME_MICROS)
                            encoder.writeFrame(pixels, bitmap.width, bitmap.height, ((next - time) / 1000).coerceAtLeast(10).milliseconds)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            try { extractor.release() } finally { retriever.release() }
        }
    }

    // The host's own native decoder produces the bitmap; its reference is closed whether or not encoding worked, and
    // the source is only replaced after the PNG is complete.
    override fun convertVvic(file: File) {
        if (runCatching { MediaFileType.detect(file.inputStream().use(::header)).mime }.getOrNull() != "image/vvic") return
        require(file.length() <= MAX_BYTES) { "VVIC image too large" }
        val bytes = file.readBytes()
        val decoder = "com.bytedance.fresco.nativeheif.Heif".toClass(hostClassLoader).resolve().optional(silent = true)
            .method { name = "toRgba"; parameterCount = 15 }.singleOrNull()
            ?: error("The VVIC decoder is unavailable")
        val data = decoder.invoke(bytes, true, bytes.size, true, 0, true, 1, true, 1, 1, -1, -1, -1, -1, true) ?: error("VVIC decoder returned null")
        val reference = data.callByName("newBitmap", null, Bitmap.Config.ARGB_8888) ?: error("VVIC bitmap unavailable")
        val temporary = File.createTempFile("nuke-vvic-", ".png", file.parentFile)
        try {
            val bitmap = reference.callByName("get") as? Bitmap ?: error("VVIC bitmap type mismatch")
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot encode the VVIC image" } }
            check(temporary.renameTo(file)) { "Cannot replace the VVIC image" }
        } finally {
            temporary.delete()
            reference.callByName("close")
        }
    }

    private fun header(input: InputStream): ByteArray {
        val bytes = ByteArray(64)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count <= 0) break
            offset += count
        }
        return bytes.copyOf(offset)
    }

    private companion object {
        const val MAX_LIVE_BYTES = 64L * 1024 * 1024
        const val REQUEST_CODE = 0x4d45
        const val TIMEOUT = 15_000
        const val BUFFER = 64 * 1024
        const val MAX_BYTES = 128L * 1024 * 1024
        const val MAX_FRAMES = 3000
        const val MAX_PIXELS = 16_000_000L
        const val DEFAULT_FRAME_MICROS = 100_000L
    }
}
