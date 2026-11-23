package dev.xhook.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.xhook.Config;
import dev.xhook.Runtime;

/** 去推广的 entryId 规则 + 机器人/垃圾帖关键词规则。 */
public final class Rules {
    /** 12.28/12.37 实测的 promoted 前缀（TwitterHideAds 文档）。 */
    private static final String[] PROMOTED_PREFIXES = {
            "promoted-tweet-",
            "promotedTweet-",
            "superhero-",
            "rtb-image-ad-",
    };

    private static final Set<String> PREMIUM_UPSELLS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "messageprompt-ads-sharing-x-premium-upsell-candidate",
                    "messageprompt-generic-non-premium-inline-prompt",
                    "messageprompt-premium-announcement-inline-prompt",
                    "messageprompt-premium-grok2-upsell-prompt",
                    "messageprompt-premium-plus-upsell-prompt"
            )));

    private static volatile String cachedRaw;
    private static volatile List<String> cachedKeywords = Collections.emptyList();

    private Rules() {
    }

    public static boolean isPromoted(String entryId) {
        if (entryId == null || entryId.isEmpty()) {
            return false;
        }
        for (String prefix : PROMOTED_PREFIXES) {
            if (entryId.startsWith(prefix)) {
                return true;
            }
        }
        if (entryId.contains("-promoted-tweet-")) {
            return true;
        }
        return PREMIUM_UPSELLS.contains(entryId);
    }

    public static boolean isConversation(String entryId) {
        return entryId != null && entryId.startsWith("conversationthread-");
    }

    /** 零宽字符剥离 + 小写 + 空白折叠：对抗“加符号绕过关键词”。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        boolean lastSpace = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '\u200B' || ch == '\u200C' || ch == '\u200D'
                    || ch == '\u2060' || ch == '\uFEFF') {
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (!lastSpace && builder.length() > 0) {
                    builder.append(' ');
                }
                lastSpace = true;
                continue;
            }
            lastSpace = false;
            builder.append(Character.toLowerCase(ch));
        }
        return builder.toString().trim();
    }

    public static boolean matches(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String keyword : keywords()) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> keywords() {
        String raw = Runtime.string(Config.KEY_KEYWORDS, "");
        if (raw.equals(cachedRaw)) {
            return cachedKeywords;
        }
        List<String> parsed = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                parsed.add(normalized);
            }
        }
        cachedKeywords = Collections.unmodifiableList(parsed);
        cachedRaw = raw;
        Runtime.log("keyword rules reloaded: " + parsed.size());
        return cachedKeywords;
    }

    public static String summary() {
        return "promoted=" + PROMOTED_PREFIXES.length
                + ", keywords=" + keywords().size()
                + ", locale=" + Locale.getDefault();
    }
}
