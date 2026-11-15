package dev.ganvivo.origintweaks;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * OriginOS 4.1.3.0 drag whitelist:
 *   yb.f (Handler) -> bb.a.a(pkg, "EnhancedDragFeatureListV2"/"EnhancedDragWhiteListV2")
 *                  -> vb.p.h(pkg, activity)
 * Force both gates open, but only provide a placeholder non-null config object
 * when the caller is yb.f, so other config consumers keep their original behavior.
 */
final class DragHooks {

    private static final AtomicInteger PASS_LOG = new AtomicInteger();

    private DragHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        try {
            Class<?> p = Class.forName("vb.p", false, cl);
            Hooks.hook(module, Hooks.method(p, "h", String.class, String.class), chain -> {
                Object pkgArg = chain.getArg(0);
                Object activityArg = chain.getArg(1);
                if (!(pkgArg instanceof String) || !(activityArg instanceof String)) {
                    return chain.proceed();
                }
                String pkg = (String) pkgArg;
                String activity = (String) activityArg;
                if (pkg.trim().length() == 0 || activity.trim().length() == 0
                        || "NA".equals(activity)) {
                    return chain.proceed();
                }
                if (PASS_LOG.getAndIncrement() < 5) {
                    Logx.i("drag gate pass pkg=" + pkg + " activity=" + activity);
                }
                return Boolean.TRUE;
            });
            Logx.i("drag gate vb.p.h -> true");
        } catch (Throwable t) {
            Logx.w("drag gate hook failed", t);
        }

        try {
            Class<?> a = Class.forName("bb.a", false, cl);
            for (Method m : Hooks.methods(a, "a")) {
                Hooks.hook(module, m, chain -> {
                    Object result = chain.proceed();
                    if (result != null || chain.getArgs().size() < 2) {
                        return result;
                    }
                    Object pkgArg = chain.getArg(0);
                    Object listArg = chain.getArg(1);
                    if (!(pkgArg instanceof String) || !(listArg instanceof String)) {
                        return result;
                    }
                    String list = (String) listArg;
                    if (!"EnhancedDragFeatureListV2".equals(list)
                            && !"EnhancedDragWhiteListV2".equals(list)) {
                        return result;
                    }
                    if (!fromYbF()) {
                        return result;
                    }
                    if (PASS_LOG.getAndIncrement() < 5) {
                        Logx.i("drag config pass pkg=" + pkgArg + " list=" + list);
                    }
                    // yb.f only compares this against null before calling vb.p.h,
                    // and vb.p.h is short-circuited above, so no cast can see it.
                    return Boolean.TRUE;
                });
            }
            Logx.i("drag config bb.a.a placeholder installed");
        } catch (Throwable t) {
            Logx.w("drag config hook failed", t);
        }
    }

    private static boolean fromYbF() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if ("yb.f".equals(e.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
