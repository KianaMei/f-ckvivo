package com.douai.module.ui;

import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ModuleReceiptStatusTest {
    @Test public void aCachedCompletionIsHistoricalAndDoesNotClaimCurrentLiveness() {
        Bundle cached = new Bundle(); cached.putBoolean("bridge_cached_receipt", true); cached.putLong("bridge_received_at", 1_700_000_000_000L);
        ScanStatus presented = ModuleReceiptStatus.present(new ScanStatus("Hook 已接入", "已有安装结果", true, false), cached);
        assertTrue(presented.title.startsWith("最近回执"));
        assertTrue(presented.detail.contains("不代表抖音进程目前仍在运行"));
        assertTrue(presented.detail.contains("已有安装结果")); assertFalse(presented.ready);
    }
    @Test public void aLiveReceiptAndMissingReceiptKeepTheirOriginalMeaning() {
        ScanStatus status = new ScanStatus("扫描中", "正在读取 DEX", false, false);
        assertSame(status, ModuleReceiptStatus.present(status, null));
        assertSame(status, ModuleReceiptStatus.present(status, new Bundle()));
    }
}
