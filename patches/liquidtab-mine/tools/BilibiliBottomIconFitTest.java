package io.github.offlineglass.hook.adapters.bilibili;

public final class BilibiliBottomIconFitTest {
    public static void main(String[] args) {
        expect(BilibiliBottomIconFit.captionColor(false) == 0xFF000000, "Light glass caption is black");
        expect(BilibiliBottomIconFit.captionColor(true) == 0xFFFFFFFF, "Dark glass caption is white");

        float wideTab = BilibiliBottomIconFit.scale(24, 20, 77, 32, 1, 1);
        expect(wideTab > 1.20f && wideTab < 1.24f, "Compact native artwork grows about 22 percent");
        float tallSkin = BilibiliBottomIconFit.scale(60, 45, 77, 32, 1, 1.4f);
        expect(tallSkin * 45 <= 32 && tallSkin * 60 <= 73, "Keep a large skin above its caption and inside the slot");
        float narrowTab = BilibiliBottomIconFit.scale(42, 24, 46, 34, 1, 1.4f);
        expect(narrowTab == 1f, "Constrain a narrow slot by width even at maximum zoom");
        float separatedPublish = BilibiliBottomIconFit.scale(38, 38, 40, 36, 1, 1.4f);
        expect(separatedPublish * 38 <= 36, "Fit detached publish art inside the button");
        expect(BilibiliBottomIconFit.scale(24, 20, 3, 32, 1, 1) == 0f, "Decline a slot without drawable space");
        System.out.println("PASS: glass caption contrast and icon sizing for wide, narrow, tall and detached slots");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
