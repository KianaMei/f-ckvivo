package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.feed.DownloadVideoWithName
import io.github.nuke.oss.app.tiktok.query.DownloadQueries
import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadVideoWithNameTest : HookHarness() {
    class Author(@JvmField var nickname: String?)
    class Address(@JvmField var urlList: List<String>?)
    class Video(@JvmField var play_addr: Address?) {
        fun play(): Address? = play_addr
    }

    class Aweme(
        @JvmField var author: Author? = null,
        @JvmField var desc: String? = null,
        @JvmField var aweme_id: String? = "1",
        @JvmField var video: Video? = null,
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
        runner = BackgroundRunner { _, task -> task() },
    )

    private fun aweme(video: Video?) = Aweme(
        author = Author("alice"), desc = "hello world", aweme_id = "42", video = video,
    )

    @Test fun `the long press save is taken over and the file is named from the template`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "template" to "#author#_#aid#", "folder" to "mine"))
        val panel = Panel(aweme(Video(Address(listOf("https://v/play.mp4")))))
        backend.call(click, panel, HostView("host"))
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
        assertEquals(ImageFolder.VIDEO, files.saved.single().folder)
        assertEquals("alice_hello_world", files.saved.single().name)
    }

    @Test fun `a post without a video keeps the host behavior`() {
        val files = RecordingMediaFiles()
        enable(feature(files))
        val panel = Panel(aweme(null))
        backend.call(click, panel, HostView("host"))
        assertEquals(1, panel.clicks)
        assertTrue(files.saved.isEmpty())
    }

    @Test fun `a video without a playable address keeps the host behavior`() {
        val files = RecordingMediaFiles()
        enable(feature(files))
        val panel = Panel(aweme(Video(Address(null))))
        backend.call(click, panel, HostView("host"))
        assertEquals(1, panel.clicks)
        assertTrue(files.saved.isEmpty())
    }

    @Test fun `a base64 address is decoded before the download`() {
        val files = RecordingMediaFiles()
        enable(feature(files, "template" to "#desc#"))
        val encoded = java.util.Base64.getEncoder().encodeToString("https://v/from-b64.mp4".toByteArray())
        backend.call(click, Panel(aweme(Video(Address(listOf(encoded))))), HostView("host"))
        assertEquals(listOf("https://v/from-b64.mp4"), files.saved.single().urls)
    }
}
