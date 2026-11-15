package dev.ganvivo.fuckdrag;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * FuckDrag: the only thing this module does is force-enable OriginOS super drag
 * for every app. It hooks exactly two gates inside com.vivo.globaldragdrop:
 *
 *   vb.p.h(pkg, activity)        -> true
 *   bb.a.a(pkg, listName)        -> non-null placeholder for yb.f only
 *
 * No system_server, launcher, recents, split-screen, share or glass hooks.
 */
public final class LibXposedEntry extends XposedModule {

    private static final Set<String> INSTALLED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    static final String VERSION = "0.2.0";
    static final String TARGET = "com.vivo.globaldragdrop";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            android.util.Log.i("FuckDrag", "FuckDrag " + VERSION
                    + " libxposed module loaded process="
                    + (param == null ? "null" : param.getProcessName())
                    + " systemServer=" + (param != null && param.isSystemServer()));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (param != null) {
            installForPackage(param.getPackageName(), param.getDefaultClassLoader());
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (param != null) {
            installForPackage(param.getPackageName(), param.getDefaultClassLoader());
        }
    }

    private void installForPackage(String pkg, ClassLoader cl) {
        if (!TARGET.equals(pkg) || cl == null || !INSTALLED.add(pkg)) {
            return;
        }
        try {
            Logx.i("package loaded: " + pkg);
            DragHooks.install(this, cl);
            SettingsListHooks.install(this, cl);
            ShareHooks.install(this, cl);
            IslandGlassHook.install(this, cl);
        } catch (Throwable t) {
            Logx.w("drag install failed", t);
        }
    }
}
