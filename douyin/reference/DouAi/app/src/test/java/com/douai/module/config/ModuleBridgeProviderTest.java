package com.douai.module.config;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.os.Bundle;
import com.douai.module.BuildConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowProcess;

import static org.junit.Assert.*;

/** Exercises the bridge with actual Binder identities, rather than caller-supplied package names. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = BuildConfig.APPLICATION_ID)
public final class ModuleBridgeProviderTest {
    private static final int MODULE_UID = 12001;
    private static final int HOST_UID = 12002;
    private static final int OTHER_UID = 12003;
    private Application application;
    private ModuleBridgeProvider provider;
    private SharedPreferences preferences;
    private ShadowPackageManager packages;

    @Before public void setUp() {
        ShadowProcess.setUid(MODULE_UID);
        ShadowBinder.setCallingUid(MODULE_UID);
        application = RuntimeEnvironment.getApplication();
        preferences = application.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        application.getSharedPreferences(ModuleBridgeProvider.META, Context.MODE_PRIVATE).edit().clear().commit();
        packages = Shadow.extract(application.getPackageManager());
        installHost(HOST_UID);
        ProviderInfo info = new ProviderInfo();
        info.authority = ModuleBridgeProvider.URI.getAuthority();
        info.exported = true;
        provider = new ModuleBridgeProvider();
        provider.attachInfo(application, info);
    }

    @Test public void ownUidCanReadAndPatchButCannotForgeHostReceipt() {
        assertEquals(0, provider.call("settings", null, null).getLong("revision"));
        assertTrue(patch(Map.of(), Map.of("enabled", true)).getBoolean("applied"));
        assertTrue(preferences.getBoolean("enabled", false));
        assertNull(provider.call("status", null, null));
        expectSecurity(() -> provider.call("host_status", null, new Bundle()));
        assertNull(ModuleBridgeProvider.readStatus(application));
    }

    @Test public void installedHostUidCanPatchAndPublishAReceiptWithTrustedIdentity() {
        ShadowBinder.setCallingUid(HOST_UID);
        assertTrue(patch(Map.of(), Map.of("filter", "enabled")).getBoolean("applied"));
        Bundle receipt = new Bundle();
        receipt.putString("startup_stage", "application_ready");
        receipt.putBoolean("startup_host_callback_reached", true);
        receipt.putInt("bridge_host_uid", OTHER_UID);
        receipt.putLong("bridge_received_at", -1);
        provider.call("host_status", null, receipt);

        ShadowBinder.setCallingUid(MODULE_UID);
        Bundle stored = provider.call("status", null, null);
        assertNotNull(stored);
        assertEquals("application_ready", stored.getString("startup_stage"));
        assertTrue(stored.getBoolean("startup_host_callback_reached"));
        assertEquals(HOST_UID, stored.getInt("bridge_host_uid"));
        assertTrue(stored.getLong("bridge_received_at") > 0);
        assertEquals("The provider must not mutate the caller's original bundle", OTHER_UID,
                receipt.getInt("bridge_host_uid"));
    }

    @Test public void hostCannotEraseReceiptButModuleCan() {
        ShadowBinder.setCallingUid(HOST_UID);
        provider.call("host_status", null, new Bundle());
        expectSecurity(() -> provider.call("clear_status", null, null));
        assertNotNull(ModuleBridgeProvider.readStatus(application));
        ShadowBinder.setCallingUid(MODULE_UID);
        provider.call("clear_status", null, null);
        assertNull(ModuleBridgeProvider.readStatus(application));
    }

    @Test public void aClaimedHostPackageCannotReplaceTheInstalledHostUid() {
        // getPackagesForUid() can contain the claimed name; the installed ApplicationInfo is authoritative.
        packages.setPackagesForUid(OTHER_UID, ModuleBridgeProvider.HOST);
        ShadowBinder.setCallingUid(OTHER_UID);
        for (String method : new String[]{"settings", "patch", "status", "host_status", "clear_status"}) {
            expectSecurity(() -> provider.call(method, ModuleBridgeProvider.HOST, new Bundle()));
        }
        assertTrue(preferences.getAll().isEmpty());
        assertNull(ModuleBridgeProvider.readStatus(application));
    }

    @Test public void anotherAndroidUserIsRejectedEvenWhenInstalledUidMatches() {
        int otherUserHost = 100000 + HOST_UID;
        installHost(otherUserHost);
        ShadowBinder.setCallingUid(otherUserHost);
        expectSecurity(() -> provider.call("settings", null, null));
        expectSecurity(() -> provider.call("host_status", null, new Bundle()));
    }

    @Test public void removedHostIsDeniedInsteadOfTrustingAStaleCallerName() {
        packages.removePackage(ModuleBridgeProvider.HOST);
        packages.setPackagesForUid(HOST_UID, ModuleBridgeProvider.HOST);
        ShadowBinder.setCallingUid(HOST_UID);
        expectSecurity(() -> provider.call("settings", null, null));
    }

    @Test public void allSupportedPreferenceTypesRoundTripThroughPatchAndSnapshot() {
        Map<String, Object> values = new HashMap<>();
        values.put("boolean", true);
        values.put("string", "桌面设置 → 抖音");
        values.put("integer", 42);
        values.put("long", 9000000000L);
        values.put("float", 1.25f);
        values.put("set", new HashSet<>(Set.of("用户一", "user-2")));
        assertEquals(values, ModuleBridgeProvider.decode(ModuleBridgeProvider.encode(values)));

        Bundle applied = patch(Map.of(), values);
        assertTrue(applied.getBoolean("applied"));
        assertEquals(values, preferences.getAll());
        assertEquals(values, ModuleBridgeProvider.decode(applied.getBundle("values")));
        assertEquals(values, ModuleBridgeProvider.decode(provider.call("settings", null, null).getBundle("values")));
        assertEquals(1, applied.getLong("revision"));
    }

    @Test public void staleSnapshotsChangingDifferentKeysMergeWithoutLosingEitherSide() {
        Map<String, Object> initial = Map.of("desktop", false, "host", false);
        patch(Map.of(), initial);
        Bundle desktop = patch(initial, Map.of("desktop", true, "host", false));
        ShadowBinder.setCallingUid(HOST_UID);
        Bundle host = patch(initial, Map.of("desktop", false, "host", true));

        assertTrue(desktop.getBoolean("applied"));
        assertTrue(host.getBoolean("applied"));
        assertFalse(host.getBoolean("conflict"));
        assertEquals(Map.of("desktop", true, "host", true), preferences.getAll());
        assertEquals(3, host.getLong("revision"));
    }

    @Test public void sameKeyConflictIsAtomicAndDoesNotAdvanceRevisionOrNotify() {
        Map<String, Object> initial = Map.of("shared", "initial", "other", false);
        patch(Map.of(), initial);
        patch(initial, Map.of("shared", "desktop", "other", false));
        AtomicInteger notifications = new AtomicInteger();
        ContentObserver observer = observer(notifications);
        application.getContentResolver().registerContentObserver(ModuleBridgeProvider.URI, false, observer);
        try {
            ShadowBinder.setCallingUid(HOST_UID);
            Bundle conflict = patch(initial, Map.of("shared", "host", "other", true));
            assertTrue(conflict.getBoolean("conflict"));
            assertFalse(conflict.getBoolean("applied"));
            assertEquals(2, conflict.getLong("revision"));
            assertEquals(Map.of("shared", "desktop", "other", false), preferences.getAll());
            assertEquals(0, notifications.get());
        } finally {
            application.getContentResolver().unregisterContentObserver(observer);
        }
    }

    @Test public void deletionPreservesOtherConcurrentKeysAndMissingDiffersFromFalse() {
        Map<String, Object> initial = Map.of("removed", "value", "untouched", false);
        patch(Map.of(), initial);
        patch(initial, Map.of("removed", "value", "untouched", true));
        Bundle deletion = patch(initial, Map.of("untouched", false));
        assertTrue(deletion.getBoolean("applied"));
        assertEquals(Map.of("untouched", true), preferences.getAll());

        // An absent expected key must not overwrite an explicitly saved false value.
        patch(Map.of("untouched", true), Map.of("untouched", true, "falseValue", false));
        Bundle conflict = patch(Map.of(), Map.of("falseValue", true));
        assertTrue(conflict.getBoolean("conflict"));
        assertFalse(preferences.getBoolean("falseValue", true));
    }

    @Test public void successfulPatchesPublishIncreasingRevisionNotifications() {
        AtomicInteger notifications = new AtomicInteger();
        ContentObserver observer = observer(notifications);
        application.getContentResolver().registerContentObserver(ModuleBridgeProvider.URI, false, observer);
        try {
            assertEquals(1, patch(Map.of(), Map.of("key", 1)).getLong("revision"));
            assertEquals(2, patch(Map.of("key", 1), Map.of("key", 2)).getLong("revision"));
            assertEquals(2, notifications.get());
        } finally {
            application.getContentResolver().unregisterContentObserver(observer);
        }
    }

    @Test public void malformedTypedPatchCannotPartiallyWritePreferences() {
        Bundle badValues = new Bundle();
        badValues.putString("valid", "should not be saved");
        badValues.putDouble("unsupported", 1.0);
        Bundle request = new Bundle();
        request.putBundle("expected", new Bundle());
        request.putBundle("replacement", badValues);
        try {
            provider.call("patch", null, request);
            fail("Double preference type must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(preferences.getAll().isEmpty());
            assertEquals(0, provider.call("settings", null, null).getLong("revision"));
        }
    }

    private Bundle patch(Map<String, ?> before, Map<String, ?> after) {
        Bundle extras = new Bundle();
        extras.putBundle("expected", ModuleBridgeProvider.encode(before));
        extras.putBundle("replacement", ModuleBridgeProvider.encode(after));
        return provider.call("patch", null, extras);
    }

    private void installHost(int uid) {
        PackageInfo info = new PackageInfo();
        info.packageName = ModuleBridgeProvider.HOST;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = ModuleBridgeProvider.HOST;
        info.applicationInfo.uid = uid;
        packages.installPackage(info);
    }

    private static ContentObserver observer(AtomicInteger notifications) {
        return new ContentObserver(null) {
            @Override public void onChange(boolean selfChange) { notifications.incrementAndGet(); }
        };
    }

    private static void expectSecurity(Runnable action) {
        try {
            action.run();
            fail("Untrusted caller must be rejected");
        } catch (SecurityException expected) {
            // The exception is the externally observable permission boundary.
        }
    }
}
