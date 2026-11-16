package com.douai.module.ui;

import android.app.Activity;
import android.view.View;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.DouAiModule;
import com.douai.module.config.ExtendedSettings;
import android.content.Context;
import android.app.Application;
import com.douai.module.BuildConfig;
import org.junit.Assume;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The unified settings surface is complete; an unverified host cannot create an operational controller. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ModuleSettings412Test {
    private Activity activity;
    private ModuleSettingsScreen screen;
    @After public void cleanUp() {
        if (screen != null) screen.dismissChildPages();
        if (activity != null) activity.finish();
        HostAdaptation.setCurrentProfile(null);
    }

    @Test public void unifiedSettingsExposeAllCapabilityCategoriesAndSearch() {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_412);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        screen = new ModuleSettingsScreen(activity, new MemoryStore());
        View home = screen.createMiuixView();
        activity.setContentView(home);
        assertNotNull(home.findViewWithTag("settings.category.FILTER"));
        assertNotNull(home.findViewWithTag("settings.category.DOWNLOAD"));
        assertNull(home.findViewWithTag("settings.category.ABOUT"));
        for (String category : new String[]{"BASIC", "INTERFACE", "AUTOPLAY", "AUTO_REPLY", "AUTO_COMMENT",
                "COMMENT", "STREAK", "MESSAGE", "SPOOF", "EXTENSION", "COMPONENT_HIDE"}) {
            assertNotNull(category, home.findViewWithTag("settings.category." + category));
        }
        assertNotNull(home.findViewWithTag("settings.search.results"));
    }

    @Test public void restrictedAndUnknownProfilesCannotInstallImmersiveController() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        DouAiModule module = new DouAiModule();
        var profileField = DouAiModule.class.getDeclaredField("hostProfile");
        profileField.setAccessible(true);
        var ensure = DouAiModule.class.getDeclaredMethod("ensureImmersiveModeController", Context.class, ExtendedSettings.class);
        ensure.setAccessible(true);
        var controller = DouAiModule.class.getDeclaredField("immersiveModeController");
        controller.setAccessible(true);
        for (HostAdaptation.Profile profile : new HostAdaptation.Profile[]{null, HostAdaptation.PROFILE_412}) {
            profileField.set(module, profile);
            ensure.invoke(module, activity, null);
            assertNull("Unreviewed system-bar behavior remains uninstalled", controller.get(module));
        }
    }

    @Test public void unknownPreviewHostCannotCreateOperationalUiController() throws Exception {
        Assume.assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".adapt412"));
        DouAiModule module = new DouAiModule();
        var ensure = DouAiModule.class.getDeclaredMethod("ensureHostUiController", Application.class);
        ensure.setAccessible(true);
        assertNull(ensure.invoke(module, RuntimeEnvironment.getApplication()));
        var field = DouAiModule.class.getDeclaredField("hostProfile");
        field.setAccessible(true);
        field.set(module, HostAdaptation.PROFILE_410);
        assertNull("A foreign Application must never receive host controls", ensure.invoke(module, RuntimeEnvironment.getApplication()));
    }

    @Test @Config(application = HostApplication.class)
    public void realHostReceivesDiagnosticsBeforeIdentityAuthorization() throws Exception {
        DouAiModule module = new DouAiModule();
        var ensure = DouAiModule.class.getDeclaredMethod("ensureHostUiController", Application.class);
        ensure.setAccessible(true);
        HostUiController controller = (HostUiController)ensure.invoke(module, RuntimeEnvironment.getApplication());
        try {
            assertNotNull("An unknown host must still expose startup evidence", controller);
            assertFalse("Diagnostic UI must not authorize feature hooks", controller.startupSnapshot().getBoolean("startup_host_authorized"));
            var ai = HostUiController.class.getDeclaredField("aiMessageEntry");
            ai.setAccessible(true);
            assertNull(ai.get(controller));
        } finally { if (controller != null) controller.close(); }
    }

    public static final class HostApplication extends Application {
        @Override public String getPackageName() { return HostAdaptation.TARGET_PACKAGE; }
    }

    @Test public void unavailableDiagnosticUiNeverBreaksTheHostApplicationCallback() throws Exception {
        DouAiModule module = new DouAiModule();
        Application faulty = new Application() {
            @Override public String getPackageName() { return HostAdaptation.TARGET_PACKAGE; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                throw new IllegalStateException("diagnostic preference storage unavailable");
            }
        };
        var callback = DouAiModule.class.getDeclaredMethod("hostApplicationAvailable", Application.class);
        callback.setAccessible(true);
        callback.invoke(module, faulty);
        var context = DouAiModule.class.getDeclaredField("targetContext");
        context.setAccessible(true);
        assertSame("The scanner can still observe the attached host", faulty, context.get(module));
        var failure = DouAiModule.class.getDeclaredField("startupFailure");
        failure.setAccessible(true);
        assertEquals("IllegalStateException", failure.get(module));
    }

    private static final class MemoryStore implements SettingsStore {
        private FeatureSettings features = FeatureSettings.defaults();
        @Override public FeatureSettings load() { return features; }
        @Override public void save(FeatureSettings value) { features = value; }
        @Override public String getString(String key, String value) { return value; }
        @Override public boolean getBoolean(String key, boolean value) { return value; }
        @Override public void putString(String key, String value) { }
        @Override public void putBoolean(String key, boolean value) { }
        @Override public boolean isInHost() { return true; }
        @Override public void close() { }
    }
}
