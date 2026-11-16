package dev.ganvivo.dragall;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class ModuleApplication extends Application {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile XposedService service;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService bound) {
                service = bound;
                Log.i("DragAll", "configuration service connected");
                notifyListeners();
            }

            @Override
            public void onServiceDied(XposedService dead) {
                if (service == dead) {
                    service = null;
                    Log.i("DragAll", "configuration service disconnected");
                    notifyListeners();
                }
            }
        });
    }

    static SharedPreferences preferences() {
        XposedService current = service;
        if (current == null) return null;
        try {
            return current.getRemotePreferences(Config.GROUP);
        } catch (RuntimeException failure) {
            Log.w("DragAll", "Remote preferences unavailable", failure);
            return null;
        }
    }

    static void subscribe(Runnable listener) {
        LISTENERS.addIfAbsent(listener);
        listener.run();
    }

    static void unsubscribe(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        MAIN.post(() -> {
            for (Runnable listener : LISTENERS) listener.run();
        });
    }
}
