package io.github.offlineglass.hook.adapters.youtube;

import java.util.List;
import java.util.Locale;

/** Native accessibility semantics, independent of class/member spellings. */
final class YouTubeExploreContract {
    enum Role { EXPLORE, NOTIFICATIONS, SEARCH }
    record Candidate<T>(T node, String description, boolean attached, boolean enabled, boolean clickable) {}

    static boolean isExplore(String label) {
        return matches(Role.EXPLORE, label);
    }

    static boolean matches(Role role, String label) {
        // The native accessibility label includes a menu role and quoted title
        // (for example: “探索”菜单). Quotes are presentation, not action identity.
        String value = label.trim().toLowerCase(Locale.ROOT).replaceAll("[\"“”‘’]", "");
        return switch (role) {
            case EXPLORE -> value.equals("explore") || value.equals("explore menu") ||
                    value.equals("探索") || value.equals("探索菜单");
            case NOTIFICATIONS -> value.equals("notifications") || value.equals("通知");
            case SEARCH -> value.equals("search") || value.equals("搜索");
        };
    }

    static <T> T unique(List<Candidate<T>> candidates) {
        return unique(Role.EXPLORE, candidates);
    }

    static <T> T unique(Role role, List<Candidate<T>> candidates) {
        T result = null;
        for (Candidate<T> candidate : candidates) {
            if (!candidate.attached || !candidate.enabled || !candidate.clickable || !matches(role, candidate.description)) continue;
            if (result != null && result != candidate.node) throw new IllegalStateException("Ambiguous native " + role + " actions");
            result = candidate.node;
        }
        if (result == null) throw new IllegalStateException("Live native " + role + " action missing");
        return result;
    }

    /** Caller verifies the native Toolbar resource/ancestry before discovery. */
    static java.lang.reflect.Method menuGetter(Class<?> toolbar, Class<?> menuInterface) {
        java.lang.reflect.Method result = null;
        for (var method : toolbar.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0 ||
                    method.getReturnType() != menuInterface || method.isBridge() || method.isSynthetic()) continue;
            if (result != null) throw new IllegalStateException("Ambiguous native Toolbar Menu getter");
            result = method;
        }
        if (result == null) throw new IllegalStateException("Native Toolbar Menu interface missing");
        return result;
    }
}
