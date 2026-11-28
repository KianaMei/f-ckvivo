package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.media.DownloadQueue
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntries
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntry
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.app.tiktok.feature.feed.DownloadVideoWithName
import io.github.nuke.oss.app.tiktok.feature.feed.StoryDownloadMenu
import io.github.nuke.oss.app.tiktok.query.DownloadQueries
import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
import io.github.nuke.oss.core.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadVideoWithNameTest : HookHarness() {
    private val queue = DownloadQueue(1)
    private val entries = RecordingEntries()
    private lateinit var storyActionFor: (Any) -> ((Any) -> Boolean)?
    private val storyMenu = StoryDownloadMenu { _, actionFor -> storyActionFor = actionFor }

    private class RecordingEntries : SharePanelEntries {
        val added = ArrayList<SharePanelEntry>()
        override fun add(scope: FeatureScope, featureId: String, entry: SharePanelEntry) {
            added += entry
        }
    }

    @After fun stopQueue() {
        queue.close()
    }

    class Author(@JvmField var nickname: String?)
    class Address(@JvmField var urlList: List<String>?)
    class Video(@JvmField var play_addr: Address?) {
        fun play(): Address? = play_addr
    }

    class Picture(@JvmField var urlList: List<String>?, @JvmField var video: Video? = null) {
        fun getVideo(): Video? = video
    }

    class Aweme(
        @JvmField var author: Author? = null,
        @JvmField var desc: String? = null,
        @JvmField var aweme_id: String? = "1",
        @JvmField var video: Video? = null,
        @JvmField var images: List<Any>? = null,
        @JvmField var storyInteractiveLabel: String? = null,
    ) {
        fun getVideo(): Video? = video
    }

    class Panel(@JvmField var post: Aweme?) {
        var clicks = 0
        fun click(view: Any?) { clicks++ }
        fun downloadAweme(): Aweme? = post
    }

    class HostView(private val context: Any) {
        fun getContext(): Any = context
    }

    private val click = method(Panel::class.java, "click", Any::class.java)
    private val post = method(Panel::class.java, "downloadAweme")
    private val play = method(Video::class.java, "play")

    private fun feature(files: RecordingMediaFiles, vararg saved: Pair<String, Any>) = DownloadVideoWithName(
        resolverOf(
            DownloadQueries.PANEL_DOWNLOAD_CLICK to click,
            DownloadQueries.PANEL_AWEME to post,
            DownloadQueries.VIDEO_PLAY to play,
        ),
        loader, { files }, Logger {}, entries, storyMenu,
        values(DownloadVideoWithName.ID, DownloadVideoWithName.OPTIONS, *saved),
        queue = queue,
    )

    private fun aweme(video: Video?) = Aweme(
        author = Author("alice"), desc = "hello world", aweme_id = "42", video = video,
    )

    @Test fun `the long press save is taken over and the file is named from the template`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "template" to "#author#_#aid#", "folder" to "mine"))
        val panel = Panel(aweme(Video(Address(listOf("https://v/play.mp4")))))
        backend.call(click, panel, HostView("host"))
        queue.awaitIdle()
        assertEquals(0, panel.clicks)
        val saved = files.saved.single()
        assertEquals(listOf("https://v/play.mp4"), saved.urls)
        assertEquals(ImageFolder.VIDEO, saved.folder)
        assertEquals("alice_42", saved.name)
    }

    @Test fun `the folder option reaches the writer`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "folder" to "../escape"))
        val panel = Panel(aweme(Video(Address(listOf("https://v/play.mp4")))))
        backend.call(click, panel, HostView("host"))
        queue.awaitIdle()
        assertEquals(ImageFolder.VIDEO, files.saved.single().folder)
        assertEquals("alice_hello_world", files.saved.single().name)
    }

    @Test fun `a post without a video keeps the host behavior`() {
        val files = RecordingMediaFiles()
        enable(feature(files))
        val panel = Panel(aweme(null))
        backend.call(click, panel, HostView("host"))
        queue.awaitIdle()
        assertEquals(1, panel.clicks)
        assertTrue(files.saved.isEmpty())
    }

    @Test fun `a video without a playable address keeps the host behavior`() {
        val files = RecordingMediaFiles()
        enable(feature(files))
        val panel = Panel(aweme(Video(Address(null))))
        backend.call(click, panel, HostView("host"))
        queue.awaitIdle()
        assertEquals(1, panel.clicks)
        assertTrue(files.saved.isEmpty())
    }

    @Test fun `an image post saves every picture with its index in the name`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "template" to "#author#_#desc#"))
        val post = aweme(null).apply {
            images = listOf(Picture(listOf("https://v/1.png")), Picture(listOf("https://v/2.png")))
        }
        backend.call(click, Panel(post), HostView("host"))
        queue.awaitIdle()
        assertEquals(listOf("https://v/1.png", "https://v/2.png"), files.saved.map { it.urls.single() })
        assertEquals(listOf("alice_hello_world_01", "alice_hello_world_02"), files.saved.map { it.name })
        assertEquals(listOf(ImageFolder.IMAGE, ImageFolder.IMAGE), files.saved.map { it.folder })
    }

    @Test fun `a live photo is saved as one motion photo with its video`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "template" to "#author#_#desc#"))
        val live = Picture(
            listOf("https://v/still.jpg"),
            Video(Address(listOf("https://v/live.mp4"))),
        )
        val post = aweme(null).apply { images = listOf(live) }
        backend.call(click, Panel(post), HostView("host"))
        queue.awaitIdle()
        assertTrue(files.saved.isEmpty())
        val motion = files.motions.single()
        assertEquals(listOf("https://v/still.jpg"), motion.imageUrls)
        assertEquals(listOf("https://v/live.mp4"), motion.videoUrls)
        assertEquals("alice_hello_world_01", motion.name)
    }

    @Test fun `a post without a video or pictures keeps the host behavior`() {
        val files = RecordingMediaFiles()
        enable(feature(files))
        val panel = Panel(aweme(null))
        backend.call(click, panel, HostView("host"))
        queue.awaitIdle()
        assertEquals(1, panel.clicks)
        assertTrue(files.saved.isEmpty())
    }

    @Test fun `the share panel row downloads the post it was opened on`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "template" to "#author#_#aid#"))
        val panel = Panel(aweme(Video(Address(listOf("https://v/play.mp4")))))
        entries.added.single().onClick(HostView("host"), panel.post!!) {}
        queue.awaitIdle()
        assertEquals("alice_42", files.saved.single().name)
        assertEquals(listOf("https://v/play.mp4"), files.saved.single().urls)
    }

    @Test fun `the time limited story action only queues the panel post on a tap`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "template" to "#author#_#aid#"))
        val ordinary = aweme(Video(Address(listOf("https://v/ordinary.mp4"))))
        val daily = aweme(Video(Address(listOf("https://v/primary.mp4", "https://v/mirror.mp4")))).apply {
            storyInteractiveLabel = "限时日常"
        }
        val action = checkNotNull(storyActionFor(daily))
        assertNull(storyActionFor(ordinary))
        assertTrue(queue.snapshot().isEmpty())
        assertTrue(action("host"))
        assertTrue(queue.awaitIdle())
        val saved = files.saved.single()
        assertEquals("alice_42", saved.name)
        assertEquals(ImageFolder.VIDEO, saved.folder)
        assertEquals(listOf("https://v/primary.mp4", "https://v/mirror.mp4"), saved.urls)
    }

    @Test fun `a time limited story without media does not queue a download`() {
        val files = RecordingMediaFiles()
        enable(feature(files))
        val daily = aweme(Video(Address(listOf("no playable url")))).apply { storyInteractiveLabel = "限时日常" }
        val action = checkNotNull(storyActionFor(daily))
        assertFalse(action("host"))
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test fun `a missing native save route does not lock the story download`() {
        val feature = feature(RecordingMediaFiles())
        assertFalse(feature.unusable(listOf(DownloadQueries.PANEL_DOWNLOAD_CLICK) + SharePanelEntries.QUERIES))
        assertTrue(feature.unusable(listOf(DownloadQueries.PANEL_DOWNLOAD_CLICK, DownloadQueries.PRESS_PANEL_VIEW) + SharePanelEntries.QUERIES))
        assertTrue(feature.unusable(listOf(DownloadQueries.VIDEO_PLAY)))
    }

    @Test fun `a base64 address is decoded before the download`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "template" to "#desc#"))
        val encoded = java.util.Base64.getEncoder().encodeToString("https://v/from-b64.mp4".toByteArray())
        backend.call(click, Panel(aweme(Video(Address(listOf(encoded))))), HostView("host"))
        queue.awaitIdle()
        assertEquals(listOf("https://v/from-b64.mp4"), files.saved.single().urls)
    }

    class RankedBitRate(
        @JvmField var play_addr: Address?,
        @JvmField var width: Int,
        @JvmField var height: Int,
        @JvmField var is_bytevc1: Int = 0,
        @JvmField var is_h265: Int = 0,
        @JvmField var mMediaType: Int = 0,
        @JvmField var bit_rate: Long = 0,
        @JvmField var gear_name: String? = null,
    )

    class RankedVideo(@JvmField var bit_rate: List<Any?>?, @JvmField var play_addr: Address? = null) {
        fun play(): Address? = play_addr
    }

    class RichPicture(
        @JvmField var watermarkFreeDownloadUrlList: List<String>? = null,
        @JvmField var urlList: List<String>? = null,
        @JvmField var downloadUrlList: List<String>? = null,
    )

    class PhotoWithBgmAweme(
        @JvmField var author: Author? = Author("alice"),
        @JvmField var desc: String? = "photos",
        @JvmField var aweme_id: String? = "99",
        @JvmField var aweme_type: Int = 68,
        @JvmField var video: Video? = Video(Address(listOf("https://v/dummy-bgm.mp4"))),
        @JvmField var images: List<Any>? = null,
    ) {
        fun getVideo(): Video? = video
    }

    @Test fun `photo posts save watermark-free image mirrors instead of dummy BGM video`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "template" to "#author#_#aid#"))
        val photo = RichPicture(
            watermarkFreeDownloadUrlList = listOf("https://v/clean-1.jpg", "https://v/clean-2.jpg"),
            urlList = listOf("https://v/plain.jpg"),
            downloadUrlList = listOf("https://v/watermarked.jpg"),
        )
        val post = PhotoWithBgmAweme(images = listOf(photo))
        val panel = Panel(null)
        entries.added.single().onClick(HostView("host"), post) {}
        queue.awaitIdle()
        val saved = files.saved.single()
        assertEquals(ImageFolder.IMAGE, saved.folder)
        assertEquals(
            listOf("https://v/clean-1.jpg", "https://v/clean-2.jpg", "https://v/plain.jpg", "https://v/watermarked.jpg"),
            saved.urls,
        )
    }

    @Test fun `video selector skips DASH audio and m3u8 and prefers highest compatible codec`() {
        val video = RankedVideo(
            bit_rate = listOf(
                RankedBitRate(Address(listOf("https://v/audio.m4a")), width = 0, height = 0, mMediaType = 1, gear_name = "audio_1080"),
                RankedBitRate(Address(listOf("https://v/hls.m3u8")), width = 1080, height = 1920),
                RankedBitRate(Address(listOf("https://v/1080-bytevc1.mp4")), width = 1080, height = 1920, is_bytevc1 = 1, bit_rate = 5000),
                RankedBitRate(Address(listOf("https://v/720-h264.mp4")), width = 720, height = 1280, bit_rate = 2000),
                RankedBitRate(Address(listOf("https://v/1080-h264.mp4")), width = 1080, height = 1920, bit_rate = 4000),
                RankedBitRate(Address(listOf("https://v/1080-hevc.mp4")), width = 1080, height = 1920, is_h265 = 1, bit_rate = 4500),
            ),
            play_addr = Address(listOf("https://v/fallback.mp4")),
        )
        val urls = io.github.nuke.oss.app.tiktok.sdk.media.MediaSelectors.selectVideoUrls(video)
        assertEquals(
            listOf(
                "https://v/1080-h264.mp4",
                "https://v/1080-hevc.mp4",
                "https://v/720-h264.mp4",
                "https://v/1080-bytevc1.mp4",
                "https://v/fallback.mp4",
            ),
            urls,
        )
    }
}
