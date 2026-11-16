package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import com.douai.module.BuildConfig;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** The separate test module must neither inherit nor rewrite installed DouAi settings. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class PreviewSettingsIsolationTest {
    @Test public void existingHostPreferencesRemainUntouchedByPreviewWrites() {
        Assume.assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".adapt412"));
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences original = context.getSharedPreferences("douai_module_settings", Context.MODE_PRIVATE);
        original.edit().clear().putBoolean("native_auto_reply", true)
                .putString("existing_marker", "keep-original").commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();

        SettingsRepository preview = new SettingsRepository(context);
        assertFalse(preview.getBoolean("native_auto_reply", false));
        assertEquals("", preview.getString("existing_marker", ""));
        preview.putBoolean("native_auto_reply", false);
        preview.putString("existing_marker", "preview-only");

        assertTrue(original.getBoolean("native_auto_reply", false));
        assertEquals("keep-original", original.getString("existing_marker", ""));
        assertEquals("preview-only", preview.getString("existing_marker", ""));
    }
}
