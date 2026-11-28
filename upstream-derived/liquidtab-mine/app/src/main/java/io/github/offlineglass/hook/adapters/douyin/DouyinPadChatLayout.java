package io.github.offlineglass.hook.adapters.douyin;

/**
 * Pure geometry solver for Douyin's foldable/tablet two-pane chat page
 * ({@code MessageFragmentPad}). Kept Android-free so every edge case runs in a
 * standalone JVM unit test.
 */
public final class DouyinPadChatLayout {
    private DouyinPadChatLayout() {}

    public static final class Adjustment {
        public final int height;
        public final int bottomMargin;
        public final boolean useMarginGuard;

        public Adjustment(int height, int bottomMargin, boolean useMarginGuard) {
            this.height = height;
            this.bottomMargin = bottomMargin;
            this.useMarginGuard = useMarginGuard;
        }
    }

    /**
     * Recognizes the right-hand chat pane inside {@code MessageFragmentPad}'s
     * horizontal root layout (e.g. {@code 997,0-2312,2363} in a {@code 2312x2504}
     * inner screen) while rejecting the left conversation list, 1px divider,
     * collapsed single-pane outer-screen views and small overlays.
     */
    public static boolean isSplitRightPane(
            int rootWidth,
            int rootHeight,
            int paneLeft,
            int paneWidth,
            int paneHeight) {
        if (rootWidth <= 0 || rootHeight <= 0 || paneWidth <= 0 || paneHeight <= 0) {
            return false;
        }
        return paneLeft >= rootWidth / 4
                && paneWidth >= rootWidth / 3
                && paneHeight > rootHeight / 2;
    }

    /**
     * Computes how many pixels from the bottom of {@code MessageFragmentPad}'s root
     * the right chat pane must stay clear of so its input bar sits cleanly above the
     * floating glass bar. Returns {@code 0} while the soft keyboard (IME) is open so
     * Douyin's native {@code adjustResize} layout owns the bottom edge without an
     * extra blank gap.
     */
    public static int computeClearance(
            int parentHeight,
            int parentBottomInScreen,
            int barTopInScreen,
            int fallbackBarHeightPx,
            float density,
            boolean barMeasured,
            int imeBottomPx) {
        if (parentHeight <= 0 || imeBottomPx > 0) {
            return 0;
        }
        int clearance;
        if (barMeasured && barTopInScreen > 0 && barTopInScreen < parentBottomInScreen) {
            clearance = parentBottomInScreen - barTopInScreen;
        } else if (fallbackBarHeightPx > 0) {
            clearance = fallbackBarHeightPx + Math.round(21f * density);
        } else {
            return 0;
        }
        int maxClearance = parentHeight / 3;
        if (clearance < 0) return 0;
        return Math.min(clearance, maxClearance);
    }

    /**
     * Solves the right pane's target {@code LayoutParams.height} and
     * {@code bottomMargin}, supporting both {@code MATCH_PARENT + bottomMargin}
     * and explicit positive pixel heights without ever shrinking native clearance.
     */
    public static Adjustment solveRightPaneLayout(
            int parentHeight,
            int originalHeight,
            int originalBottomMargin,
            int requiredClearance) {
        if (requiredClearance <= 0 || parentHeight <= 0) {
            return new Adjustment(originalHeight, originalBottomMargin, false);
        }
        if (originalHeight > 0) {
            int maxAllowedHeight = Math.max(1, parentHeight - requiredClearance);
            int targetHeight = Math.min(originalHeight, maxAllowedHeight);
            return new Adjustment(targetHeight, originalBottomMargin, false);
        }
        int targetMargin = Math.max(originalBottomMargin, requiredClearance);
        return new Adjustment(originalHeight, targetMargin, targetMargin > 0);
    }
}
