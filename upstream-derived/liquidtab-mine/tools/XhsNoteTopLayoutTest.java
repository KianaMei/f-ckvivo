package io.github.offlineglass.hook.adapters.xhs;

public final class XhsNoteTopLayoutTest {
    public static void main(String[] args) {
        // 1. Fold inner screen split-pane (2312x2504, left=0..1272, right=1272..2312, side=64, h=116, gap=17)
        XhsNoteTopLayout.Result split = XhsNoteTopLayout.compute(
                true, true,
                0, 1272,
                1272, 1040,
                116, 116,
                64, 17,
                181,
                46f, 9f, 180f, 29f
        );
        expect(split != null, "Split inner screen must produce layout");
        expect(split.back.left == 64 && split.back.right() == 180 && split.back.top == 116,
                "Back capsule sits in left pane at symmetric side margin");
        expect(split.author.left == 1272 + 64,
                "Author capsule aligns with bottom comment input left edge (1336)");
        expect(split.share.right() == 2312 - 64 && split.share.left == 2312 - 64 - 116,
                "Share capsule aligns with bottom action capsule right edge (2248)");
        expect(split.follow.right() == split.share.left - 17 && split.follow.width == 181,
                "Follow capsule sits 6dp left of share capsule");
        expect(split.author.right() <= split.follow.left - 17,
                "Author capsule never collides with follow capsule");
        expect(split.author.width > 116 && split.author.right() == Math.round(split.nameStartX + 180f + 29f),
                "Normal nickname fits without premature clamping");

        // 2. Very long nickname on split-pane clamps cleanly at follow.left - gap
        XhsNoteTopLayout.Result longName = XhsNoteTopLayout.compute(
                true, true,
                0, 1272,
                1272, 1040,
                116, 116,
                64, 17,
                181,
                46f, 9f, 900f, 29f
        );
        expect(longName != null, "Long nickname layout succeeds");
        expect(longName.author.right() == longName.follow.left - 17,
                "Long nickname clamps right before follow capsule");

        // 3. Split-pane with follow hidden (own post / already hidden) expands author ceiling to share.left - gap
        XhsNoteTopLayout.Result noFollow = XhsNoteTopLayout.compute(
                true, true,
                0, 1272,
                1272, 1040,
                116, 116,
                64, 17,
                0,
                46f, 9f, 900f, 29f
        );
        expect(noFollow != null && noFollow.follow.width == 0, "Zero follow width hides follow plate");
        expect(noFollow.author.right() == noFollow.share.left - 17,
                "Without follow button author extends up to share - gap");

        // 4. Split XML in narrow/compressed window mode (leftPane GONE, backBox wrap_content=147px, rowLeft=147)
        XhsNoteTopLayout.Result compactSplit = XhsNoteTopLayout.compute(
                true, false,
                0, 147,
                147, 1120 - 147,
                116, 116,
                48, 17,
                181,
                46f, 9f, 180f, 29f
        );
        expect(compactSplit != null, "Compact split mode produces layout");
        expect(compactSplit.back.left == 48, "Compact split keeps back at left side margin");
        expect(compactSplit.author.left == 48 + 116 + 17,
                "Compact split places author right after back capsule without overlap");
        expect(compactSplit.share.right() == 1120 - 48,
                "Compact split aligns share to window right margin");

        // 5. Classic single-column phone layout (outer screen 1120px) keeps exact legacy geometry
        XhsNoteTopLayout.Result single = XhsNoteTopLayout.compute(
                false, false,
                0, 1120,
                0, 1120,
                123, 116,
                64, 17,
                181,
                46f, 9f, 180f, 29f
        );
        expect(single != null, "Single column produces layout");
        expect(single.back.left == 64 && single.author.left == 64 + 116 + 17,
                "Single column matches original back and author coordinates");
        expect(single.share.right() == 1120 - 64 && single.follow.right() == single.share.left - 17,
                "Single column matches original share and follow coordinates");

        System.out.println("PASS: XhsNoteTopLayout split-pane, compact-split, and single-column geometry");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
