package com.douai.module.feature;

import de.sciss.jump3r.mp3.BitStream;
import de.sciss.jump3r.mp3.GainAnalysis;
import de.sciss.jump3r.mp3.ID3Tag;
import de.sciss.jump3r.mp3.Lame;
import de.sciss.jump3r.mp3.LameGlobalFlags;
import de.sciss.jump3r.mp3.MPEGMode;
import de.sciss.jump3r.mp3.Presets;
import de.sciss.jump3r.mp3.Quantize;
import de.sciss.jump3r.mp3.QuantizePVT;
import de.sciss.jump3r.mp3.Reservoir;
import de.sciss.jump3r.mp3.Takehiro;
import de.sciss.jump3r.mp3.VBRTag;
import de.sciss.jump3r.mp3.VbrMode;
import de.sciss.jump3r.mp3.Version;
import de.sciss.jump3r.mpg.Common;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.MPGLib;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Streaming PCM to real MPEG Layer III frames. No javax.sound / JavaSound dependency. */
final class PcmMp3Encoder implements Closeable {
    enum Format { SIGNED_16_LE(2), FLOAT_32_LE(4); final int bytes; Format(int bytes) { this.bytes = bytes; } }
    private static final int BLOCK_FRAMES = 4096;
    private final OutputStream output;
    private final int channels;
    private final Lame lame;
    private final LameGlobalFlags flags;
    private final int[] left = new int[BLOCK_FRAMES], right = new int[BLOCK_FRAMES];
    private final byte[] encoded = new byte[BLOCK_FRAMES * 5 / 4 + 7200];
    private final byte[] remainder = new byte[8];
    private Format previousFormat;
    private int remainderSize;
    private long frames, bytes;
    private boolean finished, closed;

    PcmMp3Encoder(OutputStream output, int sampleRate, int channels) throws IOException {
        if (output == null || channels < 1 || channels > 2 || sampleRate < 8000 || sampleRate > 192000)
            throw new IllegalArgumentException("不支持的 PCM 采样率或声道数");
        this.output = output; this.channels = channels;
        lame = new Lame();
        GainAnalysis gain = new GainAnalysis(); BitStream bitstream = new BitStream();
        Presets presets = new Presets(); QuantizePVT quantizePrivate = new QuantizePVT();
        Quantize quantize = new Quantize(); VBRTag tag = new VBRTag(); Version version = new Version();
        ID3Tag id3 = new ID3Tag(); Reservoir reservoir = new Reservoir(); Takehiro takehiro = new Takehiro();
        MPGLib mpg = new MPGLib(); Interface decoder = new Interface(); Common common = new Common();
        lame.setModules(gain, bitstream, presets, quantizePrivate, quantize, tag, version, id3, mpg);
        bitstream.setModules(gain, mpg, version, tag); id3.setModules(bitstream, version);
        presets.setModules(lame); quantize.setModules(bitstream, reservoir, quantizePrivate, takehiro);
        quantizePrivate.setModules(takehiro, reservoir, lame.enc.psy); reservoir.setModules(bitstream);
        takehiro.setModules(quantizePrivate); tag.setModules(lame, bitstream, version);
        mpg.setModules(decoder, common); decoder.setModules(tag, common);
        flags = lame.lame_init();
        if (flags == null) throw new IOException("无法初始化 MP3 编码器");
        flags.num_channels = channels; flags.in_samplerate = sampleRate;
        flags.out_samplerate = outputRate(sampleRate);
        flags.brate = flags.out_samplerate >= 32000 ? 192 : flags.out_samplerate >= 16000 ? 96 : 32;
        flags.mode = channels == 1 ? MPEGMode.MONO : MPEGMode.JOINT_STEREO;
        flags.VBR = VbrMode.vbr_off; flags.quality = 3;
        flags.bWriteVbrTag = false; flags.write_id3tag_automatic = false; flags.findReplayGain = false;
        int status = lame.lame_init_params(flags);
        if (status < 0) { lame.lame_close(flags); closed = true; throw new IOException("MP3 参数初始化失败：" + status); }
    }

    private static int outputRate(int input) {
        int selected = 8000;
        for (int candidate : new int[]{8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000})
            if (Math.abs(candidate - input) < Math.abs(selected - input)) selected = candidate;
        return selected;
    }

    /** Consumes only the supplied buffer slice and tolerates frames split across codec buffers. */
    void write(ByteBuffer source, Format format) throws IOException {
        if (closed || finished) throw new IOException("MP3 编码器已结束");
        if (source == null || format == null) throw new IllegalArgumentException("PCM 数据无效");
        if (remainderSize != 0 && previousFormat != format) throw new IOException("PCM 格式在半帧中变化");
        previousFormat = format;
        ByteBuffer pcm = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        int frameBytes = channels * format.bytes;
        if (remainderSize > 0) {
            while (pcm.hasRemaining() && remainderSize < frameBytes) remainder[remainderSize++] = pcm.get();
            if (remainderSize == frameBytes) {
                encode(ByteBuffer.wrap(remainder, 0, frameBytes).order(ByteOrder.LITTLE_ENDIAN), format, 1);
                remainderSize = 0;
            }
        }
        while (pcm.remaining() >= frameBytes) {
            int count = Math.min(BLOCK_FRAMES, pcm.remaining() / frameBytes);
            encode(pcm, format, count);
        }
        while (pcm.hasRemaining()) remainder[remainderSize++] = pcm.get();
        source.position(source.limit());
    }

    private void encode(ByteBuffer pcm, Format format, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            left[i] = sample(pcm, format);
            right[i] = channels == 2 ? sample(pcm, format) : left[i];
        }
        int length = lame.lame_encode_buffer_int(flags, left, right, count, encoded, 0, encoded.length);
        if (length < 0) throw new IOException("MP3 音轨编码失败：" + length);
        output.write(encoded, 0, length); bytes += length; frames += count;
    }

    private static int sample(ByteBuffer pcm, Format format) {
        if (format == Format.SIGNED_16_LE) return pcm.getShort() << 16;
        float value = pcm.getFloat();
        if (Float.isNaN(value)) return 0;
        if (value <= -1f) return Integer.MIN_VALUE;
        if (value >= 1f) return Integer.MAX_VALUE;
        return (int) Math.round((double) value * Integer.MAX_VALUE);
    }

    long framesWritten() { return frames; }
    long bytesWritten() { return bytes; }
    void finish() throws IOException {
        if (closed || finished) throw new IOException("MP3 编码器已结束");
        if (remainderSize != 0) throw new IOException("音轨末尾 PCM 帧不完整");
        if (frames == 0) throw new IOException("视频没有可编码的音轨样本");
        int length = lame.lame_encode_flush(flags, encoded, 0, encoded.length);
        if (length < 0) throw new IOException("MP3 编码收尾失败：" + length);
        output.write(encoded, 0, length); bytes += length; output.flush(); finished = true;
    }

    /** Abandoning an unfinished encoder never manufactures an empty or silent output. */
    @Override public void close() {
        if (closed) return; closed = true; lame.lame_close(flags);
    }
}
