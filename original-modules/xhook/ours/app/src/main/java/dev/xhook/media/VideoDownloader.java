package dev.xhook.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.xhook.Runtime;

/**
 * 在 X 进程内下载 HLS/MP4 并写入相册（支持多任务排队、自动重试与实时进度）。
 *
 * <p>只处理 {@code https://video.twimg.com}；HLS 按主播放列表 → 选档 → init+分片 →
 * {@link MediaMuxer} 合流（不转码）。下载临时目录位于宿主 {@code cacheDir}，
 * 无论成功或失败均在 {@code finally} 中彻底清理，不在设备残留临时文件。</p>
 */
public final class VideoDownloader {
    private static final Pattern ATTRS =
            Pattern.compile("(?:^|,)([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");

    private static final ExecutorService WORK = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final Map<String, Task> TASKS = new LinkedHashMap<>();
    private static final ThreadLocal<Task> CURRENT = new ThreadLocal<>();
    private static final int MAX_TASKS = 10;

    private static final class Task {
        final String id;
        String label = "准备";

        Task(String id) {
            this.id = id;
        }
    }

    private VideoDownloader() {
    }

    public static boolean hasActiveTasks() {
        synchronized (LOCK) {
            return !TASKS.isEmpty();
        }
    }

    public static void start(Context context, MediaCapture.Entry entry, Quality.Level level) {
        if (entry == null) {
            toast(context, "还没识别到视频，先播放几秒再点");
            return;
        }
        final Task task;
        synchronized (LOCK) {
            if (TASKS.containsKey(entry.id)) {
                toast(context, "该视频已在下载或排队中");
                return;
            }
            if (TASKS.size() >= MAX_TASKS) {
                toast(context, "下载队列已满，请稍后再试");
                return;
            }
            task = new Task(entry.id);
            TASKS.put(entry.id, task);
            notifyStatusLocked();
        }

        Context app = context.getApplicationContext();
        String kind = entry.gif ? "GIF"
                : (level == Quality.Level.ORIGINAL ? "原画"
                : level == Quality.Level.HD ? "高清" : "标清");
        toast(app, "开始下载 " + shortId(entry.id) + " · " + kind);

        WORK.execute(() -> {
            CURRENT.set(task);
            File cacheDir = null;
            try {
                cacheDir = new File(app.getCacheDir(),
                        "xhook-dl-" + sha256Short(entry.id + "/" + level.name())
                                + "-" + System.nanoTime());
                if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                    throw new IOException("无法创建下载缓冲目录");
                }
                String savedName = download(app, entry, level, cacheDir);
                toast(app, "已保存到相册：Movies/XHook/" + savedName);
                Runtime.log("download OK " + entry.id + " -> " + savedName);
            } catch (Throwable throwable) {
                String message = throwable.getMessage();
                if (message == null || message.isEmpty()) {
                    message = throwable.getClass().getSimpleName();
                }
                Runtime.error("download failed " + entry.id, throwable);
                toast(app, "下载失败：" + shorten(message));
            } finally {
                deleteTree(cacheDir);
                CURRENT.remove();
                synchronized (LOCK) {
                    TASKS.remove(task.id);
                    notifyStatusLocked();
                }
            }
        });
    }

    private static void setTaskLabel(String value) {
        Task task = CURRENT.get();
        if (task == null) {
            return;
        }
        synchronized (LOCK) {
            if (TASKS.get(task.id) == task) {
                task.label = value;
                notifyStatusLocked();
            }
        }
    }

    private static void notifyStatusLocked() {
        int count = TASKS.size();
        String label = count == 0 ? "↓"
                : count == 1 ? TASKS.values().iterator().next().label
                : "队" + count;
        PlayerButtons.updateStatus(label);
    }

    private static String download(Context context,
                                   MediaCapture.Entry entry,
                                   Quality.Level level,
                                   File cacheDir) throws Exception {
        URL source = safe(entry.url);
        String path = source.getPath() == null ? "" : source.getPath().toLowerCase(Locale.ROOT);

        if (!entry.master && path.endsWith(".mp4")) {
            File direct = new File(cacheDir, "video.mp4");
            fetchToFileWithRetry(source.toString(), direct, 1_073_741_824L);
            verifyDirectMp4(direct);
            return saveDirect(context, direct, entry.id);
        }

        String playlist = fetchTextWithRetry(entry.url, 2_000_000);
        Runtime.log("download playlist id=" + shortId(entry.id)
                + " bytes=" + playlist.length()
                + " media=" + Quality.isMediaPlaylist(playlist));

        File videoTrack = new File(cacheDir, "video.bin");
        File audioTrack = null;
        if (Quality.isMediaPlaylist(playlist)) {
            downloadMediaPlaylist(entry.url, playlist, videoTrack, "画");
        } else {
            Quality.Selection selection = Quality.select(playlist, level);
            if (selection.width > 0 && selection.height > 0) {
                toast(context, (selection.aboveLimit ? "无匹配档位，使用最低画质："
                        : "画质：") + selection.width + "×" + selection.height);
            }
            String videoPlaylistUrl = resolve(entry.url, selection.videoUri);
            downloadMediaPlaylist(videoPlaylistUrl, null, videoTrack, "画");
            if (selection.audioUri != null) {
                String audioPlaylistUrl = resolve(entry.url, selection.audioUri);
                audioTrack = new File(cacheDir, "audio.bin");
                downloadMediaPlaylist(audioPlaylistUrl, null, audioTrack, "音");
            }
        }
        setTaskLabel("合流");
        return saveMuxed(context, videoTrack, audioTrack, entry.id);
    }

    private static void downloadMediaPlaylist(String playlistUrl,
                                              String preloadedText,
                                              File target,
                                              String shortTag) throws Exception {
        String playlist = preloadedText != null ? preloadedText
                : fetchTextWithRetry(playlistUrl, 2_000_000);
        if (!playlist.contains("#EXT-X-ENDLIST")) {
            throw new IOException("暂不支持直播或未结束的视频流");
        }
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
            } else if (line.startsWith("#EXT-X-BYTERANGE")
                    || line.startsWith("#EXT-X-DISCONTINUITY")) {
                throw new IOException("暂不支持该 HLS 分片格式");
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
            throw new IOException("无法清理旧缓冲");
        }
        File partTemp = new File(target.getParentFile(), target.getName() + ".part");
        long total = 0;
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            int count = segments.size();
            for (int index = 0; index < count; index++) {
                String segment = segments.get(index);
                fetchToFileWithRetry(segment, partTemp, 256L * 1024 * 1024);
                long segLen = partTemp.length();
                total += segLen;
                if (total > 1_073_741_824L) {
                    throw new IOException("视频体积超出上限");
                }
                try (InputStream in = new BufferedInputStream(new FileInputStream(partTemp))) {
                    copy(in, output);
                }
                int pct = Math.min(99, (index + 1) * 100 / count);
                setTaskLabel(shortTag + pct + "%");
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            partTemp.delete();
        }
    }

    private static void verifyDirectMp4(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    if (extractor.readSampleData(ByteBuffer.allocateDirect(4 * 1024 * 1024), 0) > 0) {
                        return;
                    }
                }
            }
            throw new IOException("视频文件没有可播放画面");
        } finally {
            extractor.release();
        }
    }

    private static String saveDirect(Context context, File file, String id) throws Exception {
        String name = fileName(id);
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/XHook");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("无法创建相册条目");
        }
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "w")) {
            if (descriptor == null) {
                throw new IOException("无法打开相册文件");
            }
            try (InputStream input = new BufferedInputStream(new FileInputStream(file));
                 OutputStream output = new BufferedOutputStream(
                         new FileOutputStream(descriptor.getFileDescriptor()))) {
                copy(input, output);
            }
        } catch (Throwable throwable) {
            resolver.delete(uri, null, null);
            throw throwable;
        }
        values.clear();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return name;
    }

    private static String saveMuxed(Context context,
                                    File video,
                                    File audio,
                                    String id) throws Exception {
        String name = fileName(id);
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/XHook");
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
        return name;
    }

    /** 把 fMP4 视频/音轨重新封装成普通 MP4；不解码、不转码。 */
    private static void mux(File videoFile, File audioFile, ParcelFileDescriptor output)
            throws Exception {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;
        boolean started = false;
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
            } else {
                // 单播放列表内可能复用音视频轨
                audioTrack = selectTrack(videoExtractor, "audio/");
            }

            muxer = new MediaMuxer(output.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack));
            int muxAudio = -1;
            if (audioExtractor != null && audioTrack >= 0) {
                muxAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack));
            } else if (audioExtractor == null && audioTrack >= 0) {
                muxAudio = muxer.addTrack(videoExtractor.getTrackFormat(audioTrack));
            }
            muxer.start();
            started = true;

            copyTrackFresh(videoFile, videoTrack, muxer, muxVideo);
            if (muxAudio >= 0) {
                copyTrackFresh(audioExtractor != null ? audioFile : videoFile,
                        audioTrack, muxer, muxAudio);
            }
            muxer.stop();
            started = false;
        } finally {
            if (muxer != null) {
                if (started) {
                    try {
                        muxer.stop();
                    } catch (Throwable ignored) {
                    }
                }
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

    private static void copyTrackFresh(File sourceFile,
                                       int track,
                                       MediaMuxer muxer,
                                       int muxTrack) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(sourceFile.getPath());
            extractor.selectTrack(track);
            ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int samples = 0;
            while (true) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                long pts = extractor.getSampleTime();
                if (pts < 0) {
                    break;
                }
                int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, pts, flags);
                muxer.writeSampleData(muxTrack, buffer, info);
                samples++;
                if (!extractor.advance()) {
                    break;
                }
            }
            if (samples == 0) {
                throw new IOException("轨道没有可用采样");
            }
        } finally {
            extractor.release();
        }
    }

    private static String fetchTextWithRetry(String url, long limit) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return fetchText(url, limit);
            } catch (Exception error) {
                last = error;
                if (attempt < 2) {
                    Thread.sleep(600L * (attempt + 1));
                }
            }
        }
        throw last;
    }

    private static String fetchText(String url, long limit) throws Exception {
        HttpURLConnection conn = open(url);
        try (InputStream input = conn.getInputStream()) {
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
        } finally {
            conn.disconnect();
        }
    }

    private static void fetchToFileWithRetry(String url, File target, long limit) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                fetchToFile(url, target, limit);
                return;
            } catch (Exception error) {
                last = error;
                if (attempt < 2) {
                    Thread.sleep(800L * (attempt + 1));
                }
            }
        }
        throw last;
    }

    private static void fetchToFile(String url, File target, long limit) throws Exception {
        if (target.exists() && !target.delete()) {
            throw new IOException("无法清理临时分片");
        }
        HttpURLConnection conn = open(url);
        try {
            long expected = conn.getContentLengthLong();
            if (expected > limit) {
                throw new IOException("媒体文件过大");
            }
            long received = 0;
            long lastUi = 0;
            try (InputStream input = conn.getInputStream();
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    received += read;
                    if (received > limit) {
                        throw new IOException("媒体文件过大");
                    }
                    output.write(buffer, 0, read);
                    if (expected > 2_000_000L) {
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 400L) {
                            lastUi = now;
                            setTaskLabel(Math.min(99, received * 100 / expected) + "%");
                        }
                    }
                }
            }
            if (received == 0 || (expected > 0 && received != expected)) {
                throw new IOException("分片下载不完整");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        URL current = safe(url);
        for (int redirect = 0; redirect < 4; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int code = connection.getResponseCode();
            if (code == 200) {
                return connection;
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

    private static String sha256Short(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.US, "%02x", bytes[i] & 0xFF));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(text.hashCode());
        }
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
}
