package com.douai.module.feature;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UnifiedPortableUi412PolicyTest {
    @Test public void sidebarRequiresADeliberateRightwardEdgeSwipeRatherThanVerticalScrolling() {
        assertTrue(UnifiedPortableUi412Hook.eligibleSwipe(100, 12, 1));
        assertFalse(UnifiedPortableUi412Hook.eligibleSwipe(40, 0, 1));
        assertFalse(UnifiedPortableUi412Hook.eligibleSwipe(-100, 0, 1));
        assertFalse(UnifiedPortableUi412Hook.eligibleSwipe(100, 70, 1));
        assertFalse(UnifiedPortableUi412Hook.eligibleSwipe(100, 0, 2));
        assertTrue(UnifiedPortableUi412Hook.eligibleSwipe(200, 10, 2));
    }
}
