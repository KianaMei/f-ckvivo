package io.github.nuke.oss.app.shared.media

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// jump3r is plain Java, so the encoder runs in a unit test exactly as it does in the host.
class Mp3EncoderTest {
    private fun encode(sampleRate: Int, channels: Int, seconds: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val frames = sampleRate * seconds
        val pcm = ByteArray(frames * channels * 2) { index -> if (index % 2 == 0) 0x10 else 0x00 }
        Mp3Encoder(sampleRate, channels).use { encoder ->
            encoder.encode(pcm, pcm.size) { data, count -> out.write(data, 0, count) }
            encoder.finish { data, count -> out.write(data, 0, count) }
        }
        return out.toByteArray()
    }

    @Test fun `one second of stereo PCM becomes MP3 frames`() {
        val bytes = encode(44100, 2, 1)
        assertTrue("expected MP3 output, got ${bytes.size} bytes", bytes.size > 1_000)
        // Either an ID3 tag or a bare MPEG audio frame sync.
        val id3 = bytes.size >= 3 && String(bytes, 0, 3, Charsets.US_ASCII) == "ID3"
        assertTrue(id3 || (bytes[0].toInt() and 0xFF) == 0xFF)
    }

    @Test fun `a mono encoder accepts mono PCM and more data means more output`() {
        val short = encode(22050, 1, 1)
        val long = encode(22050, 1, 2)
        assertTrue(short.isNotEmpty())
        assertTrue(long.size > short.size)
    }

    @Test fun `finishing a stream that never received a sample writes nothing and does not throw`() {
        val out = ByteArrayOutputStream()
        Mp3Encoder(44100, 2).use { encoder -> encoder.finish { data, count -> out.write(data, 0, count) } }
        assertEquals(0, out.size())
    }

    @Test fun `the output is the same for the same input`() {
        assertEquals(encode(44100, 2, 1).toList(), encode(44100, 2, 1).toList())
    }
}
