package io.github.nuke.oss.app.shared.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaUrlsTest {
    @Test fun `addresses come out of a media list in order and once`() {
        val content = """{"url_list":["https://p3.douyinpic.com/a.jpg","https://p3.douyinpic.com/a.jpg","https://v.douyin.com/b.mp4"]}"""
        assertEquals(
            listOf("https://p3.douyinpic.com/a.jpg", "https://v.douyin.com/b.mp4"),
            ChatMediaUrls.of(content),
        )
    }

    @Test fun `a single url field is enough, and other strings are ignored`() {
        val content = """{"url":"https://p3.douyinpic.com/c.webp","text":"hello","sender":"https://example.com/profile"}"""
        assertEquals(listOf("https://p3.douyinpic.com/c.webp"), ChatMediaUrls.of(content))
    }

    @Test fun `thumbnail variants and non-http strings are dropped`() {
        val content = """{"url_list":["https://p3.douyinpic.com/a.jpg~tplv-x?width=100","ftp://x/a.jpg","not a url","https://p3.douyinpic.com/a.jpg"]}"""
        assertEquals(listOf("https://p3.douyinpic.com/a.jpg"), ChatMediaUrls.of(content))
    }

    @Test fun `an empty or broken content yields nothing`() {
        assertEquals(emptyList<String>(), ChatMediaUrls.of(null))
        assertEquals(emptyList<String>(), ChatMediaUrls.of(""))
        assertEquals(emptyList<String>(), ChatMediaUrls.of("{}"))
    }

    @Test fun `the extension comes from the address`() {
        assertEquals("jpg", ChatMediaUrls.extension("https://x/a.JPG?x=1"))
        assertEquals("mp4", ChatMediaUrls.extension("https://x/b.mp4"))
        assertEquals("bin", ChatMediaUrls.extension("https://x/no-extension"))
        assertEquals("bin", ChatMediaUrls.extension("https://x/a."))
    }

    @Test fun `a conversation id becomes one safe directory name`() {
        assertEquals("0_1234_abc", ChatMediaUrls.directoryName("0:1234/abc"))
        assertEquals("chat", ChatMediaUrls.directoryName(""))
    }
}
