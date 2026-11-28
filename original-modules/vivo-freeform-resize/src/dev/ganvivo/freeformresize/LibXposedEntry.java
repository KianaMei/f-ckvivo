package dev.ganvivo.freeformresize;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 入口。
 * 仅作用于 com.android.systemui（WMShell），绝不请求也不 hook system_server。
 */
public final class LibXposedEntry extends XposedModule {

    static final String VERSION = "0.1.0";

    private static final Set<String> INSTALLED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        String proc = param == null ? "null" : param.getProcessName();
        boolean isSys = param != null && param.isSystemServer();
        Logx.i("VivoFreeformResize " + VERSION + " loaded process=" + proc + " systemServer=" + isSys);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (param == null) {
            return;
        }
        String pkg = param.getPackageName();
        if (!"com.android.systemui".equals(pkg)) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        if (cl == null) {
            cl = param.getDefaultClassLoader();
        }
        if (cl == null) {
            return;
        }
        String key = pkg + "@" + System.identityHashCode(cl);
        if (!INSTALLED.add(key)) {
            return;
        }
        int count = FreeformResizeHooks.install(this, cl);
        if (count == 0) {
            INSTALLED.remove(key);
        }
        Logx.i("VivoFreeformResize ready hooks=" + count);
    }
}
