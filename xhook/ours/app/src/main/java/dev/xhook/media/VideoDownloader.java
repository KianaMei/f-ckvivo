package dev.xhook.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.xhook.Runtime;

/**
 * 在 X 进程内下载 HLS/MP4 并写入相册。
 *
 * <p>只处理 video.twimg.com；HLS 按主播放列表 → 变体 → init + 分片 → MediaMuxer 合流，
 * 不转码。加密 HLS、直播、字节范围分片直接拒绝。</p>
 */
public final class VideoDownloader {
    private static final Pattern ATTRS =
            Pattern.compile("(?:^|,)([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");

    private static final ExecutorService WORK = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private VideoDownloader() {
    }

    public static void start(Context context, MediaCapture.Entry entry, Quality.Level level) {
        if (entry == null) {
            toast(context, "还没识别到视频，先播放几秒再点");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            toast(context, "已有下载任务在跑，请稍等");
            return;
        }
        Context app = context.getApplicationContext();
        MediaCapture.Entry snapshot = entry;
        Quality.Level chosen = level;
        toast(app, "开始下载 " + shortId(snapshot.id)
                + " · " + (chosen == Quality.Level.ORIGINAL ? "原画"
                : chosen == Quality.Level.HD ? "高清" : "标清"));
        WORK.execute(() -> {
            File cacheDir = null;
            try {
                cacheDir = new File(app.getCacheDir(),
                        "xhook-" + snapshot.id + "-" + chosen.name());
                if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                    throw new IOException("无法创建缓存目录");
                }
                File saved = download(app, snapshot, chosen, cacheDir);
                toast(app, "已保存到相册：Movies/XHook/" + saved.getName());
                Runtime.log("download OK " + snapshot.id + " -> " + saved.getName());
            } catch (Throwable throwable) {
                String message = throwable.getMessage();
                if (message == null || message.isEmpty()) {
                    message = throwable.getClass().getSimpleName();
                }
                Runtime.error("download failed " + snapshot.id, throwable);
                toast(app, "下载失败：" + shorten(message));
            } finally {
                deleteTree(cacheDir);
                BUSY.set(false);
            }
        });
    }

    private static File download(Context context,
                                 MediaCapture.Entry entry,
                                 Quality.Level level,
                                 File cacheDir) throws Exception {
        URL source = safe(entry.url);
        String path = source.getPath() == null ? "" : source.getPath().toLowerCase(Locale.ROOT);

        if (!entry.master && path.endsWith(".mp4")) {
            File direct = new File(cacheDir, "video.mp4");
            fetchToFile(source.toString(), direct, null);
            return saveDirect(context, direct, entry.id);
        }

        String playlist = fetchText(entry.url, 2_000_000);
        Runtime.log("download playlist url=" + entry.url
                + " bytes=" + playlist.length()
                + " media=" + Quality.isMediaPlaylist(playlist));

        File videoTrack = new File(cacheDir, "video.bin");
        File audioTrack = null;
        if (Quality.isMediaPlaylist(playlist)) {
            // X 有些视频直接给单码率媒体播放列表，没有 master 变体
            downloadMediaPlaylist(entry.url, videoTrack, "画面");
        } else {
            Quality.Selection selection;
            try {
                selection = Quality.select(playlist, level);
            } catch (IOException error) {
                int head = Math.min(300, playlist.length());
                Runtime.log("master playlist head=" + playlist.substring(0, head)
                        .replace('\n', '|'));
                throw error;
            }
            String videoPlaylist = resolve(entry.url, selection.videoUri);
            downloadMediaPlaylist(videoPlaylist, videoTrack, "画面");
            if (selection.audioUri != null) {
                String audioPlaylist = resolve(entry.url, selection.audioUri);
                audioTrack = new File(cacheDir, "audio.bin");
                downloadMediaPlaylist(audioPlaylist, audioTrack, "声音");
            }
            if (selection.width > 0 && selection.height > 0) {
                toast(context, (selection.aboveLimit ? "没有对应档位，使用最低画质："
                        : "画质：") + selection.width + "×" + selection.height);
            }
        }
        return saveMuxed(context, videoTrack, audioTrack, entry.id);
    }

    private static void downloadMediaPlaylist(String playlistUrl,
                                              File target,
                                              String label) throws Exception {
        String playlist = fetchText(playlistUrl, 2_000_000);
        List<String> segments = new ArrayList<>();
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> attributes = attributes(line.substring(11));
                String method = attributes.get("METHOD");
                if (method != null && !"NONE".equalsIgnoreCase(method)) {
                    throw new IOException("加密 HLS 暂不支持");
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> attributes = attributes(line.substring(11));
                String init = attributes.get("URI");
                if (init != null && !init.isEmpty()) {
                    segments.add(0, resolve(playlistUrl, init));
                }
            } else if (!line.startsWith("#")) {
                segments.add(resolve(playlistUrl, line));
            }
        }
        if (segments.isEmpty()) {
            throw new IOException("播放列表没有分片");
        }

        if (target.exists() && !target.delete()) {
            throw new IOException("无法清理旧缓存");
        }
        long total = 0;
        try (FileOutputStream output = new FileOutputStream(target, true)) {
            for (int index = 0; index < segments.size(); index++) {
                String segment = segments.get(index);
                long received = fetchToStream(segment,
                        null,
                        output,
                        Math.min(512L * 1024 * 1024, 2_000_000_000L - total));
                total += received;
                if (index % 10 == 0 || index == segments.size() - 1) {
                    Runtime.log(label + " segments " + (index + 1) + "/" + segments.size());
                }
            }
        }
    }

    private static File saveDirect(Context context, File file, String id) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName(id));
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_MOVIES + "/XHook");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("无法创建相册条目");
        }
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "w")) {
            if (descriptor == null) {
                throw new IOException("无法打开相册文件");
            }
            try (InputStream input = new FileInputStream(file);
                 OutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
                copy(input, output);
            }
        } catch (Throwable throwable) {
            resolver.delete(uri, null, null);
            throw throwable;
        }
        values.clear();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return new File(fileName(id));
    }

    private static File saveMuxed(Context context,
                                  File video,
                                  File audio,
                                  String id) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName(id));
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_MOVIES + "/XHook");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("无法创建相册条目");
        }
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "w")) {
            if (descriptor == null) {
                throw new IOException("无法打开相册文件");
            }
            mux(video, audio, descriptor);
        } catch (Throwable throwable) {
            resolver.delete(uri, null, null);
            throw throwable;
        }
        values.clear();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return new File(fileName(id));
    }

    /** 把 fMP4 视频/音轨重新封装成普通 MP4；不解码、不转码。 */
    private static void mux(File videoFile, File audioFile, ParcelFileDescriptor output)
            throws Exception {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;
        try {
            videoExtractor.setDataSource(videoFile.getPath());
            int videoTrack = selectTrack(videoExtractor, "video/");
            if (videoTrack < 0) {
                throw new IOException("文件里没有视频轨");
            }

            int audioTrack = -1;
            if (audioFile != null && audioFile.length() > 0) {
                audioExtractor = new MediaExtractor();
                audioExtractor.setDataSource(audioFile.getPath());
                audioTrack = selectTrack(audioExtractor, "audio/");
            }

            muxer = new MediaMuxer(output.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack));
            int muxAudio = -1;
            if (audioExtractor != null && audioTrack >= 0) {
                muxAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack));
            }
            muxer.start();

            copyTrack(videoExtractor, videoTrack, muxer, muxVideo);
            if (audioExtractor != null && audioTrack >= 0 && muxAudio >= 0) {
                copyTrack(audioExtractor, audioTrack, muxer, muxAudio);
            }
            muxer.stop();
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                videoExtractor.release();
            } catch (Throwable ignored) {
            }
            if (audioExtractor != null) {
                try {
                    audioExtractor.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static int selectTrack(MediaExtractor extractor, String prefix) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            MediaFormat format = extractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    private static void copyTrack(MediaExtractor extractor,
                                  int track,
                                  MediaMuxer muxer,
                                  int muxTrack) {
        extractor.selectTrack(track);
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(muxTrack, buffer, info);
            if (!extractor.advance()) {
                break;
            }
        }
        extractor.unselectTrack(track);
    }

    private static String fetchText(String url, long limit) throws Exception {
        try (InputStream input = open(url)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("播放列表过大");
                }
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    private static void fetchToFile(String url, File target, Progress progress) throws Exception {
        if (target.exists() && !target.delete()) {
            throw new IOException("无法清理旧文件");
        }
        try (InputStream input = open(url);
             OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
    }

    private static long fetchToStream(String url,
                                      Progress progress,
                                      OutputStream output,
                                      long limit) throws Exception {
        try (InputStream input = open(url)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("媒体文件过大");
                }
                output.write(buffer, 0, read);
            }
            return total;
        }
    }

    private static InputStream open(String url) throws Exception {
        URL current = safe(url);
        for (int redirect = 0; redirect < 4; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = connection.getResponseCode();
            if (code == 200) {
                return connection.getInputStream();
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    throw new IOException("重定向缺少 Location");
                }
                current = safe(new URL(current, location).toString());
                continue;
            }
            connection.disconnect();
            throw new IOException("媒体服务器返回 " + code);
        }
        throw new IOException("重定向次数过多");
    }

    private static URL safe(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"video.twimg.com".equalsIgnoreCase(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443)
                || url.getUserInfo() != null) {
            throw new IOException("不支持的媒体地址");
        }
        return url;
    }

    private static String resolve(String base, String relative) throws IOException {
        return safe(new URL(safe(base), relative).toString()).toString();
    }

    private static Map<String, String> attributes(String line) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = ATTRS.matcher(line);
        while (matcher.find()) {
            result.put(matcher.group(1),
                    matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        }
        return result;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private static String fileName(String id) {
        return "XHook_" + id + "_" + System.currentTimeMillis() + ".mp4";
    }

    private static String shortId(String id) {
        return id.length() <= 6 ? id : id.substring(id.length() - 6);
    }

    private static String shorten(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    private static void deleteTree(File file) {
        if (file == null) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private interface Progress {
        void onProgress(long received, long total);
    }
}
