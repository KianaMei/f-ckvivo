package io.github.libxposed.api;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import java.util.List;

/**
 * 编译期桩：只保留合并模块用到的回调与参数。
 */
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        String getProcessName();

        boolean isSystemServer();
    }

    interface PackageLoadedParam {
        ApplicationInfo getApplicationInfo();

        ClassLoader getDefaultClassLoader();

        String getPackageName();

        boolean isFirstPackage();
    }

    interface PackageReadyParam extends PackageLoadedParam {
        AppComponentFactory getAppComponentFactory();

        ClassLoader getClassLoader();
    }

    interface SystemServerStartingParam {
        ClassLoader getClassLoader();
    }

    interface HotReloadingParam {
        android.os.Bundle getExtras();

        void setSavedInstanceState(Object obj);
    }

    interface HotReloadedParam extends ModuleLoadedParam {
        android.os.Bundle getExtras();

        List getOldHookHandles();

        Object getSavedInstanceState();
    }

    default void onModuleLoaded(ModuleLoadedParam moduleLoadedParam) {
    }

    default void onPackageLoaded(PackageLoadedParam packageLoadedParam) {
    }

    default void onPackageReady(PackageReadyParam packageReadyParam) {
    }

    default void onSystemServerStarting(SystemServerStartingParam systemServerStartingParam) {
    }

    default boolean onHotReloading(HotReloadingParam hotReloadingParam) {
        return false;
    }

    default void onHotReloaded(HotReloadedParam hotReloadedParam) {
    }
}
