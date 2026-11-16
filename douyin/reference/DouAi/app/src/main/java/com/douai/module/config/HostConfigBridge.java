package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Mirrors the module-owned configuration into the host; ordinary hooks keep local, cheap reads. */
public final class HostConfigBridge {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "DouAi-config-bridge"); thread.setDaemon(true); return thread;
    });
    private static volatile HostConfigBridge active;
    private final Context context;
    private final SharedPreferences local;
    // Guarded by ModuleBridgeProvider.LOCK alongside repository writes and local mirrors.
    private final Map<String, Integer> pendingKeys = new HashMap<>();
    private volatile String state = "尚未读取桌面配置";
    private volatile String observationFailure;
    private volatile long revision = -1;
    private HostConfigBridge(Context context) {
        this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
        local = this.context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        try { this.context.getContentResolver().registerContentObserver(ModuleBridgeProvider.URI, false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) { IO.execute(() -> pull(false)); }
                });
        } catch (RuntimeException failure) { observationFailure = failure.getClass().getSimpleName(); }
    }
    public static HostConfigBridge initialize(Context context) {
        if (!ModuleBridgeProvider.HOST.equals(context.getPackageName())) throw new IllegalArgumentException("Host context required");
        HostConfigBridge result = active;
        if (result == null) synchronized (HostConfigBridge.class) {
            result = active;
            if (result == null) { result = new HostConfigBridge(context); active = result; }
        }
        result.pull(true); return result;
    }
    private synchronized void pull(boolean allowSeed) {
        try {
            Bundle response = context.getContentResolver().call(ModuleBridgeProvider.URI, "settings", null, null);
            if (response == null || response.getBundle("values") == null) throw new IllegalStateException("配置提供方未响应");
            Map<String, Object> values = ModuleBridgeProvider.decode(response.getBundle("values"));
            if (allowSeed && response.getLong("revision", 0) == 0 && values.isEmpty() && !local.getAll().isEmpty()) {
                Bundle request = patch(java.util.Collections.emptyMap(), local.getAll());
                response = context.getContentResolver().call(ModuleBridgeProvider.URI, "patch", null, request);
                if (response == null || response.getBundle("values") == null) throw new IllegalStateException("初始配置迁移未响应");
                values = ModuleBridgeProvider.decode(response.getBundle("values"));
            }
            apply(values); revision = response.getLong("revision", -1); state = "已读取桌面配置，修订 " + revision;
        } catch (RuntimeException failure) { state = "桌面配置暂不可达；保留宿主本地配置（" + failure.getClass().getSimpleName() + "）"; }
    }
    private void apply(Map<String, ?> values) {
        synchronized (ModuleBridgeProvider.LOCK) {
            Map<String, Object> merged = new HashMap<>(values);
            Map<String, ?> current = local.getAll();
            for (String key : pendingKeys.keySet()) {
                if (current.containsKey(key)) merged.put(key, current.get(key)); else merged.remove(key);
            }
            if (current.equals(merged)) return;
            SharedPreferences.Editor editor = local.edit().clear();
            merged.forEach((key, value) -> ModuleBridgeProvider.put(editor, key, value));
            if (!editor.commit()) throw new IllegalStateException("宿主配置镜像写入失败");
        }
    }
    private synchronized void publish(Map<String, ?> before, Map<String, ?> after, Set<String> changedKeys) {
        boolean completed = false;
        try {
            Bundle response = context.getContentResolver().call(ModuleBridgeProvider.URI, "patch", null, patch(before, after));
            if (response == null || response.getBundle("values") == null) throw new IllegalStateException("配置保存未收到回执");
            Map<String, Object> received = ModuleBridgeProvider.decode(response.getBundle("values"));
            synchronized (ModuleBridgeProvider.LOCK) {
                finishPending(changedKeys); completed = true;
                apply(received);
            }
            revision = response.getLong("revision", -1);
            state = response.getBoolean("conflict") ? "桌面和宿主同时修改了同一设置；已保留桌面较新值，请重新设置" : "宿主设置已同步到桌面，修订 " + revision;
        } catch (RuntimeException failure) { state = "配置已保存在宿主；同步桌面失败（" + failure.getClass().getSimpleName() + "）"; }
        finally { if (!completed) synchronized (ModuleBridgeProvider.LOCK) { finishPending(changedKeys); } }
    }
    private void finishPending(Set<String> changedKeys) {
        for (String key : changedKeys) {
            int count = pendingKeys.getOrDefault(key, 0);
            if (count <= 1) pendingKeys.remove(key); else pendingKeys.put(key, count - 1);
        }
    }
    private static Bundle patch(Map<String, ?> before, Map<String, ?> after) {
        Bundle request = new Bundle(); request.putBundle("expected", ModuleBridgeProvider.encode(before));
        request.putBundle("replacement", ModuleBridgeProvider.encode(after)); return request;
    }
    static void mutated(Context context, Map<String, ?> before, Map<String, ?> after) {
        if (context == null || before.equals(after)) return;
        if (ModuleBridgeProvider.HOST.equals(context.getPackageName())) {
            HostConfigBridge bridge = active;
            if (bridge != null) synchronized (ModuleBridgeProvider.LOCK) {
                Set<String> changedKeys = new HashSet<>(before.keySet()); changedKeys.addAll(after.keySet());
                changedKeys.removeIf(key -> Objects.equals(before.get(key), after.get(key)) && before.containsKey(key) == after.containsKey(key));
                for (String key : changedKeys) bridge.pendingKeys.merge(key, 1, Integer::sum);
                IO.execute(() -> bridge.publish(before, after, changedKeys));
            }
        } else if (com.douai.module.BuildConfig.APPLICATION_ID.equals(context.getPackageName())) {
            ModuleBridgeProvider.changed(context);
        }
    }
    public static void appendDiagnostics(Bundle bundle) {
        HostConfigBridge bridge = active;
        bundle.putString("configuration_bridge_state", bridge == null ? "尚未初始化桌面配置桥" : bridge.state);
        bundle.putLong("configuration_bridge_revision", bridge == null ? -1 : bridge.revision);
        if (bridge != null && bridge.observationFailure != null)
            bundle.putString("configuration_bridge_observer_failure", bridge.observationFailure);
    }
    public static void publishHostStatus(Context context, Bundle snapshot) {
        if (context == null || snapshot == null) return;
        Bundle copy = new Bundle(snapshot);
        IO.execute(() -> {
            try { context.getContentResolver().call(ModuleBridgeProvider.URI, "host_status", null, copy); }
            catch (RuntimeException ignored) { /* Framework logs and live ordered diagnostics remain available. */ }
        });
    }
}
