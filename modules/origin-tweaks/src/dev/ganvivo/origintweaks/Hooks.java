package dev.ganvivo.origintweaks;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Native libxposed API 102 helpers.
 *
 * Vector 2.2 blocks the whole classic de.robv.android.xposed surface
 * (XC_MethodHook, XposedHelpers, XposedBridge) for modules that target API
 * 101+, so every hook goes through XposedModule.hook() and reflection here.
 */
final class Hooks {

    private Hooks() {
    }

    static void hook(XposedModule module, Executable executable,
                     XposedInterface.Hooker hooker) {
        module.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    static Method method(Class<?> clazz, String name, Class<?>... types) {
        try {
            Method m = clazz.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(name + " in " + clazz.getName());
        }
    }

    static List<Method> methods(Class<?> clazz, String name) {
        ArrayList<Method> out = new ArrayList<Method>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                out.add(m);
            }
        }
        return out;
    }

    static List<Constructor<?>> constructors(Class<?> clazz) {
        ArrayList<Constructor<?>> out = new ArrayList<Constructor<?>>();
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            c.setAccessible(true);
            out.add(c);
        }
        return out;
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

    static Object construct(Constructor<?> c, Object... args) {
        try {
            return c.newInstance(args);
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    static Object field(Object target, String name) {
        return field(target.getClass(), target, name);
    }

    static Object staticField(Class<?> clazz, String name) {
        return field(clazz, null, name);
    }

    private static Object field(Class<?> clazz, Object target, String name) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }
        throw new IllegalStateException("field " + name + " not found in " + clazz);
    }

    static boolean bool(Object target, String name) {
        return Boolean.TRUE.equals(field(target, name));
    }

    static boolean staticBool(Class<?> clazz, String name) {
        return Boolean.TRUE.equals(staticField(clazz, name));
    }

    static Object call(Object target, String name, Object... args) {
        if (target == null) {
            throw new NullPointerException("null target for " + name);
        }
        return invoke(find(target.getClass(), name, args), target, args);
    }

    static Object callStatic(Class<?> clazz, String name, Object... args) {
        return invoke(find(clazz, name, args), null, args);
    }

    private static Method find(Class<?> clazz, String name, Object[] args) {
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                if (types.length != args.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < types.length; i++) {
                    if (!compatible(types[i], args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodError(name + "/" + args.length + " in " + clazz.getName());
    }

    private static boolean compatible(Class<?> type, Object value) {
        if (value == null) {
            return !type.isPrimitive();
        }
        if (type.isInstance(value)) {
            return true;
        }
        Class<?> box = box(type);
        return box != null && box.isInstance(value);
    }

    private static Class<?> box(Class<?> t) {
        if (t == boolean.class) {
            return Boolean.class;
        }
        if (t == int.class) {
            return Integer.class;
        }
        if (t == long.class) {
            return Long.class;
        }
        if (t == float.class) {
            return Float.class;
        }
        if (t == double.class) {
            return Double.class;
        }
        if (t == short.class) {
            return Short.class;
        }
        if (t == byte.class) {
            return Byte.class;
        }
        if (t == char.class) {
            return Character.class;
        }
        return null;
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
