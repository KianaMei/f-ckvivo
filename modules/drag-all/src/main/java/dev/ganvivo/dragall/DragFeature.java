package dev.ganvivo.dragall;

import android.os.Message;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedModule;

final class DragFeature {
    private DragFeature() {}

    static HookGroup install(XposedModule module, ClassLoader loader,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        Method gate = Reflection.method(Reflection.load(loader, "vb.p"), "h", boolean.class,
                String.class, String.class);
        Method lookup = Reflection.method(Reflection.load(loader, "bb.a"), "a", Object.class,
                String.class, String.class);
        Class<?> captureClass = Reflection.load(loader, "vb.o");
        Method capture = Reflection.method(captureClass, "p", Object.class, Object.class);
        Field request = Reflection.field(captureClass, "k", Message.class);
        DragProfile profiles = new DragProfile(loader);
        List<Method> callers = new ArrayList<>();
        for (Method caller : Reflection.callers(Reflection.load(loader, "yb.f"))) {
            Class<?>[] parameters = caller.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == Message.class
                    && (caller.getReturnType() == void.class || caller.getReturnType() == boolean.class)) {
                callers.add(caller);
            }
        }
        if (callers.isEmpty()) throw new NoSuchMethodException("yb.f message handler missing");
        CallerScope scope = new CallerScope();
        HookGroup group = new HookGroup(module, diagnostics, Config.DRAG);
        try {
            group.hook(gate, chain -> {
                if (!scope.active() || !valid(chain.getArg(0)) || !valid(chain.getArg(1))) {
                    if (scope.active()) diagnostics.hit("drag_rejected:" + chain.getArg(0),
                            "invalid activity=" + chain.getArg(1));
                    return chain.proceed();
                }
                diagnostics.hit("drag_gate:" + chain.getArg(0), "gate passed");
                return true;
            });
            group.hook(lookup, chain -> {
                Object original = chain.proceed();
                if (!scope.active() || !valid(chain.getArg(0))) return original;
                Object list = chain.getArg(1);
                if (!"EnhancedDragWhiteListV2".equals(list)) return original;
                Object profile = profiles.allowPackage(original, (String) chain.getArg(0));
                diagnostics.hit("drag_profile:" + chain.getArg(0), "package policy"
                        + " generic=" + (original == null));
                return profile;
            });
            for (Method caller : callers) group.scope(caller, scope, config, Config.DRAG);
            group.hook(capture, chain -> {
                if (!config.enabled(Config.DRAG)) return chain.proceed();
                Message message = (Message) request.get(chain.getThisObject());
                if (message == null) return chain.proceed();
                CharSequence packageValue = message.getData().getCharSequence("widget_package_key");
                String packageName = packageValue == null ? null : packageValue.toString();
                if (!valid(packageName)) return chain.proceed();
                diagnostics.hit("drag_capture:" + packageName, "async request");
                int previous = scope.enter();
                try {
                    return chain.proceed();
                } finally {
                    scope.exit(previous);
                }
            });
            diagnostics.info("drag_capture caller deopt=" + module.deoptimize(capture));
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private static boolean valid(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty() && !"NA".equals(value);
    }
}
