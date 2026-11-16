package com.douai.module.ui;

import android.os.Bundle;
import androidx.activity.ComponentActivity;
import com.douai.module.config.FrameworkConfigSync;
import com.douai.module.config.ModuleConfigRuntime;

/** An always-reachable editor; opening it never implies that host injection succeeded. */
public final class DesktopSettingsActivity extends ComponentActivity {
    private ModuleSettingsScreen screen;
    private ModuleConfigRuntime runtime;
    private FrameworkConfigSync.Listener connectionListener;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("抖音融合 · 功能设置");
        runtime = ModuleConfigRuntime.get(this);
        screen = ModuleSettingsScreen.desktop(this, runtime.repository(), this::finish);
        setContentView(screen.createView());
        screen.setScanStatus(new ScanStatus("功能配置可离线保存",
                "设置界面已打开不代表模块已注入。请返回模块主页查看抖音注入回执；宿主相关的任务和数据需要在抖音中使用。",
                false, false));
        connectionListener = (connected, message, error) -> {
            if (!isDestroyed() && screen != null) screen.setFrameworkSyncStatus(connected, message, error);
        };
        runtime.addStatusListener(connectionListener);
        screen.setFrameworkSyncStatus(runtime.sync().isConnected(),
                "配置保存在模块应用中；宿主读取结果请以主页的注入回执为准。", null);
    }

    @Override protected void onDestroy() {
        if (screen != null) screen.close();
        if (runtime != null && connectionListener != null) runtime.removeStatusListener(connectionListener);
        screen = null;
        super.onDestroy();
    }
}
