package com.douai.module.ui;

import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HostStartupStatusTest {
    @Test public void stageEvidenceIsBoundedAndCannotGrantItsOwnAuthorization() {
        HostStartupStatus status = new HostStartupStatus(false);
        for (int index = 0; index < 40; index++) status.record("阶段 " + index, "证据 " + index);
        Bundle snapshot = status.snapshot();
        assertTrue(snapshot.getBoolean("startup_host_callback_reached"));
        assertFalse(snapshot.getBoolean("startup_host_authorized"));
        assertEquals(20, snapshot.getStringArrayList("startup_events").size());
        assertEquals("阶段 39", snapshot.getString("startup_stage"));
        assertFalse(status.describe().contains("证据 0\n"));
        status.authorization(true, "身份通过", "实际匹配");
        assertTrue(status.snapshot().getBoolean("startup_host_authorized"));
        status.authorization(false, "身份拒绝", "actual mismatch");
        assertFalse(status.snapshot().getBoolean("startup_host_authorized"));
        assertTrue(status.describe().contains("actual mismatch"));
    }
}
