package io.github.offlineglass.hook.adapters.bilibili;

import java.util.*;

/** JVM checks for the over-wide campaign-label trim (bili 282). */
public final class BilibiliHomeChannelsTrimTest {
    private static final List<String> REGULAR = List.of("直播", "推荐", "热门", "动画", "影视", "新征程");

    private static float width(String label) {
        // Two CJK glyphs per 60px in these fixtures: pins measure 120, a
        // campaign hot-word twice that measures 240.
        if (label.equals("年度盛典热词")) return 240f;
        return label.length() * 60f;
    }

    public static void main(String[] args) {
        List<String> withCampaign = new ArrayList<>(REGULAR);
        withCampaign.add("年度盛典热词");
        List<String> trimmed = BilibiliHomeChannels.trimOverWide(withCampaign, "推荐", BilibiliHomeChannelsTrimTest::width);
        expect(trimmed.equals(REGULAR), "Over-wide campaign label is dropped, regular order kept");

        List<String> selectedCampaign = BilibiliHomeChannels.trimOverWide(withCampaign, "年度盛典热词", BilibiliHomeChannelsTrimTest::width);
        expect(selectedCampaign.equals(withCampaign), "Selected campaign channel is never dropped");

        List<String> longButNormal = new ArrayList<>(REGULAR);
        longButNormal.add("影视综");
        expect(BilibiliHomeChannels.trimOverWide(longButNormal, "推荐", BilibiliHomeChannelsTrimTest::width).equals(longButNormal),
            "A regular-length new channel stays in the strip");

        List<String> few = List.of("直播", "推荐", "年度盛典热词");
        expect(BilibiliHomeChannels.trimOverWide(few, "推荐", BilibiliHomeChannelsTrimTest::width).equals(few),
            "Never trim below three candidates");

        List<String> noPins = List.of("热门", "动画", "年度盛典热词");
        expect(BilibiliHomeChannels.trimOverWide(noPins, "热门", BilibiliHomeChannelsTrimTest::width).equals(noPins),
            "No pin baseline means no trimming");

        System.out.println("PASS: campaign trim keeps the wide row, selection, order and minimum slot count");
    }

    private static void expect(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
