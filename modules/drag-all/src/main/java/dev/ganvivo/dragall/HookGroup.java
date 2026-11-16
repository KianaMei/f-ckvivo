package dev.ganvivo.dragall;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class HookGroup implements AutoCloseable {
    private final XposedModule module;
    private final Diagnostics diagnostics;
    private final String name;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private volatile boolean active;

    HookGroup(XposedModule module, Diagnostics diagnostics, String name) {
        this.module = module;
        this.diagnostics = diagnostics;
        this.name = name;
    }

    void hook(Executable executable, XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle handle = module.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> active ? hooker.intercept(chain) : chain.proceed());
        if (handle == null) throw new IllegalStateException("No handle: " + executable);
        handles.add(handle);
        diagnostics.info(name + " registered " + executable);
    }

    void scope(Method caller, CallerScope scope, FeatureConfig config, String key) {
        hook(caller, chain -> {
            if (!config.enabled(key)) return chain.proceed();
            int previous = scope.enter();
            try {
                return chain.proceed();
            } finally {
                scope.exit(previous);
            }
        });
        boolean deoptimized = module.deoptimize(caller);
        diagnostics.info(name + " caller deopt=" + deoptimized + " " + caller);
    }

    HookGroup commit() {
        if (handles.isEmpty()) throw new IllegalStateException("Empty hook group: " + name);
        active = true;
        diagnostics.info(name + " installed hooks=" + handles.size());
        return this;
    }

    @Override
    public void close() {
        active = false;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                handles.get(index).unhook();
            } catch (Throwable failure) {
                diagnostics.failure(name + " unhook failed", failure);
            }
        }
        handles.clear();
    }
}
