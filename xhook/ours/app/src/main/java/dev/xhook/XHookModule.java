package dev.xhook;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 入口。
 *
 * <p>只处理 X 主进程；通知、分享扩展等副进程不装 hook，省启动时间也少一层风险。</p>
 */
public final class XHookModule extends XposedModule {
    private static volatile String processName = "";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Runtime.attach(this);
        processName = param.getProcessName();
        Runtime.log("module loaded, process=" + processName
                + ", api=" + getApiVersion()
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!"com.twitter.android".equals(param.getPackageName())) {
            return;
        }
        if (!param.isFirstPackage()) {
            return;
        }
        String current = processName;
        if (current != null && !current.isEmpty()
                && !"com.twitter.android".equals(current)) {
            return;
        }
        Runtime.attach(this);
        Bootstrap.start(param.getClassLoader());
    }
}
