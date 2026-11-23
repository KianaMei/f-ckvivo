package io.github.offlineglass.hook.adapters.bilibili_island;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Public semantic API verified in Xiaomi's native island decision chain. */
public final class BilibiliIslandContract {
    private BilibiliIslandContract() {}
    public static boolean target(String pkg) {
        return "tv.danmaku.bili".equals(pkg) || "com.bilibili.app.in".equals(pkg);
    }
    public static Method eligibility(Class<?> type) {
        Method[] members = type.getMethods();
        Method media = unique(members, "mediaIslandSupportMiniWindow");
        Method activity = unique(members, "isDynamicIslandMiniWindowBlackList");
        if (media == null || activity == null) throw new IllegalStateException("Incomplete native island eligibility contract");
        return media;
    }
    private static Method unique(Method[] members, String role) {
        Method found = null;
        for (Method m : members) {
            if (!m.getName().equals(role)) continue;
            // Reject changed/ambiguous ABI rather than selecting a convenient overload.
            if (found != null || Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.isSynthetic() ||
                    m.getReturnType() != boolean.class || !Arrays.equals(m.getParameterTypes(), new Class<?>[]{String.class})) return null;
            found = m;
        }
        return found;
    }
}
