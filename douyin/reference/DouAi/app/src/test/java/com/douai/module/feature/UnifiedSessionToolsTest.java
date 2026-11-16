package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class UnifiedSessionToolsTest {
    @Test public void aSeededAlreadyResumedActivityCanExitWithoutAnotherResumeCallback() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Application host = new Application() { @Override public String getPackageName() { return "com.ss.android.ugc.aweme"; } };
        Memory settings = new Memory(); settings.values.put(UnifiedSessionTools.TIMER, "3");
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        try (UnifiedSessionTools feature = UnifiedSessionTools.install(host, settings, framework.api)) {
            feature.setCurrentActivity(activity);
            Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4));
            Intent intent = Shadow.<ShadowActivity>extract(activity).getNextStartedActivity();
            assertNotNull("The async installation must seed the already visible activity", intent);
            assertEquals(Intent.ACTION_MAIN, intent.getAction()); assertTrue(intent.hasCategory(Intent.CATEGORY_HOME));
        } finally { activity.finish(); }
    }
    @Test public void closingTheFeatureCancelsItsFutureExitChecks() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Application host = new Application() { @Override public String getPackageName() { return "com.ss.android.ugc.aweme"; } };
        Memory settings = new Memory(); settings.values.put(UnifiedSessionTools.TIMER, "3");
        UnifiedSessionTools feature = UnifiedSessionTools.install(host, settings, new NativeMessagingHooksTest.Framework().api);
        feature.setCurrentActivity(activity); feature.close();
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4));
        assertNull(Shadow.<ShadowActivity>extract(activity).getNextStartedActivity()); activity.finish();
    }
    @Test public void anExplicitProcessStopAlsoRunsAfterTheHostHasMovedToBackground() throws Exception {
        Application host = new Application() { @Override public String getPackageName() { return "com.ss.android.ugc.aweme"; } };
        Memory settings = new Memory(); settings.values.put(UnifiedSessionTools.TIMER, "3"); settings.values.put(UnifiedSessionTools.KILL, true);
        org.robolectric.shadows.ShadowProcess.setPid(123456); org.robolectric.shadows.ShadowProcess.clearKilledProcesses();
        try (UnifiedSessionTools feature = UnifiedSessionTools.install(host, settings, new NativeMessagingHooksTest.Framework().api)) {
            Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4));
            assertTrue(org.robolectric.shadows.ShadowProcess.wasKilled(123456));
        }
    }
    @Test public void closingAfterTheTimerFiresCancelsItsPendingProcessStop() throws Exception {
        Application host = new Application() { @Override public String getPackageName() { return "com.ss.android.ugc.aweme"; } };
        Memory settings = new Memory(); settings.values.put(UnifiedSessionTools.TIMER, "3"); settings.values.put(UnifiedSessionTools.KILL, true);
        org.robolectric.shadows.ShadowProcess.setPid(123457); org.robolectric.shadows.ShadowProcess.clearKilledProcesses();
        UnifiedSessionTools feature = UnifiedSessionTools.install(host, settings, new NativeMessagingHooksTest.Framework().api);
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofSeconds(210));
        feature.close(); Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertFalse(org.robolectric.shadows.ShadowProcess.wasKilled(123457));
    }
    private static final class Memory implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        public FeatureSettings load() { return FeatureSettings.defaults(); }
        public void save(FeatureSettings value) { }
        public boolean getBoolean(String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback; }
        public String getString(String key, String fallback) { Object value = values.get(key); return value instanceof String ? (String) value : fallback; }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
    }
}
