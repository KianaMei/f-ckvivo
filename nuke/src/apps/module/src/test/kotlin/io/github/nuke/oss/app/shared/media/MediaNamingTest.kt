package io.github.nuke.oss.app.shared.media

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNamingTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun `tokens expand from the metadata`() {
        val name = MediaNaming.fileName(
            "#author#_#desc#_#aid#",
            mapOf("author" to "alice", "desc" to "hello world", "aid" to "42"),
            extension = "mp4",
            now = 1_700_000_000_000,
            timeZone = utc,
        )
        assertEquals("alice_hello_world_42", name)
    }

    @Test fun `a token without a value becomes nothing and separators collapse`() {
        val name = MediaNaming.fileName("#author#_#desc#", mapOf("author" to "alice"), "mp4", now = 0, timeZone = utc)
        assertEquals("alice", name)
    }

    @Test fun `unknown tokens and braces forms are supported`() {
        val name = MediaNaming.fileName("{author}-#nope#-#type#", mapOf("author" to "a", "type" to "video"), "mp4", now = 0, timeZone = utc)
        assertEquals("a-video", name)
    }

    @Test fun `path separators wildcards and control characters are replaced`() {
        val name = MediaNaming.fileName("#desc#", mapOf("desc" to "a/b\\c:d*e?f\"g<h>i|j\u0007k"), "mp4", now = 0, timeZone = utc)
        assertEquals("a_b_c_d_e_f_g_h_i_j_k", name)
    }

    @Test fun `leading and trailing dots underscores and dashes are trimmed`() {
        val name = MediaNaming.fileName("#desc#", mapOf("desc" to " ..__--name--__.. "), "mp4", now = 0, timeZone = utc)
        assertEquals("name", name)
    }

    @Test fun `an index is appended in two digits when the template has none`() {
        val name = MediaNaming.fileName("#desc#", mapOf("desc" to "pic"), "png", index = 3, now = 0, timeZone = utc)
        assertEquals("pic_03", name)
    }

    @Test fun `a template with an index token is not appended to`() {
        val name = MediaNaming.fileName("#desc#_#index#", mapOf("desc" to "pic"), "png", index = 3, now = 0, timeZone = utc)
        assertEquals("pic_03", name)
    }

    @Test fun `date and time tokens use the given time zone`() {
        val name = MediaNaming.fileName("#date#_#time#_#datetime#", emptyMap(), "mp4", now = 1_700_000_000_000, timeZone = utc)
        assertEquals("20231114_221320_20231114_221320", name)
    }

    @Test fun `a long name is cut by bytes and keeps whole characters`() {
        val name = MediaNaming.fileName("#desc#", mapOf("desc" to "汉".repeat(200)), "mp4", now = 0, timeZone = utc)
        assertTrue(name, name.toByteArray(Charsets.UTF_8).size <= MediaNaming.MAX_BYTES)
        assertTrue(name.all { it == '汉' })
        assertTrue(name.isNotEmpty())
    }

    @Test fun `a template that expands to nothing falls back to the download time`() {
        assertEquals("media_1700", MediaNaming.fileName("#nope#", emptyMap(), "mp4", now = 1_700, timeZone = utc))
        assertEquals("media_1700", MediaNaming.fileName("---", emptyMap(), "mp4", now = 1_700, timeZone = utc))
    }

    @Test fun `only a plain folder name is accepted`() {
        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder(""))
        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder("  "))
        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder("../escape"))
        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder("my folder"))
        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder(".."))
        assertEquals(MediaNaming.DEFAULT_FOLDER, MediaNaming.folder("x".repeat(41)))
        assertEquals("mine-1_2", MediaNaming.folder(" mine-1_2 "))
    }
}
