package com.douai.module.feature;

import android.content.Context;
import java.io.File;
import org.robolectric.RuntimeEnvironment;
import com.douai.module.BuildConfig;
import com.douai.module.analytics.LocalAnalytics;
import org.junit.Assume;
import com.douai.module.scan.HostAdaptation;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AnalyticsActionCapture412Test {
    @After public void resetProfile() { HostAdaptation.setCurrentProfile(null); }

    @Test public void unifiedDownloadAttributionUsesOnlyTheIndependentAnalyticsStore() throws Exception {
        Assume.assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".adapt412"));
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_412);
        Context context = RuntimeEnvironment.getApplication();
        LocalAnalytics analytics = LocalAnalytics.get(context);
        var directory = LocalAnalytics.class.getDeclaredField("directory");
        directory.setAccessible(true);
        org.junit.Assert.assertEquals("douai-analytics-adapt412", ((File)directory.get(analytics)).getName());
        AnalyticsActionCapture capture = AnalyticsActionCapture.capture(context, new Object());
        assertNull(capture.operation("download"));
        capture.manual("download", "test", "entry");
    }
}
