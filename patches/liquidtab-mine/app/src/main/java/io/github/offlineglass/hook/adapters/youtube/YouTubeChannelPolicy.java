package io.github.offlineglass.hook.adapters.youtube;

import java.util.ArrayList;
import java.util.List;

/** Native ordered semantic keys in; no View class names, versions or positions guessed. */
final class YouTubeChannelPolicy {
    static List<String> merge(List<String> previous, List<String> observed) {
        var result = new ArrayList<>(previous);
        String next = null;
        for (int i = observed.size() - 1; i >= 0; i--) {
            String key = observed.get(i);
            int old = result.indexOf(key);
            if (next == null) {
                if (old < 0) result.add(key);
            } else if (old < 0 || old > result.indexOf(next)) {
                result.remove(key);
                result.add(result.indexOf(next), key);
            }
            next = key;
        }
        return result;
    }

    static List<String> visible(List<String> nativeOrder, String selected) {
        if (nativeOrder.size() != new java.util.HashSet<>(nativeOrder).size())
            throw new IllegalArgumentException("Ambiguous native channel keys");
        return nativeOrder.stream().filter(key -> key.equals("all") || key.equals("live") || key.equals(selected)).toList();
    }
}
