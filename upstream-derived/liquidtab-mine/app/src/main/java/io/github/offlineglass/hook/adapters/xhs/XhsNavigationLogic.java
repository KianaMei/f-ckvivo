package io.github.offlineglass.hook.adapters.xhs;

/** Pure selection-color and message-badge layout math for Xiaohongshu's bottom bar. */
public final class XhsNavigationLogic {
    private XhsNavigationLogic() {}

    /**
     * Optical refraction only supplies the accented label when the indicator lens
     * actually recorded navigation artwork in this frame. Otherwise foreground
     * drawing must paint the accented label directly so the selected tab never
     * flashes normal-black during detail-return settle windows.
     */
    public static float effectiveOpticalMix(
            boolean selectionArtworkDrawn,
            boolean detachedDirectSelection,
            float navigationOpticalMix) {
        if (!selectionArtworkDrawn || detachedDirectSelection) return 0f;
        if (navigationOpticalMix <= 0f) return 0f;
        return Math.min(navigationOpticalMix, 1f);
    }

    public static int labelColor(
            boolean hideSelectedForOptics,
            boolean highlightSelected,
            int slotIndex,
            int selectedIndex,
            int accentColor,
            int normalColor) {
        if (hideSelectedForOptics || (highlightSelected && slotIndex == selectedIndex)) {
            return accentColor;
        }
        return normalColor;
    }

    public static boolean shouldDrawInsideAccent(boolean selectionPathEmpty, float effectiveMix) {
        return !selectionPathEmpty && effectiveMix < 1f;
    }

    public static int insideAccentAlpha(float effectiveMix) {
        float clamped = Math.max(0f, Math.min(1f, effectiveMix));
        return Math.max(0, Math.min(255, Math.round((1f - clamped) * 255f)));
    }

    /** Normalize native badge text; returns null when zero or blank. */
    public static String normalizeBadgeText(CharSequence raw) {
        if (raw == null) return null;
        String text = raw.toString().trim();
        if (text.isEmpty() || "0".equals(text)) return null;
        boolean allDigits = true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            try {
                int count = Integer.parseInt(text);
                if (count <= 0) return null;
                if (count > 99) return "99+";
                return Integer.toString(count);
            } catch (NumberFormatException ignored) {
                return "99+";
            }
        }
        return text;
    }

    /**
     * Compute [left, top, right, bottom] for the message tab badge inside the
     * scaled renderer coordinate space, anchored to the label's upper-right and
     * clamped inside the slot's indicator safe area so neither narrow outer-screen
     * slots nor the selection squircle clip it.
     */
    public static float[] computeBadgeBounds(
            float slotCenterX,
            float slotWidth,
            float barHeight,
            float labelWidth,
            float labelVisualTop,
            float numberTextWidth,
            boolean dotOnly,
            float density) {
        float slotInset = 4f * density;
        float slotLeft = slotCenterX - slotWidth * 0.5f + slotInset;
        float slotRight = slotCenterX + slotWidth * 0.5f - slotInset;
        float availW = Math.max(1f, slotRight - slotLeft);
        float minTop = 5.5f * density;

        if (dotOnly) {
            float diameter = 7.5f * density;
            float left = slotCenterX + labelWidth * 0.5f + 1.5f * density;
            float maxLeft = Math.max(slotLeft, slotRight - diameter);
            if (left > maxLeft) left = maxLeft;
            if (left < slotLeft) left = slotLeft;
            float top = labelVisualTop - diameter * 0.45f;
            float maxTop = Math.max(minTop, barHeight - diameter - minTop);
            top = Math.max(minTop, Math.min(top, maxTop));
            return new float[] {left, top, left + diameter, top + diameter};
        }

        float badgeH = 15f * density;
        float badgeW = Math.max(badgeH, numberTextWidth + 8f * density);
        if (badgeW > availW) badgeW = Math.max(badgeH, availW);
        float left = slotCenterX + labelWidth * 0.5f + 1.0f * density;
        float maxLeft = Math.max(slotLeft, slotRight - badgeW);
        if (left > maxLeft) left = maxLeft;
        if (left < slotLeft) left = slotLeft;

        float top = labelVisualTop - badgeH * 0.72f;
        float maxTop = Math.max(minTop, barHeight - badgeH - minTop);
        top = Math.max(minTop, Math.min(top, maxTop));
        return new float[] {left, top, left + badgeW, top + badgeH};
    }
}
