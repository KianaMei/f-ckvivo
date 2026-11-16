package dev.ganvivo.dragall;

import android.util.Log;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.libxposed.api.XposedModule;

final class Diagnostics {
    private final XposedModule module;
    private final Set<String> warnings = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    Diagnostics(XposedModule module) {
        this.module = module;
    }

    void info(String message) {
        write(Log.INFO, message, null);
    }

    void failure(String key, Throwable failure) {
        if (warnings.add(key)) write(Log.WARN, key, failure);
    }

    void hit(String feature, String message) {
        int count = counters.computeIfAbsent(feature, ignored -> new AtomicInteger()).incrementAndGet();
        if (count <= 3 || count % 200 == 0) info(feature + " hit=" + count + " " + message);
    }

    private void write(int priority, String message, Throwable failure) {
        try {
            if (failure == null) module.log(priority, "DragAll", message);
            else module.log(priority, "DragAll", message, failure);
        } catch (Throwable ignored) {
            Log.println(priority, "DragAll", message);
        }
    }
}
