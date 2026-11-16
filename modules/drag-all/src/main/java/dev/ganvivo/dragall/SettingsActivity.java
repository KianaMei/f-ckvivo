package dev.ganvivo.dragall;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import androidx.core.graphics.Insets;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class SettingsActivity extends AppCompatActivity {
    private final Map<String, MaterialSwitch> toggles = new LinkedHashMap<>();
    private final Map<String, Slider> sliders = new LinkedHashMap<>();
    private final Runnable connectionListener = this::connect;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (preferences, key) -> runOnUiThread(this::render);
    private SharedPreferences preferences;
    private TextView status;
    private MaterialButton reconnect;
    private MaterialButton disableAll;
    private MaterialButton restartScope;
    private TextView restartResult;
    private boolean rendering;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(32));
        scroll.addView(content);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        TextView headline = new TextView(this);
        headline.setText(R.string.app_name);
        headline.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium);
        content.addView(headline);
        TextView subtitle = body(content, "让更多应用参与超级拖拽", 16);
        subtitle.setAlpha(0.8f);

        body(content, "拖拽支持哪些应用以「超级拖拽设置」官方页面为准：在那里勾选的应用即可拖拽；"
                + "支持的客户端（Telegram 系）自动使用原生精确提取，不支持的回退厂商行为。", 13);
        body(content, "PiliBro 精确拖拽还需选择 PiliBro 作用域，以读取手指位置对应的文字或图片节点。", 13);
        body(content, hostVersion(), 13);

        MaterialCardView statusCard = card(content);
        LinearLayout statusColumn = column(statusCard);
        status = new TextView(this);
        status.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        status.setText("等待连接 Vector…");
        statusColumn.addView(status);
        reconnect = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonTonalStyle);
        reconnect.setText("重新连接 Vector");
        reconnect.setGravity(Gravity.CENTER);
        reconnect.setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                .setTitle("重新连接 Vector？")
                .setMessage("本 App 会重启并自动重新打开（约 2 秒）。"
                        + "Vector 只在进程新启动时推送配置服务，所以需要一次重启。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重启并重连", (dialog, which) -> restartSelf())
                .show());
        reconnect.setVisibility(View.GONE);
        LinearLayout.LayoutParams reconnectParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reconnectParams.topMargin = dp(8);
        statusColumn.addView(reconnect, reconnectParams);

        section(content, "功能开关");
        switchRow(content, Config.DRAG, "放开应用拖拽",
                "放行入口与实际抓取检查。此前被系统判为不支持的应用，更新后需结束该应用进程再打开；仅返回桌面不能清除缓存。");
        switchRow(content, Config.APPS, "扩展设置中的应用列表",
                "合并可见的桌面应用，保留已有应用开关设置。");
        switchRow(content, Config.SHARE, "加入系统分享",
                "在超级拖拽菜单中加入 Android 系统分享入口。");
        switchRow(content, Config.GLASS, "拖拽岛玻璃效果",
                "把底部黑色背景改为半透明深色玻璃，保留文字与图标清晰度。");

        section(content, "外观");
        sliderRow(content, Config.BLUR, "背景模糊强度", 100, Config.DEFAULT_BLUR,
                "仅调节背景模糊，0 为清晰背景。");
        sliderRow(content, Config.TINT, "玻璃底色浓度", 90, Config.DEFAULT_TINT,
                "越低越通透，越高越容易看清浅色文字。");

        section(content, "操作");
        disableAll = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
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
        content.addView(disableAll, fullWidth());

        restartScope = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonTonalStyle);
        restartScope.setText("重启当前作用域");
        restartScope.setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                .setTitle("重新加载拖放直达？")
                .setMessage("将短暂中断拖拽，仅重建拖放直达的应用进程，并打开其设置页。"
                        + "内容提取服务使用系统 UID，此按钮不会结束它；新增作用域后需等待它自然重建。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> restartScope())
                .show());
        content.addView(restartScope, fullWidth());

        MaterialButton vendorPage = new MaterialButton(this);
        vendorPage.setText("打开超级拖拽设置页");
        vendorPage.setOnClickListener(view -> openVendorPage());
        content.addView(vendorPage, fullWidth());

        restartResult = body(content, "重启按钮只重建拖放直达；内容提取服务等待系统自然重建进程后加载。", 13);
        restartResult.setAlpha(0.75f);
        body(content, "更改后请重新开始一次拖拽，或重新进入拖拽设置页。"
                + "Vector 已连接仅表示设置可同步，具体功能是否命中以实际操作为准。", 12);
        TextView version = body(content, "版本 " + BuildConfig.VERSION_NAME, 12);
        version.setAlpha(0.6f);
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
                    : "未连接 Vector 配置服务（不代表模块未启用）。点下方按钮自动重启本 App 重连。");
            reconnect.setVisibility(connected ? View.GONE : View.VISIBLE);
            disableAll.setEnabled(connected);
            restartScope.setEnabled(!ScopeRestarter.isRunning());
            for (Map.Entry<String, MaterialSwitch> entry : toggles.entrySet()) {
                entry.getValue().setEnabled(connected);
                entry.getValue().setChecked(connected && preferences.getBoolean(
                        entry.getKey(), Config.defaultValue(entry.getKey())));
            }
            for (Map.Entry<String, Slider> entry : sliders.entrySet()) {
                entry.getValue().setEnabled(connected && preferences.getBoolean(Config.GLASS, false));
                int fallback = Config.BLUR.equals(entry.getKey()) ? Config.DEFAULT_BLUR : Config.DEFAULT_TINT;
                entry.getValue().setValue(connected ? preferences.getInt(entry.getKey(), fallback) : fallback);
            }
        } catch (RuntimeException failure) {
            status.setText(R.string.settings_read_failed);
            disableAll.setEnabled(false);
            restartScope.setEnabled(!ScopeRestarter.isRunning());
            for (MaterialSwitch toggle : toggles.values()) toggle.setEnabled(false);
            for (Slider slider : sliders.values()) slider.setEnabled(false);
            reconnect.setVisibility(View.VISIBLE);
        } finally {
            rendering = false;
        }
    }

    private MaterialCardView card(LinearLayout parent) {
        MaterialCardView card = new MaterialCardView(this, null,
                com.google.android.material.R.attr.materialCardViewFilledStyle);
        card.setRadius(dp(20));
        card.setCardElevation(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        params.bottomMargin = dp(8);
        parent.addView(card, params);
        return card;
    }

    private LinearLayout column(MaterialCardView card) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(16), dp(20), dp(16));
        card.addView(column);
        return column;
    }

    private void section(LinearLayout parent, String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        header.setTextColor(MaterialColors.getColor(this,
                androidx.appcompat.R.attr.colorPrimary, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(20);
        params.bottomMargin = dp(4);
        parent.addView(header, params);
    }

    private void switchRow(LinearLayout parent, String key, String title, String description) {
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setText(title);
        toggle.setTextSize(16);
        toggle.setPadding(0, dp(12), 0, dp(2));
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
        TextView hint = body(parent, description, 13);
        hint.setAlpha(0.7f);
    }

    private TextView body(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        view.setTextSize(size);
        view.setPadding(0, dp(4), 0, dp(4));
        parent.addView(view);
        return view;
    }

    private void sliderRow(LinearLayout parent, String key, String title, int maximum,
            int fallback, String description) {
        TextView label = new TextView(this);
        label.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        label.setText(title + "：" + fallback);
        parent.addView(label);
        Slider slider = new Slider(this);
        slider.setValueFrom(0f);
        slider.setValueTo(maximum);
        slider.setStepSize(1f);
        slider.setValue(fallback);
        slider.setEnabled(false);
        slider.addOnChangeListener((bar, value, fromUser) ->
                label.setText(title + "：" + Math.round(value)));
        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider bar) {}

            @Override
            public void onStopTrackingTouch(Slider bar) {
                if (preferences == null) return;
                try {
                    preferences.edit().putInt(key, Math.round(bar.getValue())).apply();
                } catch (RuntimeException failure) {
                    saveFailed();
                    render();
                }
            }
        });
        sliders.put(key, slider);
        parent.addView(slider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView hint = body(parent, description, 12);
        hint.setAlpha(0.7f);
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

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        return params;
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
