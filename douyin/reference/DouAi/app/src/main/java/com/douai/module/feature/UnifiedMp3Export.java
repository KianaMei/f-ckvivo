package com.douai.module.feature;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.function.Consumer;

/** Downloads a video's actual audio track, decodes PCM and encodes a new MP3 file. */
public final class UnifiedMp3Export {
    private static final long MAX_MP3_BYTES = 512L * 1024 * 1024;
    private static final long CODEC_WAIT_US = 10_000;
    private static final long STALL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
    interface Decoder { void encode(File source, File output, DownloadTransfer transfer) throws Exception; }
    private UnifiedMp3Export() { }

    public static boolean start(Context source, String videoUrl, String fileName, String tree, Consumer<String> log) {
        if (source == null || !MediaStoreDownloader.isHttpUrl(videoUrl)
                || fileName == null || fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\")) return false;
        String name = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3") ? fileName : fileName + ".mp3";
        Context application = source.getApplicationContext();
        Context context = application == null ? source : application;
        return DownloadTasks.submit(context, "video-original-mp3\n" + videoUrl + '\n' + name + '\n' + tree,
                name, transfer -> save(context, videoUrl, name, tree, UnifiedMp3Export::decode, transfer), log) != null;
    }

    static String save(Context context, String source, String name, String tree, Decoder decoder,
                       DownloadTransfer transfer) throws Exception {
        transfer.check();
        File owned = Files.createTempDirectory(context.getCacheDir().toPath(), "douai-mp3-").toFile();
        File video = new File(owned, "source.media"), mp3 = new File(owned, "original.mp3");
        try {
            transfer.file(name, 2);
            MediaStoreDownloader.downloadTemporary(source, video, transfer);
            transfer.fileDone();
            transfer.file(name, 2);
            transfer.phase("正在提取视频原声并编码 MP3");
            decoder.encode(video, mp3, transfer);
            transfer.check();
            if (!mp3.isFile() || mp3.length() < 4 || mp3.length() > MAX_MP3_BYTES)
                throw new IOException("MP3 编码没有生成有效文件");
            try (var input = new java.io.FileInputStream(mp3)) {
                int a = input.read(), b = input.read();
                if (a != 0xff || (b & 0xe6) != 0xe2) throw new IOException("编码结果不是 MPEG Layer III 音频");
            }
            transfer.evidence("video_original_mp3_encoded", "bytes=" + mp3.length()
                    + "; source=video_audio_track; pcm_decoded=true; mp3_encoded=true; device_decode_validation=required");
            transfer.file(name, 2);
            return MediaStoreDownloader.saveLocalMedia(context, mp3, name, "audio/mpeg", tree, transfer);
        } finally {
            // Only these files in our freshly created private cache are owned by this task.
            try { Files.deleteIfExists(video.toPath()); } catch (IOException ignored) { }
            try { Files.deleteIfExists(mp3.toPath()); } catch (IOException ignored) { }
            try { Files.deleteIfExists(owned.toPath()); } catch (IOException ignored) { }
        }
    }

    private static void decode(File video, File output, DownloadTransfer transfer) throws Exception {
        try (DownloadTransfer.Streams resources = transfer.streams()) {
            DecodeSession session = resources.add(new DecodeSession());
            transfer.check(); session.extractor.setDataSource(video.getAbsolutePath());
            MediaFormat format = null; String mime = null;
            for (int track = 0; track < session.extractor.getTrackCount(); track++) {
                transfer.check();
                MediaFormat candidate = session.extractor.getTrackFormat(track);
                String type = candidate.getString(MediaFormat.KEY_MIME);
                if (type != null && type.startsWith("audio/")) {
                    format = candidate; mime = type; session.extractor.selectTrack(track); break;
                }
            }
            if (format == null) throw new IOException("当前视频没有音轨");
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            transfer.check(); session.attach(MediaCodec.createDecoderByType(mime));
            session.codec.configure(format, null, null, 0); session.codec.start();
            OutputStream stream = resources.add(new FileOutputStream(output));
            OutputStream bounded = new FilterOutputStream(stream) {
                long written;
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (length < 0 || written > MAX_MP3_BYTES - length) throw new IOException("MP3 文件过大");
                    out.write(bytes, offset, length); written += length;
                }
                @Override public void write(int value) throws IOException {
                    if (++written > MAX_MP3_BYTES) throw new IOException("MP3 文件过大"); out.write(value);
                }
            };
            PcmMp3Encoder encoder = null;
            MediaFormat pcmFormat = null;
            boolean inputEnded = false, outputEnded = false;
            long progress = System.nanoTime();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (!outputEnded) {
                    transfer.check();
                    if (!inputEnded) {
                        int index = session.codec.dequeueInputBuffer(CODEC_WAIT_US);
                        if (index >= 0) {
                            ByteBuffer input = session.codec.getInputBuffer(index);
                            if (input == null) throw new IOException("音频解码输入缓冲区不可用");
                            input.clear();
                            int count = session.extractor.readSampleData(input, 0);
                            if (count < 0) {
                                inputEnded = true;
                                session.codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            } else {
                                if ((session.extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                                    throw new IOException("不支持加密音轨");
                                session.codec.queueInputBuffer(index, 0, count,
                                        Math.max(0, session.extractor.getSampleTime()), 0);
                                session.extractor.advance();
                            }
                            progress = System.nanoTime();
                        }
                    }
                    int index = session.codec.dequeueOutputBuffer(info, CODEC_WAIT_US);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        pcmFormat = session.codec.getOutputFormat(); progress = System.nanoTime();
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (pcmFormat == null) pcmFormat = session.codec.getOutputFormat();
                                int sampleRate = pcmFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                                int channels = pcmFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                                int encoding = pcmFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                        ? pcmFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                                PcmMp3Encoder.Format sampleFormat = pcmEncoding(encoding);
                                if (encoder == null) encoder = new PcmMp3Encoder(bounded, sampleRate, channels);
                                else if (session.sampleRate != sampleRate || session.channels != channels)
                                    throw new IOException("音轨的采样率或声道数中途变化");
                                session.sampleRate = sampleRate; session.channels = channels;
                                ByteBuffer pcm = session.codec.getOutputBuffer(index);
                                if (pcm == null || info.offset < 0 || info.size > pcm.capacity() - info.offset)
                                    throw new IOException("音频解码输出缓冲区无效");
                                ByteBuffer data = pcm.duplicate(); data.position(info.offset); data.limit(info.offset + info.size);
                                encoder.write(data, sampleFormat); transfer.advance(info.size);
                            }
                            outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                            progress = System.nanoTime();
                        } finally { session.codec.releaseOutputBuffer(index, false); }
                    }
                    if (System.nanoTime() - progress >= STALL_NANOS) throw new IOException("音轨解码长时间没有进展");
                }
                transfer.check();
                if (encoder == null) throw new IOException("音轨没有可解码的样本");
                encoder.finish();
                transfer.evidence("audio_pcm_encoded", "pcmFrames=" + encoder.framesWritten()
                        + "; mp3Bytes=" + encoder.bytesWritten() + "; sampleRate=" + session.sampleRate + "; channels=" + session.channels);
            } finally { if (encoder != null) encoder.close(); }
        } catch (Exception failure) { transfer.check(); throw failure; }
    }

    static PcmMp3Encoder.Format pcmEncoding(int encoding) throws IOException {
        if (encoding == AudioFormat.ENCODING_PCM_16BIT) return PcmMp3Encoder.Format.SIGNED_16_LE;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) return PcmMp3Encoder.Format.FLOAT_32_LE;
        throw new IOException("不支持的解码 PCM 格式：" + encoding);
    }
    private static final class DecodeSession implements Closeable {
        final MediaExtractor extractor = new MediaExtractor();
        volatile MediaCodec codec;
        int sampleRate, channels;
        boolean closed;
        synchronized void attach(MediaCodec next) throws IOException {
            if (closed) {
                try { next.release(); } catch (RuntimeException ignored) { }
                throw new IOException("音频解码任务已停止");
            }
            codec = next;
        }
        @Override public synchronized void close() {
            if (closed) return; closed = true;
            MediaCodec active = codec;
            if (active != null) {
                try { active.stop(); } catch (RuntimeException ignored) { }
                try { active.release(); } catch (RuntimeException ignored) { }
            }
            try { extractor.release(); } catch (RuntimeException ignored) { }
        }
    }
}
