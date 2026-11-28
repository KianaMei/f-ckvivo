package io.github.nuke.oss.app.tiktok.feature.playback

import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.shared.reflect.SerializedField
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

// Layer 2 (SimVideoUrlModel preload) and Layer 3 (TTVideoEngine / VideoModel ABR) quality pinning.
// All hooks live inside the caller's FeatureScope so disabling SetVideoQuality removes them cleanly,
// and every DASH audio track / audio VideoInfo query is preserved untouched.
internal object PlayerQualityPinning {
    const val SIM_VIDEO_URL_MODEL = "com.ss.android.ugc.playerkit.simapicommon.model.SimVideoUrlModel"
    val DEFAULT_ENGINE_CLASSES = listOf(
        "com.ss.ttvideoengine.TTVideoEngine",
        "com.ss.ttvideoengine.TTVideoEngineImpl",
        "com.ss.ttvideoengine.TTVideoEngineImplV2",
        "com.ss.android.ugc.aweme.video.simplayer.tt.MTTVideoEngine",
    )

    private val GEAR_HEIGHT_REGEX = Regex("(?<!\\d)(\\d{3,4})(?!\\d)")
    private val SIM_SKIP_METHODS = setOf("hashCode", "toString", "getClass", "clone")
    private val SIM_SETTER_NAMES = setOf("setBitRate", "setHitBitrate", "setHitDashVideoBitrate")
    private val ENGINE_MODEL_SETTERS = setOf("setVideoModel", "setPlayItem", "setPreloaderItem", "setVideoInfo")

    private val mediaTypeField = NamedFieldAlias("mMediaType", "mediaType", "media_type")
    private val codecTypeField = NamedFieldAlias("mCodecType", "codecType", "codec_type", "codecName")
    private val widthField = SerializedField("width")
    private val widthAlias = NamedFieldAlias("mVWidth", "videoWidth", "video_width", "width")
    private val heightField = SerializedField("height")
    private val heightAlias = NamedFieldAlias("mVHeight", "videoHeight", "video_height", "height")
    private val sizeField = SerializedField("data_size")
    private val sizeAlias = NamedFieldAlias("mSize", "size", "dataSize", "data_size")
    private val gearNameField = SerializedField("gear_name")
    private val gearNameAlias = NamedFieldAlias("mResolutionStr", "gearName", "gear_name")
    private val resolutionField = NamedFieldAlias("mResolution", "resolution")
    private val bitRateField = NamedFieldAlias("bitRate", "bit_rate")
    private val hitBitrateField = NamedFieldAlias("hitBitrate")
    private val hitDashVideoBitrateField = NamedFieldAlias("hitDashVideoBitrate")
    private val durationField = NamedFieldAlias("duration", "mVideoDuration", "videoDuration")
    private val videoRefField = NamedFieldAlias("mVideoRef", "videoRef")
    private val enableAdaptiveField = NamedFieldAlias("mEnableAdaptive", "enableAdaptive")
    private val playAddrField = NamedFieldAlias("playAddr", "play_addr")

    private val getVideoWidthCall = NamedCall("getVideoWidth")
    private val getVideoHeightCall = NamedCall("getVideoHeight")
    private val getGearNameCall = NamedCall("getGearName")
    private val getResolutionCall = NamedCall("getResolution")
    private val getVideoInfoListCall = NamedCall("getVideoInfoList")
    private val getSizeCall = NamedCall("getSize")

    internal fun targetHeightOf(level: String): Int? = when (level) {
        SetVideoQuality.Q_4K -> 2160
        SetVideoQuality.Q_2K -> 1440
        SetVideoQuality.Q_1080P -> 1080
        SetVideoQuality.Q_720P -> 720
        SetVideoQuality.Q_540P -> 540
        SetVideoQuality.Q_480P -> 480
        SetVideoQuality.Q_360P -> 360
        else -> null
    }

    // Exact height match > highest available below target > lowest available above target.
    internal fun pickTargetHeight(availableHeights: Collection<Int>, level: String): Int? {
        val sorted = availableHeights.filter { it > 0 }.distinct().sorted()
        if (sorted.isEmpty()) return null
        val target = targetHeightOf(level) ?: return when (level) {
            SetVideoQuality.LOWEST -> sorted.first()
            SetVideoQuality.MEDIUM -> sorted[sorted.size / 2]
            else -> sorted.last()
        }
        if (target in sorted) return target
        return sorted.lastOrNull { it < target } ?: sorted.firstOrNull { it > target }
    }

    internal fun isAudioCodec(codec: String?): Boolean {
        val lower = codec?.lowercase(Locale.ROOT) ?: return false
        return lower.contains("aac") ||
            lower.contains("mp4a") ||
            lower.contains("opus") ||
            lower.contains("flac") ||
            lower.contains("mp3") ||
            lower.contains("audio")
    }

    internal fun isVideoCodec(codec: String?): Boolean {
        val lower = codec?.lowercase(Locale.ROOT) ?: return false
        return lower.contains("264") ||
            lower.contains("avc") ||
            lower.contains("265") ||
            lower.contains("hevc") ||
            lower.contains("hvc") ||
            lower.contains("bytevc") ||
            lower.contains("av1") ||
            lower.contains("av01") ||
            lower.contains("vp9")
    }

    internal fun isAudioEntry(entry: Any): Boolean {
        val mediaType = readInt(entry, null, mediaTypeField)
        if (mediaType != null && mediaType != 0) return true
        val codec = readString(entry, null, codecTypeField)
        if (isAudioCodec(codec)) return true
        val w = readWidth(entry)
        val h = readHeight(entry)
        return h != null && h == 0 && (w == null || w <= 0)
    }

    internal fun isConfirmedVideoInfo(info: Any): Boolean {
        if (isAudioEntry(info)) return false
        val codec = readString(info, null, codecTypeField)
        if (codec != null && isVideoCodec(codec)) return true
        val h = heightOf(info)
        return h > 0
    }

    internal fun heightOf(item: Any): Int {
        if (isAudioEntry(item)) return 0
        val w = readWidth(item)
        val h = readHeight(item)
        if (w != null && w > 0 && h != null && h > 0) return minOf(w, h)
        if (h != null && h > 0) return h
        val raw = readString(item, gearNameField, gearNameAlias)
            ?: runCatching { getGearNameCall.of(item)?.invoke(item) as? String }.getOrNull()
            ?: runCatching { getResolutionCall.of(item)?.invoke(item)?.toString() }.getOrNull()
            ?: runCatching { resolutionField.of(item)?.get(item)?.toString() }.getOrNull()
        return parseResolutionHeight(raw) ?: 0
    }

    internal fun sizeOf(item: Any): Long {
        val direct = readLong(item, sizeField, sizeAlias)
        if (direct != null && direct > 0) return direct
        val fromGetter = runCatching { getSizeCall.of(item)?.invoke(item) as? Number }.getOrNull()?.toLong()
        if (fromGetter != null && fromGetter > 0) return fromGetter
        val playAddr = runCatching { playAddrField.of(item)?.get(item) }.getOrNull() ?: return 0L
        return readLong(playAddr, sizeField, sizeAlias) ?: 0L
    }

    internal fun parseResolutionHeight(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val lower = raw.lowercase(Locale.ROOT)
        if (lower.contains("4k") || lower.contains("uhd")) return 2160
        if (lower.contains("2k") || lower.contains("qhd")) return 1440
        return GEAR_HEIGHT_REGEX.findAll(lower)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in 144..4320 }
    }

    fun install(
        scope: FeatureScope,
        hostClassLoader: ClassLoader,
        level: String,
        maxSeconds: Long,
        maxBytes: Long,
        simModelClassName: String = SIM_VIDEO_URL_MODEL,
        engineClassNames: List<String> = DEFAULT_ENGINE_CLASSES,
    ) {
        runCatching { Class.forName(simModelClassName, false, hostClassLoader) }.getOrNull()?.let { simModel ->
            installSimVideoUrlModel(scope, simModel, level, maxSeconds, maxBytes)
        }
        val hookedEngines = HashSet<Class<*>>()
        val hookedModels = ConcurrentHashMap.newKeySet<String>()
        for (name in engineClassNames) {
            val engine = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull() ?: continue
            if (!hookedEngines.add(engine)) continue
            installEngineModelHooks(scope, engine, level, maxSeconds, maxBytes, hookedModels)
        }
    }

    private fun installSimVideoUrlModel(
        scope: FeatureScope,
        simModelClass: Class<*>,
        level: String,
        maxSeconds: Long,
        maxBytes: Long,
    ) {
        val inPin = ThreadLocal.withInitial { false }
        val pinnedSizes = Collections.synchronizedMap(WeakHashMap<Any, Int>())
        val methods = runCatching { simModelClass.declaredMethods }.getOrNull() ?: return
        var index = 0
        for (method in methods) {
            if (method.isSynthetic || Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) continue
            val isZeroArg = method.parameterCount == 0 && method.name !in SIM_SKIP_METHODS
            val isSetter = method.name in SIM_SETTER_NAMES && method.parameterCount == 1
            if (!isZeroArg && !isSetter) continue
            method.isAccessible = true
            val hookId = "QualitySim${index++}"
            runCatching {
                scope.hooks.install(method, HookSpec(
                    id = hookId,
                    before = { call ->
                        val model = call.receiver ?: return@HookSpec
                        if (inPin.get()) return@HookSpec
                        inPin.set(true)
                        try {
                            pinSimModel(model, level, maxSeconds, maxBytes, pinnedSizes)
                        } finally {
                            inPin.set(false)
                        }
                    },
                ))
            }
        }
    }

    private fun pinSimModel(
        model: Any,
        level: String,
        maxSeconds: Long,
        maxBytes: Long,
        pinnedSizes: MutableMap<Any, Int>,
    ) {
        if (maxSeconds > 0) {
            val seconds = durationSecondsOf(model)
            if (seconds > maxSeconds) return
        }
        val bitRateF = bitRateField.of(model) ?: return
        val raw = runCatching { bitRateF.get(model) as? List<*> }.getOrNull() ?: return
        if (raw.isEmpty()) return
        if (pinnedSizes[model] == raw.size) return

        val videos = ArrayList<Any>()
        val nonVideos = ArrayList<Any>()
        for (item in raw) {
            if (item == null) continue
            if (isAudioEntry(item) || heightOf(item) <= 0) nonVideos += item else videos += item
        }
        if (videos.isEmpty()) {
            pinnedSizes[model] = raw.size
            return
        }
        val target = pickTargetHeight(videos.map(::heightOf), level) ?: return
        val matched = videos.filter { heightOf(it) == target }
        if (matched.isEmpty()) return
        if (maxBytes > 0 && matched.all { sizeOf(it).let { s -> s > 0 && s > maxBytes } }) {
            pinnedSizes[model] = raw.size
            return
        }

        val combined = ArrayList<Any>(matched.size + nonVideos.size).apply {
            addAll(matched)
            addAll(nonVideos)
        }
        if (combined.size != raw.size) {
            runCatching { bitRateF.set(model, combined) }
        }
        val best = matched.maxByOrNull(::sizeOf) ?: matched.first()
        syncTypedField(hitBitrateField.of(model), model, best)
        syncTypedField(hitDashVideoBitrateField.of(model), model, best)
        pinnedSizes[model] = combined.size
    }

    private fun installEngineModelHooks(
        scope: FeatureScope,
        engineClass: Class<*>,
        level: String,
        maxSeconds: Long,
        maxBytes: Long,
        hookedModels: MutableSet<String>,
    ) {
        val methods = runCatching { engineClass.declaredMethods }.getOrNull() ?: return
        var index = 0
        for (method in methods) {
            if (method.isSynthetic || Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) continue
            if (method.name !in ENGINE_MODEL_SETTERS || method.parameterCount == 0) continue
            method.isAccessible = true
            val hookId = "QualityEng:${engineClass.simpleName}:${index++}"
            runCatching {
                scope.hooks.install(method, HookSpec(
                    id = hookId,
                    before = { call ->
                        for (argIndex in 0 until method.parameterCount) {
                            val candidate = call.argument(argIndex) ?: continue
                            inspectVideoModel(scope, candidate, level, maxSeconds, maxBytes, hookedModels)
                        }
                    },
                ))
            }
        }
    }

    private fun inspectVideoModel(
        scope: FeatureScope,
        candidate: Any,
        level: String,
        maxSeconds: Long,
        maxBytes: Long,
        hookedModels: MutableSet<String>,
    ) {
        val type = candidate.javaClass
        if (type.isPrimitive || type.name.startsWith("java.") || type.name.startsWith("kotlin.")) return
        val hasListMethod = getVideoInfoListCall.of(candidate) != null
        val ref = runCatching { videoRefField.of(candidate)?.get(candidate) }.getOrNull()
        if (!hasListMethod && ref == null) return

        // Disable ABR adaptive switching on VideoRef unless skipped by duration limit.
        if (ref != null) {
            if (maxSeconds > 0 && durationSecondsOf(ref) > maxSeconds) return
            val adaptive = enableAdaptiveField.of(ref)
            if (adaptive != null && adaptive.type == Boolean::class.javaPrimitiveType) {
                runCatching { adaptive.setBoolean(ref, false) }
            }
        }
        if (!hasListMethod || !hookedModels.add(type.name)) return
        hookVideoModelClass(scope, type, level, maxSeconds, maxBytes)
    }

    private fun hookVideoModelClass(
        scope: FeatureScope,
        modelType: Class<*>,
        level: String,
        maxSeconds: Long,
        maxBytes: Long,
    ) {
        val inModel = ThreadLocal.withInitial { false }
        val methods = generateSequence<Class<*>>(modelType) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { cls -> runCatching { cls.declaredMethods.asSequence() }.getOrDefault(emptySequence()) }
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) || Modifier.isAbstract(it.modifiers) }
            .toList()

        // 1. getVideoInfoList(): filter video tracks to target height while keeping ALL audio tracks.
        val listMethod = methods.firstOrNull { it.name == "getVideoInfoList" && it.parameterCount == 0 }
        if (listMethod != null) {
            listMethod.isAccessible = true
            runCatching {
                scope.hooks.install(listMethod, HookSpec(
                    id = "QualityModelList:${modelType.simpleName}",
                    requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE),
                    after = { call ->
                        if (inModel.get()) return@HookSpec
                        val returned = (call.outcome as? CallOutcome.Returned)?.value as? List<*> ?: return@HookSpec
                        val model = call.receiver ?: return@HookSpec
                        if (exceedsDuration(model, maxSeconds)) return@HookSpec
                        val filtered = filterVideoInfoList(returned, level, maxBytes) ?: return@HookSpec
                        call.returnValue(filtered)
                    },
                ))
            }
        }

        // 2. getSupportResolutions(): report only resolutions matching the pinned video infos.
        val resolutionsMethod = methods.firstOrNull { it.name == "getSupportResolutions" && it.parameterCount == 0 && it.returnType.isArray }
        if (resolutionsMethod != null && listMethod != null) {
            resolutionsMethod.isAccessible = true
            runCatching {
                scope.hooks.install(resolutionsMethod, HookSpec(
                    id = "QualityModelRes:${modelType.simpleName}",
                    requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE),
                    after = { call ->
                        if (inModel.get()) return@HookSpec
                        if (call.outcome !is CallOutcome.Returned) return@HookSpec
                        val model = call.receiver ?: return@HookSpec
                        if (exceedsDuration(model, maxSeconds)) return@HookSpec
                        val elemType = resolutionsMethod.returnType.componentType ?: return@HookSpec
                        val infos = readPinnedVideoInfos(model, listMethod, inModel, level, maxBytes) ?: return@HookSpec
                        val resolutions = infos.mapNotNull { resolutionOf(it, elemType) }.distinct()
                        if (resolutions.isEmpty()) return@HookSpec
                        val array = ReflectArray.newInstance(elemType, resolutions.size)
                        resolutions.forEachIndexed { i, res -> ReflectArray.set(array, i, res) }
                        call.returnValue(array)
                    },
                ))
            }
        }

        // 3. VideoInfo query methods (e.g. getVideoInfo(Resolution, ...)): only rewrite confirmed video results!
        if (listMethod != null) {
            var infoIdx = 0
            for (method in methods) {
                if (method.name == "getVideoInfoList" || method.name == "getSupportResolutions") continue
                val retName = method.returnType.simpleName
                if (retName != "VideoInfo") continue
                method.isAccessible = true
                val hookId = "QualityModelInfo:${modelType.simpleName}:${infoIdx++}"
                runCatching {
                    scope.hooks.install(method, HookSpec(
                        id = hookId,
                        requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE),
                        after = { call ->
                            if (inModel.get()) return@HookSpec
                            val returned = (call.outcome as? CallOutcome.Returned)?.value ?: return@HookSpec
                            // Never rewrite an audio VideoInfo lookup!
                            if (!isConfirmedVideoInfo(returned)) return@HookSpec
                            val model = call.receiver ?: return@HookSpec
                            if (exceedsDuration(model, maxSeconds)) return@HookSpec
                            val pinnedVideos = readPinnedVideoInfos(model, listMethod, inModel, level, maxBytes) ?: return@HookSpec
                            if (pinnedVideos.any { it === returned }) return@HookSpec
                            val replacement = pinnedVideos.maxByOrNull(::sizeOf) ?: pinnedVideos.firstOrNull() ?: return@HookSpec
                            if (method.returnType.isInstance(replacement)) {
                                call.returnValue(replacement)
                            }
                        },
                    ))
                }
            }
        }
    }

    internal fun filterVideoInfoList(raw: List<*>, level: String, maxBytes: Long): List<Any>? {
        val videos = ArrayList<Any>()
        val nonVideos = ArrayList<Any>()
        for (item in raw) {
            if (item == null) continue
            if (isAudioEntry(item) || heightOf(item) <= 0) nonVideos += item else videos += item
        }
        if (videos.size < 2) return null
        val target = pickTargetHeight(videos.map(::heightOf), level) ?: return null
        val matched = videos.filter { heightOf(it) == target }
        if (matched.isEmpty()) return null
        if (maxBytes > 0 && matched.all { sizeOf(it).let { s -> s > 0 && s > maxBytes } }) return null
        if (matched.size == videos.size) return null
        return ArrayList<Any>(matched.size + nonVideos.size).apply {
            addAll(matched)
            addAll(nonVideos)
        }
    }

    private fun readPinnedVideoInfos(
        model: Any,
        listMethod: Method,
        inModel: ThreadLocal<Boolean>,
        level: String,
        maxBytes: Long,
    ): List<Any>? {
        inModel.set(true)
        val raw = try {
            runCatching { listMethod.invoke(model) as? List<*> }.getOrNull()
        } finally {
            inModel.set(false)
        } ?: return null
        val videos = raw.filterNotNull().filter { !isAudioEntry(it) && heightOf(it) > 0 }
        if (videos.isEmpty()) return null
        val target = pickTargetHeight(videos.map(::heightOf), level) ?: return null
        val matched = videos.filter { heightOf(it) == target }
        if (matched.isEmpty()) return null
        if (maxBytes > 0 && matched.all { sizeOf(it).let { s -> s > 0 && s > maxBytes } }) return null
        return matched
    }

    private fun exceedsDuration(model: Any, maxSeconds: Long): Boolean {
        if (maxSeconds <= 0) return false
        val ref = runCatching { videoRefField.of(model)?.get(model) }.getOrNull() ?: model
        return durationSecondsOf(ref) > maxSeconds
    }

    private fun durationSecondsOf(owner: Any): Long {
        val raw = readLong(owner, null, durationField) ?: return 0L
        // VideoRef.mVideoDuration is in seconds while Aweme/SimVideoUrlModel duration is often in ms.
        return if (raw > 100_000L) raw / 1000L else raw
    }

    private fun resolutionOf(info: Any, expectedType: Class<*>): Any? {
        val fromGetter = runCatching { getResolutionCall.of(info)?.invoke(info) }.getOrNull()
        if (fromGetter != null && expectedType.isInstance(fromGetter)) return fromGetter
        val fromField = runCatching { resolutionField.of(info)?.get(info) }.getOrNull()
        return fromField?.takeIf(expectedType::isInstance)
    }

    private fun syncTypedField(field: Field?, owner: Any, value: Any) {
        if (field == null || !field.type.isInstance(value)) return
        runCatching { field.set(owner, value) }
    }

    private fun readWidth(item: Any): Int? =
        runCatching { getVideoWidthCall.of(item)?.invoke(item) as? Number }.getOrNull()?.toInt()
            ?: readInt(item, widthField, widthAlias)
            ?: runCatching { playAddrField.of(item)?.get(item) }.getOrNull()?.let { readInt(it, widthField, widthAlias) }

    private fun readHeight(item: Any): Int? =
        runCatching { getVideoHeightCall.of(item)?.invoke(item) as? Number }.getOrNull()?.toInt()
            ?: readInt(item, heightField, heightAlias)
            ?: runCatching { playAddrField.of(item)?.get(item) }.getOrNull()?.let { readInt(it, heightField, heightAlias) }

    private fun readInt(owner: Any, serialized: SerializedField?, alias: NamedFieldAlias): Int? =
        (readAny(owner, serialized, alias) as? Number)?.toInt()

    private fun readLong(owner: Any, serialized: SerializedField?, alias: NamedFieldAlias): Long? =
        (readAny(owner, serialized, alias) as? Number)?.toLong()

    private fun readString(owner: Any, serialized: SerializedField?, alias: NamedFieldAlias): String? =
        readAny(owner, serialized, alias) as? String

    private fun readAny(owner: Any, serialized: SerializedField?, alias: NamedFieldAlias): Any? =
        serialized?.let { runCatching { it.of(owner)?.get(owner) }.getOrNull() }
            ?: runCatching { alias.of(owner)?.get(owner) }.getOrNull()
}
