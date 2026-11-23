package dev.ganvivo.origintweaks;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 entry.
 *
 * Vector's loader decides by targetApiVersion first: 101+ means MODERN and
 * reads META-INF/xposed/java_init.list; only modules below 101 fall back to
 * assets/xposed_init (LEGACY). This module declares 102, so this class is the
 * entry Vector actually loads. It hands the installation to the already
 * audited hook classes.
 */
public final class LibXposedEntry extends XposedModule {

    private static final Set<String> INSTALLED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final AtomicBoolean SYSTEM_INSTALLED = new AtomicBoolean();
    static final String VERSION = "0.1.6";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            android.util.Log.i("OriginTweaks", "OriginTweaks " + VERSION
                    + " libxposed module loaded process="
                    + (param == null ? "null" : param.getProcessName())
                    + " systemServer=" + (param != null && param.isSystemServer()));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            android.util.Log.i("OriginTweaks", "libxposed onPackageLoaded pkg="
                    + (param == null ? "null" : param.getPackageName())
                    + " first=" + (param != null && param.isFirstPackage()));
        } catch (Throwable ignored) {
        }
        if (param != null) {
            installForPackage(param.getPackageName(), param.getDefaultClassLoader());
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            android.util.Log.i("OriginTweaks", "libxposed onPackageReady pkg="
                    + (param == null ? "null" : param.getPackageName()));
        } catch (Throwable ignored) {
        }
        if (param != null) {
            installForPackage(param.getPackageName(), param.getDefaultClassLoader());
        }
    }

    private void installForPackage(String pkg, ClassLoader cl) {
        try {
            if (pkg == null || cl == null || !Env.isOriginOs()) {
                return;
            }
            if (!INSTALLED.add(pkg)) {
                return;
            }
            Logx.i("libxposed package loaded: " + pkg);
            if ("com.vivo.globaldragdrop".equals(pkg)) {
                DragHooks.install(this, cl);
                SettingsListHooks.install(this, cl);
                ShareHooks.install(this, cl);
                GlassHooks.install(this, cl);
            } else if ("com.bbk.launcher2".equals(pkg)) {
                SplitHooks.install(this, cl);
                RecentsHooks.install(this, cl);
            } else if ("com.vivo.recents".equals(pkg)) {
                RecentsHooks.install(this, cl);
            }
        } catch (Throwable t) {
            Logx.w("libxposed package install failed", t);
        }
    }

    @Override
    public void onSystemServerStarting(
            XposedModuleInterface.SystemServerStartingParam param) {
        try {
            android.util.Log.i("OriginTweaks", "libxposed onSystemServerStarting");
        } catch (Throwable ignored) {
        }
        try {
            if (param == null || !Env.isOriginOs()
                    || !SYSTEM_INSTALLED.compareAndSet(false, true)) {
                return;
            }
            MultiTaskHooks.install(this, param.getClassLoader());
        } catch (Throwable t) {
            Logx.w("libxposed system_server hook failed", t);
        }
    }
}
