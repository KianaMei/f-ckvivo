package io.github.offlineglass.hook.adapters.youtube;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.function.Predicate;

/** The stable view tag supplies the object; fields are selected by ownership and argument semantics. */
final class YouTubeFragmentArguments {
    static <T> T resolve(Object fragment, Object view, Class<?> viewType, Class<T> bundleType, Predicate<T> accepts)
            throws IllegalAccessException {
        boolean ownsView = false;
        var matches = new ArrayList<T>();
        int count = 0, depth = 0;
        for (Class<?> type = fragment.getClass(); type != null; type = type.getSuperclass()) {
            if (++depth > 24) throw new IllegalStateException("Fragment hierarchy budget exceeded");
            for (var field : type.getDeclaredFields()) {
                if (++count > 512) throw new IllegalStateException("Fragment field budget exceeded");
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!viewType.isAssignableFrom(field.getType()) && field.getType() != bundleType) continue;
                field.setAccessible(true);
                Object value = field.get(fragment);
                if (viewType.isAssignableFrom(field.getType()) && value == view) ownsView = true;
                if (field.getType() == bundleType && value != null && accepts.test(bundleType.cast(value)) &&
                        matches.stream().noneMatch(previous -> previous == value)) matches.add(bundleType.cast(value));
            }
        }
        if (!ownsView || matches.size() != 1) throw new IllegalStateException("Fragment view/arguments absent or ambiguous");
        return matches.get(0);
    }
}
