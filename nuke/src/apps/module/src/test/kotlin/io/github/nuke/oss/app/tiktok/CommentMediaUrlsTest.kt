package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.mediaUrlList
import org.junit.Assert.assertEquals
import org.junit.Test

// 41.2's UrlModel has no getUrlList(): the addresses sit in the public urlList field, and older builds expose only the
// getter. The extraction has to read either one, or every emoji comment looks like it has nothing to save.
class CommentMediaUrlsTest {
    @Suppress("unused")
    private class FieldOnly(@JvmField val urlList: List<String>)

    @Suppress("unused")
    private class GetterOnly {
        fun getUrlList(): List<String> = listOf("https://example.test/getter.gif")
    }

    private class Both(@JvmField val urlList: List<String>) {
        @Suppress("unused")
        fun getUrlList(): List<String> = listOf("https://example.test/method.gif")
    }

    @Test fun `reads the urlList field when the getter is missing`() {
        assertEquals(listOf("https://example.test/field.gif"), mediaUrlList(FieldOnly(listOf("https://example.test/field.gif"))))
    }

    @Test fun `reads the getter when that is all the host has`() {
        assertEquals(listOf("https://example.test/getter.gif"), mediaUrlList(GetterOnly()))
    }

    @Test fun `prefers the getter when both exist`() {
        assertEquals(listOf("https://example.test/method.gif"), mediaUrlList(Both(listOf("https://example.test/field.gif"))))
    }

    @Test fun `a null or unreadable model yields nothing`() {
        assertEquals(emptyList<String>(), mediaUrlList(null))
        assertEquals(emptyList<String>(), mediaUrlList(Any()))
    }
}
