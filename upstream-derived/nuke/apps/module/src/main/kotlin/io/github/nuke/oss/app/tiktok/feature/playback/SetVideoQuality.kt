package io.github.nuke.oss.app.tiktok.feature.playback

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Modifier

// One playable rendition of a video: its pixel size and file size.
internal data class Rendition(val width: Int, val height: Int, val size: Long)

// Picks the rendition for a level. Non-video (0-dimension / DASH audio) renditions are ignored.
// Exact resolution targets (360p..4k) match by short side (exact > highest below > lowest above);
// highest/medium/lowest rank by pixel area, then width, then height; largest file wins ties.
internal fun chooseRendition(renditions: List<Rendition>, level: String): Rendition? {
    val valid = renditions.filter { it.width > 0 && it.height > 0 }
    if (valid.isEmpty()) return null
    val target = PlayerQualityPinning.targetHeightOf(level)
    if (target != null) {
        val chosenHeight = PlayerQualityPinning.pickTargetHeight(valid.map { minOf(it.width, it.height) }, level) ?: return null
        return valid.filter { minOf(it.width, it.height) == chosenHeight }
            .maxWithOrNull(compareBy<Rendition>({ it.width.toLong() * it.height }, { it.size }))
    }
    val sizes = valid.distinctBy { it.width to it.height }
        .sortedWith(compareBy<Rendition>({ it.width.toLong() * it.height }, { it.width }, { it.height }))
    if (sizes.isEmpty()) return null
    val picked = when (level) {
        SetVideoQuality.LOWEST -> sizes.first()
        SetVideoQuality.MEDIUM -> sizes[sizes.size / 2]
        else -> sizes.last()
    }
    return valid.filter { it.width == picked.width && it.height == picked.height }.maxByOrNull { it.size }
}

// Chooses which bitrate every feed video plays across all three layers: feed model addresses,
// SimVideoUrlModel preloading and TTVideoEngine/VideoModel ABR selection. DASH audio tracks are
// never rewritten. Videos longer or larger than the limits keep the host's choice.
@HookEntry
internal class SetVideoQuality(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val awemeName: String = AWEME,
    private val itemListName: String = FEED_ITEM_LIST,
    private val simModelName: String = PlayerQualityPinning.SIM_VIDEO_URL_MODEL,
    private val engineNames: List<String> = PlayerQualityPinning.DEFAULT_ENGINE_CLASSES,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.FEED_ADAPTER_CHANGED, OptionQueries.FEED_RESULT)
    private val level = options.choice(LEVEL)
    private val maxSeconds = options.number(SKIP_LONGER_THAN)
    private val maxBytes = options.number(SKIP_LARGER_THAN) * 1024 * 1024
    private val getVideo = NamedCall("getVideo")
    private val getItems = NamedCall("getItems")
    private val duration = NamedField("duration")
    private val bitRate = NamedField("bit_rate")
    private val addresses = listOf("play_addr_265", "play_addr_h264", "play_addr").map(::NamedField)
    private val entryAddresses = listOf("play_addr", "play_addr_265", "play_addr_264").map(::NamedField)
    private val urlList = NamedField("url_list")
    private val width = NamedField("width")
    private val height = NamedField("height")
    private val dataSize = NamedField("data_size")

    private class Address(val model: Any, val urls: MutableList<Any?>, val rendition: Rendition)

    override fun install(scope: FeatureScope) {
        val aweme = awemeName.toClass(hostClassLoader)
        val itemList = itemListName.toClass(hostClassLoader)
        val setItems = itemList.resolve().firstMethod { name = "setItems"; parameters(List::class) }.self.apply { isAccessible = true }
        val (adapterChanged, feedResult) = resolver.methods(hostClassLoader, OptionQueries.FEED_ADAPTER_CHANGED, OptionQueries.FEED_RESULT)
        val resultMethods = feedResult.declaringClass.resolve().optional(silent = true).method {
            name = feedResult.name
            parameterCount { it >= 1 }
            isSynthetic = false
        }.map { it.self }
        for (method in listOf(adapterChanged) + resultMethods) method.isAccessible = true
        scope.hooks.install(setItems, HookSpec(id = "QualitySetItems", before = { call -> applyAll(call.argument(0), aweme) }))
        scope.hooks.install(adapterChanged, HookSpec(id = "QualityAdapter", after = { call ->
            val adapter = call.receiver
            if (call.outcome is CallOutcome.Returned && adapter != null) applyAll(getItems.of(adapter)?.invoke(adapter), aweme)
        }))
        resultMethods.forEachIndexed { index, method ->
            scope.hooks.install(method, HookSpec(id = "QualityResult$index", after = { call ->
                val list = findItemList(call.argument(0), itemList, HashSet(), 0)
                if (list != null) for (field in publicListFields(list.javaClass)) applyAll(field.get(list), aweme)
            }))
        }
        // Layers 2 & 3: pin preload SimVideoUrlModel bitrates and disable TTVideoEngine ABR downgrades.
        PlayerQualityPinning.install(scope, hostClassLoader, level, maxSeconds, maxBytes, simModelName, engineNames)
    }

    private fun applyAll(items: Any?, aweme: Class<*>) {
        val videos = (items as? List<*>)?.let(::ArrayList) ?: return
        for (video in videos) if (video != null && aweme.isInstance(video)) apply(video)
    }

    private fun apply(aweme: Any) {
        val seconds = (duration.of(aweme)?.get(aweme) as? Int ?: 0) / 1000L
        if (maxSeconds > 0 && seconds > maxSeconds) return
        val video = getVideo.of(aweme)?.invoke(aweme) ?: return
        val found = addressesOf(video)
        val chosen = chooseRendition(found.map { it.rendition }, level) ?: return
        if (maxBytes > 0 && chosen.size > maxBytes) return
        val source = found.first { it.rendition == chosen }
        val chosenUrls = ArrayList(source.urls)
        for (address in found) {
            width.of(address.model)?.setInt(address.model, chosen.width)
            height.of(address.model)?.setInt(address.model, chosen.height)
            dataSize.of(address.model)?.setLong(address.model, chosen.size)
            address.urls.clear()
            address.urls.addAll(chosenUrls)
        }
    }

    // The video bitrate entries of the video itself and of each of its play addresses, skipping DASH audio entries.
    private fun addressesOf(video: Any): List<Address> {
        val entries = ArrayList<Any?>(bitRates(video))
        for (field in addresses) field.of(video)?.get(video)?.let { entries.addAll(bitRates(it)) }
        return entries.filterNotNull()
            .filterNot(PlayerQualityPinning::isAudioEntry)
            .flatMap { entry -> entryAddresses.mapNotNull { it.of(entry)?.get(entry)?.let(::address) } }
    }

    private fun bitRates(owner: Any): List<Any?> = bitRate.of(owner)?.get(owner) as? List<*> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun address(model: Any): Address? {
        val urls = urlList.of(model)?.get(model) as? MutableList<Any?> ?: return null
        val w = width.of(model)?.takeIf { it.type == Int::class.javaPrimitiveType } ?: return null
        val h = height.of(model)?.takeIf { it.type == Int::class.javaPrimitiveType } ?: return null
        val size = dataSize.of(model)?.takeIf { it.type == Long::class.javaPrimitiveType } ?: return null
        val widthVal = w.getInt(model)
        val heightVal = h.getInt(model)
        if (widthVal <= 0 || heightVal <= 0) return null
        return Address(model, urls, Rendition(widthVal, heightVal, size.getLong(model)))
    }

    // The coroutine result holds the feed list somewhere in its public fields; the search is bounded and never revisits.
    private fun findItemList(value: Any?, type: Class<*>, seen: MutableSet<Any>, depth: Int): Any? {
        if (value == null || depth > MAX_DEPTH || !seen.add(value)) return null
        if (type.isInstance(value)) return value
        if (value.javaClass.name.startsWith("java.")) return null
        for (field in publicFields(value.javaClass)) {
            findItemList(field.get(value), type, seen, depth + 1)?.let { return it }
        }
        return null
    }

    private val publicFieldCache = object : ClassValue<List<java.lang.reflect.Field>>() {
        override fun computeValue(type: Class<*>) = type.fields.filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
            .onEach { it.isAccessible = true }
    }

    private fun publicFields(type: Class<*>) = publicFieldCache.get(type)
    private fun publicListFields(type: Class<*>) = publicFields(type).filter { it.type == List::class.java }

    companion object : TikTokFeature {
        const val ID = "SetVideoQuality"
        const val LEVEL = "level"
        const val SKIP_LONGER_THAN = "skip-longer-than"
        const val SKIP_LARGER_THAN = "skip-larger-than"
        const val HIGHEST = "highest"
        const val Q_4K = "4k"
        const val Q_2K = "2k"
        const val Q_1080P = "1080p"
        const val Q_720P = "720p"
        const val Q_540P = "540p"
        const val Q_480P = "480p"
        const val Q_360P = "360p"
        const val MEDIUM = "medium"
        const val LOWEST = "lowest"
        const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"
        const val FEED_ITEM_LIST = "com.ss.android.ugc.aweme.feed.model.FeedItemList"
        private const val MAX_DEPTH = 4
        val OPTIONS = listOf(
            ChoiceOption(LEVEL, listOf(HIGHEST, Q_4K, Q_2K, Q_1080P, Q_720P, Q_540P, Q_480P, Q_360P, MEDIUM, LOWEST)),
            NumberOption(SKIP_LONGER_THAN, default = 0, min = 0, max = 86_400),
            NumberOption(SKIP_LARGER_THAN, default = 0, min = 0, max = 10_240),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(SetVideoQuality(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
