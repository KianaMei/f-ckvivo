package dev.ganvivo.dragall;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class DragAllModule extends XposedModule {
    private final Set<ClassLoader> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
    private Diagnostics diagnostics;
    private FeatureConfig config;
    private HostFeatures features;
    private HookGroup bootstrap;
    private boolean accepted;
    private String processPackage;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        accepted = !param.isSystemServer() && Config.acceptsProcess(param.getProcessName());
        if (!accepted) return;
        processPackage = param.getProcessName().split(":", 2)[0];
        diagnostics = new Diagnostics(this);
        diagnostics.info("module=" + BuildConfig.VERSION_NAME + " process=" + param.getProcessName()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " api=" + getApiVersion());
        config = new FeatureConfig(this, diagnostics);
    }

    @Override
    public synchronized void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!accepted || !processPackage.equals(param.getPackageName())) return;
        ClassLoader loader = param.getClassLoader();
        if (loader == null) return;
        candidates.add(loader);
        diagnostics.info("package ready loader=" + loader + " first=" + param.isFirstPackage());
        config.refresh();
        if (features != null) features.installMissing(loader);
        ensureBootstrap();
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getDeclaredMethod("currentApplication").invoke(null);
            if (application instanceof Application) start((Application) application);
        } catch (Throwable failure) {
            diagnostics.failure("Application lookup pending", failure);
        }
    }

    private void ensureBootstrap() {
        if (bootstrap != null) return;
        HookGroup pending = new HookGroup(this, diagnostics, "bootstrap");
        try {
            Method attach = Reflection.method(Application.class, "attach", void.class, Context.class);
            pending.hook(attach, chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof Application) {
                    start((Application) chain.getThisObject());
                }
                return result;
            });
            bootstrap = pending.commit();
        } catch (Throwable failure) {
            pending.close();
            diagnostics.failure("bootstrap installation failed", failure);
        }
    }

    private synchronized void start(Application application) {
        String packageName = application.getPackageName();
        String supportedVersion = Config.supportedVersion(packageName);
        if (supportedVersion == null) return;
        try {
            PackageInfo info = application.getPackageManager().getPackageInfo(packageName, 0);
            if (!supportedVersion.equals(info.versionName)) {
                diagnostics.failure("unsupported host version=" + info.versionName
                        + " code=" + info.getLongVersionCode(), null);
                return;
            }
            candidates.add(application.getClassLoader());
            if (features == null) {
                diagnostics.info("host version=" + info.versionName + " code=" + info.getLongVersionCode()
                        + " apk=" + application.getApplicationInfo().sourceDir);
                features = new HostFeatures(this, application, config, diagnostics);
            }
            for (ClassLoader loader : new ArrayList<>(candidates)) features.installMissing(loader);
        } catch (Throwable failure) {
            diagnostics.failure("host startup failed", failure);
        }
    }
}
