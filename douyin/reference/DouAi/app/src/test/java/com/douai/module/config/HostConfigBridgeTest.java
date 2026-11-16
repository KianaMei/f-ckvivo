package com.douai.module.config;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import com.douai.module.BuildConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowProcess;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.*;

/** Exercises separate module and host stores; using one shared store would hide mirror failures. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = BuildConfig.APPLICATION_ID)
@LooperMode(LooperMode.Mode.PAUSED)
public final class HostConfigBridgeTest {
    private static final int MODULE_UID = 11001;
    private static final int HOST_UID = 11002;
    private Application module;
    private Context host;
    private SharedPreferences desktop;
    private SharedPreferences local;
    private ModuleBridgeProvider provider;
    private HostRoute route;

    @Before public void setup() throws Exception {
        module = RuntimeEnvironment.getApplication();
        ShadowProcess.setUid(MODULE_UID);
        ShadowBinder.setCallingUid(MODULE_UID);
        setActive(null);
        ShadowContentResolver.reset();
        PackageInfo info = new PackageInfo();
        info.packageName = ModuleBridgeProvider.HOST;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = info.packageName;
        info.applicationInfo.uid = HOST_UID;
        Shadow.<ShadowPackageManager>extract(module.getPackageManager()).installPackage(info);
        host = new ContextWrapper(module) {
            @Override public String getPackageName() { return ModuleBridgeProvider.HOST; }
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return module.getSharedPreferences("test_host_" + name, mode);
            }
        };
        desktop = module.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        local = host.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        desktop.edit().clear().commit();
        local.edit().clear().commit();
        module.getSharedPreferences(ModuleBridgeProvider.META, Context.MODE_PRIVATE).edit().clear().commit();
        provider = attached(new ModuleBridgeProvider(), module);
        route = attached(new HostRoute(provider), module);
        ShadowContentResolver.registerProviderInternal(ModuleBridgeProvider.URI.getAuthority(), route);
        assertNotSame(desktop, local);
    }

    @After public void teardown() throws Exception {
        settle();
        for (ContentObserver observer : Shadow.<ShadowContentResolver>extract(module.getContentResolver())
                .getContentObservers(ModuleBridgeProvider.URI)) {
            module.getContentResolver().unregisterContentObserver(observer);
        }
        setActive(null);
        ShadowContentResolver.reset();
        ShadowBinder.reset();
    }

    @Test public void initializeRequiresARealHostContext() {
        assertThrows(IllegalArgumentException.class, () -> HostConfigBridge.initialize(module));
        Bundle diagnostics = diagnostics();
        assertEquals(-1, diagnostics.getLong("configuration_bridge_revision"));
        assertTrue(diagnostics.getString("configuration_bridge_state").contains("尚未初始化"));
    }

    @Test public void initialPullCopiesEverySupportedTypeAndReplacesStaleHostSettings() {
        Map<String, Object> typed = typedValues();
        writeDesktop(typed);
        local.edit().putString("stale_host_only", "old").commit();
        HostConfigBridge.initialize(host);
        assertEquals(typed, local.getAll());
        assertFalse(local.contains("stale_host_only"));
        assertEquals(1, diagnostics().getLong("configuration_bridge_revision"));
    }

    @Test public void firstRunMigratesExistingHostSettingsIntoAnUntouchedDesktopStore() throws Exception {
        write(local, typedValues());
        HostConfigBridge.initialize(host);
        settle();
        assertEquals(typedValues(), desktop.getAll());
        assertEquals(desktop.getAll(), local.getAll());
        assertEquals(1, diagnostics().getLong("configuration_bridge_revision"));
    }

    @Test public void aPreviouslyClearedDesktopStoreDoesNotReseedOldHostSettings() {
        ModuleBridgeProvider.changed(module);
        local.edit().putString("old_key", "stale").commit();
        HostConfigBridge.initialize(host);
        assertTrue(local.getAll().isEmpty());
        assertTrue(desktop.getAll().isEmpty());
        assertEquals(1, diagnostics().getLong("configuration_bridge_revision"));
    }

    @Test public void desktopRepositoryWriteNotifiesAndRefreshesTheHostMirror() throws Exception {
        writeDesktop(Map.of("keep", 7));
        HostConfigBridge.initialize(host);
        new SettingsRepository(module).putString("desktop_text", "桌面已保存");
        settle();
        assertEquals("桌面已保存", local.getString("desktop_text", ""));
        assertEquals(7, local.getInt("keep", 0));
        assertEquals(desktop.getAll(), local.getAll());
        assertEquals(2, diagnostics().getLong("configuration_bridge_revision"));
    }

    @Test public void hostRepositoryWritePublishesToTheDesktopAndPreservesTypes() throws Exception {
        writeDesktop(typedValues());
        HostConfigBridge.initialize(host);
        new SettingsRepository(host).putBoolean("enabled", false);
        new SettingsRepository(host).putString("text", "宿主保存");
        settle();
        assertFalse(desktop.getBoolean("enabled", true));
        assertEquals("宿主保存", desktop.getString("text", ""));
        assertEquals(Set.of("a", "b"), desktop.getStringSet("set", Set.of()));
        assertEquals(9L, desktop.getLong("long", 0));
        assertEquals(desktop.getAll(), local.getAll());
        assertEquals(3, diagnostics().getLong("configuration_bridge_revision"));
    }

    @Test public void observerPullPreservesPendingHostKeysBeforeTheirQueuedPublishes() throws Exception {
        writeDesktop(Map.of("first", false, "second", false, "desktop", "before"));
        HostConfigBridge.initialize(host);
        try (IoGate beforePull = queuedGate()) {
            beforePull.awaitEntered();
            new SettingsRepository(module).putString("desktop", "after");
            idle(); // The observer queues a pull ahead of both host publishes.
            try (IoGate beforePublish = queuedGate()) {
                new SettingsRepository(host).putBoolean("first", true);
                new SettingsRepository(host).putBoolean("second", true);
                beforePull.release();
                beforePublish.awaitEntered();
                // A remote refresh must not temporarily erase unsent UI edits.
                assertTrue(local.getBoolean("first", false));
                assertTrue(local.getBoolean("second", false));
                assertEquals("after", local.getString("desktop", ""));
                assertFalse(desktop.getBoolean("first", true));
                assertFalse(desktop.getBoolean("second", true));
            }
        }
        settle();
        assertTrue(desktop.getBoolean("first", false));
        assertTrue(desktop.getBoolean("second", false));
        assertEquals("after", desktop.getString("desktop", ""));
        assertEquals(desktop.getAll(), local.getAll());
    }

    @Test public void sameKeyConflictKeepsTheNewerDesktopValueAndReconcilesTheHost() throws Exception {
        writeDesktop(Map.of("text", "initial", "keep", 3));
        HostConfigBridge.initialize(host);
        try (IoGate gate = queuedGate()) {
            gate.awaitEntered();
            new SettingsRepository(host).putString("text", "host edit");
            new SettingsRepository(module).putString("text", "new desktop edit");
        }
        drainIo(); // Leave the notification pending so the conflict receipt remains visible.
        assertEquals("new desktop edit", desktop.getString("text", ""));
        assertEquals("new desktop edit", local.getString("text", ""));
        assertEquals(3, local.getInt("keep", 0));
        assertTrue(diagnostics().getString("configuration_bridge_state").contains("同时修改了同一设置"));
        assertEquals(2, diagnostics().getLong("configuration_bridge_revision"));
    }

    @Test public void providerOutagePreservesHostSettingsAndCanRecoverOnInitialize() throws Exception {
        local.edit().putString("local_only", "keep while unavailable").commit();
        route.unavailable = true;
        HostConfigBridge.initialize(host);
        assertEquals("keep while unavailable", local.getString("local_only", ""));
        assertTrue(diagnostics().getString("configuration_bridge_state").contains("暂不可达"));
        new SettingsRepository(host).putString("local_only", "new local value");
        drainIo();
        assertEquals("new local value", local.getString("local_only", ""));
        assertTrue(desktop.getAll().isEmpty());
        assertTrue(diagnostics().getString("configuration_bridge_state").contains("同步桌面失败"));
        route.unavailable = false;
        HostConfigBridge.initialize(host);
        settle();
        assertEquals("new local value", desktop.getString("local_only", ""));
        assertEquals(desktop.getAll(), local.getAll());
    }

    @Test public void hostStatusPublishesACopyUsingTheAuthenticatedHostUid() throws Exception {
        Bundle snapshot = new Bundle();
        snapshot.putString("startup_stage", "identity rejected");
        snapshot.putBoolean("startup_host_authorized", false);
        try (IoGate gate = queuedGate()) {
            gate.awaitEntered();
            HostConfigBridge.publishHostStatus(host, snapshot);
            snapshot.putString("startup_stage", "caller changed this later");
        }
        drainIo();
        Bundle received = ModuleBridgeProvider.readStatus(module);
        assertNotNull(received);
        assertEquals("identity rejected", received.getString("startup_stage"));
        assertFalse(received.getBoolean("startup_host_authorized"));
        assertEquals(HOST_UID, received.getInt("bridge_host_uid"));
        assertTrue(received.getLong("bridge_received_at") > 0);
    }

    @Test public void desktopSaveCannotInterleaveBetweenProviderSnapshotAndPatchCommit() throws Exception {
        desktop.edit().putString("same_key", "initial").commit();
        SnapshotGate preferences = new SnapshotGate(desktop);
        Context controlled = new ContextWrapper(module) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return SettingsRepository.PREFERENCES_NAME.equals(name) ? preferences
                        : super.getSharedPreferences(name, mode);
            }
        };
        ModuleBridgeProvider controlledProvider = attached(new ModuleBridgeProvider(), controlled);
        SettingsRepository repository = new SettingsRepository(controlled);
        Bundle patch = new Bundle();
        patch.putBundle("expected", ModuleBridgeProvider.encode(Map.of("same_key", "initial")));
        patch.putBundle("replacement", ModuleBridgeProvider.encode(Map.of("same_key", "host edit")));
        ExecutorService patchExecutor = Executors.newSingleThreadExecutor();
        Thread writer = new Thread(() -> repository.putString("same_key", "later desktop edit"), "desktop-writer-test");
        Future<Bundle> result = null;
        try {
            preferences.armed.set(true);
            result = patchExecutor.submit(() -> controlledProvider.call("patch", null, patch));
            assertTrue("provider must capture its comparison snapshot", preferences.captured.await(5, TimeUnit.SECONDS));
            writer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (writer.isAlive() && writer.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            // Correct serialization blocks the desktop write until the provider transaction ends.
            assertEquals("desktop writes must share the provider transaction lock", Thread.State.BLOCKED, writer.getState());
        } finally {
            preferences.release.countDown();
            try { if (result != null) result.get(5, TimeUnit.SECONDS); }
            finally { writer.join(5000); patchExecutor.shutdownNow(); }
        }
        assertFalse("desktop write must complete after provider releases its snapshot", writer.isAlive());
        assertEquals("later desktop edit", desktop.getString("same_key", ""));
    }

    private static <T extends ContentProvider> T attached(T provider, Context context) {
        ProviderInfo info = new ProviderInfo();
        info.authority = ModuleBridgeProvider.URI.getAuthority();
        info.packageName = BuildConfig.APPLICATION_ID;
        info.exported = true;
        provider.attachInfo(context, info);
        return provider;
    }

    private void writeDesktop(Map<String, ?> values) {
        synchronized (ModuleBridgeProvider.LOCK) { write(desktop, values); ModuleBridgeProvider.changed(module); }
    }

    private static void write(SharedPreferences target, Map<String, ?> values) {
        SharedPreferences.Editor editor = target.edit().clear();
        values.forEach((key, value) -> ModuleBridgeProvider.put(editor, key, value));
        assertTrue(editor.commit());
    }

    private static Map<String, Object> typedValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("enabled", true); values.put("text", "desktop"); values.put("int", 7);
        values.put("long", 9L); values.put("float", 1.25f); values.put("set", Set.of("a", "b"));
        return values;
    }

    private static Bundle diagnostics() { Bundle result = new Bundle(); HostConfigBridge.appendDiagnostics(result); return result; }
    private static void idle() { Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle(); }
    private static ExecutorService io() throws Exception { Field field = HostConfigBridge.class.getDeclaredField("IO"); field.setAccessible(true); return (ExecutorService) field.get(null); }
    private static void drainIo() throws Exception { io().submit(() -> { }).get(5, TimeUnit.SECONDS); }
    private static void settle() throws Exception { for (int index = 0; index < 3; index++) { idle(); drainIo(); } }
    private static void setActive(HostConfigBridge value) throws Exception { Field field = HostConfigBridge.class.getDeclaredField("active"); field.setAccessible(true); field.set(null, value); }
    private static IoGate queuedGate() throws Exception { IoGate gate = new IoGate(); io().execute(gate); return gate; }

    private static final class IoGate implements Runnable, AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        @Override public void run() { entered.countDown(); try { if (!released.await(10, TimeUnit.SECONDS)) throw new AssertionError("IO gate timed out"); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); } }
        void awaitEntered() throws InterruptedException { assertTrue("queued IO gate must be reached", entered.await(5, TimeUnit.SECONDS)); }
        void release() { released.countDown(); }
        @Override public void close() { release(); }
    }

    /** Robolectric has one JVM UID; this boundary assigns the real host's UID to routed IPC. */
    private static final class HostRoute extends ContentProvider {
        private final ModuleBridgeProvider target;
        volatile boolean unavailable;
        HostRoute(ModuleBridgeProvider target) { this.target = target; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if (unavailable) throw new IllegalStateException("test provider unavailable");
            ShadowBinder.setCallingUid(HOST_UID);
            try { return target.call(method, arg, extras); }
            finally { ShadowBinder.setCallingUid(MODULE_UID); }
        }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }

    /** Stops after the provider reads actual preferences, while a desktop writer tries to save. */
    private static final class SnapshotGate implements SharedPreferences {
        private final SharedPreferences actual;
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch captured = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        SnapshotGate(SharedPreferences actual) { this.actual = actual; }
        @Override public Map<String, ?> getAll() {
            Map<String, ?> snapshot = actual.getAll();
            if (armed.compareAndSet(true, false)) {
                captured.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("snapshot gate timed out"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
            return snapshot;
        }
        @Override public String getString(String key, String fallback) { return actual.getString(key, fallback); }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) { return actual.getStringSet(key, fallback); }
        @Override public int getInt(String key, int fallback) { return actual.getInt(key, fallback); }
        @Override public long getLong(String key, long fallback) { return actual.getLong(key, fallback); }
        @Override public float getFloat(String key, float fallback) { return actual.getFloat(key, fallback); }
        @Override public boolean getBoolean(String key, boolean fallback) { return actual.getBoolean(key, fallback); }
        @Override public boolean contains(String key) { return actual.contains(key); }
        @Override public Editor edit() { return actual.edit(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { actual.registerOnSharedPreferenceChangeListener(listener); }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { actual.unregisterOnSharedPreferenceChangeListener(listener); }
    }
}
