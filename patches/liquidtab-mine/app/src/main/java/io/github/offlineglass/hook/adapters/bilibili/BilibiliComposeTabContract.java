package io.github.offlineglass.hook.adapters.bilibili;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;

/** Map native semantics to fixed slots without relying on traversal order. */
final class BilibiliComposeTabContract {
    private static final int TAB_COUNT = 5;
    private static final int PUBLISH = 2;

    record Candidate<T>(String text, String description, T clickTarget, boolean selected) {}
    record Binding<T>(List<T> clickTargets, Integer selectedIndex) {}

    static <T> Binding<T> bind(List<Candidate<T>> candidates) {
        var slots = new HashMap<Integer, Candidate<T>>();
        for (var candidate : candidates) {
            int text = slot(candidate.text());
            int description = slot(candidate.description());
            if (text >= 0 && description >= 0 && text != description) return null;
            int index = text >= 0 ? text : description;
            if (index < 0 || candidate.clickTarget() == null) continue;
            var previous = slots.putIfAbsent(index, candidate);
            if (previous == null) continue;
            if (previous.clickTarget() != candidate.clickTarget()) return null;
            if (candidate.selected()) slots.put(index, candidate);
        }
        if (slots.size() != TAB_COUNT) return null;

        var targets = new ArrayList<T>(TAB_COUNT);
        var seenTargets = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        Integer selected = null;
        int selectedCount = 0;
        for (int index = 0; index < TAB_COUNT; index++) {
            var candidate = slots.get(index);
            if (!seenTargets.add(candidate.clickTarget())) return null;
            targets.add(candidate.clickTarget());
            if (index != PUBLISH && candidate.selected()) {
                selected = index;
                selectedCount++;
            }
        }
        return new Binding<>(List.copyOf(targets), selectedCount == 1 ? selected : null);
    }

    static int slot(String label) {
        return switch (label.trim()) {
            case "首页" -> 0;
            case "关注", "动态" -> 1;
            case "发布按钮", "发布" -> PUBLISH;
            case "会员购" -> 3;
            case "我的" -> 4;
            default -> -1;
        };
    }
}
