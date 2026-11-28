package dev.ganvivo.freeformresize;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.provider.Settings;
import android.view.GestureDetector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * OriginOS 7 折叠屏/直板机小窗无极自由调比例核心 Hook（纯 com.android.systemui / WMShell，零 system_server Hook）。
 *
 * 原理（经本机 com.android.wm.shell.jar + services.jar + vivo-services.jar 全链路源码验证）：
 * 1. 普通小窗拖拽默认走等比锁死的 ScaleGestureEventHandler；但同一个 JAR 里内置了支持任意宽高提交（TRANSIT 1510 +
 *    WCT.setBounds + ScaleResizeVeil 遮罩）且保留迷你挂起窗/下拉全屏手势的 VeiledScaleGestureEventHandler；
 * 2. 服务端 VivoWOCImpl.handleTransitionFromShell(1510) -> onFreeFormChangeStart() 不校验 IS_TABLET / inDesktopMode，
 *    也不强拉自定义宽高比，仅要求非桌面模式下 0 < freeformScale < 1.0f；
 * 3. 本模块在非桌面模式的 DragInputListener 构造完成后，将 mScaleCallback 替换为 VeiledScaleGestureEventHandler
 *    （同步重建共享同一个 DragScaleAnimationController 的 mMiniScaleCallback），并在 ScaleHelper 解除 S/M/L 三档吸附，
 *    允许左/右边独立调宽、底边独立调高、左下/右下角独立自由调任意比例，松手后通过 WCT.setBounds 触发 App 真实重新排版。
 *
 * 运行期实时开关（无需重启 SystemUI）：
 *   settings put global ganvivo_freeform_resize_enabled 1   # 开启（默认）
 *   settings put global ganvivo_freeform_resize_enabled 0   # 暂停自由调比例（新打开小窗回退原厂等比）
 */
final class FreeformResizeHooks {

    static final String SETTING_ENABLED = "ganvivo_freeform_resize_enabled";

    private static final String CLS_DRAG_LISTENER =
            "com.android.wm.shell.vivofreeform.caption.drag.DragInputListener";
    private static final String CLS_SCALE_HANDLER =
            "com.android.wm.shell.vivofreeform.caption.drag.scale.ScaleGestureEventHandler";
    private static final String CLS_VEILED_HANDLER =
            "com.android.wm.shell.vivofreeform.caption.drag.scale.VeiledScaleGestureEventHandler";
    private static final String CLS_MINI_HANDLER =
            "com.android.wm.shell.vivofreeform.caption.drag.scale.minifreeform.MiniFreeFormGestureEventHandler";
    private static final String CLS_SCALE_HELPER =
            "com.android.wm.shell.vivofreeform.caption.drag.scale.ScaleHelper";
    private static final String CLS_STATE_CONFIG =
            "com.android.wm.shell.vivofreeform.caption.drag.scale.DragScaleStateConfig";
    private static final String CLS_DEFAULT_SIZE_MGR =
            "com.vivo.smartmultiwindow.utils.FreeformDefaultSizeManage";
    private static final String CLS_FREEFORM_UTIL =
            "android.util.VivoFreeformUtil";

    /** 仅对本模块接管的普通小窗 ScaleHelper 实例生效，不影响桌面模式。 */
    private static final Set<Object> MANAGED_HELPERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    private static final Set<Object> MANAGED_HANDLERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    private static final class MiniCheckContext {
        final int dragRegion;
        final int minW;
        final int minH;
        final int rtW;
        final int rtH;

        MiniCheckContext(int dragRegion, int minW, int minH, int rtW, int rtH) {
            this.dragRegion = dragRegion;
            this.minW = minW;
            this.minH = minH;
            this.rtW = rtW;
            this.rtH = rtH;
        }
    }

    private static final ThreadLocal<MiniCheckContext> MINI_CHECK_CTX = new ThreadLocal<MiniCheckContext>();

    private FreeformResizeHooks() {
    }

    static int install(XposedModule module, ClassLoader cl) {
        Class<?> dragListenerCls = Hooks.findClassOrNull(CLS_DRAG_LISTENER, cl);
        Class<?> scaleHandlerCls = Hooks.findClassOrNull(CLS_SCALE_HANDLER, cl);
        Class<?> veiledHandlerCls = Hooks.findClassOrNull(CLS_VEILED_HANDLER, cl);
        Class<?> miniHandlerCls = Hooks.findClassOrNull(CLS_MINI_HANDLER, cl);
        Class<?> scaleHelperCls = Hooks.findClassOrNull(CLS_SCALE_HELPER, cl);
        Class<?> stateConfigCls = Hooks.findClassOrNull(CLS_STATE_CONFIG, cl);
        Class<?> sizeMgrCls = Hooks.findClassOrNull(CLS_DEFAULT_SIZE_MGR, cl);
        Class<?> utilCls = Hooks.findClassOrNull(CLS_FREEFORM_UTIL, cl);

        if (dragListenerCls == null || veiledHandlerCls == null || scaleHelperCls == null || stateConfigCls == null) {
            Logx.i("WMShell freeform classes missing in " + cl);
            return 0;
        }

        int installed = 0;

        // 1. Hook DragInputListener 构造：普通小窗将 ScaleGestureEventHandler 替换为 VeiledScaleGestureEventHandler
        installed += installDragListenerHook(module, dragListenerCls, scaleHandlerCls,
                veiledHandlerCls, miniHandlerCls, utilCls);

        // 2. Hook ScaleHelper.getBoundaryDimens(int[], Rect, int, boolean)：提供无极自由宽高边界
        installed += installBoundaryHook(module, scaleHelperCls);

        // 3. Hook ScaleHelper.changeBounds(float, float, DragScaleStateConfig, float, float)：独立宽高拖拽
        installed += installChangeBoundsHook(module, scaleHelperCls, stateConfigCls);

        // 4. Hook ScaleHelper.calculateFinalSize(DragScaleStateConfig, Rect, boolean)：取消三档吸附，保留用户拖出的任意比例
        installed += installCalculateFinalSizeHook(module, scaleHelperCls, stateConfigCls);

        // 5. Hook VeiledScaleGestureEventHandler.resizeTask(...)：尺寸有变化时强制触发 WCT.setBounds
        installed += installResizeTaskHook(module, veiledHandlerCls);

        // 6. Hook onDragScaleStateChanged + isLessThanFreeformMiniWindowCritical：
        //    保留四角拖小变迷你挂起窗，防止单边拉扁/拉窄误触发迷你窗
        if (sizeMgrCls != null) {
            installed += installMiniCriticalHook(module, veiledHandlerCls, sizeMgrCls, stateConfigCls);
        }

        return installed;
    }

    private static int installDragListenerHook(
            XposedModule module,
            Class<?> dragListenerCls,
            final Class<?> scaleHandlerCls,
            final Class<?> veiledHandlerCls,
            final Class<?> miniHandlerCls,
            final Class<?> utilCls) {
        try {
            Constructor<?> dragCtor = Hooks.findCtorByParamCount(dragListenerCls, 9);
            final Constructor<?> veiledCtor = Hooks.findCtorByParamCount(veiledHandlerCls, 7);
            final Constructor<?> miniCtor = miniHandlerCls != null ? Hooks.findCtorByParamCount(miniHandlerCls, 6) : null;
            final Method inDesktopMode = utilCls != null ? Hooks.findMethod(utilCls, "inDesktopMode", int.class) : null;

            Hooks.hook(module, dragCtor, chain -> {
                Object res = chain.proceed();
                try {
                    Object listener = chain.getThisObject();
                    Context ctx = (Context) chain.getArgs().get(0);
                    Object mainHandler = chain.getArgs().get(1);
                    Object bgHandler = chain.getArgs().get(2);
                    int displayId = ((Integer) chain.getArgs().get(4)).intValue();
                    Object syncQueue = chain.getArgs().get(7);
                    Object caption = chain.getArgs().get(8);

                    if (!isEnabled(ctx)) {
                        return res;
                    }
                    if (inDesktopMode != null && Boolean.TRUE.equals(Hooks.invoke(inDesktopMode, null, displayId))) {
                        return res;
                    }
                    Object oldCallback = Hooks.getFieldOrNull(listener, "mScaleCallback");
                    if (oldCallback == null || (scaleHandlerCls != null && !scaleHandlerCls.isInstance(oldCallback))) {
                        return res;
                    }
                    Object dragDetector = Hooks.getField(listener, "mDragDetector");
                    Object scaleHelper = Hooks.getField(listener, "mScaleHelper");

                    // 构造 VeiledScaleGestureEventHandler(dragDetector, syncQueue, ctx, caption, scaleHelper, bgHandler, mainHandler)
                    Object veiled = veiledCtor.newInstance(
                            dragDetector, syncQueue, ctx, caption, scaleHelper, bgHandler, mainHandler);
                    Hooks.setField(listener, "mScaleCallback", veiled);

                    // 重建 mMiniScaleCallback 与 mGestureDetector，确保共享新 handler 的 DragScaleAnimationController
                    Object oldMini = Hooks.getFieldOrNull(listener, "mMiniScaleCallback");
                    if (miniCtor != null) {
                        Object animCtrl = Hooks.callNoArg(veiled, "getDragScaleAnimationController");
                        Object newMini = miniCtor.newInstance(
                                dragDetector, syncQueue, ctx, caption, scaleHelper, animCtrl);
                        Hooks.setField(listener, "mMiniScaleCallback", newMini);
                        Object gestureListener = Hooks.getFieldOrNull(newMini, "mGestureListener");
                        if (gestureListener instanceof GestureDetector.OnGestureListener) {
                            Hooks.setField(listener, "mGestureDetector",
                                    new GestureDetector(ctx, (GestureDetector.OnGestureListener) gestureListener));
                        }
                    }

                    // 释放被替换的旧回调
                    try {
                        Hooks.callNoArg(oldCallback, "close");
                    } catch (Throwable ignored) {
                    }
                    if (oldMini != null) {
                        try {
                            Hooks.callNoArg(oldMini, "close");
                        } catch (Throwable ignored) {
                        }
                    }

                    MANAGED_HELPERS.add(scaleHelper);
                    MANAGED_HANDLERS.add(veiled);
                    Logx.iLimited("attach", 10,
                            "Attached free-resize handler on display=" + displayId);
                } catch (Throwable t) {
                    Logx.w("DragInputListener swap failed (kept stock handler)", t);
                }
                return res;
            });
            return 1;
        } catch (Throwable t) {
            Logx.w("hook DragInputListener failed", t);
            return 0;
        }
    }

    private static int installBoundaryHook(XposedModule module, Class<?> scaleHelperCls) {
        try {
            Method m = Hooks.findMethod(scaleHelperCls, "getBoundaryDimens",
                    int[].class, Rect.class, int.class, boolean.class);
            Hooks.hook(module, m, chain -> {
                Object helper = chain.getThisObject();
                if (!MANAGED_HELPERS.contains(helper)) {
                    return chain.proceed();
                }
                int[] out = (int[]) chain.getArgs().get(0);
                Rect startRect = (Rect) chain.getArgs().get(1);
                int dragRegion = ((Integer) chain.getArgs().get(2)).intValue();
                boolean miniLauncher = ((Boolean) chain.getArgs().get(3)).booleanValue();

                int[] limits = computeLimits(helper);
                int minW = limits[0];
                int minH = limits[1];
                int maxW = limits[2];
                int maxH = limits[3];
                int curW = startRect != null && startRect.width() > 0 ? startRect.width() : minW;
                int curH = startRect != null && startRect.height() > 0 ? startRect.height() : minH;

                if (miniLauncher) {
                    out[0] = minW;
                    out[1] = minH;
                    out[2] = maxW;
                    out[3] = maxH;
                } else if (dragRegion == 1 || dragRegion == 5) {
                    out[0] = minW;
                    out[1] = curH;
                    out[2] = maxW;
                    out[3] = curH;
                } else if (dragRegion == 3 || dragRegion == 7) {
                    out[0] = curW;
                    out[1] = minH;
                    out[2] = curW;
                    out[3] = maxH;
                } else {
                    out[0] = minW;
                    out[1] = minH;
                    out[2] = maxW;
                    out[3] = maxH;
                }
                return null;
            });
            return 1;
        } catch (Throwable t) {
            Logx.w("hook getBoundaryDimens failed", t);
            return 0;
        }
    }

    private static int installChangeBoundsHook(
            XposedModule module, Class<?> scaleHelperCls, Class<?> stateConfigCls) {
        try {
            Method m = Hooks.findMethod(scaleHelperCls, "changeBounds",
                    float.class, float.class, stateConfigCls, float.class, float.class);
            Hooks.hook(module, m, chain -> {
                Object helper = chain.getThisObject();
                if (!MANAGED_HELPERS.contains(helper)) {
                    return chain.proceed();
                }
                float dx = ((Float) chain.getArgs().get(0)).floatValue();
                float dy = ((Float) chain.getArgs().get(1)).floatValue();
                Object cfg = chain.getArgs().get(2);

                Rect rt = (Rect) Hooks.callNoArg(cfg, "getDragRtDisplayBounds");
                int dragRegion = ((Integer) Hooks.callNoArg(cfg, "getDragRegion")).intValue();
                int[] b = new int[4];
                Hooks.call(cfg, "getBoundaryDimens", new Class<?>[]{int[].class}, (Object) b);
                int minW = b[0] > 0 ? b[0] : 420;
                int minH = b[1] > 0 ? b[1] : 380;
                int maxW = b[2] > minW ? b[2] : minW + 600;
                int maxH = b[3] > minH ? b[3] : minH + 600;

                int oldL = rt.left, oldT = rt.top, oldR = rt.right, oldB = rt.bottom;
                RectF f = new RectF(rt);

                float hardMinW = Math.max(160f, minW * 0.55f);
                float hardMinH = Math.max(150f, minH * 0.55f);

                if (dragRegion == 1 || dragRegion == 2) {
                    float effDx = dampAxisDelta(f.width(), -dx, minW, maxW) * -1f;
                    f.left += effDx;
                    if (f.right - f.left < hardMinW) {
                        f.left = f.right - hardMinW;
                    }
                } else if (dragRegion == 4 || dragRegion == 5) {
                    float effDx = dampAxisDelta(f.width(), dx, minW, maxW);
                    f.right += effDx;
                    if (f.right - f.left < hardMinW) {
                        f.right = f.left + hardMinW;
                    }
                }

                if (dragRegion == 2 || dragRegion == 3 || dragRegion == 4) {
                    float effDy = dampAxisDelta(f.height(), dy, minH, maxH);
                    f.bottom += effDy;
                    if (f.bottom - f.top < hardMinH) {
                        f.bottom = f.top + hardMinH;
                    }
                } else if (dragRegion == 7) {
                    float effDy = dampAxisDelta(f.height(), -dy, minH, maxH) * -1f;
                    f.top += effDy;
                    if (f.bottom - f.top < hardMinH) {
                        f.top = f.bottom - hardMinH;
                    }
                }

                Rect rounded = new Rect();
                f.round(rounded);
                Hooks.call(cfg, "setDragRtDisplayBounds", new Class<?>[]{Rect.class}, rounded);

                // 同步更新 resizedDisplayBounds / dragRtScale / taskBounds
                applyFreeFinalSize(helper, cfg, new Rect(rounded), false);

                boolean changed = (oldL != rounded.left || oldT != rounded.top
                        || oldR != rounded.right || oldB != rounded.bottom);
                return Boolean.valueOf(changed);
            });
            return 1;
        } catch (Throwable t) {
            Logx.w("hook changeBounds failed", t);
            return 0;
        }
    }

    private static int installCalculateFinalSizeHook(
            XposedModule module, Class<?> scaleHelperCls, Class<?> stateConfigCls) {
        try {
            Method m = Hooks.findMethod(scaleHelperCls, "calculateFinalSize",
                    stateConfigCls, Rect.class, boolean.class);
            Hooks.hook(module, m, chain -> {
                Object helper = chain.getThisObject();
                if (!MANAGED_HELPERS.contains(helper)) {
                    return chain.proceed();
                }
                Object cfg = chain.getArgs().get(0);
                Rect outRect = (Rect) chain.getArgs().get(1);
                boolean isEnd = ((Boolean) chain.getArgs().get(2)).booleanValue();
                int level = applyFreeFinalSize(helper, cfg, outRect, isEnd);
                return Integer.valueOf(level);
            });
            return 1;
        } catch (Throwable t) {
            Logx.w("hook calculateFinalSize failed", t);
            return 0;
        }
    }

    private static int installResizeTaskHook(XposedModule module, Class<?> veiledHandlerCls) {
        try {
            Method m = Hooks.findMethod(veiledHandlerCls, "resizeTask",
                    ActivityManager.RunningTaskInfo.class, Rect.class, Bundle.class, int.class, boolean.class);
            Hooks.hook(module, m, chain -> {
                Object handler = chain.getThisObject();
                if (!MANAGED_HANDLERS.contains(handler)) {
                    return chain.proceed();
                }
                Rect newTaskBounds = (Rect) chain.getArgs().get(1);
                Object reqCfg = Hooks.getFieldOrNull(handler, "mRequestDragConfig");
                if (reqCfg != null && newTaskBounds != null && !newTaskBounds.isEmpty()) {
                    Rect startTask = (Rect) Hooks.callNoArg(reqCfg, "getStartTaskBounds");
                    boolean sizeChanged = startTask == null
                            || Math.abs(startTask.width() - newTaskBounds.width()) >= 2
                            || Math.abs(startTask.height() - newTaskBounds.height()) >= 2;
                    if (sizeChanged) {
                        // 原厂仅在 startWindowLevel != newWindowLevel 时调用 WCT.setBounds；
                        // 置为 -99 确保任意自由比例都会下发真实 taskBounds 让 App 重新排版。
                        Hooks.call(reqCfg, "setStartWindowLevel", new Class<?>[]{int.class}, Integer.valueOf(-99));
                        Logx.iLimited("commit", 20,
                                "Commit freeform resize taskBounds=" + newTaskBounds);
                    }
                }
                return chain.proceed();
            });
            return 1;
        } catch (Throwable t) {
            Logx.w("hook resizeTask failed", t);
            return 0;
        }
    }

    private static int installMiniCriticalHook(
            XposedModule module,
            Class<?> veiledHandlerCls,
            Class<?> sizeMgrCls,
            Class<?> stateConfigCls) {
        int count = 0;
        try {
            Method onStateChanged = Hooks.findMethod(veiledHandlerCls, "onDragScaleStateChanged",
                    stateConfigCls, int.class, int.class, float.class, float.class);
            Hooks.hook(module, onStateChanged, chain -> {
                Object handler = chain.getThisObject();
                if (!MANAGED_HANDLERS.contains(handler)) {
                    return chain.proceed();
                }
                Object cfg = chain.getArgs().get(0);
                int dragRegion = ((Integer) Hooks.callNoArg(cfg, "getDragRegion")).intValue();
                int[] b = new int[4];
                Hooks.call(cfg, "getBoundaryDimens", new Class<?>[]{int[].class}, (Object) b);
                Rect rt = (Rect) Hooks.callNoArg(cfg, "getDragRtDisplayBounds");
                MINI_CHECK_CTX.set(new MiniCheckContext(dragRegion, b[0], b[1], rt.width(), rt.height()));
                try {
                    return chain.proceed();
                } finally {
                    MINI_CHECK_CTX.remove();
                }
            });
            count++;

            Method isMini = Hooks.findMethod(sizeMgrCls, "isLessThanFreeformMiniWindowCritical",
                    int.class, int.class, int.class, boolean.class);
            Hooks.hook(module, isMini, chain -> {
                MiniCheckContext mc = MINI_CHECK_CTX.get();
                if (mc == null) {
                    return chain.proceed();
                }
                // 仅当拖动左下/右下角且宽高同时显著小于最小阈值时才进入迷你挂起小窗，
                // 避免把小窗拉扁（横屏视频）或拉窄（竖屏聊天）时误触发迷你挂起窗。
                boolean isCorner = (mc.dragRegion == 2 || mc.dragRegion == 4);
                boolean hitMini = isCorner
                        && mc.minW > 0 && mc.minH > 0
                        && mc.rtW < Math.round(mc.minW * 0.80f)
                        && mc.rtH < Math.round(mc.minH * 0.80f);
                return Boolean.valueOf(hitMini);
            });
            count++;
        } catch (Throwable t) {
            Logx.w("hook mini critical failed", t);
        }
        return count;
    }

    /**
     * 核心：不吸附三档预设，按用户拖出来的真实宽高生成最终 displayBounds、scale (<1.0f) 与 taskBounds。
     */
    private static int applyFreeFinalSize(Object helper, Object cfg, Rect outDisplay, boolean isEnd) {
        Rect rt = (Rect) Hooks.callNoArg(cfg, "getDragRtDisplayBounds");
        int dragRegion = ((Integer) Hooks.callNoArg(cfg, "getDragRegion")).intValue();
        int[] limits = computeLimits(helper);
        int minW = limits[0];
        int minH = limits[1];
        int maxW = limits[2];
        int maxH = limits[3];
        Rect screen = (Rect) Hooks.callNoArg(helper, "getScreenSize");
        int sw = screen != null && screen.width() > 0 ? screen.width() : 1080;
        int sh = screen != null && screen.height() > 0 ? screen.height() : 2400;
        float density = getDensity(helper);

        int w = clamp(rt.width(), minW, maxW);
        int h = clamp(rt.height(), minH, maxH);

        int left, top, right, bottom;
        top = Math.max(Math.round(28f * density), rt.top);
        if (dragRegion == 1 || dragRegion == 2) {
            right = rt.right;
            left = right - w;
        } else {
            left = rt.left;
            right = left + w;
        }
        bottom = top + h;

        // 保证至少留出 72dp 在屏幕水平范围内，且底边不过分越界
        int visibleMin = Math.round(72f * density);
        if (right < visibleMin) {
            int shift = visibleMin - right;
            left += shift;
            right += shift;
        } else if (left > sw - visibleMin) {
            int shift = left - (sw - visibleMin);
            left -= shift;
            right -= shift;
        }
        int safeBottom = sh - Math.round(16f * density);
        if (bottom > safeBottom) {
            int availH = safeBottom - top;
            if (availH >= minH) {
                bottom = safeBottom;
                h = bottom - top;
            } else {
                int shift = bottom - safeBottom;
                top = Math.max(Math.round(24f * density), top - shift);
                bottom = top + h;
            }
        }

        Rect finalDisplay = new Rect(left, top, right, bottom);
        if (outDisplay != null) {
            outDisplay.set(finalDisplay);
        }
        Hooks.call(cfg, "setResizedDisplayBounds", new Class<?>[]{Rect.class}, finalDisplay);

        // 密度自适应缩放比（严格满足服务端 0 < scale < 1.0f 约束，大窗清晰不放大、小窗不裁切控件）
        int shortSide = Math.min(sw, sh);
        float baseScale = (shortSide >= 1600) ? 0.82f : 0.72f;
        float sizeRatio = Math.max((float) w / (float) sw, (float) h / (float) sh);
        if (sizeRatio > 0.75f) {
            baseScale = Math.min(0.90f, baseScale + (sizeRatio - 0.75f) * 0.4f);
        }
        float minDimScaleCap = Math.min(w, h) / 680.0f;
        float scale = Math.min(baseScale, Math.max(0.45f, minDimScaleCap));
        scale = Math.max(0.45f, Math.min(0.95f, scale));

        int taskW = Math.max(360, Math.round(w / scale));
        int taskH = Math.max(360, Math.round(h / scale));
        Rect taskBounds = new Rect(0, 0, taskW, taskH);

        Hooks.call(cfg, "setDragRtScale", new Class<?>[]{float.class}, Float.valueOf(scale));
        Hooks.call(cfg, "setTaskBounds", new Class<?>[]{Rect.class}, taskBounds);

        // 同步 CaptionState.inLandMode
        try {
            Object caption = Hooks.getFieldOrNull(helper, "mCaption");
            Object state = Hooks.getFieldOrNull(caption, "mState");
            if (state != null) {
                Hooks.setField(state, "inLandMode", Boolean.valueOf(w > h));
            }
        } catch (Throwable ignored) {
        }

        float aspect = (float) w / (float) Math.max(1, h);
        if (isEnd) {
            Logx.iLimited("final", 20,
                    "FreeResize finalDisplay=" + finalDisplay + " taskBounds=" + taskBounds
                            + " scale=" + String.format("%.3f", Float.valueOf(scale))
                            + " aspect=" + String.format("%.2f", Float.valueOf(aspect)));
        }
        return aspect < 0.72f ? 1 : (aspect < 1.15f ? 2 : 3);
    }

    private static float dampAxisDelta(float currentSize, float deltaSize, int minSize, int maxSize) {
        if (deltaSize < 0f && currentSize <= minSize) {
            return deltaSize * 0.25f;
        }
        if (deltaSize > 0f && currentSize >= maxSize) {
            return deltaSize * 0.25f;
        }
        return deltaSize;
    }

    private static int[] computeLimits(Object helper) {
        Rect screen = (Rect) Hooks.callNoArg(helper, "getScreenSize");
        int sw = screen != null && screen.width() > 0 ? screen.width() : 1080;
        int sh = screen != null && screen.height() > 0 ? screen.height() : 2400;
        int shortSide = Math.min(sw, sh);
        float density = getDensity(helper);

        int minW = Math.max(Math.round(170f * density), Math.round(shortSide * 0.22f));
        int minH = Math.max(Math.round(150f * density), Math.round(shortSide * 0.18f));
        int maxW = Math.max(minW + 120, Math.round(sw * 0.95f));
        int maxH = Math.max(minH + 120, Math.round(sh * 0.88f));
        return new int[]{minW, minH, maxW, maxH};
    }

    private static float getDensity(Object helper) {
        try {
            Context ctx = (Context) Hooks.getFieldOrNull(helper, "mContext");
            if (ctx != null && ctx.getResources() != null) {
                return ctx.getResources().getDisplayMetrics().density;
            }
        } catch (Throwable ignored) {
        }
        return 3.0f;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean isEnabled(Context ctx) {
        if (ctx == null) {
            return true;
        }
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), SETTING_ENABLED, 1) != 0;
        } catch (Throwable ignored) {
            return true;
        }
    }
}
