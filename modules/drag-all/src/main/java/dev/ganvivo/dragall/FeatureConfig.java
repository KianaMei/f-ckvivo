package dev.ganvivo.dragall;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.libxposed.api.XposedModule;

final class FeatureConfig {
    static final long ANY_VERSION = -1L;
    private final XposedModule module;
    private final Diagnostics diagnostics;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (preferences, key) -> refresh();
    private SharedPreferences preferences;
    private volatile int enabledMask;
    private volatile int blurStrength = Config.DEFAULT_BLUR;
    private volatile int tintOpacity = Config.DEFAULT_TINT;
    private volatile Map<String, Long> nativeClients = Map.of();

    FeatureConfig(XposedModule module, Diagnostics diagnostics) {
        this.module = module;
        this.diagnostics = diagnostics;
        refresh();
    }

    boolean enabled(String feature) {
        for (int index = 0; index < Config.KEYS.length; index++) {
            if (Config.KEYS[index].equals(feature)) return (enabledMask & (1 << index)) != 0;
        }
        return false;
    }

    synchronized void refresh() {
        int nextMask = 0;
        int nextBlur = Config.DEFAULT_BLUR;
        int nextTint = Config.DEFAULT_TINT;
        Map<String, Long> nextClients = Map.of();
        try {
            if (preferences == null) {
                SharedPreferences candidate = module.getRemotePreferences(Config.GROUP);
                candidate.registerOnSharedPreferenceChangeListener(preferenceListener);
                preferences = candidate;
            }
            for (int index = 0; index < Config.KEYS.length; index++) {
                String key = Config.KEYS[index];
                if (preferences.getBoolean(key, Config.defaultValue(key))) nextMask |= 1 << index;
            }
            nextBlur = Math.max(0, Math.min(100, preferences.getInt(Config.BLUR, Config.DEFAULT_BLUR)));
            nextTint = Math.max(0, Math.min(90, preferences.getInt(Config.TINT, Config.DEFAULT_TINT)));
            nextClients = parseClients(preferences.getString(Config.NATIVE_CLIENTS, ""));
        } catch (Throwable failure) {
            nextMask = 0;
            diagnostics.failure("preferences unavailable; features disabled", failure);
        }
        if (enabledMask == nextMask && blurStrength == nextBlur && tintOpacity == nextTint
                && nativeClients.equals(nextClients)) return;
        enabledMask = nextMask;
        blurStrength = nextBlur;
        tintOpacity = nextTint;
        nativeClients = nextClients;
        diagnostics.info("config mask=" + nextMask + " blur=" + nextBlur + " tint=" + nextTint
                + " extraClients=" + nextClients.size());
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable failure) {
                diagnostics.failure("config listener failed", failure);
            }
        }
    }

    Map<String, Long> nativeClients() {
        return nativeClients;
    }

    static Map<String, Long> parseClients(String raw) {
        Map<String, Long> parsed = new HashMap<>();
        if (raw == null || raw.isEmpty()) return parsed;
        for (String line : raw.split("\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.lastIndexOf(':');
            String name = separator < 0 ? line : line.substring(0, separator);
            long code = ANY_VERSION;
            if (separator >= 0) {
                try {
                    code = Long.parseLong(line.substring(separator + 1));
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
            if (name.isEmpty() || !name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) continue;
            parsed.put(name, code);
        }
        return parsed;
    }

    void addListener(Runnable listener) {
        listeners.addIfAbsent(listener);
    }

    int blurStrength() {
        return blurStrength;
    }

    int tintOpacity() {
        return tintOpacity;
    }
}
