package de.robv.android.xposed;

public final class XposedBridge {
    public static void log(String message) {}
    public static void log(Throwable throwable) {}

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(
        Class<?> hookClass,
        String methodName,
        XC_MethodHook callback
    ) {
        return null;
    }

    public static XC_MethodHook.Unhook hookMethod(
        java.lang.reflect.Member hookMethod,
        XC_MethodHook callback
    ) {
        return null;
    }

    public static Object invokeOriginalMethod(
        java.lang.reflect.Member method,
        Object thisObject,
        Object[] args
    ) throws NullPointerException, IllegalAccessException, IllegalArgumentException,
        java.lang.reflect.InvocationTargetException {
        return null;
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllConstructors(
        Class<?> hookClass,
        XC_MethodHook callback
    ) {
        return null;
    }
}
