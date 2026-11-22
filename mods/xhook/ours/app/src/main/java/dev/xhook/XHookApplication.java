package dev.xhook;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 进程：连接 Vector/LSPosed 的 LibXposed Service，拿到 daemon binder。
 *
 * <p>只有通过这个 service 的 {@code getRemotePreferences(group)} 写出来的配置，
 * 才和 X 进程里 {@code XposedInterface.getRemotePreferences(group)} 读的是同一组。</p>
 */
public final class XHookApplication extends Application {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> PENDING =
            new CopyOnWriteArrayList<>();

    private static volatile XposedService service;

    public static XposedService service() {
        return service;
    }

    public static SharedPreferences remotePreferences(String group) {
        XposedService current = service;
        if (current == null) {
            return null;
        }
        try {
            return current.getRemotePreferences(group);
        } catch (Throwable throwable) {
            Runtime.error("remotePreferences failed", throwable);
            return null;
        }
    }

    /** service 已连接就立刻执行，否则等 onServiceBind。 */
    public static void whenServiceBound(Runnable action) {
        if (action == null) {
            return;
        }
        if (service != null) {
            action.run();
            return;
        }
        PENDING.add(action);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService bound) {
                service = bound;
                Runtime.log("LibXposed service connected: " + bound.getFrameworkName()
                        + " " + bound.getFrameworkVersion()
                        + " api=" + bound.getApiVersion());
                MAIN.post(() -> {
                    for (Runnable action : PENDING) {
                        try {
                            action.run();
                        } catch (Throwable throwable) {
                            Runtime.error("service-bound callback failed", throwable);
                        }
                    }
                    PENDING.clear();
                });
            }

            @Override
            public void onServiceDied(XposedService dead) {
                if (service == dead) {
                    service = null;
                }
                Runtime.log("LibXposed service disconnected");
            }
        });
    }
}
