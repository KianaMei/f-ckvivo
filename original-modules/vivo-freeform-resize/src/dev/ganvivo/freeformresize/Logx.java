package dev.ganvivo.freeformresize;

import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class Logx {

    static final String TAG = "VivoFreeformResize";

    private static final ConcurrentHashMap<String, AtomicInteger> COUNTERS =
            new ConcurrentHashMap<String, AtomicInteger>();

    private Logx() {
    }

    static void i(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    static void w(String msg, Throwable t) {
        try {
            Log.w(TAG, msg, t);
        } catch (Throwable ignored) {
        }
    }

    static void iLimited(String key, int limit, String msg) {
        AtomicInteger c = COUNTERS.get(key);
        if (c == null) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger existing = COUNTERS.putIfAbsent(key, created);
            c = existing != null ? existing : created;
        }
        int n = c.incrementAndGet();
        if (n <= limit) {
            i(msg + " (#" + n + ")");
        }
    }
}
