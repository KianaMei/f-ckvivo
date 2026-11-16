package com.douai.module.feature;

import com.douai.module.scan.HostAdaptation;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONObject;

public final class PlaybackSourceCaptureHook implements AutoCloseable {
    private static final long MAX_AGE_MILLIS = 10 * 60 * 1000L;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<String, Snapshot> sources = new LinkedHashMap<>();
    private final Map<String, WeakReference<Object>> works = new LinkedHashMap<>();
    private final Consumer<String> log;
    private volatile boolean closed;

    PlaybackSourceCaptureHook(Consumer<String> log) {
        this.log = log == null ? ignored -> { } : log;
    }

    public static PlaybackSourceCaptureHook install(XposedInterface framework, ClassLoader loader,
                                                    Consumer<String> log) throws Exception {
        if (HostAdaptation.currentProfile() != HostAdaptation.PROFILE_412)
            throw new IllegalStateException("41.2 verified profile required");
        PlaybackSourceCaptureHook feature = new PlaybackSourceCaptureHook(log);
        try {
            Class<?> model = Class.forName("com.ss.ttvideoengine.model.VideoModel", false, loader);
            Class<?> info = Class.forName("com.ss.ttvideoengine.model.VideoInfo", false, loader);
            Class<?> reference = Class.forName("com.ss.ttvideoengine.model.VideoRef", false, loader);
            for (String name : new String[]{"mMainUrl", "mMainUrlVer2", "mURLs", "mURLsVer2", "mMediaType", "mEncrypt"})
                info.getDeclaredField(name);
            reference.getDeclaredField("mVideoId");
            model.getDeclaredField("vodVideoRef");
            Method list = model.getDeclaredMethod("getVideoInfoList");
            if (list.getReturnType() != List.class) throw new NoSuchMethodException("VideoModel.getVideoInfoList");
            feature.add(framework, list, "DouAi.PlaybackSource.Capture", chain -> {
                Object original = chain.proceed();
                if (!feature.closed && original instanceof List<?>) {
                    try { feature.capture(chain.getThisObject(), (List<?>)original); }
                    catch (RuntimeException | LinkageError failure) { feature.warn(failure); }
                }
                return original;
            });
            Class<?> aweme = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader);
            feature.add(framework, aweme.getDeclaredMethod("getVideo"), "DouAi.PlaybackSource.Work", chain -> {
                Object result = chain.proceed();
                try { feature.rememberWork(chain.getThisObject()); }
                catch (RuntimeException | LinkageError failure) { feature.warn(failure); }
                return result;
            });
            return feature;
        } catch (Exception | LinkageError failure) { feature.close(); throw failure; }
    }

    private void add(XposedInterface framework, Method method, String id, XposedInterface.Hooker callback) {
        XposedInterface.HookHandle handle = framework.hook(method).setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(callback);
        if (handle == null) throw new IllegalStateException("Empty playback source hook handle");
        handles.add(handle);
    }

    void capture(Object model, List<?> infos) {
        Object reference = HostTargets.read(model, "vodVideoRef");
        String vid = text(HostTargets.read(reference, "mVideoId"));
        if (vid.isEmpty()) vid = text(HostTargets.read(reference, "mVideoIdVer2"));
        if (closed || vid.isEmpty()) return;
        String format = text(HostTargets.read(reference, "mFormat")).toLowerCase(java.util.Locale.ROOT);
        boolean split = format.contains("dash") || format.contains("mpd") || format.contains("hls")
                || Boolean.TRUE.equals(HostTargets.read(reference, "mFormatHasDASH"));
        Set<String> urls = new LinkedHashSet<>();
        if (infos.size() > 128) {
            synchronized (sources) { sources.remove(vid); }
            return;
        }
        for (Object info : infos) {
            Object type = HostTargets.read(info, "mMediaType");
            if (!(type instanceof Number)) continue;
            if (((Number)type).intValue() == 1) { split = true; continue; }
            if (((Number)type).intValue() != 0 || Boolean.TRUE.equals(HostTargets.read(info, "mEncrypt"))) continue;
            if (!text(HostTargets.read(info, "mInitRange")).isEmpty()
                    || !text(HostTargets.read(info, "mIndexRange")).isEmpty()) { split = true; continue; }
            for (String field : new String[]{"mMainUrl", "mMainUrlVer2", "mURLs", "mURLsVer2"})
                addUrls(HostTargets.read(info, field), urls);
        }
        synchronized (sources) {
            if (urls.isEmpty() || split) sources.remove(vid);
            else sources.put(vid, new Snapshot(new ArrayList<>(urls), System.currentTimeMillis()));
            while (sources.size() > 32) sources.remove(sources.keySet().iterator().next());
        }
    }

    public void rememberWork(Object work) {
        String aid = workId(work);
        if (closed || aid.isEmpty()) return;
        synchronized (works) {
            works.put(aid, new WeakReference<>(work));
            while (works.size() > 64) works.remove(works.keySet().iterator().next());
        }
    }

    public Object workForId(String aid) {
        synchronized (works) {
            WeakReference<Object> value = works.get(aid);
            return value == null ? null : value.get();
        }
    }

    public List<String> playbackUrlsFor(Object work) {
        if (closed || work == null) return Collections.emptyList();
        Object video = DownloadActionHook.jsonValue(work, "video");
        Set<String> ids = new LinkedHashSet<>();
        for (String key : new String[]{"play_addr", "play_addr_h264", "play_addr_265", "download_addr", "new_download_addr"}) {
            String uri = text(DownloadActionHook.jsonValue(DownloadActionHook.jsonValue(video, key), "uri"));
            if (!uri.isEmpty()) ids.add(uri);
        }
        Object encoded = DownloadActionHook.jsonValue(video, "video_model");
        if (encoded instanceof String && ((String)encoded).length() <= 1024 * 1024) {
            try {
                JSONObject root = new JSONObject((String)encoded);
                JSONObject details = root.optJSONObject("video_info");
                for (JSONObject object : new JSONObject[]{root, details}) if (object != null) {
                    String value = object.optString("video_id", "");
                    if (!value.isEmpty()) ids.add(value);
                }
            } catch (org.json.JSONException ignored) { }
        }
        synchronized (sources) {
            for (String vid : ids) {
                Snapshot value = sources.get(vid);
                if (value == null) continue;
                if (System.currentTimeMillis() - value.recordedAt > MAX_AGE_MILLIS) { sources.remove(vid); continue; }
                return value.urls;
            }
        }
        return Collections.emptyList();
    }

    private static void addUrls(Object value, Set<String> found) {
        if (value instanceof String[]) { for (String url : (String[])value) addUrls(url, found); return; }
        if (!(value instanceof String) || found.size() >= 32) return;
        String url = ((String)value).trim();
        if (!MediaStoreDownloader.isHttpUrl(url)) return;
        String path = java.net.URI.create(url).getPath().toLowerCase(java.util.Locale.ROOT);
        if (!path.endsWith(".m3u8") && !path.endsWith(".mpd")) found.add(url);
    }

    static String workId(Object work) {
        Object value = DownloadActionHook.jsonValue(work, "aweme_id");
        if (value == null) value = HostTargets.invoke(work, "getAid");
        return text(value);
    }
    private static String text(Object value) { return value instanceof String ? ((String)value).trim() : ""; }
    private void warn(Throwable failure) {
        try { log.accept("Playback source observation unavailable: " + failure.getClass().getSimpleName()); }
        catch (RuntimeException ignored) { }
    }
    @Override public void close() {
        closed = true;
        for (XposedInterface.HookHandle handle : handles) try { handle.unhook(); } catch (RuntimeException ignored) { }
        handles.clear();
        synchronized (sources) { sources.clear(); }
        synchronized (works) { works.clear(); }
    }
    private static final class Snapshot {
        final List<String> urls;
        final long recordedAt;
        Snapshot(List<String> urls, long recordedAt) { this.urls = Collections.unmodifiableList(urls); this.recordedAt = recordedAt; }
    }
}
