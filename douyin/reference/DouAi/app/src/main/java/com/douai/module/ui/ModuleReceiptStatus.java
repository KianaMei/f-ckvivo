package com.douai.module.ui;

import android.os.Bundle;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Stored evidence is deliberately labelled as historical rather than current process liveness. */
public final class ModuleReceiptStatus {
    private ModuleReceiptStatus() { }
    public static ScanStatus present(ScanStatus status, Bundle receipt) {
        if (receipt == null || !receipt.getBoolean("bridge_cached_receipt", false)) return status;
        long received = receipt.getLong("bridge_received_at", 0L);
        String time = received > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(received)) : "时间未知";
        return new ScanStatus("最近回执 · " + status.title,
                "保存于 " + time + "。这是最近收到的记录，不代表抖音进程目前仍在运行。\n" + status.detail,
                false, status.restartRequired);
    }
}
