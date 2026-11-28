package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.general.*
import io.github.nuke.oss.app.tiktok.feature.playback.*
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.core.Logger
import org.junit.Assert.*
import org.junit.Test

// Contract tests for the autoplay, video quality and plugin features against stand-ins for the host classes. They
// prove the Hook semantics and the option handling, not that any Douyin build has these targets.
class PlaybackFeaturesTest : HookHarness() {
    open class BaseLiveData { fun postValue(value: Any?) { posted += value } }
    class FakeLiveData : BaseLiveData()
    class AutoPlayModel(val name: String) {
        private val autoplay = FakeLiveData()
        fun autoplay(): FakeLiveData = autoplay
        fun label(): String = name
    }
    class TwoLiveDataModel {
        fun first(): FakeLiveData = FakeLiveData()
        fun second(): FakeLiveData = FakeLiveData()
    }

    private fun autoplay(model: Class<*>) = KeepAutoPlayOn(
        loader, FeedEvents(resolver(), loader), ManualScheduler(), { RecordingAutoPlayButton() }, values(KeepAutoPlayOn.ID, KeepAutoPlayOn.OPTIONS), model.name, FakeLiveData::class.java.name,
    )

    @Test fun `autoplay is switched on whenever the view model is created`() {
        posted.clear()
        val feature = autoplay(AutoPlayModel::class.java)
        enable(feature)
        val constructor = AutoPlayModel::class.java.getDeclaredConstructor(String::class.java)
        backend.call(constructor, null, "a")
        assertEquals(listOf<Any?>(true), posted)
        disable(feature)
        backend.call(constructor, null, "b")
        assertEquals(1, posted.size)
    }

    class RightBar

    @Test fun `the switch button turns Douyin's autoplay off and on`() {
        posted.clear()
        val ui = RecordingAutoPlayButton()
        enable(KeepAutoPlayOn(
            loader, FeedEvents(resolver(), loader), ManualScheduler(), { ui }, values(KeepAutoPlayOn.ID, KeepAutoPlayOn.OPTIONS, "button" to true),
            AutoPlayModel::class.java.name, FakeLiveData::class.java.name, RightBar::class.java.name,
        ))
        val constructor = AutoPlayModel::class.java.getDeclaredConstructor(String::class.java)
        backend.call(constructor, null, "a")
        backend.call(RightBar::class.java.getDeclaredConstructor())
        val button = ui.attached.single()
        button.onClick()
        assertEquals(listOf<Any?>(true, false), posted)
        assertFalse(button.active())
        // A view model made while the switch is off does not turn autoplay back on.
        backend.call(constructor, null, "b")
        assertEquals(listOf<Any?>(true, false, false), posted)
        button.onClick()
        assertEquals(listOf<Any?>(true, false, false, true), posted)
        assertEquals(listOf(false, true), ui.updates)
    }

    @Test fun `two live data getters make the autoplay setting ambiguous`() {
        failsToInstall(autoplay(TwoLiveDataModel::class.java), "found 2")
    }

    class UrlModel(@JvmField var url_list: MutableList<Any?>?, @JvmField var width: Int, @JvmField var height: Int, @JvmField var data_size: Long)
    class BitRate(@JvmField var play_addr: Any?, @JvmField var play_addr_265: Any? = null, @JvmField var play_addr_264: Any? = null)
    class Video(@JvmField var bit_rate: List<Any?>?, @JvmField var play_addr: Any? = null) {
        @JvmField var play_addr_265: Any? = null
        @JvmField var play_addr_h264: Any? = null
    }
    class FakeAweme(private val video: Video?, @JvmField var duration: Int = 30_000) { fun getVideo(): Video? = video }
    class FakeItemList {
        @JvmField var items: List<Any?>? = null
        fun setItems(list: List<Any?>?) { items = list }
    }
    class FeedAdapter(private val items: List<Any?>) {
        fun getItems(): List<Any?> = items
        fun notifyChanged() {}
    }
    class FeedResultHolder { @JvmField var list: FakeItemList? = null }
    class FeedStep { fun invokeSuspend(result: Any?): Any? = result }

    private fun url(name: String, width: Int, height: Int, size: Long) = UrlModel(mutableListOf(name), width, height, size)

    private fun quality(level: String, longer: Long = 0, larger: Long = 0) = SetVideoQuality(
        resolver(
            OptionQueries.FEED_ADAPTER_CHANGED to listOf(method(FeedAdapter::class.java, "notifyChanged")),
            OptionQueries.FEED_RESULT to listOf(method(FeedStep::class.java, "invokeSuspend", Any::class.java)),
        ),
        loader,
        values(SetVideoQuality.ID, SetVideoQuality.OPTIONS, "level" to level, "skip-longer-than" to longer, "skip-larger-than" to larger),
        FakeAweme::class.java.name, FakeItemList::class.java.name,
    )

    @Test fun `every address of a video points at the chosen rendition`() {
        enable(quality(SetVideoQuality.HIGHEST))
        val low = url("low", 540, 960, 10)
        val high = url("high", 1080, 1920, 50)
        val highSmall = url("high-small", 1080, 1920, 40)
        val main = url("main", 720, 1280, 20)
        val video = Video(listOf(BitRate(low), BitRate(high, play_addr_265 = highSmall)), main)
        val items = FakeItemList()
        backend.call(method(FakeItemList::class.java, "setItems", List::class.java), items, listOf<Any?>(FakeAweme(video), "not a video"))
        for (model in listOf(low, high, highSmall)) {
            assertEquals(listOf<Any?>("high"), model.url_list)
            assertEquals(1080, model.width)
            assertEquals(50L, model.data_size)
        }
        // The video's own play address carries no bitrate list of its own here, so it is not one of the renditions.
        assertEquals(listOf<Any?>("main"), main.url_list)
    }

    @Test fun `limits keep the host choice and the adapter and feed result paths apply too`() {
        enable(quality(SetVideoQuality.LOWEST, longer = 60, larger = 1))
        val longVideo = FakeAweme(Video(listOf(BitRate(url("a", 1080, 1920, 5)), BitRate(url("b", 540, 960, 3)))), duration = 61_000)
        val low = url("small", 540, 960, 3)
        val high = url("big", 1080, 1920, 5)
        val huge = url("huge", 540, 960, 2L * 1024 * 1024)
        val bigger = url("bigger", 1080, 1920, 3L * 1024 * 1024)
        val adapter = FeedAdapter(listOf(longVideo, FakeAweme(Video(listOf(BitRate(high), BitRate(low)))), FakeAweme(Video(listOf(BitRate(huge), BitRate(bigger))))))
        backend.call(method(FeedAdapter::class.java, "notifyChanged"), adapter)
        assertEquals(listOf<Any?>("a"), ((longVideo.getVideo()!!.bit_rate!![0] as BitRate).play_addr as UrlModel).url_list)
        assertEquals(listOf<Any?>("small"), high.url_list)
        assertEquals(listOf<Any?>("bigger"), bigger.url_list)
        val small = url("tiny", 540, 960, 2)
        val other = url("other", 1080, 1920, 3)
        val holder = FeedResultHolder().apply { list = FakeItemList().apply { items = listOf(FakeAweme(Video(listOf(BitRate(small), BitRate(other))))) } }
        backend.call(method(FeedStep::class.java, "invokeSuspend", Any::class.java), FeedStep(), holder)
        assertEquals(listOf<Any?>("tiny"), other.url_list)
    }

    @Test fun `renditions are ranked by area and the largest file of a size wins`() {
        val renditions = listOf(Rendition(720, 1280, 5), Rendition(1080, 1920, 8), Rendition(1080, 1920, 9), Rendition(540, 960, 2))
        assertEquals(Rendition(1080, 1920, 9), chooseRendition(renditions, SetVideoQuality.HIGHEST))
        assertEquals(Rendition(720, 1280, 5), chooseRendition(renditions, SetVideoQuality.MEDIUM))
        assertEquals(Rendition(540, 960, 2), chooseRendition(renditions, SetVideoQuality.LOWEST))
        assertEquals(Rendition(1080, 1920, 9), chooseRendition(renditions, SetVideoQuality.Q_1080P))
        assertEquals(Rendition(720, 1280, 5), chooseRendition(renditions, SetVideoQuality.Q_720P))
        // 2K unavailable -> falls back to highest below target (1080p); 360p unavailable -> lowest above target (540p).
        assertEquals(Rendition(1080, 1920, 9), chooseRendition(renditions, SetVideoQuality.Q_2K))
        assertEquals(Rendition(540, 960, 2), chooseRendition(renditions, SetVideoQuality.Q_360P))
        // Zero-dimension DASH audio renditions are ignored even for LOWEST.
        assertEquals(Rendition(540, 960, 2), chooseRendition(renditions + Rendition(0, 0, 1), SetVideoQuality.LOWEST))
        assertNull(chooseRendition(emptyList(), SetVideoQuality.HIGHEST))
    }

    class SimBitRate(
        @JvmField var mVWidth: Int,
        @JvmField var mVHeight: Int,
        @JvmField var mSize: Long,
        @JvmField var mMediaType: Int = 0,
        @JvmField var mCodecType: String = "h264",
    )

    class FakeSimModel(@JvmField var bitRate: List<Any?>?) {
        @JvmField var hitBitrate: SimBitRate? = null
        fun getBitRate(): List<Any?>? = bitRate
    }

    enum class FakeResolution { R540, R720, R1080 }

    class VideoInfo(
        @JvmField var mVWidth: Int,
        @JvmField var mVHeight: Int,
        @JvmField var mSize: Long,
        @JvmField var mMediaType: Int = 0,
        @JvmField var mCodecType: String = "h264",
        @JvmField var mResolution: FakeResolution? = null,
    )

    class FakeVideoRef(@JvmField var mEnableAdaptive: Boolean = true, @JvmField var mVideoDuration: Long = 30)

    class FakeVideoModel(private val infos: List<VideoInfo>, @JvmField val mVideoRef: FakeVideoRef = FakeVideoRef()) {
        fun getVideoInfoList(): List<VideoInfo> = infos
        fun getSupportResolutions(): Array<FakeResolution> = infos.mapNotNull { it.mResolution }.distinct().toTypedArray()
        fun getVideoInfo(res: FakeResolution): VideoInfo? = infos.firstOrNull { it.mResolution == res && it.mMediaType == 0 }
        fun getAudioInfo(): VideoInfo? = infos.firstOrNull { it.mMediaType == 1 }
    }

    class FakeEngine {
        fun setVideoModel(model: Any?) {}
    }

    @Test fun `SimVideoUrlModel and TTVideoEngine pinning lock target height while preserving DASH audio`() {
        val feature = SetVideoQuality(
            resolver(
                OptionQueries.FEED_ADAPTER_CHANGED to listOf(method(FeedAdapter::class.java, "notifyChanged")),
                OptionQueries.FEED_RESULT to listOf(method(FeedStep::class.java, "invokeSuspend", Any::class.java)),
            ),
            loader,
            values(SetVideoQuality.ID, SetVideoQuality.OPTIONS, "level" to SetVideoQuality.Q_720P),
            FakeAweme::class.java.name, FakeItemList::class.java.name,
            simModelName = FakeSimModel::class.java.name,
            engineNames = listOf(FakeEngine::class.java.name),
        )
        enable(feature)

        // Layer 2: SimVideoUrlModel pins to 720p and keeps DASH audio track untouched.
        val v540 = SimBitRate(540, 960, 10)
        val v720 = SimBitRate(720, 1280, 20)
        val v1080 = SimBitRate(1080, 1920, 40)
        val audio = SimBitRate(0, 0, 5, mMediaType = 1, mCodecType = "aac")
        val sim = FakeSimModel(listOf(v540, v720, v1080, audio))
        backend.call(method(FakeSimModel::class.java, "getBitRate"), sim)
        assertEquals(listOf(v720, audio), sim.bitRate)
        assertSame(v720, sim.hitBitrate)

        // Layer 3: TTVideoEngine disables ABR, filters getVideoInfoList/getSupportResolutions,
        // rewrites video VideoInfo lookups, and leaves audio VideoInfo lookups alone.
        val info540 = VideoInfo(540, 960, 10, mResolution = FakeResolution.R540)
        val info720 = VideoInfo(720, 1280, 20, mResolution = FakeResolution.R720)
        val info1080 = VideoInfo(1080, 1920, 40, mResolution = FakeResolution.R1080)
        val infoAudio = VideoInfo(0, 0, 5, mMediaType = 1, mCodecType = "aac")
        val model = FakeVideoModel(listOf(info540, info720, info1080, infoAudio))
        backend.call(method(FakeEngine::class.java, "setVideoModel", Any::class.java), FakeEngine(), model)
        assertFalse(model.mVideoRef.mEnableAdaptive)
        assertEquals(listOf(info720, infoAudio), backend.call(method(FakeVideoModel::class.java, "getVideoInfoList"), model))
        @Suppress("UNCHECKED_CAST")
        val resolutions = backend.call(method(FakeVideoModel::class.java, "getSupportResolutions"), model) as Array<FakeResolution>
        assertEquals(listOf(FakeResolution.R720), resolutions.toList())
        assertSame(info720, backend.call(method(FakeVideoModel::class.java, "getVideoInfo", FakeResolution::class.java), model, FakeResolution.R540))
        assertSame(infoAudio, backend.call(method(FakeVideoModel::class.java, "getAudioInfo"), model))
    }

    class PluginLoader {
        var loads = 0
        fun load(pkg: String?, flags: Int): Boolean { loads++; return true }
    }
    class WrongLoader { fun load(pkg: String?, flags: Int): Int = 0 }

    private fun plugins(blocked: List<String>, target: java.lang.reflect.Method, logged: MutableList<String> = ArrayList()) = BlockPluginUpdates(
        resolver(OptionQueries.PLUGIN_LOAD to listOf(target)), loader,
        values(BlockPluginUpdates.ID, BlockPluginUpdates.OPTIONS, "blocked" to blocked), Logger { logged += it },
    )

    @Test fun `listed plugins are refused and every plugin is logged once`() {
        val logged = ArrayList<String>()
        val load = method(PluginLoader::class.java, "load", String::class.java, Int::class.javaPrimitiveType!!)
        enable(plugins(listOf("com.ss.plugin.live"), load, logged))
        val host = PluginLoader()
        assertEquals(false, backend.call(load, host, "com.ss.plugin.live", 0))
        assertEquals(true, backend.call(load, host, "com.ss.plugin.other", 0))
        assertEquals(true, backend.call(load, host, "com.ss.plugin.other", 0))
        assertEquals(2, host.loads)
        assertEquals(2, logged.size)
        assertTrue(logged[0].contains("package=com.ss.plugin.live blocked=true"))
    }

    @Test fun `a loader that does not answer with a boolean fails the install`() {
        failsToInstall(plugins(emptyList(), method(WrongLoader::class.java, "load", String::class.java, Int::class.javaPrimitiveType!!)), "is not a boolean")
    }

    companion object {
        val posted = ArrayList<Any?>()
    }
}
