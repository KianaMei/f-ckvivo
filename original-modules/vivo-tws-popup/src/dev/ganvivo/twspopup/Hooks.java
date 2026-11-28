package dev.ganvivo.twspopup;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class Hooks {

    private Hooks() {
    }

    static XposedInterface.HookHandle hook(
            XposedModule module,
            Executable executable,
            XposedInterface.Hooker hooker) {
        return module.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    static Method findMethod(Class<?> clazz, String name, Class<?>... types) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodError(name + " in " + clazz.getName());
    }

    static Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("field " + name + " not found in " + clazz.getName());
    }

    static Object getField(Object target, String name) {
        try {
            return findField(target.getClass(), name).get(target);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    static void setField(Object target, String name, Object value) {
        try {
            findField(target.getClass(), name).set(target, value);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args) {
        return invoke(findMethod(target.getClass(), name, types), target, args);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
