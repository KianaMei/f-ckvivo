package io.github.nuke.oss.app.shared.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException

// The result of taking a video's audio track out: the MP3 file and how long the source was, or why it could not be done.
internal sealed interface AudioExport {
    data class Converted(val file: File, val durationMillis: Long) : AudioExport

    data class Failed(val reason: String) : AudioExport
}

// Decodes the first audio track of a video and writes it as MP3. Blocking, so callers run it off the main thread.
internal object VideoAudio {
    fun toMp3(video: File, output: File): AudioExport {
        if (!video.isFile) return AudioExport.Failed("no video file")
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var encoder: Mp3Encoder? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(video.path) }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return AudioExport.Failed("no audio track")
            val format = extractor.getTrackFormat(track)
            val duration = (runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L).takeIf { it > 0 } ?: 0L) / 1_000
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec.configure(format, null, null, 0)
            codec.start()
            output.delete()
            output.outputStream().use { sink ->
                val info = MediaCodec.BufferInfo()
                var ended = false
                while (!ended) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_MICROS)
                    if (inputIndex >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(inputIndex))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_MICROS)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            // A second change would mean a second stream, which this deliberately does not mix.
                            if (encoder != null) return AudioExport.Failed("output format changed twice")
                            encoder = Mp3Encoder(
                                out.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                out.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            )
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            val buffer = checkNotNull(codec.getOutputBuffer(outputIndex))
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val pcm = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.get(pcm)
                                encoder?.encode(pcm, pcm.size) { data, count -> sink.write(data, 0, count) }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                        }
                    }
                }
                encoder?.finish { data, count -> sink.write(data, 0, count) }
            }
            if (!output.isFile || output.length() == 0L) {
                output.delete()
                return AudioExport.Failed("the encoder wrote nothing")
            }
            AudioExport.Converted(output, duration)
        } catch (error: IOException) {
            output.delete()
            AudioExport.Failed(error.javaClass.simpleName + ": " + error.message.orEmpty().take(120))
        } catch (error: RuntimeException) {
            output.delete()
            AudioExport.Failed(error.javaClass.simpleName + ": " + error.message.orEmpty().take(120))
        } finally {
            encoder?.close()
            codec?.let { runCatching { it.stop() } }
            codec?.release()
            extractor?.release()
        }
    }

    // A codec that stops answering must not hold the worker thread for ever.
    private const val TIMEOUT_MICROS = 10_000L
}
