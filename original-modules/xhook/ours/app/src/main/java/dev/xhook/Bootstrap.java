package dev.xhook;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.xhook.filter.Discovery;
import dev.xhook.filter.RenderAdFilter;
import dev.xhook.filter.TargetCache;
import dev.xhook.filter.TimelineItemFilter;
import dev.xhook.link.LinkCleaner;
import dev.xhook.media.MediaCapture;
import dev.xhook.media.PlayerButtons;
import dev.xhook.media.PlayerTracker;
import dev.xhook.timeline.FeatureSwitchHook;
import dev.xhook.timeline.RefreshGuard;

/** 在 X 的 Application.attach 之后启动所有功能。 */
public final class Bootstrap {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean FEATURES = new AtomicBoolean(false);

    private Bootstrap() {
    }

    public static void start(ClassLoader classLoader) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> application = Class.forName("android.app.Application", false, classLoader);
            Method attach = application.getDeclaredMethod("attach", Context.class);
            Runtime.hook(attach, chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    Object argument = chain.getArg(0);
                    Context context = self instanceof Context ? (Context) self
                            : argument instanceof Context ? (Context) argument : null;
                    if (context != null) {
                        installFeatures(context, classLoader);
                    }
                } catch (Throwable throwable) {
                    Runtime.error("feature bootstrap failed", throwable);
                }
                return result;
            });
            Runtime.log("Application.attach guard installed");
        } catch (Throwable throwable) {
            Runtime.error("unable to hook Application.attach", throwable);
        }

        // 某些框架在 onPackageReady 时 Application 已经 attach 完；补一次。
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread", false, classLoader);
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            if (application instanceof Context) {
                installFeatures((Context) application, classLoader);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void installFeatures(Context context, ClassLoader classLoader) {
        if (!FEATURES.compareAndSet(false, true)) {
            return;
        }
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Runtime.log("features: android=" + android.os.Build.VERSION.RELEASE
                + " (" + android.os.Build.VERSION.SDK_INT + ")"
                + " videoButton=" + Runtime.bool(Config.KEY_VIDEO_BUTTON, true)
                + " adFilter=" + Runtime.bool(Config.KEY_AD_FILTER, true)
                + " hideUpsells=" + Runtime.bool(Config.KEY_HIDE_UPSELLS, true)
                + " hideGrok=" + Runtime.bool(Config.KEY_HIDE_GROK_PROMPTS, false)
                + " spamFilter=" + Runtime.bool(Config.KEY_SPAM_FILTER, false)
                + " cleanShare=" + Runtime.bool(Config.KEY_CLEAN_SHARE_LINKS, true)
                + " expandTco=" + Runtime.bool(Config.KEY_EXPAND_TCO, true));

        long startedAt = System.currentTimeMillis();

        // 1. 零 DEX 扫描依赖的功能：媒体抓取、播放器追踪、FeatureSwitch 净化、链接清洗
        try {
            MediaCapture.install(classLoader);
        } catch (Throwable throwable) {
            Runtime.error("MediaCapture install failed", throwable);
        }
        try {
            PlayerTracker.install(classLoader);
        } catch (Throwable throwable) {
            Runtime.error("PlayerTracker install failed", throwable);
        }
        try {
            PlayerButtons.install(app);
        } catch (Throwable throwable) {
            Runtime.error("PlayerButtons install failed", throwable);
        }
        try {
            FeatureSwitchHook.install(classLoader);
        } catch (Throwable throwable) {
            Runtime.error("FeatureSwitchHook install failed", throwable);
        }
        try {
            LinkCleaner.install(classLoader);
        } catch (Throwable throwable) {
            Runtime.error("LinkCleaner install failed", throwable);
        }

        // 2. 结构化目标解析（带 APK + 模块双指纹缓存；命中时完全跳过 libdexkit.so 加载）
        TargetCache.Bundle targets = Discovery.resolveAll(app, classLoader);

        try {
            TimelineItemFilter.install(classLoader, targets);
        } catch (Throwable throwable) {
            Runtime.error("TimelineItemFilter install failed", throwable);
        }
        try {
            RenderAdFilter.install(classLoader, targets);
        } catch (Throwable throwable) {
            Runtime.error("RenderAdFilter install failed", throwable);
        }
        try {
            RefreshGuard.install(app, targets);
        } catch (Throwable throwable) {
            Runtime.error("RefreshGuard install failed", throwable);
        }

        Runtime.log("feature install finished in " + (System.currentTimeMillis() - startedAt)
                + "ms (cache=" + (targets != null && targets.fromCache) + ")");
    }
}
