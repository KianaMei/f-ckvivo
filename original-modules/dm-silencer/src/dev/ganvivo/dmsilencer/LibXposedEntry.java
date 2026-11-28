package dev.ganvivo.dmsilencer;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 入口。
 * 仅作用于 com.vivo.devicereg 与 com.mobiletools.systemhelper，绝不触碰 system_server。
 */
public final class LibXposedEntry extends XposedModule {

    static final String VERSION = "0.2.0";

    private static final Set<String> INSTALLED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static volatile String sProcessName = "";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        String proc = param == null ? "null" : param.getProcessName();
        sProcessName = proc == null ? "" : proc;
        boolean isSys = param != null && param.isSystemServer();
        Logx.i("DmSilencer " + VERSION + " loaded process=" + proc + " systemServer=" + isSys);
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

        String key = pkg + "@" + System.identityHashCode(cl);
        if (!INSTALLED.add(key)) {
            return;
        }

        int blockerHooks = 0;
        int fenceHooks = 0;
        if ("com.vivo.devicereg".equals(pkg)) {
            blockerHooks = DeviceRegBlocker.install(this, cl);
            fenceHooks = RuntimeFence.install(this, pkg);
        } else if ("com.mobiletools.systemhelper".equals(pkg)) {
            blockerHooks = SystemHelperBlocker.install(this, cl);
            fenceHooks = RuntimeFence.install(this, pkg);
        } else {
            INSTALLED.remove(key);
            return;
        }
        StatsResponder.install(this);
        Logx.recordLoaded(pkg, sProcessName, blockerHooks, fenceHooks);
        Logx.i("DmSilencer ready pkg=" + pkg + " hooks=" + (blockerHooks + fenceHooks));
    }
}
