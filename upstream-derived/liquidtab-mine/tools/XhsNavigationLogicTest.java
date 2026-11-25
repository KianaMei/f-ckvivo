package io.github.offlineglass.hook.adapters.xhs;

public final class XhsNavigationLogicTest {
    private static final int ACCENT_RED = 0xFFFF2442;
    private static final int NORMAL_BLACK = 0xFF282828;

    public static void main(String[] args) {
        // 1. Return settle frame: selectionArtworkDrawn=false must drop optical mix to 0
        //    and draw an opaque accented label inside the selection path instead of normal black.
        float settleMix = XhsNavigationLogic.effectiveOpticalMix(false, false, 1f);
        expect(settleMix == 0f, "Paused return frame must fall back to direct inside-accent draw");
        expect(XhsNavigationLogic.shouldDrawInsideAccent(false, settleMix), "Inside accent overlay required on settle frame");
        expect(XhsNavigationLogic.insideAccentAlpha(settleMix) == 255, "Inside accent overlay must be fully opaque");
        expect(XhsNavigationLogic.labelColor(true, false, 0, 0, ACCENT_RED, NORMAL_BLACK) == ACCENT_RED,
                "Inside selection path uses accent red");
        expect(XhsNavigationLogic.labelColor(false, false, 1, 0, ACCENT_RED, NORMAL_BLACK) == NORMAL_BLACK,
                "Outside selection path keeps normal black");

        // 2. Normal optical frame: lens already drew accented home label, no inside overlay needed.
        float liveMix = XhsNavigationLogic.effectiveOpticalMix(true, false, 1f);
        expect(liveMix == 1f, "Live optical frame retains full mix");
        expect(!XhsNavigationLogic.shouldDrawInsideAccent(false, liveMix), "No duplicate inside draw at mix=1");

        // 3. Empty selectionPath fallback (e.g. slider disabled): selected tab still paints red.
        expect(!XhsNavigationLogic.shouldDrawInsideAccent(true, settleMix), "Empty path skips clipped inside pass");
        expect(XhsNavigationLogic.labelColor(false, true, 0, 0, ACCENT_RED, NORMAL_BLACK) == ACCENT_RED,
                "Empty-path fallback highlights selected Home tab in red");
        expect(XhsNavigationLogic.labelColor(false, true, 1, 0, ACCENT_RED, NORMAL_BLACK) == NORMAL_BLACK,
                "Empty-path fallback keeps unselected Market tab normal");

        // 4. Detached direct selection path matches settle behavior.
        expect(XhsNavigationLogic.effectiveOpticalMix(true, true, 1f) == 0f,
                "Detached direct selection draws inside accent directly");

        // 5. Message badge text normalization.
        expect(XhsNavigationLogic.normalizeBadgeText(null) == null, "Null badge yields null");
        expect(XhsNavigationLogic.normalizeBadgeText("") == null, "Empty badge yields null");
        expect(XhsNavigationLogic.normalizeBadgeText("0") == null, "Zero badge hidden");
        expect("1".equals(XhsNavigationLogic.normalizeBadgeText("1")), "Single digit preserved");
        expect("42".equals(XhsNavigationLogic.normalizeBadgeText(" 42 ")), "Two digits trimmed");
        expect("99+".equals(XhsNavigationLogic.normalizeBadgeText("128")), "Count above 99 caps at 99+");
        expect("99+".equals(XhsNavigationLogic.normalizeBadgeText("99+")), "Pre-formatted 99+ preserved");

        // 6. Badge geometry on both wide inner-screen slot and narrow outer-screen slot.
        float d = 3f;
        float barH = 56f * d;
        float labelW = 36f * d;
        float visualTop = 20f * d;

        float innerCx = 500f;
        float innerSlotW = 78f * d;
        float[] innerSingle = XhsNavigationLogic.computeBadgeBounds(
                innerCx, innerSlotW, barH, labelW, visualTop, 6f * d, false, d);
        expect(innerSingle[2] - innerSingle[0] >= 14.9f * d && innerSingle[2] - innerSingle[0] <= 15.1f * d,
                "Single-digit badge stays circular at 15dp");
        expect(innerSingle[0] >= innerCx + labelW * 0.5f,
                "Wide slot leaves breathing room to the right of the label");
        expect(innerSingle[1] >= 5.5f * d && innerSingle[2] <= innerCx + innerSlotW * 0.5f - 4f * d + 0.01f,
                "Wide slot badge stays inside indicator safe area");

        float outerCx = 400f;
        float outerSlotW = 66f * d;
        float[] outerWideNum = XhsNavigationLogic.computeBadgeBounds(
                outerCx, outerSlotW, barH, labelW, visualTop, 18f * d, false, d);
        expect(outerWideNum[2] - outerWideNum[0] > outerWideNum[3] - outerWideNum[1],
                "99+ badge expands horizontally into a pill");
        expect(outerWideNum[1] >= 5.5f * d && outerWideNum[3] <= barH - 5.5f * d + 0.01f,
                "Outer slot 99+ stays vertically inside safe area");
        expect(outerWideNum[0] >= outerCx - outerSlotW * 0.5f + 4f * d - 0.01f &&
                outerWideNum[2] <= outerCx + outerSlotW * 0.5f - 4f * d + 0.01f,
                "Outer slot 99+ stays horizontally inside slot safe area");

        float[] dot = XhsNavigationLogic.computeBadgeBounds(
                outerCx, outerSlotW, barH, labelW, visualTop, 0f, true, d);
        expect(Math.abs((dot[2] - dot[0]) - (dot[3] - dot[1])) < 0.01f, "Red dot is circular");
        expect(dot[1] >= 5.5f * d && dot[2] <= outerCx + outerSlotW * 0.5f - 4f * d + 0.01f,
                "Red dot stays inside safe area");

        System.out.println("PASS: XhsNavigationLogic color/return-settle and message-badge layout");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
