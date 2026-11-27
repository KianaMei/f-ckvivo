package dev.xhook.timeline;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import dev.xhook.Config;
import dev.xhook.Runtime;
import dev.xhook.filter.TargetCache;

/**
 * 阻止 X 在回到前台时自动刷新首页 / 重选 Home 时自动刷新。
 *
 * <ul>
 *   <li>Home 重选（<=12.43）：将 {@code com.x.home.tabbed} 的 {@code boolean(boolean, boolean)}
 *       第二个参数置 false；12.45+ 官方已移除该返回键超时处理器并改为顶栏双击刷新，
 *       由 {@link FeatureSwitchHook} 关闭 {@code home_timeline_navigation_tap_to_refresh_at_top_enabled}。</li>
 *   <li>回前台自动刷新：在回前台窗口内且已有时间线内容（{@code hasTimeline}）时，
 *       拦截 {@code VIEWPORT_AWARE_AUTO_REFRESH} 与无游标（{@code cursor == null}）的
 *       {@code AUTO_REFRESH}；首次冷启动放行，避免空白信息流。</li>
 * </ul>
 */
public final class RefreshGuard {
    private static final String TYPE_AUTO = "AUTO_REFRESH";
    private static final String TYPE_VIEWPORT = "VIEWPORT_AWARE_AUTO_REFRESH";

    private static final AtomicLong LAST_LOG = new AtomicLong();
    private static volatile boolean installed;
    private static volatile boolean hasTimeline;
    private static volatile long lastResumeAt;
    private static volatile long lastPauseAt;

    private RefreshGuard() {
    }

    public static void install(Context context, TargetCache.Bundle bundle) {
        if (installed) {
            return;
        }
        installed = true;
        trackLifecycle(context);
        if (bundle == null) {
            Runtime.log("RefreshGuard: no targets bundle");
            return;
        }

        int homeCount = 0;
        for (Method method : bundle.homeReselectMethods) {
            if (hookHomeReselect(method)) {
                homeCount++;
            }
        }
        int refreshCount = 0;
        for (Method method : bundle.refreshMethods) {
            if (hookRefresh(method)) {
                refreshCount++;
            }
        }
        Runtime.log("RefreshGuard installed: home=" + homeCount
                + " refresh=" + refreshCount
                + (homeCount == 0 ? " (12.45+ handled via FeatureSwitch)" : ""));
    }

    private static void trackLifecycle(Context context) {
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) {
            return;
        }
        ((Application) app).registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    }

                    @Override
                    public void onActivityStarted(Activity activity) {
                    }

                    @Override
                    public void onActivityResumed(Activity activity) {
                        lastResumeAt = SystemClock.elapsedRealtime();
                    }

                    @Override
                    public void onActivityPaused(Activity activity) {
                        lastPauseAt = SystemClock.elapsedRealtime();
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                    }

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                    }
                });
    }

    private static boolean hookHomeReselect(Method method) {
        if (Runtime.hook(method, chain -> {
            if (!Runtime.bool(Config.KEY_BLOCK_HOME_RESELECT, true)) {
                return chain.proceed();
            }
            try {
                Object[] args = chain.getArgs().toArray();
                if (args.length >= 2) {
                    args[1] = Boolean.FALSE;
                    log("Home reselect guard: " + method.getName() + " -> false");
                    return chain.proceed(args);
                }
            } catch (Throwable throwable) {
                Runtime.error("Home reselect guard failed", throwable);
            }
            return chain.proceed();
        }) == null) {
            return false;
        }
        Runtime.log("Home reselect hook: " + method.getDeclaringClass().getName()
                + "#" + method.getName()
                + " deopt=" + Runtime.deoptimize(method));
        return true;
    }

    private static boolean hookRefresh(Method method) {
        if (Runtime.hook(method, chain -> {
            boolean enabled = Runtime.bool(Config.KEY_BLOCK_REFRESH, false);
            Object type = null;
            Object cursor = null;
            try {
                type = chain.getArg(0);
                cursor = chain.getArg(1);
            } catch (Throwable ignored) {
            }
            String name = type instanceof Enum ? ((Enum<?>) type).name() : "";
            boolean isAuto = TYPE_VIEWPORT.equals(name)
                    || (TYPE_AUTO.equals(name) && cursor == null);
            if (enabled && isAuto && justResumed() && hasTimeline) {
                log("Refresh guard: blocked " + name + " on resume ("
                        + method.getDeclaringClass().getSimpleName() + "#" + method.getName() + ")");
                return null;
            }
            try {
                Object result = chain.proceed();
                hasTimeline = true;
                return result;
            } catch (Throwable throwable) {
                hasTimeline = true;
                throw throwable;
            }
        }) == null) {
            return false;
        }
        Runtime.log("Refresh repository hook: " + method.getDeclaringClass().getName()
                + "#" + method.getName()
                + " deopt=" + Runtime.deoptimize(method));
        return true;
    }

    private static boolean justResumed() {
        long now = SystemClock.elapsedRealtime();
        return lastPauseAt > 0 && now - lastResumeAt < 10_000L;
    }

    private static void log(String message) {
        long now = System.currentTimeMillis();
        long last = LAST_LOG.get();
        if (now - last > 3000L && LAST_LOG.compareAndSet(last, now)) {
            Runtime.log(message);
        }
    }
}
