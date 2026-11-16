package com.douai.module.feature;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UnifiedPlayback412PolicyTest {
    @Test public void completionAdvancesOnceForTheCurrentWorkAndNeverWrapsTheLastItem() {
        UnifiedPlayback412Policy policy = new UnifiedPlayback412Policy();
        policy.selected("a");
        assertEquals(3, policy.completion("a", true, true, false, false, 2, 5));
        assertEquals(-1, policy.completion("a", true, true, false, false, 2, 5));
        policy.selected("b");
        assertEquals(-1, policy.completion("a", true, true, false, false, 2, 5));
        assertEquals(-1, policy.completion("b", true, true, false, false, 4, 5));
        assertEquals(4, policy.completion("b", true, true, false, false, 3, 5));
    }
    @Test public void backgroundTouchesCommentsAndDisabledStateDoNotConsumeCompletion() {
        UnifiedPlayback412Policy policy = new UnifiedPlayback412Policy(); policy.selected("a");
        assertEquals(-1, policy.completion("a", false, true, false, false, 0, 3));
        assertEquals(-1, policy.completion("a", true, false, false, false, 0, 3));
        assertEquals(-1, policy.completion("a", true, true, true, false, 0, 3));
        assertEquals(-1, policy.completion("a", true, true, false, true, 0, 3));
        assertEquals(1, policy.completion("a", true, true, false, false, 0, 3));
    }
    @Test public void reverseDirectionPreservesTheFirstItemAndClearCancelsStaleWork() {
        UnifiedPlayback412Policy policy = new UnifiedPlayback412Policy(); policy.selected("a"); policy.direction(-1);
        assertEquals(-1, policy.completion("a", true, true, false, false, 0, 4));
        assertEquals(1, policy.completion("a", true, true, false, false, 2, 4));
        policy.clear(); assertEquals(-1, policy.completion("a", true, true, false, false, 2, 4));
    }
}
