package io.github.nuke.oss.app.shared.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoTest {
    private fun jpeg(body: String = "jpeg body"): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body.toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test fun `the video is appended and its length is recorded in the XMP`() {
        val video = ByteArray(4096) { it.toByte() }
        val packed = MotionPhoto.wrap(jpeg(), video, presentationUs = 1_000_000)
        val overhead = packed.size - jpeg().size - 4096
        assertTrue("xmp segment overhead $overhead", overhead in 200..2000)
        assertEquals(packed.size - 4096, MotionPhoto.videoOffset(packed))
        assertTrue(String(packed, Charsets.ISO_8859_1).contains("GCamera:MicroVideo=\"1\""))
        assertTrue(String(packed, Charsets.ISO_8859_1).contains("GCamera:MicroVideoPresentationTimestampUs=\"1000000\""))
        // The MP4 bytes are untouched at the end.
        assertEquals(video.toList(), packed.toList().subList(packed.size - 4096, packed.size))
    }

    @Test fun `a plain JPEG has no motion video offset`() {
        assertNull(MotionPhoto.videoOffset(jpeg()))
    }

    @Test fun `packing refuses something that is not a JPEG`() {
        val failure = runCatching { MotionPhoto.wrap("not a jpeg".toByteArray(), byteArrayOf(1)) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException)
    }

    @Test fun `packing refuses an empty video`() {
        val failure = runCatching { MotionPhoto.wrap(jpeg(), ByteArray(0)) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException)
    }

    @Test fun `a second package keeps the first video readable`() {
        val first = MotionPhoto.wrap(jpeg(), ByteArray(64) { 1 })
        val second = MotionPhoto.wrap(jpeg(), ByteArray(128) { 2 })
        assertEquals(64, MotionPhoto.videoOffset(first)!!.let { first.size - it })
        assertEquals(128, MotionPhoto.videoOffset(second)!!.let { second.size - it })
    }
}
