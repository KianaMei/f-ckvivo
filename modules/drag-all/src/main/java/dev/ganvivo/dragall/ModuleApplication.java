package dev.ganvivo.dragall;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class ModuleApplication extends Application {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final long RECONNECT_GUARD_MS = 90_000L;
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
        // Vector only pushes the binder when the daemon chooses to; a reused process
        // that missed the push stays disconnected forever. Cold-restarting ourselves
        // re-triggers the handshake, guarded against loops.
        MAIN.postDelayed(this::ensureConnected, 3000);
    }

    private void ensureConnected() {
        if (service != null) return;
        long now = SystemClock.elapsedRealtime();
        SharedPreferences local = getSharedPreferences("dragall_local", Context.MODE_PRIVATE);
        long last = local.getLong("self_restart_at", -1L);
        if (last > 0 && now >= last && now - last < RECONNECT_GUARD_MS) {
            Log.i("DragAll", "still disconnected; reconnect guard active, not restarting");
            return;
        }
        local.edit().putLong("self_restart_at", now).apply();
        Log.i("DragAll", "disconnected after launch; restarting own process to re-handshake");
        Process.killProcess(Process.myPid());
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
