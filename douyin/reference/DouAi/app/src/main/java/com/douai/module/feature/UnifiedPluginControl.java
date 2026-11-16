package com.douai.module.feature;

import com.douai.module.config.SettingsStore;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;

/** Manages actual Mira load requests without deleting host plugin files. */
public final class UnifiedPluginControl implements AutoCloseable {
    public static final String BLOCKED = "unified_blocked_plugins";
    private static volatile UnifiedPluginControl active;
    private final Set<String> observed = new ConcurrentSkipListSet<>();
    private final Method installed;
    private final XposedInterface.HookHandle handle;
    private UnifiedPluginControl(Method installed, XposedInterface.HookHandle handle) { this.installed = installed; this.handle = handle; }
    public static UnifiedPluginControl install(XposedInterface framework, ClassLoader loader, SettingsStore settings) throws Exception {
        Class<?> mira = Class.forName("com.bytedance.mira.Mira", false, loader);
        Method load = mira.getDeclaredMethod("loadPlugin", String.class);
        Method list = mira.getDeclaredMethod("getInstalledPackageNames");
        if (!Modifier.isStatic(load.getModifiers()) || load.getReturnType() != boolean.class
                || !Modifier.isStatic(list.getModifiers()) || list.getReturnType() != List.class) throw new IllegalStateException("Mira plugin contracts changed");
        UnifiedPluginControl[] reference = new UnifiedPluginControl[1];
        XposedInterface.HookHandle handle = framework.hook(load).setId("DouAi.Unified.PluginControl")
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    String name = (String) chain.getArg(0);
                    if (name != null && reference[0] != null) reference[0].observed.add(name);
                    for (String blocked : settings.getString(BLOCKED, "").split("[\\n,;]")) {
                        if (!blocked.trim().isEmpty() && blocked.trim().equals(name)) return false;
                    }
                    return chain.proceed();
                });
        if (handle == null) throw new IllegalStateException("Plugin manager hook unavailable");
        UnifiedPluginControl control = new UnifiedPluginControl(list, handle); reference[0] = control; active = control; return control;
    }
    public static List<String> installedNames() {
        UnifiedPluginControl control = active;
        Set<String> names = new TreeSet<>();
        if (control != null) {
            names.addAll(control.observed);
            try { Object result = control.installed.invoke(null); if (result instanceof List) for (Object item : (List<?>) result) if (item instanceof String) names.add((String) item); }
            catch (ReflectiveOperationException ignored) { }
        }
        return new ArrayList<>(names);
    }
    @Override public void close() { handle.unhook(); if (active == this) active = null; observed.clear(); }
}
