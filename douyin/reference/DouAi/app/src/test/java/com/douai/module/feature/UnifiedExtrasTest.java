package com.douai.module.feature;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UnifiedExtrasTest {
    @Test public void gameSelectionOnlyMatchesNamedResources() {
        assertTrue(UnifiedGameChoiceHook.matchesOutcome("https://cdn.example.com/game/骰子6.png?cache=1", "骰子6"));
        assertTrue(UnifiedGameChoiceHook.matchesOutcome("https://cdn.example.com/game/%E7%9F%B3%E5%A4%B4.webp", "石头"));
        assertFalse(UnifiedGameChoiceHook.matchesOutcome("https://cdn.example.com/game/骰子6.png", "骰子1"));
        assertFalse(UnifiedGameChoiceHook.matchesOutcome("https://cdn.example.com/random.webp", ""));
    }
    @Test public void exitClockAcceptsOnlyBoundedMinutesAndExactThreshold() {
        assertEquals(0, UnifiedSessionTools.minutes("2")); assertEquals(0, UnifiedSessionTools.minutes("1441"));
        assertEquals(3, UnifiedSessionTools.minutes("3")); assertEquals(1440, UnifiedSessionTools.minutes("1440"));
        assertFalse(UnifiedSessionTools.elapsed(179_999, 0, 3));
        assertTrue(UnifiedSessionTools.elapsed(180_000, 0, 3)); assertFalse(UnifiedSessionTools.elapsed(999_999, 0, 0));
    }
    @Test public void chapterSkipOnlyUsesBoundedHostLabeledIndices() {
        assertEquals(java.util.Arrays.asList(0, 2), UnifiedChapterSkipHook.validIndices(
                java.util.Arrays.asList(-1, 0, 2, 2, 3, 1.5, "1"), 3));
        assertTrue(UnifiedChapterSkipHook.validIndices(java.util.Arrays.asList(0, 1), 0).isEmpty());
    }
}
