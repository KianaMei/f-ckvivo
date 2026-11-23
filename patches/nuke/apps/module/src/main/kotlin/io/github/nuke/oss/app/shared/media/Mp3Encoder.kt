package io.github.nuke.oss.app.shared.media

import de.sciss.jump3r.lowlevel.LameEncoder
import javax.sound.sampled.AudioFormat

// MP3 encoding for audio the module already has as PCM. jump3r is a pure-Java LAME port, so this needs no native
// library and runs wherever the module does. Callers pass 16-bit little-endian PCM, mono or stereo.
internal class Mp3Encoder(
    sampleRate: Int,
    channels: Int,
    bitrateKbps: Int = 192,
) : AutoCloseable {
    private val encoder = LameEncoder(
        AudioFormat(sampleRate.toFloat(), 16, channels, true, false),
        bitrateKbps,
        if (channels == 1) LameEncoder.CHANNEL_MODE_MONO else LameEncoder.CHANNEL_MODE_STEREO,
        LameEncoder.QUALITY_MIDDLE,
        false,
    )
    private val frame = ByteArray(encoder.getMP3BufferSize())
    private val chunk = ByteArray(encoder.getPCMBufferSize())
    private var fed = 0L

    // LAME fills whole frames; a decoder hands back buffers of any length, so they are split here.
    fun encode(pcm: ByteArray, length: Int, sink: (data: ByteArray, count: Int) -> Unit) {
        var offset = 0
        while (offset < length) {
            val count = minOf(length - offset, chunk.size)
            System.arraycopy(pcm, offset, chunk, 0, count)
            val written = encoder.encodeBuffer(chunk, 0, count, frame)
            if (written > 0) sink(frame, written)
            fed += count
            offset += count
        }
    }

    fun finish(sink: (data: ByteArray, count: Int) -> Unit) {
        // LAME asserts while flushing a stream that never received a sample.
        if (fed == 0L) return
        val written = encoder.encodeFinish(frame)
        if (written > 0) sink(frame, written)
    }

    override fun close() = encoder.close()
}
