package io.github.offlineglass.hook.adapters.meituan_main;

/** No obfuscated symbols: the public artwork interface and Android's selected
 * property must belong to the same native tab implementation. Class identity
 * scopes the caller's cache to the APK/class loader that supplied the contract. */
public final class MeituanMainTabContract {
    public static boolean matches(Class<?> type, Class<?> view, Class<?> drawable) {
        try {
            if (!view.isAssignableFrom(type) || !nativeType(type)) return false;
            var icon = type.getMethod("getIcon");
            var title = type.getMethod("getTitle");
            var selected = type.getMethod("setSelected", boolean.class);
            return drawable.isAssignableFrom(icon.getReturnType()) && title.getReturnType() == String.class
                && selected.getReturnType() == void.class && nativeType(selected.getDeclaringClass())
                && nativeType(icon.getDeclaringClass()) && nativeType(title.getDeclaringClass());
        } catch (ReflectiveOperationException | SecurityException failure) { return false; }
    }
    public static <T> T unique(java.util.List<T> candidates) {
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
    private static boolean nativeType(Class<?> type) {
        return type.getName().startsWith("com.meituan.android.pt.homepage.tab.");
    }
}
