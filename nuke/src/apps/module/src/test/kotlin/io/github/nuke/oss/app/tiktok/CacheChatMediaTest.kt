package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.media.ChatMediaFetcher
import io.github.nuke.oss.app.tiktok.feature.chat.CacheChatMedia
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.core.Logger
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheChatMediaTest : HookHarness() {
    class Raw(@JvmField var content: String?)
    class Message(private val chat: String, private val id: Long) {
        fun getConversationId(): String = chat
        fun getMsgId(): Long = id
    }

    class Handler {
        fun handle(first: Any?, second: Any?, raw: Raw): Message = Message("0:42", 7)
    }

    private val raw = Raw(null)
    private val handler = method(Handler::class.java, "handle", Any::class.java, Any::class.java, Raw::class.java)
    private val dir = Files.createTempDirectory("chat-media").toFile()

    private fun feature(fetcher: ChatMediaFetcher) = CacheChatMedia(
        resolverOf(ChatQueries.INCOMING_MESSAGE to handler),
        loader, dir, Logger {}, fetcher, Runnable::run,
    )

    @Test fun `the media of an incoming message is written under its chat`() {
        val seen = ArrayList<String>()
        enable(feature { address, target ->
            seen += address
            target.writeBytes(byteArrayOf(1, 2, 3))
            true
        })
        raw.content = """{"url_list":["https://p3.douyinpic.com/a.jpg","https://p3.douyinpic.com/b.png"]}"""
        backend.call(handler, Handler(), "a", "b", raw)
        assertEquals(2, seen.size)
        val files = File(dir, "0_42").listFiles()!!.sortedBy { it.name }
        assertEquals(listOf("7_0.jpg", "7_1.png"), files.map { it.name })
    }

    @Test fun `a message without media writes nothing`() {
        var calls = 0
        enable(feature { _, _ -> calls++; true })
        raw.content = """{"text":"hello"}"""
        backend.call(handler, Handler(), "a", "b", raw)
        assertEquals(0, calls)
    }

    @Test fun `a failed download leaves no file behind`() {
        enable(feature { _, target -> target.writeBytes(byteArrayOf(1)); false })
        raw.content = """{"url":"https://p3.douyinpic.com/c.jpg"}"""
        backend.call(handler, Handler(), "a", "b", raw)
        assertTrue(File(dir, "0_42").listFiles().orEmpty().isEmpty())
    }
}
