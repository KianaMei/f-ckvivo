package dev.ganvivo.dragall;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class Reflection {
    private Reflection() {}

    static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    static Method method(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (method.getReturnType() != result || Modifier.isAbstract(method.getModifiers())) {
            throw new NoSuchMethodException("Unexpected signature: " + method);
        }
        method.setAccessible(true);
        return method;
    }

    static List<Method> callers(Class<?> owner) throws NoSuchMethodException {
        List<Method> methods = new ArrayList<>();
        for (Method method : owner.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) continue;
            method.setAccessible(true);
            methods.add(method);
        }
        if (methods.isEmpty()) throw new NoSuchMethodException("No caller methods in " + owner.getName());
        return methods;
    }

    static Field field(Class<?> owner, String name, Class<?> type)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type) throw new NoSuchFieldException("Unexpected type: " + field);
        field.setAccessible(true);
        return field;
    }

    static Object invoke(Method method, Object receiver, Object... arguments) throws Throwable {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
