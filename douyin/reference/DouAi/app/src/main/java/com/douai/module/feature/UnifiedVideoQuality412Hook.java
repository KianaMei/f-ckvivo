package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostAdaptation;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Pins an existing native height per codec, preserving every audio entry. */
public final class UnifiedVideoQuality412Hook implements AutoCloseable {
    private final BehaviorHookGroup hooks;
    private final Field mediaType, height, height2, codec, codec2;
    private final Method infos;
    private final ThreadLocal<Boolean> reading = new ThreadLocal<>();
    private volatile LocalOptions options;
    private boolean closed;

    private UnifiedVideoQuality412Hook(LocalOptions options, Field mediaType, Field height, Field height2,
            Field codec, Field codec2, Method infos, Consumer<String> log) {
        this.options = options; this.mediaType = mediaType; this.height = height; this.height2 = height2;
        this.codec = codec; this.codec2 = codec2; this.infos = infos;
        hooks = new BehaviorHookGroup("unified_quality_412", log == null ? ignored -> { } : log);
    }

    public static UnifiedVideoQuality412Hook install(XposedInterface framework, ClassLoader loader,
            LocalOptions options, Consumer<String> log) throws ReflectiveOperationException {
        if (HostAdaptation.currentProfile() != HostAdaptation.PROFILE_412)
            throw new IllegalStateException("41.2 verified profile required");
        Class<?> model = Class.forName("com.ss.ttvideoengine.model.VideoModel", false, loader);
        Class<?> info = Class.forName("com.ss.ttvideoengine.model.VideoInfo", false, loader);
        Method list = model.getDeclaredMethod("getVideoInfoList");
        if (list.getReturnType() != List.class) throw new NoSuchMethodException("VideoModel.getVideoInfoList");
        UnifiedVideoQuality412Hook feature = new UnifiedVideoQuality412Hook(options,
                field(info, "mMediaType", int.class), field(info, "mVHeight", int.class),
                field(info, "mVHeightVer2", int.class), field(info, "mCodecType", String.class),
                field(info, "mCodecTypeVer2", String.class), list, log);
        try {
            feature.hooks.add(framework, list, chain -> {
                Object original = chain.proceed();
                if (!feature.enabled() || Boolean.TRUE.equals(feature.reading.get()) || !(original instanceof List<?>)) return original;
                try { return feature.select((List<?>) original); }
                catch (ReflectiveOperationException | RuntimeException failure) { return original; }
            });
            for (Method method : model.getDeclaredMethods()) {
                if (method.getReturnType() != info || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                feature.hooks.add(framework, method, chain -> {
                    Object original = chain.proceed();
                    if (!feature.enabled() || original == null) return original;
                    feature.reading.set(true);
                    try {
                        if (feature.mediaType.getInt(original) != 0) return original;
                        Object choices = feature.infos.invoke(chain.getThisObject());
                        if (!(choices instanceof List<?>)) return original;
                        return feature.replaceVideo(original, (List<?>) choices);
                    } catch (ReflectiveOperationException | RuntimeException failure) { return original; }
                    finally { feature.reading.remove(); }
                });
            }
            return feature;
        } catch (RuntimeException | LinkageError failure) { feature.close(); throw failure; }
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        Field value = owner.getDeclaredField(name);
        if (value.getType() != type) throw new NoSuchFieldException(name + " type");
        value.setAccessible(true); return value;
    }
    public void setOptions(LocalOptions options) { this.options = options; }
    public boolean isActive() { return !closed && hooks.isActive(); }
    private boolean enabled() {
        LocalOptions value = options;
        return !closed && qualityHeight(value) > 0;
    }

    static int qualityHeight(LocalOptions options) {
        if (options == null) return 0;
        if (options.enabled(LocalOptions.LOCK_HIGHEST_VIDEO_QUALITY)) return Integer.MAX_VALUE;
        String value = options.text("unified_video_quality", "auto");
        if ("max".equals(value)) return Integer.MAX_VALUE;
        for (String height : new String[]{"360", "480", "540", "720", "1080", "1440", "2160"})
            if (height.equals(value)) return Integer.parseInt(height);
        return 0;
    }

    private int height(Object info) throws IllegalAccessException {
        int value = height.getInt(info); return value > 0 ? value : height2.getInt(info);
    }
    private String codec(Object info) throws IllegalAccessException {
        Object first = codec.get(info), second = codec2.get(info);
        return first instanceof String && !((String) first).isEmpty() ? (String) first
                : second instanceof String ? (String) second : "";
    }
    private Object replaceVideo(Object original, List<?> choices) throws IllegalAccessException {
        String type = codec(original); int target = qualityHeight(options);
        int best = -1; Object winner = original;
        for (Object candidate : choices) {
            if (candidate == null || candidate.getClass() != original.getClass()
                    || mediaType.getInt(candidate) != 0 || !type.equals(codec(candidate))) continue;
            int candidateHeight = height(candidate);
            if (preferred(candidateHeight, best, target)) { best = candidateHeight; winner = candidate; }
        }
        return winner;
    }
    private List<?> select(List<?> original) throws IllegalAccessException {
        List<Stream> streams = new ArrayList<>();
        for (Object item : original) {
            if (item == null) { streams.add(new Stream(item, false, "", 0)); continue; }
            streams.add(new Stream(item, mediaType.getInt(item) == 0, codec(item), height(item)));
        }
        List<Object> selected = select(streams, qualityHeight(options));
        return selected.size() == original.size() ? original : selected;
    }

    static final class Stream {
        final Object value; final boolean video; final String codec; final int height;
        Stream(Object value, boolean video, String codec, int height) {
            this.value = value; this.video = video; this.codec = codec; this.height = height;
        }
    }
    static List<Object> highest(List<Stream> streams) {
        return select(streams, Integer.MAX_VALUE);
    }
    static boolean preferred(int candidate, int current, int target) {
        if (candidate <= 0 || target <= 0) return false;
        if (current <= 0) return true;
        if (target == Integer.MAX_VALUE) return candidate > current;
        if (candidate <= target) return current > target || candidate > current;
        return current > target && candidate < current;
    }
    static List<Object> select(List<Stream> streams, int target) {
        java.util.Map<String, Integer> best = new java.util.HashMap<>();
        for (Stream stream : streams) if (stream.video && preferred(stream.height, best.getOrDefault(stream.codec, -1), target))
            best.put(stream.codec, stream.height);
        List<Object> result = new ArrayList<>();
        for (Stream stream : streams) {
            // Unknown dimensions and non-video tracks are retained, never guessed or fabricated.
            if (!stream.video || stream.height <= 0 || stream.height == best.getOrDefault(stream.codec, stream.height))
                result.add(stream.value);
        }
        return result;
    }
    @Override public void close() { if (closed) return; closed = true; hooks.close(); }
}
