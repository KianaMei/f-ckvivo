package io.github.nuke.oss.app.tiktok.sdk.media

import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.shared.reflect.SerializedField
import java.lang.reflect.Method
import java.util.Locale

// Pure selection logic for Douyin video and image post media URLs. Keeps photo-post detection,
// DASH audio exclusion, codec compatibility ranking and watermark-free image mirror extraction
// testable on the JVM without Android framework classes.
internal object MediaSelectors {
    internal enum class VideoCodec(val priority: Int) {
        H264(0),
        UNKNOWN(1),
        HEVC(2),
        BYTEVC1(3),
    }

    private val isMultiImageCall = NamedCall("isMultiImage")
    private val isImageCall = NamedCall("isImage")
    private val isSlidesCall = NamedCall("isSlides")
    private val isPhotoModeCall = NamedCall("isPhotoMode")
    private val getImagesCall = NamedCall("getImages")
    private val getImageInfosCall = NamedCall("getImageInfos")
    private val getVideoCall = NamedCall("getVideo")
    private val getUrlListCall = NamedCall("getUrlList")
    private val getVideoHeightCall = NamedCall("getVideoHeight")
    private val getVideoWidthCall = NamedCall("getVideoWidth")
    private val getGearNameCall = NamedCall("getGearName")

    private val awemeTypeField = SerializedField("aweme_type")
    private val awemeTypeAlias = NamedFieldAlias("awemeType", "aweme_type")
    private val imagesField = SerializedField("images")
    private val imageInfosField = SerializedField("image_infos")
    private val imagesAlias = NamedFieldAlias("images", "imageInfos", "image_infos")
    private val videoField = NamedFieldAlias("video")

    private val bitRateField = SerializedField("bit_rate")
    private val bitRateAlias = NamedFieldAlias("bit_rate", "bitRate")
    private val mediaTypeField = NamedFieldAlias("mMediaType", "mediaType", "media_type")
    private val gearNameField = SerializedField("gear_name")
    private val gearNameAlias = NamedFieldAlias("gearName", "gear_name")
    private val isBytevc1Field = SerializedField("is_bytevc1")
    private val isBytevc1Alias = NamedFieldAlias("isBytevc1", "is_bytevc1")
    private val isH265Field = SerializedField("is_h265")
    private val isH265Alias = NamedFieldAlias("isH265", "is_h265")
    private val codecTypeField = NamedFieldAlias("mCodecType", "codecType", "codec_type", "codecName")
    private val bitRateValueField = NamedFieldAlias("bitRate", "bit_rate", "mBitrate")

    private val urlListField = SerializedField("url_list")
    private val urlListAlias = NamedFieldAlias("urlList", "url_list")
    private val widthField = SerializedField("width")
    private val widthAlias = NamedFieldAlias("width", "mVWidth", "videoWidth", "video_width")
    private val heightField = SerializedField("height")
    private val heightAlias = NamedFieldAlias("height", "mVHeight", "videoHeight", "video_height")
    private val dataSizeField = SerializedField("data_size")
    private val dataSizeAlias = NamedFieldAlias("dataSize", "data_size", "size", "mSize")

    private val playAddrH264 = NamedFieldAlias("play_addr_h264", "playAddrH264", "play_addr_264", "playAddr264")
    private val playAddrDefault = NamedFieldAlias("play_addr", "playAddr")
    private val playAddr265 = NamedFieldAlias("play_addr_265", "playAddr265", "play_addr_h265", "playAddrH265")
    private val playAddrBytevc1 = NamedFieldAlias("play_addr_bytevc1", "playAddrBytevc1")

    private val watermarkFreeList = NamedFieldAlias("watermarkFreeDownloadUrlList", "watermark_free_download_url_list")
    private val watermarkFreeSerialized = SerializedField("watermark_free_download_url_list")
    private val labelLarge = NamedFieldAlias("labelLarge", "label_large")
    private val labelOrigin = NamedFieldAlias("labelOrigin", "label_origin")
    private val originImage = NamedFieldAlias("origin", "large", "originUrl", "origin_url")
    private val labelThumb = NamedFieldAlias("labelThumb", "label_thumb", "thumb")
    private val downloadUrlListField = NamedFieldAlias("downloadUrlList", "download_url_list")
    private val downloadUrlListSerialized = SerializedField("download_url_list")

    private val PHOTO_AWEME_TYPES = setOf(2, 68, 163)
    private val GEAR_HEIGHT_REGEX = Regex("(?<!\\d)(\\d{3,4})(?!\\d)")

    private data class VideoCandidate(
        val urls: List<String>,
        val height: Int,
        val codec: VideoCodec,
        val bitrate: Long,
        val size: Long,
    )

    internal fun isStreamingMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains(".m3u8") ||
            lower.contains("mpegurl") ||
            lower.contains(".mpd") ||
            lower.contains("dash+xml") ||
            lower.contains("format=hls") ||
            lower.contains("format=dash")
    }

    internal fun isAudioOnlyUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val path = lower.substringBefore('?').substringBefore('#')
        return lower.contains("/audio/") ||
            lower.contains("media-audio") ||
            lower.contains("mime_type=audio") ||
            lower.contains("audio_only") ||
            path.endsWith(".mp3") ||
            path.endsWith(".m4a") ||
            path.endsWith(".aac") ||
            path.endsWith(".ogg") ||
            path.endsWith(".wav") ||
            path.endsWith(".flac")
    }

    // Photo posts often carry a dummy BGM video object. Detecting photo posts first ensures
    // they save their pictures / live photos instead of downloading the background music track.
    internal fun isPhotoPost(aweme: Any): Boolean {
        if (callBool(isMultiImageCall, aweme) ||
            callBool(isImageCall, aweme) ||
            callBool(isSlidesCall, aweme) ||
            callBool(isPhotoModeCall, aweme)
        ) {
            return true
        }
        val type = readInt(aweme, awemeTypeField, awemeTypeAlias)
        if (type != null && type in PHOTO_AWEME_TYPES) return true
        return extractImageList(aweme).isNotEmpty()
    }

    internal fun extractImageList(aweme: Any): List<Any> {
        val raw = runCatching { imagesField.of(aweme)?.get(aweme) as? List<*> }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { imageInfosField.of(aweme)?.get(aweme) as? List<*> }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { imagesAlias.of(aweme)?.get(aweme) as? List<*> }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { getImagesCall.of(aweme)?.invoke(aweme) as? List<*> }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { getImageInfosCall.of(aweme)?.invoke(aweme) as? List<*> }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return raw.filterNotNull()
    }

    // Collects all watermark-free image mirror URLs in priority order, keeping download_url_list
    // strictly as a last-resort fallback.
    internal fun extractImageUrls(image: Any): List<String> {
        val collected = ArrayList<String>()
        fun addFrom(value: Any?) {
            for (url in urlsOfObject(value)) collected += url
        }
        addFrom(runCatching { watermarkFreeSerialized.of(image)?.get(image) }.getOrNull())
        addFrom(runCatching { watermarkFreeList.of(image)?.get(image) }.getOrNull())
        addFrom(runCatching { labelLarge.of(image)?.get(image) }.getOrNull())
        addFrom(runCatching { labelOrigin.of(image)?.get(image) }.getOrNull())
        addFrom(runCatching { originImage.of(image)?.get(image) }.getOrNull())
        addFrom(urlsOfObject(image))
        addFrom(runCatching { labelThumb.of(image)?.get(image) }.getOrNull())
        addFrom(runCatching { downloadUrlListSerialized.of(image)?.get(image) }.getOrNull())
        addFrom(runCatching { downloadUrlListField.of(image)?.get(image) }.getOrNull())
        return mediaUrls(collected)
    }

    // Preferred watermark-free URL list for rewriting Douyin's own image downloader.
    internal fun preferredWatermarkFreeList(image: Any): List<*>? {
        val wmFree = (runCatching { watermarkFreeSerialized.of(image)?.get(image) as? List<*> }.getOrNull()
            ?: runCatching { watermarkFreeList.of(image)?.get(image) as? List<*> }.getOrNull())
            ?.takeIf { it.isNotEmpty() }
        if (wmFree != null) return wmFree
        return (runCatching { urlListField.of(image)?.get(image) as? List<*> }.getOrNull()
            ?: runCatching { urlListAlias.of(image)?.get(image) as? List<*> }.getOrNull()
            ?: runCatching { getUrlListCall.of(image)?.invoke(image) as? List<*> }.getOrNull())
            ?.takeIf { it.isNotEmpty() }
    }

    internal fun extractPictureVideoUrls(image: Any, playMethod: Method? = null): List<String> {
        val clip = runCatching { getVideoCall.of(image)?.invoke(image) }.getOrNull()
            ?: runCatching { videoField.of(image)?.get(image) }.getOrNull()
            ?: return emptyList()
        return selectVideoUrls(clip, playMethod)
    }

    // Selects progressive video URLs ordered by highest compatible resolution (preferring H.264 /
    // HEVC over ByteVC1), skipping DASH audio-only gears and streaming playlists.
    internal fun selectVideoUrls(video: Any, playMethod: Method? = null): List<String> {
        val bitRates = readBitRates(video)
        val candidates = ArrayList<VideoCandidate>()
        for (entry in bitRates) {
            if (entry == null) continue
            if (isDashAudioBitRate(entry)) continue
            val entryCodec = codecOf(entry)
            val gearName = readString(entry, gearNameField, gearNameAlias)
                ?: runCatching { getGearNameCall.of(entry)?.invoke(entry) as? String }.getOrNull()
            val bitrate = readLong(entry, null, bitRateValueField) ?: 0L

            val slots = listOf(
                playAddrH264 to VideoCodec.H264,
                playAddrDefault to entryCodec,
                playAddr265 to VideoCodec.HEVC,
                playAddrBytevc1 to VideoCodec.BYTEVC1,
            )
            for ((slot, slotCodec) in slots) {
                val model = runCatching { slot.of(entry)?.get(entry) }.getOrNull() ?: continue
                val urls = filterProgressiveVideoUrls(urlsOfObject(model))
                if (urls.isEmpty()) continue
                val declaredW = readDimension(entry, model, widthField, widthAlias, getVideoWidthCall)
                val declaredH = readDimension(entry, model, heightField, heightAlias, getVideoHeightCall)
                // Explicitly 0 height with non-positive width marks a DASH audio-only track; never
                // infer a video resolution from its gear_name.
                if (declaredH != null && declaredH == 0 && (declaredW == null || declaredW <= 0)) continue
                val height = effectiveHeight(declaredW, declaredH, gearName)
                val size = readLong(model, dataSizeField, dataSizeAlias) ?: 0L
                val codec = if (slot === playAddrDefault) entryCodec else slotCodec
                candidates += VideoCandidate(urls, height, codec, bitrate, size)
            }
        }

        val ordered = LinkedHashSet<String>()
        val compatible = candidates.filter { it.codec != VideoCodec.BYTEVC1 }
            .sortedWith(
                compareByDescending<VideoCandidate> { it.height }
                    .thenBy { it.codec.priority }
                    .thenByDescending { it.bitrate }
                    .thenByDescending { it.size },
            )
        val bytevc1 = candidates.filter { it.codec == VideoCodec.BYTEVC1 }
            .sortedWith(
                compareByDescending<VideoCandidate> { it.height }
                    .thenByDescending { it.bitrate }
                    .thenByDescending { it.size },
            )
        for (candidate in compatible) ordered.addAll(candidate.urls)
        for (candidate in bytevc1) ordered.addAll(candidate.urls)

        // Append top-level video play addresses as fallbacks (and primary path when bit_rate is absent).
        val topLevel = listOfNotNull(
            playMethod?.let { runCatching { it.invoke(video) }.getOrNull() },
            runCatching { playAddrH264.of(video)?.get(video) }.getOrNull(),
            runCatching { playAddrDefault.of(video)?.get(video) }.getOrNull(),
            runCatching { playAddr265.of(video)?.get(video) }.getOrNull(),
            runCatching { playAddrBytevc1.of(video)?.get(video) }.getOrNull(),
        )
        for (model in topLevel) {
            ordered.addAll(filterProgressiveVideoUrls(urlsOfObject(model)))
        }
        return ordered.toList()
    }

    internal fun effectiveHeight(width: Int?, height: Int?, gearName: String?): Int {
        if (width != null && width > 0 && height != null && height > 0) return minOf(width, height)
        if (height != null && height > 0) return height
        return parseGearHeight(gearName) ?: 0
    }

    internal fun parseGearHeight(gearName: String?): Int? {
        if (gearName.isNullOrBlank()) return null
        return GEAR_HEIGHT_REGEX.findAll(gearName)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in 144..4320 }
    }

    private fun filterProgressiveVideoUrls(raw: List<String>): List<String> =
        mediaUrls(raw).filterNot { isStreamingMediaUrl(it) || isAudioOnlyUrl(it) }

    private fun isDashAudioBitRate(entry: Any): Boolean {
        val mediaType = readInt(entry, null, mediaTypeField)
        if (mediaType != null && mediaType != 0) return true
        val codecStr = readString(entry, null, codecTypeField)?.lowercase(Locale.ROOT)
        if (codecStr != null && (codecStr.contains("aac") || codecStr.contains("mp4a") || codecStr.contains("opus") || codecStr.contains("audio"))) {
            return true
        }
        val h = runCatching { getVideoHeightCall.of(entry)?.invoke(entry) as? Number }.getOrNull()?.toInt()
        val w = runCatching { getVideoWidthCall.of(entry)?.invoke(entry) as? Number }.getOrNull()?.toInt()
        return h != null && h == 0 && (w == null || w <= 0)
    }

    private fun codecOf(entry: Any): VideoCodec {
        if (isTruthy(readAny(entry, isBytevc1Field, isBytevc1Alias))) return VideoCodec.BYTEVC1
        if (isTruthy(readAny(entry, isH265Field, isH265Alias))) return VideoCodec.HEVC
        val raw = readString(entry, null, codecTypeField)?.lowercase(Locale.ROOT) ?: return VideoCodec.H264
        return when {
            raw.contains("bytevc1") -> VideoCodec.BYTEVC1
            raw.contains("265") || raw.contains("hevc") || raw.contains("hvc") -> VideoCodec.HEVC
            raw.contains("264") || raw.contains("avc") -> VideoCodec.H264
            else -> VideoCodec.UNKNOWN
        }
    }

    private fun isTruthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }

    private fun readBitRates(owner: Any): List<Any?> {
        val direct = runCatching { bitRateField.of(owner)?.get(owner) as? List<*> }.getOrNull()
            ?: runCatching { bitRateAlias.of(owner)?.get(owner) as? List<*> }.getOrNull()
        return direct.orEmpty()
    }

    private fun urlsOfObject(value: Any?): List<String> {
        if (value == null) return emptyList()
        if (value is String) return listOf(value)
        if (value is List<*>) return value.filterIsInstance<String>()
        val fromSerialized = runCatching { urlListField.of(value)?.get(value) as? List<*> }.getOrNull()
        if (!fromSerialized.isNullOrEmpty()) return fromSerialized.filterIsInstance<String>()
        val fromField = runCatching { urlListAlias.of(value)?.get(value) as? List<*> }.getOrNull()
        if (!fromField.isNullOrEmpty()) return fromField.filterIsInstance<String>()
        val fromGetter = runCatching { getUrlListCall.of(value)?.invoke(value) as? List<*> }.getOrNull()
        if (!fromGetter.isNullOrEmpty()) return fromGetter.filterIsInstance<String>()
        return emptyList()
    }

    private fun readDimension(entry: Any, model: Any, serialized: SerializedField, alias: NamedFieldAlias, getter: NamedCall): Int? {
        val fromGetter = runCatching { getter.of(entry)?.invoke(entry) as? Number }.getOrNull()?.toInt()
        if (fromGetter != null) return fromGetter
        val fromModel = readInt(model, serialized, alias)
        if (fromModel != null) return fromModel
        return readInt(entry, serialized, alias)
    }

    private fun callBool(call: NamedCall, owner: Any): Boolean =
        runCatching { call.of(owner)?.invoke(owner) as? Boolean }.getOrNull() == true

    private fun readAny(owner: Any, serialized: SerializedField?, alias: NamedFieldAlias): Any? =
        serialized?.let { runCatching { it.of(owner)?.get(owner) }.getOrNull() }
            ?: runCatching { alias.of(owner)?.get(owner) }.getOrNull()

    private fun readInt(owner: Any, serialized: SerializedField?, alias: NamedFieldAlias): Int? =
        (readAny(owner, serialized, alias) as? Number)?.toInt()

    private fun readLong(owner: Any, serialized: SerializedField?, alias: NamedFieldAlias): Long? =
        (readAny(owner, serialized, alias) as? Number)?.toLong()

    private fun readString(owner: Any, serialized: SerializedField?, alias: NamedFieldAlias): String? =
        readAny(owner, serialized, alias) as? String
}
