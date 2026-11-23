package io.github.offlineglass.hook.adapters.youtube;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/** Called only after the two native behavior roles and Coordinator parent are verified. */
final class YouTubeBehaviorAccess {
    record Access(Field field, Method setter) {
        Object get(Object params) throws ReflectiveOperationException { return field.get(params); }
        void set(Object params, Object behavior) throws ReflectiveOperationException {
            setter.invoke(params, behavior);
            if (get(params) != behavior) throw new IllegalStateException("Behavior setter postcondition failed");
        }
    }

    static Access resolve(Class<?> params, Object scrolling, Object header) {
        var matches = new ArrayList<Access>();
        for (Field field : params.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()) ||
                    !field.getType().isInstance(scrolling) || !field.getType().isInstance(header) ||
                    field.getType() == Object.class) continue;
            // Native Behavior's attach callback consumes this exact LP type.
            boolean attaches = false;
            for (Method callback : field.getType().getMethods()) {
                if (!Modifier.isStatic(callback.getModifiers()) && callback.getReturnType() == void.class &&
                        java.util.Arrays.equals(callback.getParameterTypes(), new Class<?>[]{params})) attaches = true;
            }
            if (!attaches) continue;
            for (Method setter : params.getMethods()) {
                if (setter.getDeclaringClass() == params && !Modifier.isStatic(setter.getModifiers()) &&
                        setter.getReturnType() == void.class &&
                        java.util.Arrays.equals(setter.getParameterTypes(), new Class<?>[]{field.getType()})) {
                    matches.add(new Access(field, setter));
                }
            }
        }
        if (matches.size() != 1) throw new IllegalStateException("Expected unique Behavior field/setter/attach contract, found " + matches.size());
        return matches.get(0);
    }
}
