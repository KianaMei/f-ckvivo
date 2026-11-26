package dev.ganvivo.vivoworkbench;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * libxposed API 102 强类型反射与安全 Hook 工具。
 * 全部 hook 使用 ExceptionMode.PROTECTIVE 并返回 HookHandle 以便统计与管理。
 */
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

    static Class<?> findClassOrNull(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
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

    static Method findMethodByParamCount(Class<?> clazz, String name, int paramCount) {
        Method matched = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            if (m.getParameterTypes().length != paramCount) {
                continue;
            }
            if (matched != null) {
                throw new IllegalStateException("ambiguous method " + name + "/" + paramCount
                        + " in " + clazz.getName());
            }
            matched = m;
        }
        if (matched == null) {
            throw new NoSuchMethodError(name + "/" + paramCount + " in " + clazz.getName());
        }
        matched.setAccessible(true);
        return matched;
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

    static Object getFieldOrNull(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            return findField(target.getClass(), name).get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static int getIntField(Object target, String name, int def) {
        Object v = getFieldOrNull(target, name);
        return (v instanceof Integer) ? ((Integer) v).intValue() : def;
    }

    static boolean getBoolField(Object target, String name, boolean def) {
        Object v = getFieldOrNull(target, name);
        return (v instanceof Boolean) ? ((Boolean) v).booleanValue() : def;
    }

    static void setField(Object target, String name, Object value) {
        try {
            findField(target.getClass(), name).set(target, value);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    static Object getStaticFieldOrNull(Class<?> clazz, String name) {
        if (clazz == null) {
            return null;
        }
        try {
            return findField(clazz, name).get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean getStaticBool(Class<?> clazz, String name, boolean def) {
        Object v = getStaticFieldOrNull(clazz, name);
        return (v instanceof Boolean) ? ((Boolean) v).booleanValue() : def;
    }

    static void setStaticField(Class<?> clazz, String name, Object value) {
        try {
            findField(clazz, name).set(null, value);
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
        Method m = findMethod(target.getClass(), name, types);
        return invoke(m, target, args);
    }

    static Object callStatic(Class<?> clazz, String name, Class<?>[] types, Object... args) {
        Method m = findMethod(clazz, name, types);
        return invoke(m, null, args);
    }

    private static RuntimeException sneaky(Throwable t) {
        if (t instanceof RuntimeException) {
            return (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        return new RuntimeException(t);
    }
}
