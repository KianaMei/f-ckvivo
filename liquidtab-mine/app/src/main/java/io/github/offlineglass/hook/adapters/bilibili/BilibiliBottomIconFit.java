package io.github.offlineglass.hook.adapters.bilibili;

final class BilibiliBottomIconFit {
    private BilibiliBottomIconFit() {}

    static int captionColor(boolean darkGlass) {
        return darkGlass ? 0xFFFFFFFF : 0xFF000000;
    }

    static float scale(float artworkWidth, float artworkHeight, float slotWidth, float availableHeight,
                       float density, float iconScale) {
        if (!(artworkWidth > 0 && artworkHeight > 0 && density > 0 && iconScale > 0)) return 0f;
        float horizontalRoom = slotWidth - 4f * density;
        if (!(horizontalRoom > 0 && availableHeight > 0)) return 0f;
        return Math.min(1.22f * iconScale, Math.min(horizontalRoom / artworkWidth, availableHeight / artworkHeight));
    }
}
