package dev.ganvivo.vivoworkbench;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 入口。
 *
 * 仅作用于 com.android.systemui、com.vivo.recents 与 com.bbk.launcher2（用于其内嵌加载的 QuickstepPlugin）。
 * 绝不请求也不 hook system_server。
 *
 * 按 (packageName, ClassLoader 身份) 去重，且在 onPackageReady 使用最终 getClassLoader() 安装，
 * 确保宿主的自定义/插件 ClassLoader 都能准确挂载。
 */
public final class LibXposedEntry extends XposedModule {

    static final String VERSION = "0.2.0";

    private static final Set<String> INSTALLED_KEYS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        String proc = param == null ? "null" : param.getProcessName();
        boolean isSys = param != null && param.isSystemServer();
        Logx.i("VivoWorkbench " + VERSION + " loaded process=" + proc + " systemServer=" + isSys);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (param == null) {
            return;
        }
        String pkg = param.getPackageName();
        ClassLoader cl = param.getClassLoader();
        if (cl == null) {
            cl = param.getDefaultClassLoader();
        }
        if (pkg == null || cl == null) {
            return;
        }
        if (!OuterScreenGuard.isOriginOs()) {
            return;
        }

        String key = pkg + "@" + System.identityHashCode(cl);
        if (INSTALLED_KEYS.contains(key)) {
            return;
        }

        Logx.d("onPackageReady pkg=" + pkg + " cl=" + cl.getClass().getSimpleName());

        int installed = 0;
        if ("com.android.systemui".equals(pkg)) {
            installed = SystemUiWorkbenchHooks.install(this, cl);
        } else if ("com.vivo.recents".equals(pkg) || "com.bbk.launcher2".equals(pkg)) {
            // QuickstepPlugin（com.vivo.recents）同时运行在 com.vivo.recents 与 com.bbk.launcher2 进程。
            // RecentsWorkbenchHooks 仅在目标类存在时安装，不会干扰桌面其他类。
            installed = RecentsWorkbenchHooks.install(this, cl);
            if (installed == 0) {
                // Vector 在 launcher 进程里给到的可能是空壳 ClassLoader（DexPathList 无 dex），
                // 此时改走 AOSP 插件框架拆到的真实插件 ClassLoader。
                installed = PluginBridge.install(this, cl);
            }
        }

        if (installed > 0) {
            INSTALLED_KEYS.add(key);
        }
    }
}
