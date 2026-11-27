package dev.xhook.media;

import android.app.Activity;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import dev.xhook.Runtime;

/**
 * 追踪当前屏幕上真实可见 / 正在播放的 Media3 播放器实例。
 *
 * <p>适配 X 12.45+ 实测要点：</p>
 * <ul>
 *   <li>X 将 {@code PlayerView} 配合自定义 {@code AsyncSurfaceTextureView} 使用，
 *       {@code ExoPlayerImpl} 自身的 {@code TextureView} 字段通常为 {@code null}，
 *       因此必须以可见 {@code PlayerView} 及其屏幕可见面积作为主定位依据；</li>
 *   <li>{@code MediaItem} 含 3 个带 {@code Uri} 的子对象（{@code LocalConfiguration}、
 *       {@code MediaMetadata}、{@code RequestMetadata}），需通过
 *       「唯一 {@code Uri} + {@code List} + {@code long} + 无 {@code Bundle}」特征精准命中
 *       {@code LocalConfiguration}，绝不硬编码混淆字段名。</li>
 * </ul>
 */
public final class PlayerTracker {
    private static final Object LOCK = new Object();
    private static final List<WeakReference<Object>> PLAYERS = new ArrayList<>();
    private static volatile WeakReference<Object> lastPlaying = new WeakReference<>(null);

    private static volatile boolean installed;
    private static volatile boolean ready;
    private static Class<?> playerClass;
    private static Class<?> playerViewClass;
    private static Method playerViewGetPlayer;
    private static Method isPlayingMethod;
    private static Method mediaItemGetter;
    private static Field videoTextureField;
    private static Field localConfigField;
    private static Field localConfigUriField;

    private PlayerTracker() {
    }

    public static void install(ClassLoader classLoader) {
        if (installed) {
            return;
        }
        installed = true;
        try {
            Class<?> impl = findExoPlayerImpl(classLoader);
            if (impl == null) {
                Runtime.log("PlayerTracker: ExoPlayer impl not found, using MediaCapture fallback");
                return;
            }
            if (!resolvePlayerAccess(impl)) {
                Runtime.log("PlayerTracker: MediaItem access unresolved on " + impl.getName());
                return;
            }
            playerClass = impl;

            try {
                playerViewClass = Class.forName("androidx.media3.ui.PlayerView", false, classLoader);
                playerViewGetPlayer = playerViewClass.getMethod("getPlayer");
            } catch (Throwable ignored) {
            }

            int ctors = 0;
            for (Constructor<?> ctor : impl.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                if (Runtime.hook(ctor, chain -> {
                    Object res = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self != null) {
                        trackPlayer(self);
                    }
                    return res;
                }) != null) {
                    ctors++;
                }
            }

            Method isPlaying = findIsPlaying(impl);
            if (isPlaying != null) {
                isPlayingMethod = isPlaying;
                Runtime.hook(isPlaying, chain -> {
                    Object res = chain.proceed();
                    try {
                        if (Boolean.TRUE.equals(res)) {
                            Object self = chain.getThisObject();
                            if (self != null && impl.isInstance(self)) {
                                lastPlaying = new WeakReference<>(self);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return res;
                });
            }

            ready = true;
            Runtime.log("PlayerTracker installed on " + impl.getName()
                    + " getter=" + mediaItemGetter.getName()
                    + " cfg=" + localConfigField.getType().getSimpleName()
                    + " ctors=" + ctors
                    + " playerView=" + (playerViewGetPlayer != null));
        } catch (Throwable throwable) {
            Runtime.error("PlayerTracker install failed", throwable);
        }
    }

    public static boolean isReady() {
        return ready;
    }

    /** 检查当前前台 Activity 是否存在可见的 X 视频播放器（用于控制悬浮下载按钮显隐）。 */
    public static boolean hasVisibleVideo(Activity activity) {
        if (activity == null) {
            return false;
        }
        if (!ready) {
            return MediaCapture.hasRecent(15_000L);
        }
        Map<Object, Long> visible = findVisiblePlayers(activity);
        if (visible.isEmpty()) {
            // 若未能绑定 PlayerView，仍允许短窗口 MediaCapture 兜底；若 PlayerView 可用但屏幕无播放器，则滑离后立刻隐藏
            return playerViewGetPlayer == null && MediaCapture.hasRecent(12_000L);
        }
        // 屏幕上有可见播放器：优先检查其当前 MediaItem 是否为 X 视频
        for (Object player : visible.keySet()) {
            Uri uri = readMediaUri(player);
            if (extractMediaId(uri) != null) {
                MediaCapture.record(uri);
                return true;
            }
        }
        // 若播放器使用了本地代理/缓存 URI，只要近期有抓到 X 视频也显示按钮
        return MediaCapture.hasRecent(30_000L);
    }

    /** 优先返回当前屏幕上正在播放或可见的视频条目；若未取到直接 URI 则回退到最近抓到的视频。 */
    public static MediaCapture.Entry resolveCurrentEntry(Activity activity) {
        if (!ready || activity == null) {
            return MediaCapture.latest();
        }
        try {
            Map<Object, Long> visible = findVisiblePlayers(activity);
            if (visible.isEmpty()) {
                return MediaCapture.latest();
            }
            Object player = pickBestPlayer(visible);
            if (player != null) {
                Uri uri = readMediaUri(player);
                if (uri != null) {
                    MediaCapture.record(uri);
                    String id = extractMediaId(uri);
                    if (id != null) {
                        MediaCapture.Entry mapped = MediaCapture.byId(id);
                        if (mapped != null) {
                            return mapped;
                        }
                    }
                }
            }
            return MediaCapture.latest();
        } catch (Throwable ignored) {
            return MediaCapture.latest();
        }
    }

    private static Map<Object, Long> findVisiblePlayers(Activity activity) {
        Map<Object, Long> visible = new IdentityHashMap<>();
        if (playerViewClass != null && playerViewGetPlayer != null) {
            try {
                View root = activity.getWindow().getDecorView();
                collectVisiblePlayerViews(root, visible);
            } catch (Throwable ignored) {
            }
        }
        if (visible.isEmpty() && videoTextureField != null) {
            Rect rect = new Rect();
            View decor = null;
            try {
                decor = activity.getWindow().getDecorView();
            } catch (Throwable ignored) {
            }
            for (Object player : snapshotPlayers()) {
                long area = visibleAreaByTexture(player, decor, rect);
                if (area > 0L) {
                    visible.put(player, area);
                }
            }
        }
        return visible;
    }

    private static Object pickBestPlayer(Map<Object, Long> visible) {
        List<Object> playingEligible = new ArrayList<>();
        List<Object> eligible = new ArrayList<>();
        List<Object> playingAny = new ArrayList<>();

        for (Object player : visible.keySet()) {
            boolean playing = isPlayerPlaying(player);
            if (playing) {
                playingAny.add(player);
            }
            Uri uri = readMediaUri(player);
            if (extractMediaId(uri) != null) {
                eligible.add(player);
                if (playing) {
                    playingEligible.add(player);
                }
            }
        }

        if (!playingEligible.isEmpty()) {
            Object best = pickLargestArea(playingEligible, visible);
            lastPlaying = new WeakReference<>(best);
            return best;
        }
        Object recent = lastPlaying.get();
        if (recent != null && eligible.contains(recent)) {
            return recent;
        }
        if (!eligible.isEmpty()) {
            return pickLargestArea(eligible, visible);
        }
        if (!playingAny.isEmpty()) {
            return pickLargestArea(playingAny, visible);
        }
        return pickLargestArea(new ArrayList<>(visible.keySet()), visible);
    }

    private static Object pickLargestArea(List<Object> players, Map<Object, Long> areas) {
        Object best = null;
        long bestArea = -1L;
        for (Object p : players) {
            Long a = areas.get(p);
            long area = a == null ? 0L : a;
            if (area > bestArea) {
                bestArea = area;
                best = p;
            }
        }
        return best;
    }

    private static void collectVisiblePlayerViews(View view, Map<Object, Long> out) {
        if (view == null || view.getVisibility() != View.VISIBLE || !view.isShown()) {
            return;
        }
        if (playerViewClass.isInstance(view)) {
            Rect bounds = new Rect();
            if (view.getGlobalVisibleRect(bounds) && bounds.width() > 48 && bounds.height() > 48) {
                try {
                    Object player = playerViewGetPlayer.invoke(view);
                    if (player != null && playerClass.isInstance(player)) {
                        long area = (long) bounds.width() * bounds.height();
                        Long prev = out.get(player);
                        if (prev == null || area > prev) {
                            out.put(player, area);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectVisiblePlayerViews(group.getChildAt(i), out);
            }
        }
    }

    private static long visibleAreaByTexture(Object player, View decor, Rect rect) {
        try {
            View view = (View) videoTextureField.get(player);
            if (view == null || !view.isShown()) {
                return 0L;
            }
            if (decor != null && view.getRootView() != decor) {
                return 0L;
            }
            if (!view.getGlobalVisibleRect(rect) || rect.width() <= 48 || rect.height() <= 48) {
                return 0L;
            }
            return (long) rect.width() * rect.height();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static boolean isPlayerPlaying(Object player) {
        if (isPlayingMethod == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isPlayingMethod.invoke(player));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Uri readMediaUri(Object player) {
        try {
            Object item = mediaItemGetter.invoke(player);
            if (item == null) {
                return null;
            }
            Object config = localConfigField.get(item);
            if (config == null) {
                return null;
            }
            Object uri = localConfigUriField.get(config);
            return uri instanceof Uri ? (Uri) uri : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractMediaId(Uri uri) {
        if (uri == null) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"video.twimg.com".equalsIgnoreCase(uri.getHost())) {
            return null;
        }
        String path = uri.getPath();
        if (path == null) {
            return null;
        }
        Matcher matcher = MediaCapture.MEDIA_ID.matcher(path);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Class<?> findExoPlayerImpl(ClassLoader loader) {
        List<String> names = new ArrayList<>();
        names.add("j0");
        for (char a = 'a'; a <= 'z'; a++) {
            names.add(String.valueOf(a));
        }
        for (char a = 'a'; a <= 'z'; a++) {
            for (char b = 'a'; b <= 'z'; b++) {
                names.add("" + a + b);
            }
            for (char d = '0'; d <= '9'; d++) {
                names.add("" + a + d);
            }
        }
        for (String name : names) {
            try {
                Class<?> c = Class.forName("androidx.media3.exoplayer." + name, false, loader);
                if (findUniqueFieldByType(c, TextureView.class) != null
                        && findIsPlaying(c) != null) {
                    return c;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean resolvePlayerAccess(Class<?> impl) {
        videoTextureField = findUniqueFieldByType(impl, TextureView.class);
        Method foundGetter = null;
        Field foundConfig = null;
        Field foundUri = null;
        for (Method m : impl.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.getParameterCount() != 0) {
                continue;
            }
            Class<?> ret = m.getReturnType();
            if (!ret.getName().startsWith("androidx.media3.common.")) {
                continue;
            }
            Field[] pair = findMediaItemLocalConfigUri(ret);
            if (pair == null) {
                continue;
            }
            if (foundGetter != null) {
                return false; // 存在歧义，安全放弃
            }
            foundGetter = m;
            foundConfig = pair[0];
            foundUri = pair[1];
        }
        if (foundGetter == null) {
            return false;
        }
        foundGetter.setAccessible(true);
        mediaItemGetter = foundGetter;
        localConfigField = foundConfig;
        localConfigUriField = foundUri;
        return true;
    }

    /**
     * 在 MediaItem 类中精准定位 {@code LocalConfiguration} 及其唯一 {@code Uri} 字段：
     * MediaItem 同时含 {@code LocalConfiguration}（有 List + long，无 Bundle）、
     * {@code MediaMetadata}（含 Bundle 与大量 CharSequence，无 List/long）与
     * {@code RequestMetadata}（含 Bundle，无 List/long），按结构特征可唯一区分。
     */
    private static Field[] findMediaItemLocalConfigUri(Class<?> itemClass) {
        boolean hasString = false;
        for (Field f : itemClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                hasString = true;
                break;
            }
        }
        if (!hasString) {
            return null;
        }
        Field cfgMatch = null;
        Field uriMatch = null;
        for (Field f : itemClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Class<?> ft = f.getType();
            if (!ft.getName().startsWith("androidx.media3.common.")) {
                continue;
            }
            Field uri = findLocalConfigUri(ft);
            if (uri != null) {
                if (cfgMatch != null) {
                    return null;
                }
                f.setAccessible(true);
                cfgMatch = f;
                uriMatch = uri;
            }
        }
        return cfgMatch == null ? null : new Field[]{cfgMatch, uriMatch};
    }

    private static Field findLocalConfigUri(Class<?> cfgClass) {
        Field uriField = null;
        boolean hasList = false;
        boolean hasLong = false;
        boolean hasBundle = false;
        for (Field f : cfgClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Class<?> t = f.getType();
            if (t == Uri.class) {
                if (uriField != null) {
                    return null;
                }
                uriField = f;
            } else if (List.class.isAssignableFrom(t)) {
                hasList = true;
            } else if (t == long.class) {
                hasLong = true;
            } else if (t == Bundle.class) {
                hasBundle = true;
            }
        }
        if (uriField != null && hasList && hasLong && !hasBundle) {
            uriField.setAccessible(true);
            return uriField;
        }
        return null;
    }

    private static Field findUniqueFieldByType(Class<?> owner, Class<?> targetType) {
        Field result = null;
        for (Field f : owner.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != targetType) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = f;
        }
        if (result != null) {
            result.setAccessible(true);
        }
        return result;
    }

    private static Method findIsPlaying(Class<?> impl) {
        for (Class<?> cur = impl; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getDeclaredMethod("isPlaying");
                if (m.getReturnType() == boolean.class) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static void trackPlayer(Object player) {
        synchronized (LOCK) {
            Iterator<WeakReference<Object>> it = PLAYERS.iterator();
            while (it.hasNext()) {
                Object existing = it.next().get();
                if (existing == null) {
                    it.remove();
                } else if (existing == player) {
                    return;
                }
            }
            PLAYERS.add(new WeakReference<>(player));
        }
    }

    private static List<Object> snapshotPlayers() {
        List<Object> out = new ArrayList<>();
        synchronized (LOCK) {
            Iterator<WeakReference<Object>> it = PLAYERS.iterator();
            while (it.hasNext()) {
                Object p = it.next().get();
                if (p == null) {
                    it.remove();
                } else {
                    out.add(p);
                }
            }
        }
        return out;
    }
}
