package dev.ganvivo.origintweaks;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * "后台近期应用" / 后台样式 in com.vivo.recents (QuickstepPlugin.apk).
 *
 * RecentsPreferences.getLayoutMode() hard-codes the inner screen to page/grid:
 *   if (FoldedStateMonitor.isFoldableOpenState() || isPad())
 *       return getPageOrGridLayoutMode();
 * Only the outer screen reads recents_view_mode==0 and returns 128 (堆叠).
 *
 * We keep the real fold state for everything else, and only let the layout
 * mode become 128 on the inner screen when the user selected 堆叠.
 */
final class RecentsHooks {

    private static final int STACK = 128;
    private static final AtomicInteger ATOMIC_MENU_LOG = new AtomicInteger();
    private static final ThreadLocal<Boolean> ATOMIC_TASK_SCOPE = new ThreadLocal<Boolean>();

    private RecentsHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        try {
            final Class<?> prefs = Class.forName(
                    "com.vivo.recents.recents.utils.RecentsPreferences", false, cl);
            final Class<?> folded = Class.forName(
                    "com.vivo.recents.fold.FoldedStateMonitor", false, cl);
            final Class<?> version = Class.forName(
                    "com.vivo.recents.util.VersionUtils", false, cl);

            Hooks.hook(module, Hooks.method(prefs, "getLayoutMode"), chain -> {
                Object result = chain.proceed();
                if (!(result instanceof Integer) || ((Integer) result).intValue() == STACK) {
                    return result;
                }
                if (Hooks.staticBool(version, "FEATURE_ONLY_PAGE_LAYOUT_MODE")) {
                    return result;
                }
                Object self = chain.getThisObject();
                Object app = Hooks.field(self, "mAppContext");
                if (!(app instanceof Context)) {
                    return result;
                }
                Object monitor = Hooks.callStatic(folded, "getInstance", app);
                Object open = Hooks.call(monitor, "isFoldableOpenState");
                if (!Boolean.TRUE.equals(open)) {
                    return result;
                }
                Object settings = Hooks.call(self, "getSettingsLayoutValue");
                if (!(settings instanceof Integer) || ((Integer) settings).intValue() != 0) {
                    return result;
                }
                Logx.i("inner recents layout -> 堆叠 (128)");
                return Integer.valueOf(STACK);
            });
            Logx.i("recents layout hook installed");
        } catch (Throwable t) {
            Logx.w("recents layout hook failed", t);
        }

        // The recents task menu hides 原子工作台 on the cover screen:
        //   VivoTaskMenuView.showAtomicTask():
        //     if (VersionUtils.isFold() && !FoldedStateMonitor.isFoldableOpenState()) return false;
        // Only lie to that one call site; all other recents layout logic keeps
        // the real fold state.
        try {
            final Class<?> folded = Class.forName(
                    "com.vivo.recents.fold.FoldedStateMonitor", false, cl);
            try {
                Class<?> menu = Class.forName(
                        "com.vivo.recents.views.VivoTaskMenuView", false, cl);
                for (Method m : Hooks.methods(menu, "showAtomicTask")) {
                    Hooks.hook(module, m, chain -> {
                        ATOMIC_TASK_SCOPE.set(Boolean.TRUE);
                        try {
                            return chain.proceed();
                        } finally {
                            ATOMIC_TASK_SCOPE.remove();
                        }
                    });
                }
                Logx.i("atomic task scope hook installed");
            } catch (Throwable t) {
                Logx.w("atomic task scope hook failed", t);
            }
            Hooks.hook(module, Hooks.method(folded, "isFoldableOpenState"), chain -> {
                Object result = chain.proceed();
                if (Boolean.TRUE.equals(result)) {
                    return result;
                }
                if (!Boolean.TRUE.equals(ATOMIC_TASK_SCOPE.get()) && !fromAtomicTaskMenu()) {
                    return result;
                }
                if (ATOMIC_MENU_LOG.getAndIncrement() < 5) {
                    Logx.i("recents atomic-task menu unlocked on outer");
                }
                return Boolean.TRUE;
            });
            Logx.i("recents atomic-task menu hook installed");
        } catch (Throwable t) {
            Logx.w("recents atomic-task menu hook failed", t);
        }

        try {
            Class<?> holder = Class.forName(
                    "com.vivo.recents.settings.adapter.RecentsLayoutModeViewHolder", false, cl);
            for (Constructor<?> ctor : Hooks.constructors(holder)) {
                Hooks.hook(module, ctor, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (chain.getArgs().isEmpty()
                                || !(chain.getArg(0) instanceof View)) {
                            return result;
                        }
                        View view = (View) chain.getArg(0);
                        Context context = view.getContext();
                        int id = context.getResources().getIdentifier(
                                "vivo_recent_view_mode_phone_description",
                                "string", "com.vivo.recents");
                        if (id == 0) {
                            return result;
                        }
                        Object value = Hooks.field(chain.getThisObject(), "mDescriptionTextView");
                        if (value instanceof TextView) {
                            ((TextView) value).setText(context.getString(id));
                        }
                    } catch (Throwable t) {
                        Logx.w("recents description hook", t);
                    }
                    return result;
                });
            }
            Logx.i("recents description hook installed");
        } catch (Throwable t) {
            Logx.w("recents description hook failed", t);
        }
    }

    private static boolean fromAtomicTaskMenu() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("com.vivo.recents.views.VivoTaskMenuView".equals(frame.getClassName())
                    && "showAtomicTask".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }
}
