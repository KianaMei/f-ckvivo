package dev.ganvivo.dragall;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class DragProfile {
    private final Class<?> profileClass;
    private final Constructor<?> constructor;
    private final Method setPackage;
    private final Method setLevel;
    private final Map<Method, Method> properties = new LinkedHashMap<>();

    DragProfile(ClassLoader loader) throws ReflectiveOperationException {
        profileClass = Reflection.load(loader,
                "com.vivo.common.dragdrop.data.bean.EnhancedDragWhiteList$EnhancedConfigBean");
        constructor = profileClass.getDeclaredConstructor();
        setPackage = profileClass.getMethod("setPackageName", String.class);
        setLevel = profileClass.getMethod("setStrictLevel", String.class);
        property("Label", String.class);
        property("Disabled", Boolean.class);
        for (String name : new String[]{"TextWhiteList", "ImageWhiteList", "CustomWhiteList",
                "TargetViewsList", "BlackList", "NoWidgetLimitWhiteList"}) {
            property(name, ArrayList.class);
        }
    }

    private void property(String name, Class<?> type) throws ReflectiveOperationException {
        properties.put(profileClass.getMethod("get" + name), profileClass.getMethod("set" + name, type));
    }

    Object allowPackage(Object original, String packageName) throws Throwable {
        if (original != null && !profileClass.isInstance(original)) return original;
        Object copy = constructor.newInstance();
        if (original != null) {
            for (Map.Entry<Method, Method> property : properties.entrySet()) {
                Reflection.invoke(property.getValue(), copy, Reflection.invoke(property.getKey(), original));
            }
        }
        Reflection.invoke(setPackage, copy, packageName);
        Reflection.invoke(setLevel, copy, "package");
        return copy;
    }
}
