package dev.ganvivo.vivoworkbench;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * QuickstepPlugin 插件 ClassLoader 桥。
 *
 * 现象（2026-11-26 真机实测）：Vector 在 com.bbk.launcher2 进程里回调 onPackageReady(pkg=com.vivo.recents) 时，
 * 给到的是 {@code PathClassLoader[DexPathList[[], ...]]} 这类**空壳加载器**（DexPathList 里没有任何 dex），
 * 因此 {@code com.vivo.recents.views.VivoTaskMenuView} 在该 ClassLoader 下不可见，Recents 侧 Hook 全部装不上。
 *
 * 真正的插件类由 AOSP 插件框架 {@code com.android.systemui.shared.plugins.PluginActionManager} 创建，
 * 该框架类本身在 launcher 的 dex 里可见。本类只做一件事：等插件连接完成后，用插件实例自己的 ClassLoader
 * 再调一次 {@link RecentsWorkbenchHooks#install}。
 */
final class PluginBridge {

    private static final String CLS_ACTION_MANAGER = "com.android.systemui.shared.plugins.PluginActionManager";
    private static final String CLS_PLUGIN_INSTANCE = "com.android.systemui.shared.plugins.PluginInstance";
    private static final String TARGET_CLASS = "com.vivo.recents.views.VivoTaskMenuView";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean WATCHING = new AtomicBoolean(false);

    private PluginBridge() {
    }

    static int install(XposedModule module, ClassLoader hostCl) {
        ClassLoader ready = findReadyLoader(hostCl);
        if (ready != null) {
            Logx.d("PluginBridge: resolved plugin ClassLoader eagerly " + ready);
            if (RecentsWorkbenchHooks.install(module, ready) > 0) {
                INSTALLED.set(true);
                return 1;
            }
        }
        startRetryWatcher(module);

        Class<?> managerCls = Hooks.findClassOrNull(CLS_ACTION_MANAGER, hostCl);
        Class<?> instanceCls = Hooks.findClassOrNull(CLS_PLUGIN_INSTANCE, hostCl);
        if (managerCls == null || instanceCls == null) {
            Logx.w("PluginBridge: plugin framework classes not visible from host ClassLoader");
            return 0;
        }

        int hooked = 0;
        try {
            Method onConnected = Hooks.findMethod(managerCls, "onPluginConnected", instanceCls);
            Hooks.hook(module, onConnected, chain -> {
                Object result = chain.proceed();
                try {
                    apply(module, chain.getArg(0));
                } catch (Throwable t) {
                    Logx.w("PluginBridge: apply on connected failed", t);
                }
                return result;
            });
            Logx.d("PluginBridge: hooked PluginActionManager.onPluginConnected");
            hooked++;
        } catch (Throwable t) {
            Logx.w("PluginBridge: hook onPluginConnected failed", t);
        }

        try {
            Method loadPlugin = Hooks.findMethod(instanceCls, "loadPlugin");
            Hooks.hook(module, loadPlugin, chain -> {
                Object result = chain.proceed();
                try {
                    apply(module, chain.getThisObject());
                } catch (Throwable t) {
                    Logx.w("PluginBridge: apply on loadPlugin failed", t);
                }
                return result;
            });
            Logx.d("PluginBridge: hooked PluginInstance.loadPlugin");
            hooked++;
        } catch (Throwable t) {
            Logx.w("PluginBridge: hook loadPlugin failed", t);
        }

        Logx.d("PluginBridge installed count=" + hooked);
        return hooked;
    }

    private static void apply(XposedModule module, Object instance) {
        if (instance == null || INSTALLED.get()) {
            return;
        }
        Object plugin = Hooks.call(instance, "getPlugin", new Class<?>[0]);
        if (plugin != null) {
            ClassLoader pluginCl = plugin.getClass().getClassLoader();
            if (installWith(module, pluginCl)) {
                return;
            }
        }
        Object ctx = null;
        try {
            ctx = Hooks.call(instance, "getPluginContext", new Class<?>[0]);
        } catch (Throwable ignored) {
        }
        if (ctx instanceof android.content.Context) {
            if (installWith(module, ((android.content.Context) ctx).getClassLoader())) {
                return;
            }
        }
        ClassLoader scanned = findReadyLoader(null);
        if (scanned != null) {
            installWith(module, scanned);
        }
    }

    private static boolean installWith(XposedModule module, ClassLoader cl) {
        if (cl == null || INSTALLED.get()) {
            return false;
        }
        if (Hooks.findClassOrNull(TARGET_CLASS, cl) == null) {
            return false;
        }
        Logx.i("PluginBridge: plugin ClassLoader ready " + cl);
        if (RecentsWorkbenchHooks.install(module, cl) > 0) {
            INSTALLED.set(true);
            return true;
        }
        return false;
    }

    /**
     * 插件包（com.vivo.recents）的 LoadedApk 是懒加载的：launcher 启动时还不在 mPackages 里，
     * 首次进入最近任务后才注册。这里用有限次数的后台重试泵等它出现，成功后立即停止。
     */
    private static void startRetryWatcher(XposedModule module) {
        if (WATCHING.compareAndSet(false, true)) {
            Thread t = new Thread(() -> {
                for (int i = 0; i < 120 && !INSTALLED.get(); i++) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    try {
                        ClassLoader cl = findReadyLoader(null);
                        if (cl != null && installWith(module, cl)) {
                            Logx.i("PluginBridge: installed via retry watcher after " + (i + 1) + "s");
                            return;
                        }
                    } catch (Throwable t2) {
                        Logx.w("PluginBridge: retry failed", t2);
                    }
                }
            }, "vivoworkbench-plugin-bridge");
            t.setDaemon(true);
            t.start();
            Logx.d("PluginBridge: retry watcher started");
        }
    }

    /**
     * 尽力早解析：插件在 launcher 进程里以 LoadedApk 形式注册，逐个 ClassLoader 试探目标类。
     */
    static ClassLoader findReadyLoader(ClassLoader hostCl) {
        if (hostCl != null && Hooks.findClassOrNull(TARGET_CLASS, hostCl) != null) {
            return hostCl;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = Hooks.callStatic(at, "currentActivityThread", new Class<?>[0]);
            if (thread != null) {
                for (String field : new String[]{"mPackages", "mResourcePackages"}) {
                    Object map = Hooks.getFieldOrNull(thread, field);
                    if (!(map instanceof Map)) {
                        continue;
                    }
                    for (Object value : ((Map<?, ?>) map).values()) {
                        Object loadedApk = value;
                        if (loadedApk instanceof java.lang.ref.WeakReference) {
                            loadedApk = ((java.lang.ref.WeakReference<?>) loadedApk).get();
                        }
                        if (loadedApk == null) {
                            continue;
                        }
                        Object loader = Hooks.call(loadedApk, "getClassLoader", new Class<?>[0]);
                        if (loader instanceof ClassLoader
                                && Hooks.findClassOrNull(TARGET_CLASS, (ClassLoader) loader) != null) {
                            return (ClassLoader) loader;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null && Hooks.findClassOrNull(TARGET_CLASS, ctx) != null) {
            return ctx;
        }
        return null;
    }
}
