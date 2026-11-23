package dev.ganvivo.dragall;

import android.os.SystemClock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records the stock vivo whitelist verdict for the app currently being dragged.
 * The vivo drag-settings list is the single source of truth: only apps the user
 * enabled there may use native extraction.
 */
final class GateState {
    private static final long WINDOW_MS = 10_000L;
    private static final ConcurrentHashMap<String, Long> ALLOWED = new ConcurrentHashMap<>();

    private GateState() {}

    static void record(String packageName, boolean allowed) {
        if (packageName == null) return;
        if (allowed) ALLOWED.put(packageName, SystemClock.elapsedRealtime());
        else ALLOWED.remove(packageName);
    }

    static boolean allowed(String packageName) {
        if (packageName == null) return false;
        Long at = ALLOWED.get(packageName);
        return at != null && SystemClock.elapsedRealtime() - at < WINDOW_MS;
    }
}
