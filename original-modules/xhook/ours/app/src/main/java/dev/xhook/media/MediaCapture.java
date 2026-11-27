package dev.xhook.media;

import android.net.Uri;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.xhook.Runtime;

/**
 * 从 X 的播放器数据源构造器里抓视频地址。
 *
 * <p>X 12.4x 的媒体播放走 androidx.media3。构造器签名是
 * {@code (Uri, long, int, byte[], Map, long, long, String, int, ...)}，与 X 自身
 * 混淆无关。只接受 video.twimg.com，不碰其它域名。</p>
 */
public final class MediaCapture {
    static final Pattern MEDIA_ID =
            Pattern.compile("/(?:ext_tw_video|amplify_video|tweet_video)/([A-Za-z0-9_-]+)");
    private static final Pattern HLS_SEGMENT_LIKE =
            Pattern.compile(".*/(?:vid|aud)/[^/]+/0/0/.*");

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Entry> ENTRIES =
            new LinkedHashMap<String, Entry>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, MediaCapture.Entry> eldest) {
                    return size() > 40;
                }
            };

    private static volatile boolean installed;
    private static volatile String lastLoggedId = "";
    private static volatile long lastLoggedAt;

    private MediaCapture() {
    }

    public static final class Entry {
        public final String id;
        public final String url;
        public final boolean master;
        public final boolean gif;
        public final long at;

        Entry(String id, String url, boolean master, boolean gif, long at) {
            this.id = id;
            this.url = url;
            this.master = master;
            this.gif = gif;
            this.at = at;
        }
    }

    public static void install(ClassLoader classLoader) {
        if (installed) {
            return;
        }
        installed = true;

        List<String> names = new ArrayList<>();
        names.add("n");
        for (char a = 'a'; a <= 'z'; a++) {
            names.add(String.valueOf(a));
        }
        for (char a = 'a'; a <= 'z'; a++) {
            for (char b = 'a'; b <= 'z'; b++) {
                names.add("" + a + b);
            }
        }

        int hooked = 0;
        for (String name : names) {
            Class<?> type;
            try {
                type = Class.forName("androidx.media3.datasource." + name, false, classLoader);
            } catch (Throwable ignored) {
                continue;
            }
            if (type == null) {
                continue;
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (!looksLikeDataSource(constructor)) {
                    continue;
                }
                try {
                    constructor.setAccessible(true);
                    Runtime.hook(constructor, chain -> {
                        Object result = chain.proceed();
                        try {
                            Object argument = chain.getArg(0);
                            if (argument instanceof Uri) {
                                record((Uri) argument);
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });
                    hooked++;
                } catch (Throwable throwable) {
                    Runtime.error("media ctor hook failed: " + constructor, throwable);
                }
            }
            if (hooked > 0) {
                break;
            }
        }
        Runtime.log("MediaCapture installed, hooked=" + hooked);
    }

    private static boolean looksLikeDataSource(Constructor<?> constructor) {
        Class<?>[] types = constructor.getParameterTypes();
        if (types.length < 9 || types.length > 11) {
            return false;
        }
        return types[0] == Uri.class
                && types[1] == long.class
                && types[2] == int.class
                && types[3] == byte[].class
                && Map.class.isAssignableFrom(types[4])
                && types[5] == long.class
                && types[6] == long.class
                && types[7] == String.class
                && types[8] == int.class;
    }

    public static void record(Uri uri) {
        if (uri == null) {
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"video.twimg.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return;
        }
        String path = uri.getPath();
        if (path == null) {
            return;
        }
        Matcher matcher = MEDIA_ID.matcher(path);
        if (!matcher.find()) {
            return;
        }
        String id = matcher.group(1);
        String lower = path.toLowerCase(Locale.ROOT);
        boolean gif = path.contains("/tweet_video/");
        boolean master = lower.endsWith(".m3u8") && path.contains("/pl/");
        boolean direct = lower.endsWith(".mp4")
                && !HLS_SEGMENT_LIKE.matcher(path).matches();
        boolean segment = lower.endsWith(".m4s") || lower.endsWith(".ts");

        if (!master && !direct) {
            if (segment) {
                synchronized (LOCK) {
                    Entry previous = ENTRIES.get(id);
                    if (previous != null) {
                        ENTRIES.put(id, new Entry(previous.id, previous.url,
                                previous.master, previous.gif, System.currentTimeMillis()));
                    }
                }
            }
            return;
        }

        synchronized (LOCK) {
            Entry previous = ENTRIES.get(id);
            boolean replace = previous == null
                    || (master && !previous.master)
                    || (master == previous.master);
            if (replace) {
                ENTRIES.put(id, new Entry(id, uri.toString(), master, gif, System.currentTimeMillis()));
            } else {
                ENTRIES.put(id, new Entry(id, previous.url, previous.master, previous.gif,
                        System.currentTimeMillis()));
            }
        }
        long now = System.currentTimeMillis();
        if (!id.equals(lastLoggedId) || now - lastLoggedAt > 30_000L) {
            lastLoggedId = id;
            lastLoggedAt = now;
            Runtime.log("media captured id=" + shortId(id) + " master=" + master + " gif=" + gif);
        }
    }

    /** 按 mediaId 精确查找（供 PlayerTracker 将当前可见播放器映射到其 master 播放列表）。 */
    public static Entry byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            return ENTRIES.get(id);
        }
    }

    /** 最近仍在播放/刚播放过的视频；超过 2 分钟视为旧数据。 */
    public static Entry latest() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            Entry best = null;
            for (Iterator<Entry> iterator = ENTRIES.values().iterator(); iterator.hasNext(); ) {
                Entry entry = iterator.next();
                if (now - entry.at > 120_000L) {
                    continue;
                }
                if (entry.master) {
                    best = entry;
                } else if (best == null) {
                    best = entry;
                }
            }
            return best;
        }
    }

    public static boolean hasRecent(long windowMs) {
        Entry entry = latest();
        return entry != null && System.currentTimeMillis() - entry.at < windowMs;
    }

    private static String shortId(String id) {
        return id.length() <= 6 ? id : id.substring(id.length() - 6);
    }
}
