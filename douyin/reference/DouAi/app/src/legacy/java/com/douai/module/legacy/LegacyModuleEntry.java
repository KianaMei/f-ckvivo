package com.douai.module.legacy;

import android.app.AppComponentFactory;
import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import com.douai.module.BuildConfig;
import com.douai.module.DouAiModule;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedModuleInterface;
import java.util.concurrent.atomic.AtomicBoolean;

/** Classic API82 entry in the dedicated legacy variant only. Never packaged as a modern entry. */
public final class LegacyModuleEntry implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static final String HOST = "com.ss.android.ugc.aweme";
    private static volatile String modulePath;
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile DouAiModule active;
    private static volatile LegacyHookBridge bridge;

    @Override public void initZygote(StartupParam startupParam) {
        if (startupParam != null) modulePath = startupParam.modulePath;
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loaded) {
        if (loaded == null || !HOST.equals(loaded.packageName) || !HOST.equals(loaded.processName)
                || !loaded.isFirstApplication || !STARTED.compareAndSet(false, true)) return;
        String path = LegacyModulePath.resolve(modulePath, LegacyModuleEntry.class.getClassLoader());
        if (path == null || path.isEmpty()) {
            XposedBridge.log("[DouAi.Legacy] Cannot start: neither initZygote.modulePath nor the entry resource identifies a readable module APK");
            STARTED.set(false);
            return;
        }
        modulePath = path;
        ApplicationInfo module = new ApplicationInfo();
        module.packageName = BuildConfig.APPLICATION_ID;
        module.sourceDir = path;
        module.publicSourceDir = path;
        module.targetSdkVersion = 35;
        try {
            LegacyHookBridge adapter = new LegacyHookBridge(module);
            DouAiModule implementation = bindCompatibilityFramework(adapter);
            bridge = adapter; active = implementation;
            implementation.onModuleLoaded(new XposedModuleInterface.ModuleLoadedParam() {
                @Override public boolean isSystemServer() { return false; }
                @Override public String getProcessName() { return loaded.processName; }
            });
            PackageParam ready = new PackageParam(loaded);
            implementation.onPackageLoaded(ready);
            // Classic LoadPackageParam exposes its effective class loader here. This variant does
            // not pretend the framework delivered native API102 PackageReady semantics.
            implementation.onPackageReady(ready);
            XposedBridge.log("[DouAi.Legacy] Compatibility bootstrap dispatched; native API102=false; "
                    + "no deoptimization, remote service or class-initializer capability; "
                    + "disable other modules on the same target before use");
        } catch (Throwable failure) {
            XposedBridge.log("[DouAi.Legacy] Bootstrap failed before feature readiness: " + failure.getClass().getName());
            XposedBridge.log(failure);
            LegacyHookBridge adapter = bridge;
            if (adapter != null) adapter.close();
            active = null; bridge = null;
        }
    }

    /** This bootstrap is the embedded framework adapter, so it owns the API102 binding. */
    @SuppressLint("XposedInternalApi")
    private static DouAiModule bindCompatibilityFramework(LegacyHookBridge adapter) {
        // Only this legacy variant packages the real API102 classes. Modern loaders keep
        // ownership of this internal call and never package or execute this bootstrap.
        DouAiModule implementation = new DouAiModule();
        implementation.attachFramework(adapter, adapter::close);
        return implementation;
    }

    private static final class PackageParam implements XposedModuleInterface.PackageReadyParam {
        private final XC_LoadPackage.LoadPackageParam source;
        PackageParam(XC_LoadPackage.LoadPackageParam source) { this.source = source; }
        @Override public String getPackageName() { return source.packageName; }
        @Override public ApplicationInfo getApplicationInfo() { return source.appInfo; }
        @Override public boolean isFirstPackage() { return source.isFirstApplication; }
        @Override public ClassLoader getDefaultClassLoader() { return source.classLoader; }
        @Override public ClassLoader getClassLoader() { return source.classLoader; }
        @Override public AppComponentFactory getAppComponentFactory() { return null; }
    }
}
