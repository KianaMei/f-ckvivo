package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.media.DownloadQueue
import io.github.nuke.oss.app.tiktok.feature.feed.DownloadVideoWithName
import io.github.nuke.oss.app.tiktok.query.DownloadQueries
import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
import io.github.nuke.oss.core.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadVideoWithNameTest : HookHarness() {
    private val queue = DownloadQueue(1)

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
        loader, { files }, Logger {},
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

    @Test fun `a base64 address is decoded before the download`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "template" to "#desc#"))
        val encoded = java.util.Base64.getEncoder().encodeToString("https://v/from-b64.mp4".toByteArray())
        backend.call(click, Panel(aweme(Video(Address(listOf(encoded))))), HostView("host"))
        queue.awaitIdle()
        assertEquals(listOf("https://v/from-b64.mp4"), files.saved.single().urls)
    }
}
