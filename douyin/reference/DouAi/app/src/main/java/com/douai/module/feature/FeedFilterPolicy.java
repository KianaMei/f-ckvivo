package com.douai.module.feature;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Pure policy kept separate from the version-specific feed model adapter. */
public final class FeedFilterPolicy {
    /** First matching rule in the established short-circuit order; no item contents leave this policy. */
    public enum Reason {
        NONE, ADVERTISEMENT, LIVE, IMAGE_POST, RECOMMENDATION_CARD, RECOMMENDATION_MERCHANT,
        KEYWORD, MINIMUM_DURATION, MINIMUM_LIKES, PRODUCT_CARD, HOTSPOT_RANK_CARD, MINI_GAME, LONG_VIDEO,
        NORMAL_VIDEO, EMPTY_DESCRIPTION, DESCRIPTION_REGEX;
        public boolean hides() { return this != NONE; }
    }
    private FeedFilterPolicy() { }

    public static boolean shouldHide(FeedItem item, FeatureSettings settings) {
        boolean legacyRecommendation = item != null && item.recommendationCard;
        return shouldHide(item, settings, legacyRecommendation, legacyRecommendation);
    }

    public static boolean shouldHide(FeedItem item, FeatureSettings settings,
                                     boolean recommendationCard, boolean recommendationMerchant) {
        return reason(item, settings, recommendationCard, recommendationMerchant).hides();
    }

    public static Reason reason(FeedItem item, FeatureSettings settings,
                                boolean recommendationCard, boolean recommendationMerchant) {
        if (item == null || settings == null || !settings.feedFilterEnabled) return Reason.NONE;
        if (settings.hideAds && item.advertisement) return Reason.ADVERTISEMENT;
        if (settings.hideLives && item.live) return Reason.LIVE;
        if (settings.hideImagePosts && item.imagePost) return Reason.IMAGE_POST;
        if (settings.hideRecommendationCards && recommendationCard) return Reason.RECOMMENDATION_CARD;
        if (settings.hideRecommendationMerchants && recommendationMerchant) return Reason.RECOMMENDATION_MERCHANT;
        if (settings.keywordFilterEnabled && containsKeyword(item.searchableText, settings.keyword)) return Reason.KEYWORD;
        if (settings.minimumDurationEnabled && item.durationKnown
                && item.durationMillis < settings.minimumDurationSeconds * 1000L) return Reason.MINIMUM_DURATION;
        return settings.minimumLikesEnabled && item.likeCountKnown
                && item.likeCount < settings.minimumLikeCount ? Reason.MINIMUM_LIKES : Reason.NONE;
    }

    public static boolean shouldHide(FeedItem item, FeatureSettings settings,
                                     boolean recommendationCard, boolean recommendationMerchant,
                                     boolean game, int hostType, LocalOptions options) {
        return reason(item, settings, recommendationCard, recommendationMerchant, game, hostType, options).hides();
    }

    public static Reason reason(FeedItem item, FeatureSettings settings,
                                boolean recommendationCard, boolean recommendationMerchant,
                                boolean game, int hostType, LocalOptions options) {
        return reason(item, settings, recommendationCard, recommendationMerchant, game, hostType, options,
                item == null ? "" : item.searchableText, normalVideo(item, hostType, true));
    }

    /** Description is the original work text, never the author/music search expansion. */
    public static Reason reason(FeedItem item, FeatureSettings settings,
                                boolean recommendationCard, boolean recommendationMerchant,
                                boolean game, int hostType, LocalOptions options,
                                String description, boolean normalVideo) {
        if (item == null || settings == null || !settings.feedFilterEnabled) return Reason.NONE;
        Reason base = reason(item, settings, recommendationCard, recommendationMerchant);
        if (base.hides()) return base;
        if (options == null) return Reason.NONE;
        if (options.enabled("unified_hide_normal_videos") && normalVideo) return Reason.NORMAL_VIDEO;
        if (options.enabled("unified_hide_empty_desc") && (description == null || description.trim().isEmpty()))
            return Reason.EMPTY_DESCRIPTION;
        if (matchesDescriptionRegex(description, options.text("unified_feed_regex", ""))) return Reason.DESCRIPTION_REGEX;
        if (options.enabled("hide_product_cards") && (hostType == 112 || hostType == 9001)) return Reason.PRODUCT_CARD;
        if (options.enabled("hide_hotspot_rank_cards") && hostType == 104) return Reason.HOTSPOT_RANK_CARD;
        if (options.enabled("hide_mini_games") && game) return Reason.MINI_GAME;
        if (options.enabled("hide_long_videos")) {
            int threshold = Math.max(1, options.integer("maximum_duration_seconds", 120));
            return hostType == 127 || hostType == 128
                    || item.durationKnown && item.durationMillis > threshold * 1000L ? Reason.LONG_VIDEO : Reason.NONE;
        }
        return Reason.NONE;
    }

    static boolean normalVideo(FeedItem item, int type, boolean hasVideoModel) {
        return item != null && hasVideoModel && !item.advertisement && !item.live && !item.imagePost
                && !item.recommendationCard && type != 2 && type != 68 && type != 101 && type != 163
                && type != 140 && type != 145 && type != 112 && type != 9001 && type != 104;
    }

    private static final int MAX_REGEX_LENGTH = 256;
    private static final int MAX_REGEX_TEXT = 2048;
    private static volatile RegexCache regexCache = new RegexCache("", null, null);
    private static final class RegexCache {
        final String source, error;
        final Pattern pattern;
        RegexCache(String source, Pattern pattern, String error) { this.source = source; this.pattern = pattern; this.error = error; }
    }

    /** A deliberately bounded regex subset: no groups/alternation/backrefs and at most one quantifier. */
    public static String regexError(String expression) { return regex(expression).error; }

    static boolean matchesDescriptionRegex(String text, String expression) {
        if (text == null || text.length() > MAX_REGEX_TEXT) return false;
        Pattern pattern = regex(expression).pattern;
        return pattern != null && pattern.matcher(text).find();
    }

    private static RegexCache regex(String expression) {
        String source = expression == null ? "" : expression;
        RegexCache cached = regexCache;
        if (source.equals(cached.source)) return cached;
        String error = regexSubsetError(source); Pattern compiled = null;
        if (error == null && !source.isEmpty()) {
            try { compiled = Pattern.compile(source); }
            catch (PatternSyntaxException invalid) { error = "正则表达式语法无效"; }
        }
        RegexCache next = new RegexCache(source, compiled, error); regexCache = next; return next;
    }

    private static String regexSubsetError(String source) {
        if (source.length() > MAX_REGEX_LENGTH) return "正则表达式最多 256 个字符";
        boolean inClass = false; int quantifiers = 0;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (value == '\\') {
                if (++i >= source.length()) return "正则转义不完整";
                char escape = source.charAt(i);
                if (Character.isDigit(escape) || escape == 'k' || escape == 'Q' || escape == 'E')
                    return "不支持回溯引用或引用分组";
                continue;
            }
            if (value == '[') { inClass = true; continue; }
            if (value == ']' && inClass) { inClass = false; continue; }
            if (inClass) continue;
            if (value == '(' || value == ')' || value == '|') return "不支持分组、前后查找或分支";
            if (value == '*' || value == '+' || value == '?') quantifiers++;
            if (value == '{') {
                int end = source.indexOf('}', i + 1);
                if (end < 0) return "正则重复范围不完整";
                String range = source.substring(i + 1, end);
                if (!range.matches("[0-9]{1,2}(,[0-9]{0,2})?")) return "重复范围须为 0～64";
                for (String part : range.split(",", -1))
                    if (!part.isEmpty() && Integer.parseInt(part) > 64) return "重复范围须为 0～64";
                quantifiers++; i = end;
            }
            if (quantifiers > 1) return "为避免回溯卡顿，最多允许一个重复量词";
        }
        return null;
    }

    private static boolean containsKeyword(String text, String configured) {
        if (configured == null || configured.trim().isEmpty()) return false;
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String term : configured.split("[,，\\n]+")) {
            String needle = term.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && haystack.contains(needle)) return true;
        }
        return false;
    }
}
