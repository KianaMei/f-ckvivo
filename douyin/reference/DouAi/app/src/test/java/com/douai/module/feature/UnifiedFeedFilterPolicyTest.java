package com.douai.module.feature;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UnifiedFeedFilterPolicyTest {
    private static final FeatureSettings ENABLED = new FeatureSettings(false, false, true,
            false, false, false, false, false, "", false, 0, false, 0, false, false, false, false, false);
    private static FeedItem item(boolean ad, boolean live, boolean image, String text) {
        return new FeedItem(ad, live, image, false, text, -1, -1);
    }
    private static FeedFilterPolicy.Reason reason(FeedItem item, String desc, boolean ordinary, Map<String, ?> options) {
        return FeedFilterPolicy.reason(item, ENABLED, false, false, false, 0, new LocalOptions(options), desc, ordinary);
    }
    @Test public void ordinaryVideoClassificationDoesNotDependOnWhichCategoryHideSwitchIsEnabled() {
        FeedItem normal = item(false, false, false, "caption");
        assertTrue(FeedFilterPolicy.normalVideo(normal, 0, true));
        assertFalse(FeedFilterPolicy.normalVideo(normal, 0, false));
        assertFalse(FeedFilterPolicy.normalVideo(item(false, true, false, ""), 101, true));
        assertFalse(FeedFilterPolicy.normalVideo(item(false, false, true, ""), 68, true));
        assertFalse(FeedFilterPolicy.normalVideo(item(true, false, false, ""), 0, true));
        assertFalse(FeedFilterPolicy.normalVideo(normal, 140, true));
        assertEquals(FeedFilterPolicy.Reason.NORMAL_VIDEO, reason(normal, "caption", true,
                Map.of("unified_hide_normal_videos", true)));
        assertEquals(FeedFilterPolicy.Reason.NONE, FeedFilterPolicy.reason(normal, FeatureSettings.defaults(),
                false, false, false, 0, new LocalOptions(Map.of("unified_hide_normal_videos", true)), "caption", true));
    }
    @Test public void emptyDescriptionUsesTheRawWorkDescriptionNotTitleAuthorOrMusic() {
        FeedItem expanded = item(false, false, false, "author\nmusic\nnon-empty title");
        assertEquals(FeedFilterPolicy.Reason.EMPTY_DESCRIPTION, reason(expanded, " \n\t", true,
                Map.of("unified_hide_empty_desc", true)));
        assertEquals(FeedFilterPolicy.Reason.NONE, reason(expanded, "actual caption", true,
                Map.of("unified_hide_empty_desc", true)));
    }
    @Test public void regexIsOptInAndOnlyMatchesTheDescription() {
        FeedItem expanded = item(false, false, false, "推广123 author");
        assertNull(FeedFilterPolicy.regexError("^推广[0-9]+$"));
        assertEquals(FeedFilterPolicy.Reason.DESCRIPTION_REGEX, reason(expanded, "推广123", true,
                Map.of("unified_feed_regex", "^推广[0-9]+$")));
        assertEquals(FeedFilterPolicy.Reason.NONE, reason(expanded, "ordinary caption", true,
                Map.of("unified_feed_regex", "^推广[0-9]+$")));
        assertEquals(FeedFilterPolicy.Reason.NONE, reason(expanded, "推广123", true, Map.of()));
    }
    @Test public void dangerousInvalidAndOversizedPatternsNeverReachTheMatcher() {
        for (String pattern : new String[]{"(a+)+$", "a*a*", "a?a?", "(a|aa)+", "\\1", "a{99}", "[", "x".repeat(257)}) {
            assertNotNull(pattern, FeedFilterPolicy.regexError(pattern));
            assertFalse(FeedFilterPolicy.matchesDescriptionRegex("a".repeat(2048) + "!", pattern));
        }
        assertFalse(FeedFilterPolicy.matchesDescriptionRegex("a".repeat(2049), "a+"));
        assertTrue(FeedFilterPolicy.matchesDescriptionRegex("topic [a]", "\\[a\\]"));
        assertTrue(FeedFilterPolicy.matchesDescriptionRegex("foo something bar", "foo.*bar"));
    }
}
