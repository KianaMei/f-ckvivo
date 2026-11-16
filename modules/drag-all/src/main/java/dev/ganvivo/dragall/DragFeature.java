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
                Object original;
                if (!scope.active() || !valid(chain.getArg(0)) || !valid(chain.getArg(1))) {
                    original = chain.proceed();
                    if (scope.active()) diagnostics.hit("drag_rejected:" + chain.getArg(0),
                            "invalid activity=" + chain.getArg(1));
                    return original;
                }
                original = chain.proceed();
                String gatePackage = (String) chain.getArg(0);
                String gateActivity = String.valueOf(chain.getArg(1));
                if (isPlayerSurface(gatePackage, gateActivity)) {
                    GateState.record(gatePackage, false);
                    diagnostics.hit("drag_gate:" + gatePackage,
                            "activity=" + gateActivity + " player_blocked original=" + original);
                    return false;
                }
                GateState.record(gatePackage, Boolean.TRUE.equals(original));
                diagnostics.hit("drag_gate:" + gatePackage,
                        "activity=" + gateActivity + " gate passed original=" + original);
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

    private static final String[][] PLAYER_SURFACES = {
            {"tv.danmaku.bili", "UnitedBizDetailsActivity"},
            {"tv.danmaku.bili", "VideoDetailActivity"},
            {"tv.danmaku.bili", "StoryVideoActivity"},
            {"tv.danmaku.bili", "BangumiDetailActivity"},
            {"tv.danmaku.bili", "LiveRoomActivity"},
            {"tv.danmaku.bili", "LivePlayer"},
            {"tv.danmaku.bili", "PlayerWebActivity"},
    };

    /** Video player pages must keep their own long-press (2x speed) and scrub gestures. */
    private static boolean isPlayerSurface(String packageName, String activity) {
        for (String[] rule : PLAYER_SURFACES) {
            if (rule[0].equals(packageName) && activity.contains(rule[1])) return true;
        }
        return false;
    }

    private static boolean valid(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty() && !"NA".equals(value);
    }
}
