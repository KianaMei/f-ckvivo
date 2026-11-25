package io.github.offlineglass.hook.adapters.xhs;

/**
 * Pure geometry solver for NoteDetailActivity floating top-bar capsules.
 * Supports both single-column phone layouts and fold/tablet split-pane layouts
 * without Android framework dependencies so it can be unit-tested on the JVM.
 */
final class XhsNoteTopLayout {
    static final class RectInt {
        final int left;
        final int top;
        final int width;
        final int height;

        RectInt(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        int right() {
            return left + width;
        }
    }

    static final class Result {
        final RectInt back;
        final RectInt author;
        final RectInt follow;
        final RectInt share;
        final float avatarCenterX;
        final float nameStartX;
        final float centerY;

        Result(RectInt back, RectInt author, RectInt follow, RectInt share,
               float avatarCenterX, float nameStartX, float centerY) {
            this.back = back;
            this.author = author;
            this.follow = follow;
            this.share = share;
            this.avatarCenterX = avatarCenterX;
            this.nameStartX = nameStartX;
            this.centerY = centerY;
        }
    }

    private XhsNoteTopLayout() {}

    static Result compute(
            boolean splitLayout,
            boolean splitPaneVisible,
            int backBoxLeft,
            int backBoxWidth,
            int rowLeft,
            int rowWidth,
            int y,
            int height,
            int side,
            int gap,
            int followWidth,
            float avatarRadius,
            float nameGapPx,
            float textWidth,
            float authorEndPadPx
    ) {
        if (height <= 0 || rowWidth <= 0) return null;
        int end = rowLeft + rowWidth - side;
        int shareStart = end - height;
        int effectiveFollowWidth = Math.max(0, followWidth);
        int followStart = shareStart - gap - effectiveFollowWidth;

        int backLeft;
        int authorStart;
        if (splitLayout && splitPaneVisible) {
            int maxBackLeft = Math.max(0, backBoxWidth - height);
            backLeft = backBoxLeft + Math.min(side, maxBackLeft);
            authorStart = rowLeft + side;
        } else if (splitLayout) {
            backLeft = backBoxLeft + side;
            authorStart = Math.max(backLeft + height + gap, rowLeft);
        } else {
            backLeft = rowLeft + side;
            authorStart = backLeft + height + gap;
        }

        int authorEnd = effectiveFollowWidth <= 0 ? (shareStart - gap) : (followStart - gap);
        if (authorEnd - authorStart < height) return null;

        float avatarCenterX = authorStart + height / 2f;
        float nameStartX = avatarCenterX + avatarRadius + nameGapPx;
        int rawAuthorRight = Math.round(nameStartX + textWidth + authorEndPadPx);
        int authorRight = Math.max(authorStart + height, Math.min(rawAuthorRight, authorEnd));

        return new Result(
                new RectInt(backLeft, y, height, height),
                new RectInt(authorStart, y, authorRight - authorStart, height),
                new RectInt(followStart, y, effectiveFollowWidth, height),
                new RectInt(shareStart, y, height, height),
                avatarCenterX,
                nameStartX,
                y + height / 2f
        );
    }
}
