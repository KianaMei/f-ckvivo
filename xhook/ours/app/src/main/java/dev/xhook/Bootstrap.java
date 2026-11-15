package dev.xhook;

import android.content.Context;

import org.luckypray.dexkit.DexKitBridge;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.xhook.filter.RenderAdFilter;
import dev.xhook.media.MediaCapture;
import dev.xhook.media.PlayerButtons;
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
                + " spamFilter=" + Runtime.bool(Config.KEY_SPAM_FILTER, false));

        long startedAt = System.currentTimeMillis();
        try {
            MediaCapture.install(classLoader);
        } catch (Throwable throwable) {
            Runtime.error("MediaCapture install failed", throwable);
        }
        try {
            PlayerButtons.install(app);
        } catch (Throwable throwable) {
            Runtime.error("PlayerButtons install failed", throwable);
        }

        DexKitBridge bridge = null;
        try {
            long dexStartedAt = System.currentTimeMillis();
            // DexKit 的 AAR 不会自动装载 native 库，必须显式 loadLibrary。
            // 模块类由 Vector 的 ClassLoader 加载，nativeLibraryDir 指向模块 APK 的 lib 目录。
            try {
                System.loadLibrary("dexkit");
            } catch (Throwable first) {
                Runtime.error("loadLibrary(dexkit) failed, trying module native dir", first);
                android.content.pm.ApplicationInfo moduleInfo =
                        Runtime.api() == null ? null : Runtime.api().getModuleApplicationInfo();
                if (moduleInfo == null || moduleInfo.nativeLibraryDir == null) {
                    throw first;
                }
                System.load(new File(moduleInfo.nativeLibraryDir, "libdexkit.so")
                        .getAbsolutePath());
            }
            bridge = DexKitBridge.create(app.getApplicationInfo().sourceDir);
            Runtime.log("DexKit ready in " + (System.currentTimeMillis() - dexStartedAt) + "ms");
        } catch (Throwable throwable) {
            Runtime.error("DexKit init failed; filter/refresh guards disabled", throwable);
        }
        try {
            // 12.42+：按 promotedMetadata 字段在渲染边界跳过广告。
            RenderAdFilter.install(classLoader, bridge);
        } catch (Throwable throwable) {
            Runtime.error("RenderAdFilter install failed", throwable);
        }
        try {
            // 旧版 entryId 过滤（12.42 已删掉这些字符串，只对老版本 X 有效）。
            // 保留代码但不默认安装，避免每次启动多一次无用的 DexKit 查询。
            if (Runtime.bool("legacy_entryid_filter", false)) {
                dev.xhook.filter.TimelineFilter.install(app, classLoader, bridge);
            }
        } catch (Throwable throwable) {
            Runtime.error("TimelineFilter install failed", throwable);
        }
        try {
            RefreshGuard.install(app, classLoader, bridge);
        } catch (Throwable throwable) {
            Runtime.error("RefreshGuard install failed", throwable);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        Runtime.log("feature install finished in " + (System.currentTimeMillis() - startedAt) + "ms");
    }
}
