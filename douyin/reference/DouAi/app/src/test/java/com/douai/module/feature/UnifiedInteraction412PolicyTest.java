package com.douai.module.feature;

import org.junit.Test;
import java.util.TimeZone;
import static org.junit.Assert.*;

public final class UnifiedInteraction412PolicyTest {
    @Test public void followConfirmationIsManualUnfollowedKnownTargetOnly() {
        assertTrue(UnifiedInteraction412Hook.shouldConfirm(true, false, true, 0, "uid"));
        assertFalse(UnifiedInteraction412Hook.shouldConfirm(false, false, true, 0, "uid"));
        assertFalse(UnifiedInteraction412Hook.shouldConfirm(true, true, true, 0, "uid"));
        assertFalse(UnifiedInteraction412Hook.shouldConfirm(true, false, false, 0, "uid"));
        for (int status : new int[]{-1, 1, 2, 3, 4}) assertFalse(UnifiedInteraction412Hook.shouldConfirm(true, false, true, status, "uid"));
        assertFalse(UnifiedInteraction412Hook.shouldConfirm(true, false, true, 0, ""));
    }
    @Test public void nestedAutomaticScopeIsAlwaysRestoredEvenWhenNativeClickThrows() {
        assertFalse(UnifiedInteraction412Hook.automaticFollowActive());
        assertThrows(IllegalStateException.class, () -> UnifiedInteraction412Hook.automaticFollow(() -> {
            assertTrue(UnifiedInteraction412Hook.automaticFollowActive());
            assertTrue(UnifiedInteraction412Hook.automaticFollow(() -> UnifiedInteraction412Hook.automaticFollowActive()));
            throw new IllegalStateException("native failure");
        }));
        assertFalse(UnifiedInteraction412Hook.automaticFollowActive());
    }
    @Test public void hiddenEmojiRequiresTheVisibilityFlagAndAnAlreadyProvidedAsset() {
        assertTrue(UnifiedInteraction412Hook.shouldExposeEmoji(true, true, true));
        assertFalse(UnifiedInteraction412Hook.shouldExposeEmoji(false, true, true));
        assertFalse(UnifiedInteraction412Hook.shouldExposeEmoji(true, true, false));
        assertFalse(UnifiedInteraction412Hook.shouldExposeEmoji(true, null, true));
        assertFalse(UnifiedInteraction412Hook.shouldExposeEmoji(true, false, true));
    }
    @Test public void secondsAndMillisecondsDescribeTheSameInstantWithoutOverwritingTheModel() {
        assertEquals(1700000000000L, UnifiedInteraction412Hook.epochMillis(1700000000L));
        assertEquals(1700000000123L, UnifiedInteraction412Hook.epochMillis(1700000000123L));
        assertEquals("2023-11-14 22:13:20.123 Z", UnifiedInteraction412Hook.fullTimestamp(1700000000123L, TimeZone.getTimeZone("UTC")));
        assertEquals("2023-11-15 06:13:20.123 +08:00", UnifiedInteraction412Hook.fullTimestamp(1700000000123L, TimeZone.getTimeZone("Asia/Hong_Kong")));
        assertNull(UnifiedInteraction412Hook.fullTimestamp(0, TimeZone.getTimeZone("UTC")));
        assertEquals(-1, UnifiedInteraction412Hook.epochMillis(Long.MAX_VALUE));
    }
}
