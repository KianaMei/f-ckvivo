package dev.xhook.media;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 HLS 主播放列表并按画质选档；不重编码。 */
public final class Quality {
    public enum Level { ORIGINAL, HD, SD }

    public static final class Selection {
        public final String videoUri;
        public final String audioUri;
        public final int width;
        public final int height;
        public final boolean aboveLimit;

        Selection(String videoUri, String audioUri, int width, int height, boolean aboveLimit) {
            this.videoUri = videoUri;
            this.audioUri = audioUri;
            this.width = width;
            this.height = height;
            this.aboveLimit = aboveLimit;
        }
    }

    private static final Pattern ATTRS =
            Pattern.compile("(?:^|,)([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");

    private Quality() {
    }

    private static Map<String, String> attributes(String line) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = ATTRS.matcher(line);
        while (matcher.find()) {
            result.put(matcher.group(1),
                    matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        }
        return result;
    }

    private static final class Variant {
        String uri;
        String audioGroup;
        int width;
        int height;
        long bandwidth;

        long area() {
            return (long) width * height;
        }

        int shortSide() {
            return Math.min(width, height);
        }
    }

    public static Selection select(String masterPlaylist, Level level) throws IOException {
        Map<String, String> audioGroups = new HashMap<>();
        List<Variant> variants = new ArrayList<>();
        Map<String, String> pending = null;

        for (String raw : masterPlaylist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> attributes = attributes(line.substring(13));
                if ("AUDIO".equals(attributes.get("TYPE"))
                        && attributes.containsKey("GROUP-ID")
                        && attributes.containsKey("URI")) {
                    audioGroups.put(attributes.get("GROUP-ID"), attributes.get("URI"));
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = attributes(line.substring(18));
            } else if (!line.startsWith("#") && pending != null) {
                Variant variant = new Variant();
                variant.uri = line;
                variant.audioGroup = pending.get("AUDIO");
                try {
                    String[] size = pending.getOrDefault("RESOLUTION", "").split("x");
                    variant.width = Integer.parseInt(size[0]);
                    variant.height = Integer.parseInt(size[1]);
                    if (variant.width <= 0 || variant.height <= 0) {
                        variant.width = 0;
                        variant.height = 0;
                    }
                } catch (Throwable ignored) {
                    variant.width = 0;
                    variant.height = 0;
                }
                try {
                    variant.bandwidth = Math.max(0L,
                            Long.parseLong(pending.getOrDefault("BANDWIDTH", "0")));
                } catch (Throwable ignored) {
                    variant.bandwidth = 0L;
                }
                variants.add(variant);
                pending = null;
            }
        }

        if (variants.isEmpty()) {
            throw new IOException("master playlist has no variants");
        }

        Comparator<Variant> rank = Comparator
                .comparingLong(Variant::area)
                .thenComparingLong(variant -> variant.bandwidth);

        Variant chosen = null;
        boolean aboveLimit = false;
        if (level == Level.ORIGINAL) {
            chosen = Collections.max(variants, rank);
        } else {
            int cap = level == Level.HD ? 720 : 480;
            for (Variant variant : variants) {
                if (variant.area() > 0 && variant.shortSide() <= cap
                        && (chosen == null || rank.compare(variant, chosen) > 0)) {
                    chosen = variant;
                }
            }
            if (chosen == null) {
                chosen = Collections.min(variants, rank);
                aboveLimit = true;
            }
        }

        String audioUri = chosen.audioGroup == null ? null : audioGroups.get(chosen.audioGroup);
        return new Selection(chosen.uri, audioUri, chosen.width, chosen.height, aboveLimit);
    }

    public static boolean isMasterPlaylist(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return lower.contains(".m3u8");
    }

    /** 单码率媒体播放列表（没有 EXT-X-STREAM-INF），直接就是分片列表。 */
    public static boolean isMediaPlaylist(String text) {
        return text != null
                && text.contains("#EXTINF")
                && !text.contains("#EXT-X-STREAM-INF");
    }
}
