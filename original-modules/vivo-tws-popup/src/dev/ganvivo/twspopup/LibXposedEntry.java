package dev.ganvivo.twspopup;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 入口。
 * 仅作用于 com.android.vivo.tws.vivotws 主进程，不 hook com.android.bluetooth，也不触碰 system_server。
 */
public final class LibXposedEntry extends XposedModule {

    static final String VERSION = "0.1.0";

    private static volatile String sProcessName = "";
    private static final Set<String> INSTALLED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sProcessName = param == null || param.getProcessName() == null ? "" : param.getProcessName();
        boolean isSys = param != null && param.isSystemServer();
        Logx.i("VivoTwsPopup " + VERSION + " loaded process=" + sProcessName + " systemServer=" + isSys);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (param == null) {
            return;
        }
        String pkg = param.getPackageName();
        if (!VivoTwsBridge.PACKAGE.equals(pkg)) {
            return;
        }
        // 只在主进程挂载（:ui 子进程仅负责绘制）
        if (!sProcessName.isEmpty() && !VivoTwsBridge.PACKAGE.equals(sProcessName)) {
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
        try {
            PopupEngine.install(this, cl);
        } catch (Throwable t) {
            INSTALLED.remove(key);
            Logx.w("PopupEngine install failed", t);
        }
    }
}
