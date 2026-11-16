package dev.ganvivo.dragall;

import android.content.SharedPreferences;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.libxposed.api.XposedModule;

final class FeatureConfig {
    private final XposedModule module;
    private final Diagnostics diagnostics;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (preferences, key) -> refresh();
    private SharedPreferences preferences;
    private volatile int enabledMask;
    private volatile int blurStrength = Config.DEFAULT_BLUR;
    private volatile int tintOpacity = Config.DEFAULT_TINT;

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
        } catch (Throwable failure) {
            nextMask = 0;
            diagnostics.failure("preferences unavailable; features disabled", failure);
        }
        if (enabledMask == nextMask && blurStrength == nextBlur && tintOpacity == nextTint) return;
        enabledMask = nextMask;
        blurStrength = nextBlur;
        tintOpacity = nextTint;
        diagnostics.info("config mask=" + nextMask + " blur=" + nextBlur + " tint=" + nextTint);
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable failure) {
                diagnostics.failure("config listener failed", failure);
            }
        }
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
