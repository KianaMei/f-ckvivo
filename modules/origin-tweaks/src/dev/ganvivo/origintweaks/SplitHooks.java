package dev.ganvivo.origintweaks;

import android.content.Context;
import android.view.MotionEvent;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * Launcher caps split selection at 4 slots on the inner screen and 2 on the
 * outer screen:
 *
 *   DesktopSplitSelectManager.c(...):
 *     final int maxSlots = isInnerScreen ? 4 : 2;
 *
 * isInnerScreen is LauncherEnvironmentManagerMain.G() -> a3.b.G().
 * We only force it true while the caller is the split-selection manager, so
 * normal launcher layout still sees the real fold state.
 */
final class SplitHooks {

    private static final AtomicInteger GESTURE_LOG = new AtomicInteger();

    private SplitHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        boolean installed = false;
        try {
            Class<?> env = Class.forName(
                    "com.bbk.launcher2.environment.LauncherEnvironmentManagerMain", false, cl);
            hook(module, env);
            installed = true;
        } catch (Throwable t) {
            Logx.w("split env hook failed", t);
        }
        try {
            // FoldStateManager delegate, used by LauncherEnvironmentManagerMain.G().
            Class<?> core = Class.forName("a3.b", false, cl);
            hook(module, core);
            installed = true;
        } catch (Throwable t) {
            Logx.w("split fold-state hook failed", t);
        }
        if (installed) {
            Logx.i("split slot hook installed");
        }
        installMultiTaskGesture(module, cl);
    }

    private static void hook(XposedModule module, Class<?> clazz) {
        Hooks.hook(module, Hooks.method(clazz, "G"), chain -> {
            Object result = chain.proceed();
            if (!Boolean.FALSE.equals(result) || !fromSplitWindow()) {
                return result;
            }
            Logx.i("split slot cap: outer treated as inner");
            return Boolean.TRUE;
        });
    }

    private static void installMultiTaskGesture(XposedModule module, ClassLoader cl) {
        try {
            Class<?> nav = Class.forName(
                    "com.android.quickstep.SysUINavigationMode", false, cl);
            Class<?> folded = Class.forName(
                    "com.android.quickstep.FoldedStateMonitor", false, cl);
            Hooks.hook(module,
                    Hooks.method(nav, "canTriggerMultiTaskGestureAction", MotionEvent.class),
                    chain -> {
                        Object result = chain.proceed();
                        if (Boolean.TRUE.equals(result)) {
                            return result;
                        }
                        if (chain.getArgs().size() < 1
                                || !(chain.getArg(0) instanceof MotionEvent)) {
                            return result;
                        }
                        Object self = chain.getThisObject();
                        if (!Hooks.bool(self, "mVivoMultiTashSlideEnterGestureEnabled")) {
                            return result;
                        }
                        Object helper = Hooks.field(self, "mNavigationTouchRegionHelper");
                        if (helper == null) {
                            return result;
                        }
                        if (!Boolean.TRUE.equals(Hooks.call(helper,
                                "isInMultiTaskGestureArea", chain.getArg(0)))) {
                            return result;
                        }
                        Object context = Hooks.field(self, "mContext");
                        if (!(context instanceof Context)) {
                            return result;
                        }
                        if (!Boolean.TRUE.equals(Hooks.callStatic(folded, "isFoldable"))) {
                            return result;
                        }
                        Object monitor = Hooks.callStatic(folded, "getInstance", context);
                        Object open = Hooks.call(monitor, "isFoldableOpenState");
                        if (Boolean.TRUE.equals(open)) {
                            return result;
                        }
                        // Foldable + cover screen: the original condition
                        // (isFoldableOpenState || isPad || isPhone) is false,
                        // even though the multi-task gesture region is armed.
                        if (GESTURE_LOG.getAndIncrement() < 5) {
                            Logx.i("outer multi-task gesture unlocked");
                        }
                        return Boolean.TRUE;
                    });
            Logx.i("outer multi-task gesture hook installed");
        } catch (Throwable t) {
            Logx.w("outer multi-task gesture hook failed", t);
        }
    }

    private static boolean fromSplitWindow() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if ("com.bbk.launcher2.launcherBasic.splitwindow.a".equals(e.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
