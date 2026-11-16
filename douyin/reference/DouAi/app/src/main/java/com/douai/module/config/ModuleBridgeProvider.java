package com.douai.module.config;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import com.douai.module.BuildConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/** Explicit module/host IPC. No public caller can read configuration credentials or forge host status. */
public final class ModuleBridgeProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".bridge");
    public static final String HOST = "com.ss.android.ugc.aweme";
    static final Object LOCK = new Object();
    static final String META = "douai_bridge_meta";
    static final String STATUS = "last_host_status";
    @Override public boolean onCreate() { return true; }

    private boolean hostCaller() {
        Context context = getContext(); int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return false;
        if (uid / 100000 != Process.myUid() / 100000) throw new SecurityException("Another user cannot access module settings");
        try {
            ApplicationInfo host = context.getPackageManager().getApplicationInfo(HOST, 0);
            if (host.uid == uid) return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        throw new SecurityException("Only this module or its scoped host can access the bridge");
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        boolean host = hostCaller();
        Context context = getContext();
        SharedPreferences preferences = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        synchronized (LOCK) {
            if ("settings".equals(method)) return settings(context, preferences);
            if ("patch".equals(method)) {
                if (extras == null) throw new IllegalArgumentException("Missing configuration patch");
                Bundle expected = extras.getBundle("expected"), replacement = extras.getBundle("replacement");
                if (expected == null || replacement == null) throw new IllegalArgumentException("Missing snapshots");
                Map<String, Object> before = decode(expected), after = decode(replacement);
                Map<String, ?> actual = preferences.getAll();
                java.util.Set<String> changed = new HashSet<>(before.keySet()); changed.addAll(after.keySet());
                changed.removeIf(key -> Objects.equals(before.get(key), after.get(key)) && before.containsKey(key) == after.containsKey(key));
                for (String key : changed) {
                    if (!Objects.equals(actual.get(key), before.get(key)) || actual.containsKey(key) != before.containsKey(key)) {
                        Bundle result = settings(context, preferences); result.putBoolean("conflict", true); return result;
                    }
                }
                SharedPreferences.Editor edit = preferences.edit();
                for (String key : changed) { if (!after.containsKey(key)) edit.remove(key); else put(edit, key, after.get(key)); }
                if (!edit.commit()) throw new IllegalStateException("Module configuration could not be saved");
                changed(context);
                Bundle result = settings(context, preferences); result.putBoolean("applied", true); return result;
            }
            if ("host_status".equals(method)) {
                if (!host) throw new SecurityException("Only the host can publish its injection receipt");
                if (extras == null || extras.keySet().size() > 2500) throw new IllegalArgumentException("Invalid host status");
                Bundle value = new Bundle(extras); value.putLong("bridge_received_at", System.currentTimeMillis());
                value.putInt("bridge_host_uid", Binder.getCallingUid());
                saveStatus(context, value);
                return new Bundle();
            }
            if ("status".equals(method)) return readStatus(context);
            if ("clear_status".equals(method)) {
                if (host) throw new SecurityException("Only the module clears old receipts");
                context.getSharedPreferences(META, Context.MODE_PRIVATE).edit().remove(STATUS).commit();
                return new Bundle();
            }
            throw new IllegalArgumentException("Unknown bridge operation");
        }
    }
    private static Bundle settings(Context context, SharedPreferences preferences) {
        Bundle result = new Bundle(); result.putBundle("values", encode(preferences.getAll()));
        result.putLong("revision", context.getSharedPreferences(META, Context.MODE_PRIVATE).getLong("revision", 0)); return result;
    }
    static void changed(Context context) {
        SharedPreferences meta = context.getSharedPreferences(META, Context.MODE_PRIVATE);
        synchronized (LOCK) { meta.edit().putLong("revision", meta.getLong("revision", 0) + 1).commit(); }
        context.getContentResolver().notifyChange(URI, null);
    }
    static Bundle encode(Map<String, ?> values) {
        Bundle result = new Bundle();
        if (values.size() > 2500) throw new IllegalArgumentException("Too many configuration entries");
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (key.length() > 160) throw new IllegalArgumentException("Configuration key too long");
            if (value instanceof Boolean) result.putBoolean(key, (Boolean) value);
            else if (value instanceof String) { if (((String) value).length() > 2 * 1024 * 1024) throw new IllegalArgumentException("Configuration value too large"); result.putString(key, (String) value); }
            else if (value instanceof Integer) result.putInt(key, (Integer) value);
            else if (value instanceof Long) result.putLong(key, (Long) value);
            else if (value instanceof Float) result.putFloat(key, (Float) value);
            else if (value instanceof java.util.Set<?>) { java.util.ArrayList<String> strings = new java.util.ArrayList<>(); for (Object item : (java.util.Set<?>) value) { if (!(item instanceof String)) throw new IllegalArgumentException("Non-string set item"); strings.add((String) item); } result.putStringArrayList(key, strings); }
            else throw new IllegalArgumentException("Unsupported configuration value");
        }
        return result;
    }
    static Map<String, Object> decode(Bundle values) {
        Map<String, Object> result = new HashMap<>();
        for (String key : values.keySet()) { Object value = values.get(key); if (value instanceof java.util.ArrayList<?>) value = new HashSet<>((java.util.ArrayList<?>) value); result.put(key, value); }
        encode(result); return result;
    }
    static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof java.util.Set<?>) { java.util.Set<String> strings = new HashSet<>(); for (Object item : (java.util.Set<?>) value) strings.add((String) item); editor.putStringSet(key, strings); }
        else throw new IllegalArgumentException("Unsupported configuration type");
    }
    private static void saveStatus(Context context, Bundle snapshot) {
        // Parcel preserves bounded diagnostic primitive types without JSON key/value loss.
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try { parcel.writeBundle(snapshot); byte[] data = parcel.marshall(); if (data.length > 512 * 1024) throw new IllegalArgumentException("Host diagnostic receipt too large");
            context.getSharedPreferences(META, Context.MODE_PRIVATE).edit().putString(STATUS, android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)).commit();
        } finally { parcel.recycle(); }
    }
    public static Bundle readStatus(Context context) {
        String stored = context.getSharedPreferences(META, Context.MODE_PRIVATE).getString(STATUS, ""); if (stored.isEmpty()) return null;
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try { byte[] bytes = android.util.Base64.decode(stored, android.util.Base64.DEFAULT); parcel.unmarshall(bytes, 0, bytes.length); parcel.setDataPosition(0); return parcel.readBundle(ModuleBridgeProvider.class.getClassLoader()); }
        catch (RuntimeException invalid) { return null; } finally { parcel.recycle(); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
