import io.github.offlineglass.hook.adapters.douyin.DouyinPadChatLayout;

/** Standalone JVM tests for Douyin foldable two-pane chat bottom-bar lift. */
public final class DouyinPadChatLayoutTest {
    public static void main(String[] args) {
        testSplitRightPaneClassification();
        testLiveInnerScreenMarginLift();
        testExplicitHeightLift();
        testImeOpenRestoresNativeLayout();
        testFallbackAndCap();
        System.out.println("DouyinPadChatLayoutTest: all checks passed");
    }

    private static void testSplitRightPaneClassification() {
        // Real Douyin 41.2.0 inner screen (2312x2504) geometry:
        // left list: 0..996, divider: 996..997, right chat pane: 997..2312 (w=1315, h=2363)
        assertTrue(!DouyinPadChatLayout.isSplitRightPane(2312, 2504, 0, 996, 2504), "left list rejected");
        assertTrue(!DouyinPadChatLayout.isSplitRightPane(2312, 2504, 996, 1, 2504), "1px divider rejected");
        assertTrue(DouyinPadChatLayout.isSplitRightPane(2312, 2504, 997, 1315, 2363), "right pane matched");
        // Folded outer screen single-pane (1120x2528): full-width view starts at x=0
        assertTrue(!DouyinPadChatLayout.isSplitRightPane(1120, 2528, 0, 1120, 2387), "single pane rejected");
        // Small floating widget rejected
        assertTrue(!DouyinPadChatLayout.isSplitRightPane(2312, 2504, 1500, 400, 300), "small overlay rejected");
    }

    private static void testLiveInnerScreenMarginLift() {
        // GlassHostLayout top=2285 in 2504px window -> 219px clearance vs native 141px margin.
        int clearance = DouyinPadChatLayout.computeClearance(
                2504, 2504, 2285, 155, 2.8875f, true, 0);
        assertEquals(219, clearance, "exact clearance from bar top");
        DouyinPadChatLayout.Adjustment adj = DouyinPadChatLayout.solveRightPaneLayout(
                2504, -1, 141, clearance);
        assertEquals(-1, adj.height, "MATCH_PARENT preserved");
        assertEquals(219, adj.bottomMargin, "margin raised from 141 to 219");
        assertTrue(adj.useMarginGuard, "margin guard enabled");

        // If native margin is already larger than bar clearance, never shrink it.
        DouyinPadChatLayout.Adjustment largerNative = DouyinPadChatLayout.solveRightPaneLayout(
                2504, -1, 260, clearance);
        assertEquals(260, largerNative.bottomMargin, "native larger margin preserved");
    }

    private static void testExplicitHeightLift() {
        // Explicit height variant: originalHeight=2363, originalBottomMargin=0 -> shrinks to 2285.
        DouyinPadChatLayout.Adjustment adj = DouyinPadChatLayout.solveRightPaneLayout(
                2504, 2363, 0, 219);
        assertEquals(2285, adj.height, "explicit height reduced to bar top");
        assertEquals(0, adj.bottomMargin, "bottomMargin left untouched");
        assertTrue(!adj.useMarginGuard, "margin guard not used for explicit height");
    }

    private static void testImeOpenRestoresNativeLayout() {
        int clearance = DouyinPadChatLayout.computeClearance(
                2504, 2504, 2285, 155, 2.8875f, true, 880);
        assertEquals(0, clearance, "IME open releases extra clearance");
        DouyinPadChatLayout.Adjustment adj = DouyinPadChatLayout.solveRightPaneLayout(
                2504, -1, 141, clearance);
        assertEquals(-1, adj.height, "original height restored");
        assertEquals(141, adj.bottomMargin, "original margin restored");
        assertTrue(!adj.useMarginGuard, "margin guard released");
    }

    private static void testFallbackAndCap() {
        int fallback = DouyinPadChatLayout.computeClearance(
                2504, 2504, 0, 155, 3.0f, false, 0);
        assertEquals(218, fallback, "unmeasured bar falls back to barHeight + 21dp");
        int capped = DouyinPadChatLayout.computeClearance(
                2504, 2504, 500, 155, 3.0f, true, 0);
        assertEquals(2504 / 3, capped, "clearance capped at one third of parent height");
    }

    private static void assertTrue(boolean cond, String label) {
        if (!cond) throw new AssertionError("Expected true: " + label);
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }
}
