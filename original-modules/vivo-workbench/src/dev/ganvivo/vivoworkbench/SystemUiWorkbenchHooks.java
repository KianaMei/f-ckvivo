package dev.ganvivo.vivoworkbench;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedModule;

/**
 * com.android.systemui（WMShell: /system/framework/com.android.wm.shell.jar）侧外屏原子工作台解锁。
 *
 * 核心原则：
 * 1. 绝不伪造 sIsFold=false —— 保持 sIsFold=true 让 VivoMultiTaskHelper.isPhoneState() 返回 true，
 *    自动启用 OriginOS 7 原生直板机竖屏工作台排版；
 * 2. 在切到外屏时补刷 LocationManager.initResource()、外屏物理宽高、UiUtil bounds 与 UiModel.onDisplayChange()；
 * 3. 在 SystemUI 侧放行顶栏/小窗/分屏菜单（VivoMultiTaskProxy + MinimizeThumbnailContainerView）
 *    及桌面快捷组合（VivoMultiTaskController.moveTasksToVivoMultiTask）；
 * 4. 不动合盖强退逻辑（保留工作台活跃时动态合盖的保护），不动右下角手势（不碰 system_server）。
 */
final class SystemUiWorkbenchHooks {

    private static final String CLS_HELPER =
            "com.android.wm.shell.vivomultitask.utils.VivoMultiTaskHelper";
    private static final String CLS_PROXY =
            "com.android.wm.shell.vivomultitask.VivoMultiTaskProxy";
    private static final String CLS_CONTROLLER =
            "com.android.wm.shell.vivomultitask.VivoMultiTaskController";
    private static final String CLS_COORDINATOR =
            "com.android.wm.shell.vivomultitask.VivoMultiTaskCoordinator";
    private static final String CLS_DEVICE_STATE_LISTENER =
            "com.android.wm.shell.vivomultitask.VivoMultiTaskCoordinator$DeviceStateListener";
    private static final String CLS_LOCATION_MANAGER =
            "com.android.wm.shell.vivomultitask.vivomultitaskui.VivoMultiTaskLocationManager";
    private static final String CLS_UI_UTIL =
            "com.android.wm.shell.vivomultitask.vivomultitaskui.VivoMultiTaskUiUtil";
    private static final String CLS_MINIMIZE_THUMB =
            "com.android.wm.shell.vivofreeform.minimize.view.thumbnail.MinimizeThumbnailContainerView";

    /** 仅在 VivoMultiTaskController.moveTasksToVivoMultiTask(Bundle, ArrayList) 内部放行快捷组合外屏检查。 */
    private static final ThreadLocal<Integer> SHORTCUT_ENTER_DEPTH = new ThreadLocal<Integer>();

    private SystemUiWorkbenchHooks() {
    }

    static int install(XposedModule module, ClassLoader cl) {
        Class<?> helperCls = Hooks.findClassOrNull(CLS_HELPER, cl);
        if (helperCls == null) {
            Logx.w("SystemUI: VivoMultiTaskHelper not found in " + cl);
            return 0;
        }
        if (!OuterScreenGuard.isFoldableDevice(cl) || !OuterScreenGuard.isFeatureSupported(cl)) {
            Logx.i("SystemUI: not a foldable device with vivo.software.multitask, skip");
            return 0;
        }

        int installed = 0;
        installed += installProxySupportHook(module, cl, helperCls);
        installed += installMinimizeThumbSupportHook(module, cl, helperCls);
        installed += installShortcutEnterHook(module, cl);
        installed += installLocationManagerHook(module, cl, helperCls);
        installed += installCoordinatorHooks(module, cl, helperCls);
        installed += installCornerRadiusHook(module, cl, helperCls);

        Logx.i("SystemUI workbench hooks installed count=" + installed);
        return installed;
    }

    /** 1. 放行小窗/分屏/顶栏小白条菜单的 isSupportVivoMultiTask() 外屏检查。 */
    private static int installProxySupportHook(XposedModule module, ClassLoader cl, Class<?> helperCls) {
        try {
            Class<?> proxyCls = Class.forName(CLS_PROXY, false, cl);
            Method m = Hooks.findMethod(proxyCls, "isSupportVivoMultiTask");
            Hooks.hook(module, m, chain -> {
                Object result = chain.proceed();
                if (!Boolean.FALSE.equals(result)) {
                    return result;
                }
                Context ctx = extractContext(chain.getThisObject());
                if (!shouldUnlockOuterSupport(cl, helperCls, ctx)) {
                    return result;
                }
                Logx.iLimited("sysui.proxy.support", 8, "SystemUI: VivoMultiTaskProxy.isSupportVivoMultiTask -> true (outer)");
                return Boolean.TRUE;
            });
            Logx.d("SystemUI: hooked VivoMultiTaskProxy.isSupportVivoMultiTask");
            return 1;
        } catch (Throwable t) {
            Logx.w("SystemUI: hook VivoMultiTaskProxy failed", t);
            return 0;
        }
    }

    /** 2. 放行小窗最小化缩略图入口的 isSupportVivoMultiTask()。 */
    private static int installMinimizeThumbSupportHook(XposedModule module, ClassLoader cl, Class<?> helperCls) {
        try {
            Class<?> thumbCls = Class.forName(CLS_MINIMIZE_THUMB, false, cl);
            Method m = Hooks.findMethod(thumbCls, "isSupportVivoMultiTask");
            Hooks.hook(module, m, chain -> {
                Object result = chain.proceed();
                if (!Boolean.FALSE.equals(result)) {
                    return result;
                }
                Context ctx = extractContext(chain.getThisObject());
                if (!shouldUnlockOuterSupport(cl, helperCls, ctx)) {
                    return result;
                }
                Logx.iLimited("sysui.thumb.support", 5, "SystemUI: MinimizeThumbnailContainerView.isSupportVivoMultiTask -> true (outer)");
                return Boolean.TRUE;
            });
            Logx.d("SystemUI: hooked MinimizeThumbnailContainerView.isSupportVivoMultiTask");
            return 1;
        } catch (Throwable t) {
            Logx.w("SystemUI: hook MinimizeThumbnailContainerView failed", t);
            return 0;
        }
    }

    /**
     * 3. 放行桌面快捷方式组合在外屏进入（VivoMultiTaskController L282）：
     * 仅在 moveTasksToVivoMultiTask(Bundle, ArrayList) 同步调用栈内让 getFoldState() 返回 false，
     * 绝不影响 sIsFold 或其他路径。
     */
    private static int installShortcutEnterHook(XposedModule module, ClassLoader cl) {
        int count = 0;
        try {
            Class<?> ctrlCls = Class.forName(CLS_CONTROLLER, false, cl);
            Class<?> coordCls = Class.forName(CLS_COORDINATOR, false, cl);
            Method moveTasks = Hooks.findMethod(ctrlCls, "moveTasksToVivoMultiTask",
                    Bundle.class, ArrayList.class);
            Method getFoldState = Hooks.findMethod(coordCls, "getFoldState");

            Hooks.hook(module, moveTasks, chain -> {
                Integer prev = SHORTCUT_ENTER_DEPTH.get();
                int next = (prev == null ? 0 : prev.intValue()) + 1;
                SHORTCUT_ENTER_DEPTH.set(Integer.valueOf(next));
                try {
                    return chain.proceed();
                } finally {
                    if (next <= 1) {
                        SHORTCUT_ENTER_DEPTH.remove();
                    } else {
                        SHORTCUT_ENTER_DEPTH.set(Integer.valueOf(next - 1));
                    }
                }
            });
            count++;

            Hooks.hook(module, getFoldState, chain -> {
                Integer depth = SHORTCUT_ENTER_DEPTH.get();
                if (depth != null && depth.intValue() > 0) {
                    Object res = chain.proceed();
                    if (Boolean.TRUE.equals(res)) {
                        Logx.iLimited("sysui.shortcut.enter", 5,
                                "SystemUI: shortcut outer-screen gate lifted");
                        return Boolean.FALSE;
                    }
                    return res;
                }
                return chain.proceed();
            });
            count++;
            Logx.d("SystemUI: hooked shortcut outer-screen gate");
        } catch (Throwable t) {
            Logx.w("SystemUI: hook shortcut enter failed", t);
        }
        return count;
    }

    /**
     * 4. 修复 LocationManager.initResource()：
     * 原厂在 IS_FOLD 下强制过滤 physicalHeight/physicalWidth < 2.0（只取内屏）写入 mDisplayWidth/Height。
     * 外屏态（isPhoneState() == true）执行 initResource() 后，立刻将 mDisplayWidth/Height 纠偏为外屏物理宽高。
     */
    private static int installLocationManagerHook(XposedModule module, ClassLoader cl, Class<?> helperCls) {
        try {
            Class<?> lmCls = Class.forName(CLS_LOCATION_MANAGER, false, cl);
            Method initResource = Hooks.findMethod(lmCls, "initResource");
            Hooks.hook(module, initResource, chain -> {
                Object result = chain.proceed();
                if (!Hooks.getStaticBool(helperCls, "IS_FOLD", false)) {
                    return result;
                }
                if (!Hooks.getStaticBool(helperCls, "sIsFold", false)) {
                    return result;
                }
                Object lm = chain.getThisObject();
                Context ctx = extractContext(lm);
                applyOuterDisplaySizeToLocationManager(lm, ctx, null);
                return result;
            });
            Logx.d("SystemUI: hooked VivoMultiTaskLocationManager.initResource");
            return 1;
        } catch (Throwable t) {
            Logx.w("SystemUI: hook LocationManager.initResource failed", t);
            return 0;
        }
    }

    /**
     * 5. 修复 Coordinator 在折叠切换、配置变更及进入工作台前的外屏状态同步：
     * - DeviceStateListener.onDeviceStateChanged：切换屏幕后补刷 initResource()、外屏 bounds、mIsSupportTopBottomUI；
     * - onDisplayConfigurationChanged：外屏不再跳过 uiModel.onDisplayChange()；
     * - moveTasksToVivoMultiTask（7 参主入口）：进入前幂等校验外屏 Phone* 参数与 bounds 已初始化。
     */
    private static int installCoordinatorHooks(XposedModule module, ClassLoader cl, Class<?> helperCls) {
        int count = 0;
        Class<?> coordCls = Hooks.findClassOrNull(CLS_COORDINATOR, cl);
        Class<?> uiUtilCls = Hooks.findClassOrNull(CLS_UI_UTIL, cl);
        if (coordCls == null) {
            return 0;
        }

        // 5a. DeviceStateListener.onDeviceStateChanged(DeviceState)
        try {
            Class<?> listenerCls = Class.forName(CLS_DEVICE_STATE_LISTENER, false, cl);
            Class<?> deviceStateCls = Class.forName("android.hardware.devicestate.DeviceState", false, cl);
            Method onChanged = Hooks.findMethod(listenerCls, "onDeviceStateChanged", deviceStateCls);
            Hooks.hook(module, onChanged, chain -> {
                boolean prevFold = Hooks.getStaticBool(helperCls, "sIsFold", false);
                Object result = chain.proceed();
                boolean nowFold = Hooks.getStaticBool(helperCls, "sIsFold", false);
                Object coordinator = Hooks.getFieldOrNull(chain.getThisObject(), "this$0");
                if (coordinator != null && (prevFold != nowFold || nowFold)) {
                    syncCoordinatorForCurrentScreen(coordinator, helperCls, uiUtilCls, nowFold, null, "deviceState");
                }
                return result;
            });
            Logx.d("SystemUI: hooked DeviceStateListener.onDeviceStateChanged");
            count++;
        } catch (Throwable t) {
            Logx.w("SystemUI: hook DeviceStateListener failed", t);
        }

        // 5b. Coordinator.onDisplayConfigurationChanged(int, Configuration)
        try {
            Method onConfig = Hooks.findMethod(coordCls, "onDisplayConfigurationChanged",
                    int.class, Configuration.class);
            Hooks.hook(module, onConfig, chain -> {
                Object coordinator = chain.getThisObject();
                int displayId = ((Integer) chain.getArg(0)).intValue();
                Configuration config = (Configuration) chain.getArg(1);
                int prevRotation = Hooks.getIntField(coordinator, "mCurrentRotation", -1);

                Object result = chain.proceed();

                if (displayId != 0 || config == null) {
                    return result;
                }
                boolean isFolded = Hooks.getBoolField(coordinator, "mIsFolded", false);
                boolean isTablet = Hooks.getStaticBool(helperCls, "IS_TABLET", false);
                if (isFolded && !isTablet) {
                    Rect bounds = OuterScreenGuard.getBoundsFromConfig(config);
                    syncCoordinatorForCurrentScreen(coordinator, helperCls, uiUtilCls, true, bounds, "displayConfig");
                    Object uiModel = Hooks.getFieldOrNull(coordinator, "mVivoMultiTaskUiModel");
                    if (uiModel != null) {
                        try {
                            Hooks.call(uiModel, "onDisplayChange",
                                    new Class<?>[]{Configuration.class, int.class},
                                    config, Integer.valueOf(prevRotation));
                            Logx.iLimited("sysui.uimodel.display", 6,
                                    "SystemUI: synced UiModel.onDisplayChange on outer bounds=" + bounds);
                        } catch (Throwable t) {
                            Logx.w("SystemUI: UiModel.onDisplayChange failed on outer", t);
                        }
                    }
                }
                return result;
            });
            Logx.d("SystemUI: hooked Coordinator.onDisplayConfigurationChanged");
            count++;
        } catch (Throwable t) {
            Logx.w("SystemUI: hook Coordinator.onDisplayConfigurationChanged failed", t);
        }

        // 5c. Coordinator.moveTasksToVivoMultiTask（7 参主入口进入前兜底确保外屏资源就绪）
        try {
            Method moveMain = Hooks.findMethodByParamCount(coordCls, "moveTasksToVivoMultiTask", 7);
            Hooks.hook(module, moveMain, chain -> {
                Object coordinator = chain.getThisObject();
                Context ctx = extractContext(coordinator);
                boolean isFolded = Hooks.getBoolField(coordinator, "mIsFolded", false);
                if (!isFolded && OuterScreenGuard.isCurrentDefaultDisplayOuter(ctx)) {
                    isFolded = true;
                }
                if (isFolded) {
                    Rect bounds = (Rect) Hooks.getFieldOrNull(coordinator, "mDisplayBounds");
                    if ((bounds == null || bounds.isEmpty()) && ctx != null) {
                        bounds = OuterScreenGuard.getBoundsFromConfig(ctx.getResources().getConfiguration());
                    }
                    ensureOuterReadyBeforeEnter(coordinator, helperCls, uiUtilCls, bounds);
                }
                return chain.proceed();
            });
            Logx.d("SystemUI: hooked Coordinator.moveTasksToVivoMultiTask(7) pre-check");
            count++;
        } catch (Throwable t) {
            Logx.w("SystemUI: hook Coordinator.moveTasksToVivoMultiTask(7) failed", t);
        }

        return count;
    }

    /** 6. 动态修正主卡片圆角（内屏启动时静态字段锁定 74px，外屏直板机应为 18dp）。 */
    private static int installCornerRadiusHook(XposedModule module, ClassLoader cl, Class<?> helperCls) {
        try {
            Class<?> uiUtilCls = Class.forName(CLS_UI_UTIL, false, cl);
            Method getCorner = Hooks.findMethod(uiUtilCls, "getCornerRadiusMainTask");
            Method dp2px = Hooks.findMethod(uiUtilCls, "dp2px", float.class);
            Hooks.hook(module, getCorner, chain -> {
                if (Hooks.getStaticBool(helperCls, "IS_FOLD", false)
                        && Hooks.getStaticBool(helperCls, "sIsFold", false)) {
                    Object px = Hooks.invoke(dp2px, null, Float.valueOf(18.0f));
                    if (px instanceof Integer) {
                        return px;
                    }
                }
                return chain.proceed();
            });
            Logx.d("SystemUI: hooked VivoMultiTaskUiUtil.getCornerRadiusMainTask");
            return 1;
        } catch (Throwable t) {
            Logx.w("SystemUI: hook getCornerRadiusMainTask failed", t);
            return 0;
        }
    }

    private static boolean shouldUnlockOuterSupport(ClassLoader cl, Class<?> helperCls, Context ctx) {
        if (!Hooks.getStaticBool(helperCls, "IS_FOLD", false)) {
            return false;
        }
        Context c = ctx != null ? ctx : OuterScreenGuard.currentApplication();
        boolean isFolded = Hooks.getStaticBool(helperCls, "sIsFold", false);
        if (!isFolded && OuterScreenGuard.isCurrentDefaultDisplayOuter(c)) {
            isFolded = true;
            try {
                Hooks.setStaticField(helperCls, "sIsFold", Boolean.TRUE);
            } catch (Throwable ignored) {
            }
        }
        if (!isFolded) {
            return false;
        }
        return !OuterScreenGuard.isSystemModeBlocked(c);
    }

    private static void syncCoordinatorForCurrentScreen(
            Object coordinator,
            Class<?> helperCls,
            Class<?> uiUtilCls,
            boolean isFolded,
            Rect configBounds,
            String source) {
        try {
            if (Hooks.getStaticBool(helperCls, "sIsFold", false) != isFolded) {
                Hooks.setStaticField(helperCls, "sIsFold", Boolean.valueOf(isFolded));
            }
            Context ctx = extractContext(coordinator);
            boolean isTablet = Hooks.getStaticBool(helperCls, "IS_TABLET", false);

            // 重算 mIsSupportTopBottomUI（原厂仅在构造时算一次）
            boolean supportTopBottom = isFolded;
            if (!supportTopBottom && !isTablet && ctx != null) {
                supportTopBottom = readAutoTopBottom(ctx.getContentResolver());
            }
            try {
                Hooks.setField(coordinator, "mIsSupportTopBottomUI", Boolean.valueOf(supportTopBottom));
            } catch (Throwable ignored) {
            }

            // 同步当前屏幕 bounds 到 UiUtil
            Rect bounds = configBounds;
            if ((bounds == null || bounds.isEmpty()) && ctx != null) {
                bounds = OuterScreenGuard.getBoundsFromConfig(ctx.getResources().getConfiguration());
            }
            if ((bounds == null || bounds.isEmpty())) {
                Object cur = Hooks.getFieldOrNull(coordinator, "mDisplayBounds");
                if (cur instanceof Rect && !((Rect) cur).isEmpty()) {
                    bounds = new Rect((Rect) cur);
                }
            }
            if (bounds != null && !bounds.isEmpty() && uiUtilCls != null) {
                try {
                    Hooks.callStatic(uiUtilCls, "setVivoMultiTaskBound",
                            new Class<?>[]{Rect.class}, new Rect(bounds));
                } catch (Throwable ignored) {
                }
            }

            // 刷新 LocationManager：重新按最新 isPhoneState() 加载 Phone* 或 Fold* 参数，并纠偏屏幕宽高
            Object lm = Hooks.getFieldOrNull(coordinator, "mLocationManager");
            if (lm != null) {
                Hooks.call(lm, "initResource", new Class<?>[0]);
                if (isFolded) {
                    applyOuterDisplaySizeToLocationManager(lm, ctx, bounds);
                }
            }

            // 同步可用区域给 Core/WMS
            try {
                Hooks.call(coordinator, "updateMaxAvailableIfNeeded",
                        new Class<?>[]{boolean.class}, Boolean.TRUE);
            } catch (Throwable t) {
                Logx.w("SystemUI: updateMaxAvailableIfNeeded failed (" + source + ")", t);
            }

            Logx.iLimited("sysui.sync." + source + "." + isFolded, 8,
                    "SystemUI: synced screen source=" + source
                            + " isFolded=" + isFolded
                            + " supportTopBottom=" + supportTopBottom
                            + " bounds=" + bounds);
        } catch (Throwable t) {
            Logx.w("SystemUI: syncCoordinatorForCurrentScreen failed (" + source + ")", t);
        }
    }

    private static void ensureOuterReadyBeforeEnter(
            Object coordinator,
            Class<?> helperCls,
            Class<?> uiUtilCls,
            Rect bounds) {
        try {
            Hooks.setStaticField(helperCls, "sIsFold", Boolean.TRUE);
            Object lm = Hooks.getFieldOrNull(coordinator, "mLocationManager");
            boolean needReinit = false;
            if (lm != null) {
                int phoneBasis = Hooks.getIntField(lm, "mVerticalPhoneItemWidthBasisPort", 0);
                int curW = Hooks.getIntField(lm, "mDisplayWidth", 0);
                int curH = Hooks.getIntField(lm, "mDisplayHeight", 0);
                boolean sizeLooksInner = (curW > 0 && curH > 0)
                        && ((double) Math.max(curW, curH) / (double) Math.min(curW, curH) < 1.95d);
                if (phoneBasis <= 0 || sizeLooksInner) {
                    needReinit = true;
                }
            }
            if (needReinit) {
                syncCoordinatorForCurrentScreen(coordinator, helperCls, uiUtilCls, true, bounds, "preEnter");
            } else if (bounds != null && !bounds.isEmpty() && uiUtilCls != null) {
                Hooks.callStatic(uiUtilCls, "setVivoMultiTaskBound",
                        new Class<?>[]{Rect.class}, new Rect(bounds));
            }
            Logx.iLimited("sysui.preenter", 10,
                    "SystemUI: preEnter outer ready needReinit=" + needReinit + " bounds=" + bounds);
        } catch (Throwable t) {
            Logx.w("SystemUI: ensureOuterReadyBeforeEnter failed", t);
        }
    }

    private static void applyOuterDisplaySizeToLocationManager(Object lm, Context ctx, Rect bounds) {
        try {
            int w = 0;
            int h = 0;
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                double ratio = (double) Math.max(bounds.width(), bounds.height())
                        / (double) Math.min(bounds.width(), bounds.height());
                if (ratio >= 1.95d) {
                    w = Math.min(bounds.width(), bounds.height());
                    h = Math.max(bounds.width(), bounds.height());
                }
            }
            if (w <= 0 || h <= 0) {
                int[] phys = OuterScreenGuard.findOuterPhysicalSize(ctx);
                if (phys != null && phys[0] > 0 && phys[1] > 0) {
                    w = Math.min(phys[0], phys[1]);
                    h = Math.max(phys[0], phys[1]);
                }
            }
            if (w > 0 && h > 0) {
                Hooks.setField(lm, "mDisplayWidth", Integer.valueOf(w));
                Hooks.setField(lm, "mDisplayHeight", Integer.valueOf(h));
                Logx.iLimited("sysui.lm.size", 6,
                        "SystemUI: LocationManager outer display size -> " + w + "x" + h);
            }
        } catch (Throwable t) {
            Logx.w("SystemUI: applyOuterDisplaySizeToLocationManager failed", t);
        }
    }

    private static Context extractContext(Object target) {
        Object c = Hooks.getFieldOrNull(target, "mContext");
        if (c instanceof Context) {
            return (Context) c;
        }
        return OuterScreenGuard.currentApplication();
    }

    private static boolean readAutoTopBottom(ContentResolver cr) {
        try {
            Object v = Hooks.callStatic(Settings.Secure.class, "getIntForUser",
                    new Class<?>[]{ContentResolver.class, String.class, int.class, int.class},
                    cr, "vivo_multi_task_auto_top_bottom", Integer.valueOf(1), Integer.valueOf(-2));
            if (v instanceof Integer) {
                return ((Integer) v).intValue() == 1;
            }
        } catch (Throwable ignored) {
        }
        return Settings.Secure.getInt(cr, "vivo_multi_task_auto_top_bottom", 1) == 1;
    }
}
