package io.github.nuke.oss.app.tiktok.injector

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
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
import io.github.nuke.oss.app.tiktok.sdk.media.MediaSelectors
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.microseconds
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
        var fallbackVideoOnly: File? = null
        var fallbackVideoType: MediaFileType? = null
        var converted: File? = null
        try {
            var detected: MediaFileType? = null
            var lastError: Exception? = null
            val expectImage = !audio && folder != ImageFolder.VIDEO
            val expectVideo = !audio && folder == ImageFolder.VIDEO
            for (url in urls.distinct()) {
                try {
                    if (expectVideo && MediaSelectors.isStreamingMediaUrl(url)) {
                        throw IllegalArgumentException("Streaming video URL is not downloadable")
                    }
                    downloadWithUaFallback(url, source, progress, cancelled, isImage = expectImage, isVideo = expectVideo)
                    gunzipIfNeeded(source, host.cacheDir)
                    val candidate = source.inputStream().use { MediaFileType.detect(header(it), audio) }
                    require(when {
                        audio -> candidate.mime.startsWith("audio/")
                        expectVideo -> candidate.mime.startsWith("video/")
                        else -> candidate.mime.startsWith("image/") || candidate.mime.startsWith("video/")
                    }) { "Unexpected downloaded MIME ${candidate.mime}" }
                    if (expectVideo) {
                        val inspection = inspectVideo(source)
                        require(inspection.hasVideo && inspection.canDecode) {
                            "Video track is unplayable (${inspection.videoMime ?: "none"})"
                        }
                        if (!inspection.hasAudio) {
                            // Remember a decodable video-only stream as fallback in case the post is genuinely mute,
                            // while continuing to look for a complete muxed video+audio candidate (e.g. non-DASH MP4).
                            if (fallbackVideoOnly == null) {
                                fallbackVideoOnly = File.createTempFile("nuke-media-vo-", ".mp4", host.cacheDir)
                                source.copyTo(fallbackVideoOnly, overwrite = true)
                                fallbackVideoType = candidate
                            }
                            throw IllegalStateException("Video candidate lacks an audio track")
                        }
                    } else if (expectImage && candidate.mime.startsWith("image/") &&
                        candidate.mime != "image/vvic" && candidate.mime != "image/gif" && candidate.mime != "image/heif-sequence"
                    ) {
                        require(isDecodableImage(source)) { "Downloaded image cannot be decoded (${candidate.mime})" }
                    }
                    detected = candidate
                    break
                } catch (error: java.io.IOException) {
                    lastError = error
                } catch (error: IllegalArgumentException) {
                    lastError = error
                } catch (error: IllegalStateException) {
                    lastError = error
                }
            }
            var output = source
            var type = detected ?: run {
                val fallback = fallbackVideoOnly
                val fallbackType = fallbackVideoType
                if (fallback != null && fallbackType != null) {
                    output = fallback
                    fallbackType
                } else {
                    error("No media URL succeeded: ${lastError?.message}")
                }
            }
            android.util.Log.i("NukeApp-OSS", "media_downloaded source=${type.mime}")
            if (!audio && folder != ImageFolder.VIDEO && (type.mime.startsWith("video/") || type.mime == "image/heif-sequence")) {
                // An animated sticker arrives as a video or as a HEIF image sequence; both become a GIF the gallery can
                // play. A sequence the extractor cannot read is kept as the host served it rather than failing the save.
                val animation = File.createTempFile("nuke-sticker-", ".gif", host.cacheDir)
                val convertedToGif = runCatching { toGif(output, animation) }.isSuccess
                if (convertedToGif) {
                    converted = animation
                    output = animation
                    type = MediaFileType("image/gif", "gif")
                } else {
                    animation.delete()
                    android.util.Log.i("NukeApp-OSS", "media_saved sticker_sequence_unreadable")
                }
            } else if (type.mime == "image/vvic") {
                convertVvic(output)
                type = MediaFileType("image/png", "png")
            } else if (expectImage && type.mime in TRANSCODE_TO_JPEG_MIMES) {
                val jpeg = File.createTempFile("nuke-img-", ".jpg", host.cacheDir)
                if (runCatching { transcodeToJpeg(output, jpeg) }.isSuccess) {
                    converted = jpeg
                    output = jpeg
                    type = MediaFileType("image/jpeg", "jpg")
                } else {
                    jpeg.delete()
                }
            }
            // The file is deleted in the finally block below, so whoever needs the bytes takes them here.
            MediaSaved.publish(host, output, type)
            if (tree != null) {
                saveTree(host, output, type, tree, name)
            } else if (Build.VERSION.SDK_INT < 29) {
                require(hasLegacyStorage(host)) { "Storage permission is required on Android 8–9" }
                saveLegacy(host, output, type, audio, folder, name)
            } else {
                saveScoped(host, output, type, audio, folder, name)
            }
        } finally {
            source.delete()
            fallbackVideoOnly?.delete()
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
        val still = File.createTempFile("nuke-live-still-", ".img", host.cacheDir)
        var stillJpeg: File? = null
        val clip = File.createTempFile("nuke-live-video-", ".mp4", host.cacheDir)
        val packed = File.createTempFile("nuke-live-", ".jpg", host.cacheDir)
        try {
            downloadFirst(imageUrls, still, progress, cancelled, cacheDir = host.cacheDir, isImage = true, isVideo = false)
            val stillType = still.inputStream().use { MediaFileType.detect(header(it), audio = false) }
            val readyStill = if (stillType.mime == "image/jpeg") {
                still
            } else {
                if (stillType.mime == "image/vvic") convertVvic(still)
                File.createTempFile("nuke-live-still-", ".jpg", host.cacheDir).also { target ->
                    transcodeToJpeg(still, target)
                    stillJpeg = target
                }
            }
            downloadFirst(videoUrls, clip, { _, _ -> }, cancelled, cacheDir = host.cacheDir, isImage = false, isVideo = true)
            require(readyStill.length() <= MAX_LIVE_BYTES && clip.length() <= MAX_LIVE_BYTES) { "The live photo is too large" }
            packed.writeBytes(MotionPhoto.wrap(readyStill.readBytes(), clip.readBytes()))
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
            stillJpeg?.delete()
            clip.delete()
            packed.delete()
        }
    }

    // The first address that answers, written to target; the same choice save makes, for a second file.
    private fun downloadFirst(
        urls: List<String>,
        target: File,
        progress: (Long, Long) -> Unit,
        cancelled: () -> Boolean,
        cacheDir: File,
        isImage: Boolean,
        isVideo: Boolean,
    ) {
        var lastError: Exception? = null
        for (url in urls.distinct()) {
            try {
                if (isVideo && MediaSelectors.isStreamingMediaUrl(url)) {
                    throw IllegalArgumentException("Streaming video URL is not downloadable")
                }
                downloadWithUaFallback(url, target, progress, cancelled, isImage = isImage, isVideo = isVideo)
                gunzipIfNeeded(target, cacheDir)
                if (isVideo) {
                    val inspection = inspectVideo(target)
                    require(inspection.hasVideo && inspection.canDecode) {
                        "Live photo video track is unplayable (${inspection.videoMime ?: "none"})"
                    }
                }
                return
            } catch (error: java.io.IOException) {
                lastError = error
            } catch (error: IllegalArgumentException) {
                lastError = error
            } catch (error: IllegalStateException) {
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

    private fun downloadWithUaFallback(
        url: String,
        target: File,
        progress: (bytes: Long, total: Long) -> Unit,
        cancelled: () -> Boolean,
        isImage: Boolean,
        isVideo: Boolean,
    ) {
        val agents = listOfNotNull(
            System.getProperty("http.agent")?.takeIf { it.isNotBlank() },
            BROWSER_USER_AGENT,
        ).distinct()
        var lastError: Exception? = null
        for (agent in agents) {
            try {
                download(url, target, progress, cancelled, userAgent = agent, isImage = isImage, isVideo = isVideo)
                return
            } catch (cancelledError: DownloadCancelled) {
                throw cancelledError
            } catch (error: java.io.IOException) {
                lastError = error
            } catch (error: IllegalArgumentException) {
                lastError = error
            } catch (error: IllegalStateException) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Download failed")
    }

    private fun download(
        url: String,
        target: File,
        progress: (bytes: Long, total: Long) -> Unit,
        cancelled: () -> Boolean,
        userAgent: String = BROWSER_USER_AGENT,
        isImage: Boolean = false,
        isVideo: Boolean = false,
    ) {
        val parsed = URL(if (url.startsWith("//")) "https:$url" else url)
        require(parsed.protocol == "https" || parsed.protocol == "http") { "Unsupported media URL" }
        val connection = parsed.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Referer", "https://www.douyin.com/")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty(
                "Accept",
                if (isImage) "image/jpeg,image/png,image/webp,image/*;q=0.8" else "*/*",
            )
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            if (isVideo && (contentType.contains("mpegurl") || contentType.contains("dash+xml"))) {
                throw IllegalArgumentException("Streaming content type $contentType")
            }
            if (isImage && (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("html"))) {
                throw IllegalArgumentException("Non-image response $contentType")
            }
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
                    if (announced > 0) {
                        require(total == announced) { "Incomplete download ($total / $announced bytes)" }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun gunzipIfNeeded(file: File, cacheDir: File) {
        val magic = file.inputStream().use { input ->
            val b0 = input.read()
            val b1 = input.read()
            b0 == 0x1f && b1 == 0x8b
        }
        if (!magic) return
        val unpacked = File.createTempFile("nuke-gunzip-", ".bin", cacheDir)
        try {
            GZIPInputStream(file.inputStream()).use { input ->
                unpacked.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BYTES) { "Unpacked media exceeds 128 MiB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            unpacked.copyTo(file, overwrite = true)
        } finally {
            unpacked.delete()
        }
    }

    private data class VideoInspection(val hasVideo: Boolean, val hasAudio: Boolean, val videoMime: String?, val canDecode: Boolean)

    private fun inspectVideo(file: File): VideoInspection {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            var hasVideo = false
            var hasAudio = false
            var videoMime: String? = null
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> {
                        hasVideo = true
                        if (videoMime == null) videoMime = mime
                    }
                    mime.startsWith("audio/") -> hasAudio = true
                }
            }
            val canDecode = videoMime != null && isVideoMimeDecodable(videoMime)
            VideoInspection(hasVideo, hasAudio, videoMime, canDecode)
        } catch (_: Exception) {
            VideoInspection(hasVideo = false, hasAudio = false, videoMime = null, canDecode = false)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun isVideoMimeDecodable(mime: String): Boolean {
        val lower = mime.lowercase(Locale.ROOT)
        if (lower.contains("bytevc1")) return false
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    private fun isDecodableImage(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun transcodeToJpeg(source: File, destination: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable image bounds" }
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_IMAGE_PIXELS) {
            sample *= 2
        }
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = checkNotNull(BitmapFactory.decodeFile(source.path, decode)) { "Cannot decode image" }
        try {
            destination.outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "Cannot encode JPEG" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    // Frames are decoded through the platform codec, not MediaMetadataRetriever: for HEIF image sequences (major brand
    // msf1) the retriever hands back the raw samples, which is why saved stickers used to flicker into grey frames. Only
    // one frame is held while the next decodes, so a long sticker never sits in memory whole.
    private fun toGif(source: File, destination: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                val mime = extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/") || mime.startsWith("image/")
            } ?: error("No visual track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            // An image sequence still carries HEVC/AV1 samples, which the video decoders accept.
            val declared = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val mime = when {
                declared.startsWith("video/") -> declared
                declared.startsWith("image/avif") -> "video/av01"
                else -> "video/hevc"
            }
            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()
            SystemFileSystem.sink(Path(destination.path)).buffered().use { sink ->
                GifEncoder(sink).use { encoder ->
                    val info = MediaCodec.BufferInfo()
                    var frames = 0
                    var inputDone = false
                    var outputDone = false
                    var pending: IntArray? = null
                    var pendingWidth = 0
                    var pendingHeight = 0
                    var pendingTime = 0L
                    fun encode(pixels: IntArray, width: Int, height: Int, delay: Long) {
                        require(frames < MAX_FRAMES) { "The sticker has too many frames" }
                        require(width.toLong() * height <= MAX_PIXELS) { "A sticker frame is too large" }
                        encoder.writeFrame(pixels, width, height, delay.coerceAtLeast(10_000L).microseconds)
                        frames++
                    }
                    fun push(pixels: IntArray, width: Int, height: Int, time: Long) {
                        pending?.let { encode(it, pendingWidth, pendingHeight, time - pendingTime) }
                        pending = pixels
                        pendingWidth = width
                        pendingHeight = height
                        pendingTime = time
                    }
                    while (!outputDone) {
                        if (!inputDone) {
                            val input = decoder.dequeueInputBuffer(CODEC_TIMEOUT_MICROS)
                            if (input >= 0) {
                                val buffer = checkNotNull(decoder.getInputBuffer(input))
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        when (val index = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_MICROS)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER,
                            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                            else -> if (index >= 0) {
                                val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val image = decoder.getOutputImage(index) ?: error("The sticker decoder returned no image")
                                    try {
                                        push(yuvFrame(image), image.width, image.height, info.presentationTimeUs)
                                    } finally {
                                        image.close()
                                    }
                                }
                                decoder.releaseOutputBuffer(index, false)
                                if (last) outputDone = true
                            }
                        }
                    }
                    pending?.let { encode(it, pendingWidth, pendingHeight, DEFAULT_FRAME_MICROS) }
                    check(frames > 0) { "No video frames" }
                }
            }
        } finally {
            try { codec?.release() } finally { extractor.release() }
        }
    }

    // The codec's YUV_420_888 output (planar or semi-planar, either pixel stride) as ARGB, BT.601 limited range.
    private fun yuvFrame(image: Image): IntArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            val yRow = row * yPlane.rowStride
            val chromaRow = row shr 1
            for (col in 0 until width) {
                val luma = ((yBuffer.get(yRow + col * yPlane.pixelStride).toInt() and 0xFF) - 16).coerceAtLeast(0)
                val cb = (uBuffer.get(chromaRow * uPlane.rowStride + (col shr 1) * uPlane.pixelStride).toInt() and 0xFF) - 128
                val cr = (vBuffer.get(chromaRow * vPlane.rowStride + (col shr 1) * vPlane.pixelStride).toInt() and 0xFF) - 128
                val red = ((298 * luma + 409 * cr + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * luma - 100 * cb - 208 * cr + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * luma + 516 * cb + 128) shr 8).coerceIn(0, 255)
                pixels[row * width + col] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return pixels
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
        const val MAX_IMAGE_PIXELS = 24_000_000L
        const val JPEG_QUALITY = 92
        const val DEFAULT_FRAME_MICROS = 100_000L
        const val CODEC_TIMEOUT_MICROS = 10_000L
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        val TRANSCODE_TO_JPEG_MIMES = setOf("image/avif", "image/heic", "image/heif", "image/webp")
    }
}
