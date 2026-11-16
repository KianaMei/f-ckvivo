package dev.ganvivo.dragall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class SettingsActivity extends Activity {
    private final Map<String, Switch> toggles = new LinkedHashMap<>();
    private final Map<String, SeekBar> sliders = new LinkedHashMap<>();
    private final Runnable connectionListener = this::connect;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (preferences, key) -> runOnUiThread(this::render);
    private SharedPreferences preferences;
    private TextView status;
    private Button reconnect;
    private Button disableAll;
    private Button restartScope;
    private TextView restartResult;
    private boolean rendering;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(20), dp(24), dp(24));
        scroll.addView(content);
        text(content, "drag-all", 30);
        text(content, "让更多应用参与超级拖拽", 16);
        text(content, "在 Vector 中启用 drag-all，选择「拖放直达」和「内容提取服务」。"
                + "两个应用各有一层拖拽白名单，开关同步控制两层。", 14);
        text(content, "PiliBro 精确拖拽还需选择 PiliBro 作用域，以读取手指位置对应的文字或图片节点。", 13);
        text(content, "拖拽支持哪些应用以「超级拖拽设置」官方页面为准：在那里勾选的应用即可拖拽；支持的客户端（Telegram 系）自动使用原生精确提取，不支持的自带回退厂商行为。", 13);
        text(content, hostVersion(), 13);
        status = text(content, "等待连接 Vector…", 14);
        reconnect = new Button(this);
        reconnect.setText("重新连接（重启本 App 进程）");
        reconnect.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("重新连接 Vector？")
                .setMessage("本 App 会重启并自动重新打开（约 2 秒）。"
                        + "Vector 只在进程新启动时推送配置服务，所以需要一次重启。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重启并重连", (dialog, which) -> restartSelf())
                .show());
        reconnect.setVisibility(android.view.View.GONE);
        content.addView(reconnect);
        toggle(content, Config.DRAG, "放开应用拖拽", "放行入口与实际抓取检查。此前被系统判为不支持的应用，更新后需结束该应用进程再打开；仅返回桌面不能清除缓存。");
        toggle(content, Config.APPS, "扩展设置中的应用列表", "合并可见的桌面应用，保留已有应用开关设置。");
        toggle(content, Config.SHARE, "加入系统分享", "在超级拖拽菜单中加入 Android 系统分享入口。");
        toggle(content, Config.GLASS, "拖拽岛玻璃效果", "把底部黑色背景改为半透明深色玻璃，保留文字与图标清晰度。");
        Button vendorPage = new Button(this);
        vendorPage.setText("打开超级拖拽设置页");
        vendorPage.setOnClickListener(view -> openVendorPage());
        content.addView(vendorPage);
        text(content, "应用清单与开关都在官方页面里维护；模块已把全部已安装应用并入该列表。", 12);
        disableAll = new Button(this);
        disableAll.setText("关闭全部增强");
        disableAll.setOnClickListener(view -> {
            if (preferences == null) return;
            try {
                SharedPreferences.Editor editor = preferences.edit();
                for (String key : Config.KEYS) editor.putBoolean(key, false);
                editor.apply();
                render();
            } catch (RuntimeException failure) {
                saveFailed();
            }
        });
        content.addView(disableAll);
        restartScope = new Button(this);
        restartScope.setText("重启当前作用域");
        restartScope.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("重新加载拖放直达？")
                .setMessage("将短暂中断拖拽，仅重建拖放直达的应用进程，并打开其设置页。"
                        + "内容提取服务使用系统 UID，此按钮不会结束它；新增作用域后需等待它自然重建。"
                        + "首次使用需要授予 drag-all root 权限。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> restartScope())
                .show());
        content.addView(restartScope);
        restartResult = text(content, "此按钮只重建拖放直达；内容提取服务等待系统自然重建进程后加载。", 13);
        text(content, "更改后请重新开始一次拖拽，或重新进入拖拽设置页。"
                + "Vector 已连接仅表示设置可同步，具体功能是否命中以实际操作为准。", 12);
        text(content, "版本 " + BuildConfig.VERSION_NAME, 12);
        setContentView(scroll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApplication.subscribe(connectionListener);
    }

    @Override
    protected void onStop() {
        ModuleApplication.unsubscribe(connectionListener);
        detachPreferences();
        super.onStop();
    }

    private void connect() {
        detachPreferences();
        try {
            SharedPreferences candidate = ModuleApplication.preferences();
            if (candidate != null) candidate.registerOnSharedPreferenceChangeListener(preferenceListener);
            preferences = candidate;
        } catch (RuntimeException failure) {
            preferences = null;
        }
        render();
    }

    private void detachPreferences() {
        SharedPreferences previous = preferences;
        preferences = null;
        if (previous != null) {
            try {
                previous.unregisterOnSharedPreferenceChangeListener(preferenceListener);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void render() {
        rendering = true;
        try {
            boolean connected = preferences != null;
            status.setText(connected ? "已连接 Vector · 设置可同步"
                    : "未连接 Vector 配置服务（不代表模块未启用）。点下方「重新连接」会自动重启本 App。");
            reconnect.setVisibility(connected ? android.view.View.GONE : android.view.View.VISIBLE);
            disableAll.setEnabled(connected);
            restartScope.setEnabled(!ScopeRestarter.isRunning());
            for (Map.Entry<String, Switch> entry : toggles.entrySet()) {
                entry.getValue().setEnabled(connected);
                entry.getValue().setChecked(connected && preferences.getBoolean(
                        entry.getKey(), Config.defaultValue(entry.getKey())));
            }
            for (Map.Entry<String, SeekBar> entry : sliders.entrySet()) {
                entry.getValue().setEnabled(connected && preferences.getBoolean(Config.GLASS, false));
                int fallback = Config.BLUR.equals(entry.getKey()) ? Config.DEFAULT_BLUR : Config.DEFAULT_TINT;
                entry.getValue().setProgress(connected ? preferences.getInt(entry.getKey(), fallback) : fallback);
            }
        } catch (RuntimeException failure) {
            status.setText(R.string.settings_read_failed);
            disableAll.setEnabled(false);
            restartScope.setEnabled(!ScopeRestarter.isRunning());
            for (Switch toggle : toggles.values()) toggle.setEnabled(false);
            for (SeekBar slider : sliders.values()) slider.setEnabled(false);
        } finally {
            rendering = false;
        }
    }

    private void toggle(LinearLayout parent, String key, String title, String description) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(16);
        toggle.setPadding(0, dp(16), 0, dp(4));
        toggle.setEnabled(false);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (rendering || preferences == null) return;
            try {
                preferences.edit().putBoolean(key, enabled).apply();
                if (Config.GLASS.equals(key)) render();
            } catch (RuntimeException failure) {
                saveFailed();
                render();
            }
        });
        toggles.put(key, toggle);
        parent.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        text(parent, description, 13);
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(6), 0, dp(6));
        parent.addView(view);
        return view;
    }

    private void slider(LinearLayout parent, String key, String title, int maximum,
            int fallback, String description) {
        TextView value = text(parent, title + "：" + fallback, 15);
        SeekBar slider = new SeekBar(this);
        slider.setMax(maximum);
        slider.setProgress(fallback);
        slider.setEnabled(false);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(title + "：" + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (preferences == null) return;
                try {
                    preferences.edit().putInt(key, seekBar.getProgress()).apply();
                } catch (RuntimeException failure) {
                    saveFailed();
                    render();
                }
            }
        });
        sliders.put(key, slider);
        parent.addView(slider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        text(parent, description, 12);
    }


    private void restartSelf() {
        final int pid = Process.myPid();
        Toast.makeText(this, "正在重启…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                java.lang.Process process = new ProcessBuilder("su", "-c",
                        "sh -c 'kill -9 " + pid
                                + "; sleep 1; am start -n dev.ganvivo.dragall/.SettingsActivity'")
                        .redirectErrorStream(true).start();
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // fall through: at least exit so the next manual open is cold
            }
            Process.killProcess(pid);
        }, "DragAllSelfRestart").start();
    }

    private void openVendorPage() {
        final String component = Config.TARGET
                + "/com.vivo.transisland.ui.settings.SuperDraggingSettingActivity";
        try {
            Intent intent = new Intent();
            intent.setComponent(ComponentName.unflattenFromString(component));
            startActivity(intent);
            return;
        } catch (RuntimeException refused) {
            // Not exported to third-party callers; fall back to a root launch.
        }
        new Thread(() -> {
            String message;
            try {
                java.lang.Process process = new ProcessBuilder("su", "-c",
                        "am start -W --user 0 -n " + component)
                        .redirectErrorStream(true).start();
                boolean finished = process.waitFor(10, TimeUnit.SECONDS);
                if (!finished) process.destroy();
                message = finished && process.exitValue() == 0
                        ? "已打开超级拖拽设置页" : "打开失败，请手动进入系统设置";
            } catch (Exception failure) {
                message = "打开失败：" + failure.getMessage();
            }
            String shown = message;
            runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_SHORT).show());
        }, "DragAllVendorPage").start();
    }

    private String hostVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(Config.TARGET, 0);
            return "拖放直达 " + info.versionName
                    + (Config.SUPPORTED_VERSION.equals(info.versionName)
                    ? " · 适配版本" : " · 当前未适配，增强不会挂载");
        } catch (Exception failure) {
            return "未读取到拖放直达版本；当前适配 " + Config.SUPPORTED_VERSION;
        }
    }

    private void saveFailed() {
        Toast.makeText(this, "设置未保存，请检查 Vector 连接", Toast.LENGTH_SHORT).show();
    }

    private void restartScope() {
        if (ScopeRestarter.isRunning()) return;
        restartScope.setEnabled(false);
        restartResult.setText("正在处理…若出现 KernelSU 授权提示，请允许本次 root 请求。");
        ScopeRestarter.restart(getApplicationContext(), result -> {
            if (isFinishing() || isDestroyed()) return;
            restartResult.setText(result.message);
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
            render();
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
