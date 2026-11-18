package io.github.offlineglass.hook.adapters.bilibili;

import java.util.ArrayList;
import java.util.List;

/** Standalone JVM checks for the wide-screen native tab binding. */
public final class BilibiliComposeTabContractTest {
    public static void main(String[] args) {
        Object home = new Object();
        Object following = new Object();
        Object publish = new Object();
        Object shop = new Object();
        Object mine = new Object();
        var semantics = List.of(
            tab("我的", "", mine, false),
            tab("", "发布按钮", publish, true),
            tab("会员购", "", shop, false),
            tab("首页", "", home, true),
            tab("关注", "", following, false),
            tab("10", "", following, false)
        );
        var binding = BilibiliComposeTabContract.bind(semantics);
        check(binding != null && binding.clickTargets().equals(List.of(home, following, publish, shop, mine)),
            "Resolve slots by label, not the semantics tree order");
        check(Integer.valueOf(0).equals(binding.selectedIndex()), "Publish is not a selectable page");

        var switched = new ArrayList<>(semantics);
        switched.set(3, tab("首页", "", home, false));
        switched.set(4, tab("动态", "", following, true));
        switched.add(tab("关注", "", following, false));
        binding = BilibiliComposeTabContract.bind(switched);
        check(binding != null && Integer.valueOf(1).equals(binding.selectedIndex()),
            "Native following selection is read after recomposition; duplicate labels for the same action are safe");

        var ambiguous = new ArrayList<>(semantics);
        ambiguous.add(tab("关注", "", new Object(), false));
        check(BilibiliComposeTabContract.bind(ambiguous) == null, "Do not dispatch an ambiguous slot");
        ambiguous.removeLast();
        ambiguous.set(4, tab("关注", "", home, false));
        check(BilibiliComposeTabContract.bind(ambiguous) == null, "Reject an action shared across different slots");
        ambiguous.remove(4);
        check(BilibiliComposeTabContract.bind(ambiguous) == null, "Do not shift slots when a native action is missing");

        var noUniqueSelection = new ArrayList<>(semantics);
        noUniqueSelection.set(4, tab("关注", "", following, true));
        check(BilibiliComposeTabContract.bind(noUniqueSelection).selectedIndex() == null,
            "Do not report a selected page when two native pages claim selection");
        System.out.println("PASS: native labels, publish, recomposition, missing and ambiguous actions/selection");
    }

    private static BilibiliComposeTabContract.Candidate<Object> tab(String text, String description,
        Object clickTarget, boolean selected) {
        return new BilibiliComposeTabContract.Candidate<>(text, description, clickTarget, selected);
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
