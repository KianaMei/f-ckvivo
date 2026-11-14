package de.robv.android.xposed;

public final class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(
        Class<?> clazz,
        Object... parameterTypesAndCallback
    ) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
        Class<?> clazz,
        String methodName,
        Object... parameterTypesAndCallback
    ) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return false;
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {}

    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {}
}
