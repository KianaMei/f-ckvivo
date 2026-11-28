package dev.ganvivo.dmsilencer;

import java.lang.reflect.Executable;
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

    static Class<?> findClassOrNull(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static int hookAllByName(
            XposedModule module,
            Class<?> clazz,
            String methodName,
            XposedInterface.Hooker hooker) {
        if (clazz == null) {
            return 0;
        }
        int count = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            try {
                m.setAccessible(true);
                hook(module, m, hooker);
                count++;
            } catch (Throwable t) {
                Logx.w("hook fail " + clazz.getSimpleName() + "." + methodName, t);
            }
        }
        return count;
    }

    static Object defaultReturnFor(Class<?> returnType) {
        if (returnType == null || returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(-1L);
        }
        if (returnType == Float.TYPE) {
            return Float.valueOf(0.0f);
        }
        if (returnType == Double.TYPE) {
            return Double.valueOf(0.0d);
        }
        if (returnType == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (returnType == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (returnType == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return null;
    }
}
