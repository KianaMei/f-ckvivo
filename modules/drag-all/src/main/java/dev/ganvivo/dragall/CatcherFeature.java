package dev.ganvivo.dragall;

import android.content.ComponentName;
import java.lang.reflect.Method;
import java.util.List;
import io.github.libxposed.api.XposedModule;

final class CatcherFeature {
    private CatcherFeature() {}

    static HookGroup install(XposedModule module, ClassLoader loader,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        Class<?> policy = Reflection.load(loader, "com.vivo.accessibilityenhance.dc.zb");
        Class<?> dragList = Reflection.load(loader,
                "com.vivo.accessibilityenhanceconfig.list.business.DragDropWhiteList");
        Method authorize = Reflection.method(policy, "N", boolean.class, String.class,
                Class.class, String.class, String.class, ComponentName.class);
        Method gate = Reflection.method(policy, "q1", boolean.class,
                Class.class, String.class, ComponentName.class);
        CallerScope scope = new CallerScope();
        HookGroup group = new HookGroup(module, diagnostics, "catcher_drag");
        try {
            group.hook(gate, chain -> {
                Object original = chain.proceed();
                ComponentName component = (ComponentName) chain.getArg(2);
                if (!scope.active() || chain.getArg(0) != dragList
                        || !"dragdrop".equals(chain.getArg(1)) || component == null) return original;
                diagnostics.hit("catcher_gate:" + component.getPackageName(),
                        "app policy original=" + original);
                return true;
            });
            group.hook(authorize, chain -> {
                if (!config.enabled(Config.DRAG) || chain.getArg(1) != dragList
                        || !Config.TARGET.equals(chain.getArg(2))
                        || !"dragdrop".equals(chain.getArg(3))) return chain.proceed();
                int previous = scope.enter();
                try {
                    Object result = chain.proceed();
                    ComponentName component = (ComponentName) chain.getArg(4);
                    if (component != null) diagnostics.hit("catcher_authorize:" + component.getPackageName(),
                            "allowed=" + result);
                    return result;
                } finally {
                    scope.exit(previous);
                }
            });
            diagnostics.info("catcher policy caller deopt=" + module.deoptimize(authorize));
            installNodeDiagnostics(loader, group, config, diagnostics);
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private static void installNodeDiagnostics(ClassLoader loader, HookGroup group,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        Class<?> process = Reflection.load(loader,
                "com.vivo.accessibilityenhance.process.process.GrabDragDropContentProcess");
        Class<?> result = Reflection.load(loader, "com.vivo.accessibilityenhance.dc.j50");
        Method finish = Reflection.method(process, "finishAnalyzeNode", void.class,
                Reflection.load(loader, "com.vivo.accessibilityenhance.dc.pw"));
        Method component = process.getMethod("getComponentName");
        Method hasNodes = Reflection.method(result, "f", boolean.class);
        Method nodeKind = Reflection.method(result, "g", int.class, int.class);
        Method nodes = Reflection.method(result, "k", List.class, int.class);
        group.hook(finish, chain -> {
            if (config.enabled(Config.DRAG) && result.isInstance(chain.getArg(0))) {
                try {
                    ComponentName source = (ComponentName) Reflection.invoke(component, chain.getThisObject());
                    if (source != null && Boolean.TRUE.equals(Reflection.invoke(hasNodes, chain.getArg(0)))) {
                        List<?> found = (List<?>) Reflection.invoke(nodes, chain.getArg(0), 0);
                        diagnostics.hit("catcher_nodes:" + source.getPackageName(),
                                "kind=" + Reflection.invoke(nodeKind, chain.getArg(0), 0)
                                        + " count=" + (found == null ? 0 : found.size()));
                    }
                } catch (Throwable failure) {
                    diagnostics.failure("catcher node diagnostics unavailable", failure);
                }
            }
            return chain.proceed();
        });
    }
}
