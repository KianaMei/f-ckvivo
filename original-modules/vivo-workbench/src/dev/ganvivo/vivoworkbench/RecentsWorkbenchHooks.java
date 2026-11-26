package dev.ganvivo.vivoworkbench;

import android.content.ComponentName;
import android.content.Context;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * com.vivo.recents（QuickstepPlugin.apk）侧外屏最近任务卡片菜单解锁。
 *
 * 注意：QuickstepPlugin.apk 同时会被 com.vivo.recents 进程和 com.bbk.launcher2 进程加载。
 * 本类只 hook QuickstepPlugin 自己的类，绝不修改桌面其他行为。
 *
 * 两条精准放行：
 * 1. VivoTaskMenuView.showAtomicTask(Task) 同步作用域内，让 FoldedStateMonitor.isFoldableOpenState() 返回 true；
 * 2. 同一作用域内，当 SystemUiProxy.isPkgSupportEnterMultitask(pkg, act) 因外屏返回 false 时，
 *    在本地复核原厂黑名单与系统受限模式后返回 true，无需 hook system_server。
 */
final class RecentsWorkbenchHooks {

    private static final String CLS_TASK_MENU = "com.vivo.recents.views.VivoTaskMenuView";
    private static final String CLS_TASK = "com.vivo.recents.shared.model.Task";
    private static final String CLS_FOLD_MONITOR = "com.vivo.recents.fold.FoldedStateMonitor";
    private static final String CLS_SYSTEM_UI_PROXY = "com.vivo.recents.SystemUiProxy";
    private static final String CLS_VERSION_UTILS = "com.vivo.recents.util.VersionUtils";
    private static final String CLS_TASK_UTILS = "com.vivo.recents.recents.utils.TaskUtils";

    private static final ThreadLocal<Integer> SHOW_ATOMIC_DEPTH = new ThreadLocal<Integer>();

    private RecentsWorkbenchHooks() {
    }

    static int install(XposedModule module, ClassLoader cl) {
        Class<?> menuCls = Hooks.findClassOrNull(CLS_TASK_MENU, cl);
        boolean foldable = OuterScreenGuard.isFoldableDevice(cl);
        boolean feature = OuterScreenGuard.isFeatureSupported(cl);
        Logx.d("Recents: install cl=" + cl + " menuCls=" + (menuCls != null)
                + " foldable=" + foldable + " feature=" + feature);
        if (menuCls == null) {
            Logx.w("Recents: VivoTaskMenuView not visible from this ClassLoader");
            return 0;
        }

        int installed = 0;

        // 1. 标记 showAtomicTask(Task) 同步作用域；原厂拒绝时在本地复核后强制保留菜单项
        try {
            final Class<?> taskCls = Class.forName(CLS_TASK, false, cl);
            final Class<?> taskUtilsCls = Hooks.findClassOrNull(CLS_TASK_UTILS, cl);
            final Class<?> versionCls = Hooks.findClassOrNull(CLS_VERSION_UTILS, cl);
            Method showAtomic = Hooks.findMethod(menuCls, "showAtomicTask", taskCls);
            Hooks.hook(module, showAtomic, chain -> {
                Integer prev = SHOW_ATOMIC_DEPTH.get();
                int next = (prev == null ? 0 : prev.intValue()) + 1;
                SHOW_ATOMIC_DEPTH.set(Integer.valueOf(next));
                Object result;
                try {
                    result = chain.proceed();
                } finally {
                    if (next <= 1) {
                        SHOW_ATOMIC_DEPTH.remove();
                    } else {
                        SHOW_ATOMIC_DEPTH.set(Integer.valueOf(next - 1));
                    }
                }
                if (Boolean.TRUE.equals(result)) {
                    Logx.iLimited("recents.atomic.host", 12,
                            "Recents: showAtomicTask -> true (host allowed)");
                    return result;
                }
                if (!forceVisible(cl, chain, taskCls, taskUtilsCls, versionCls)) {
                    Logx.iLimited("recents.atomic.skip", 12,
                            "Recents: showAtomicTask stays false (guarded)");
                    return result;
                }
                Logx.iLimited("recents.atomic.force", 12,
                        "Recents: showAtomicTask forced -> true (outer)");
                return Boolean.TRUE;
            });
            Logx.d("Recents: hooked VivoTaskMenuView.showAtomicTask");
            installed++;
        } catch (Throwable t) {
            Logx.w("Recents: hook showAtomicTask failed", t);
            return installed;
        }

        // 2. 仅在 showAtomicTask 作用域内，把无参实例方法 FoldedStateMonitor.isFoldableOpenState() 放行
        try {
            Class<?> foldCls = Class.forName(CLS_FOLD_MONITOR, false, cl);
            Method isOpen = Hooks.findMethod(foldCls, "isFoldableOpenState");
            Hooks.hook(module, isOpen, chain -> {
                Object result = chain.proceed();
                if (!Boolean.FALSE.equals(result)) {
                    return result;
                }
                Integer depth = SHOW_ATOMIC_DEPTH.get();
                if (depth == null || depth.intValue() <= 0) {
                    return result;
                }
                Logx.iLimited("recents.fold.open", 8,
                        "Recents: showAtomicTask fold gate -> true (outer)");
                return Boolean.TRUE;
            });
            Logx.d("Recents: hooked FoldedStateMonitor.isFoldableOpenState");
            installed++;
        } catch (Throwable t) {
            Logx.w("Recents: hook FoldedStateMonitor failed", t);
        }

        // 3. 仅在 showAtomicTask 作用域内，外屏本地复核黑名单与系统受限模式后放行 isPkgSupportEnterMultitask
        try {
            Class<?> proxyCls = Class.forName(CLS_SYSTEM_UI_PROXY, false, cl);
            Class<?> foldCls = Class.forName(CLS_FOLD_MONITOR, false, cl);
            Method isPkgSupport = Hooks.findMethod(proxyCls, "isPkgSupportEnterMultitask", Object[].class);
            Hooks.hook(module, isPkgSupport, chain -> {
                Object result = chain.proceed();
                if (!Boolean.FALSE.equals(result)) {
                    return result;
                }
                Integer depth = SHOW_ATOMIC_DEPTH.get();
                if (depth == null || depth.intValue() <= 0) {
                    return result;
                }
                Object proxy = chain.getThisObject();
                Context ctx = null;
                Object c = Hooks.getFieldOrNull(proxy, "mContext");
                if (c instanceof Context) {
                    ctx = (Context) c;
                }
                if (ctx == null) {
                    ctx = OuterScreenGuard.currentApplication();
                }
                // 读取真实折叠状态字段 isMonitorFoldOpen（避免受上面 isFoldableOpenState hook 干扰）
                if (!isActuallyFolded(foldCls, ctx)) {
                    return result;
                }
                if (OuterScreenGuard.isSystemModeBlocked(ctx)) {
                    return result;
                }
                Object[] varargs = (chain.getArgs().size() >= 1 && chain.getArg(0) instanceof Object[])
                        ? (Object[]) chain.getArg(0)
                        : null;
                if (varargs == null || varargs.length < 1 || !(varargs[0] instanceof String)) {
                    return result;
                }
                String pkg = (String) varargs[0];
                String act = (varargs.length >= 2 && varargs[1] instanceof String) ? (String) varargs[1] : null;
                if (!OuterScreenGuard.isPkgAllowedByConstants(cl, pkg, act)) {
                    return result;
                }
                Logx.iLimited("recents.pkg.support", 10,
                        "Recents: isPkgSupportEnterMultitask -> true (outer) pkg=" + pkg);
                return Boolean.TRUE;
            });
            Logx.d("Recents: hooked SystemUiProxy.isPkgSupportEnterMultitask");
            installed++;
        } catch (Throwable t) {
            Logx.w("Recents: hook SystemUiProxy.isPkgSupportEnterMultitask failed", t);
        }

        Logx.i("Recents workbench hooks installed count=" + installed);
        return installed;
    }

    /**
     * 外屏强制保留「原子工作台」菜单项的本地复核：
     * 必须确实处于合盖外屏、未被系统受限模式禁用、宿主 ROM 具备能力，
     * 且该任务不是原厂本来就排除的原子任务/高级多任务/组合任务，命中原厂包黑名单则放弃。
     */
    private static boolean forceVisible(
            ClassLoader cl,
            XposedInterface.Chain chain,
            Class<?> taskCls,
            Class<?> taskUtilsCls,
            Class<?> versionCls) {
        try {
            Context ctx = null;
            Object c = Hooks.getFieldOrNull(chain.getThisObject(), "mContext");
            if (c instanceof Context) {
                ctx = (Context) c;
            }
            if (ctx == null) {
                ctx = OuterScreenGuard.currentApplication();
            }
            if (ctx == null) {
                return false;
            }
            Class<?> foldCls = Hooks.findClassOrNull(CLS_FOLD_MONITOR, cl);
            if (!isActuallyFolded(foldCls, ctx)) {
                return false;
            }
            if (OuterScreenGuard.isSystemModeBlocked(ctx)) {
                return false;
            }
            if (versionCls != null) {
                Object support = Hooks.callStatic(versionCls, "isSupportVivoMultiTasks", new Class<?>[0]);
                if (Boolean.FALSE.equals(support)) {
                    return false;
                }
            }
            Object task = chain.getArgs().isEmpty() ? null : chain.getArg(0);
            if (task == null || !taskCls.isInstance(task)) {
                return false;
            }
            if (taskUtilsCls != null && isExcludedTask(taskUtilsCls, taskCls, task)) {
                return false;
            }
            Object key = Hooks.getFieldOrNull(task, "key");
            if (key == null) {
                return false;
            }
            Object component = Hooks.call(key, "getComponent", new Class<?>[0]);
            if (!(component instanceof ComponentName)) {
                return false;
            }
            String pkg = ((ComponentName) component).getPackageName();
            String act = null;
            try {
                Object top = Hooks.call(task, "getTopActivityName", new Class<?>[0]);
                if (top instanceof String) {
                    act = (String) top;
                }
            } catch (Throwable ignored) {
            }
            return OuterScreenGuard.isPkgAllowedByConstants(cl, pkg, act);
        } catch (Throwable t) {
            Logx.w("Recents: forceVisible failed", t);
            return false;
        }
    }

    private static boolean isExcludedTask(Class<?> taskUtilsCls, Class<?> taskCls, Object task) {
        for (String name : new String[]{"isAtomicTask", "isVivoAdvancedMulti", "isPairTask"}) {
            try {
                Object r = Hooks.callStatic(taskUtilsCls, name, new Class<?>[]{taskCls}, task);
                if (Boolean.TRUE.equals(r)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean isActuallyFolded(Class<?> foldCls, Context ctx) {
        try {
            if (ctx != null) {
                Object monitor = Hooks.callStatic(foldCls, "getInstance",
                        new Class<?>[]{Context.class}, ctx);
                if (monitor != null) {
                    return !Hooks.getBoolField(monitor, "isMonitorFoldOpen", true);
                }
            }
        } catch (Throwable ignored) {
        }
        return OuterScreenGuard.isCurrentDefaultDisplayOuter(ctx);
    }
}
