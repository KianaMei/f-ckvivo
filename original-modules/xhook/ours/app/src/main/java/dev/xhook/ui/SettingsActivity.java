package dev.xhook.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import dev.xhook.Config;
import dev.xhook.Runtime;
import dev.xhook.XHookApplication;

/** 模块设置页：通过 LibXposed Service 写 Remote Preferences，X 进程读同一组。 */
public final class SettingsActivity extends Activity {
    private SharedPreferences preferences;
    private TextView statusView;
    private boolean loading;

    private Switch videoButton;
    private Switch adFilter;
    private Switch spamFilter;
    private Switch spamHome;
    private Switch blockHomeReselect;
    private Switch blockRefresh;
    private EditText keywords;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = localPreferences();

        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("XHook");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("在 Vector / LSPosed 中启用本模块，作用域勾选 com.twitter.android，"
                + "然后重启 X。改动会实时同步；若未生效，重启 X 后再看。\n\n"
                + "只 hook X 主进程；不读通知、不采集数据、没有任何网络上报。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        statusView = new TextView(this);
        statusView.setText("正在连接 LibXposed Service…");
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        statusView.setTextColor(Color.DKGRAY);
        statusView.setPadding(0, 0, 0, dp(8));
        root.addView(statusView);

        videoButton = addSwitch(root, "视频下载按钮",
                "在 X 视频播放页右下角显示下载按钮；长按可选画质。",
                Config.KEY_VIDEO_BUTTON, true);
        adFilter = addSwitch(root, "去推广 / 广告（实验）",
                "按 entryId 规则拦截 promoted / RTB 条目；找不到目标方法时不生效。",
                Config.KEY_AD_FILTER, true);
        spamFilter = addSwitch(root, "机器人 / 垃圾帖过滤",
                "按关键词匹配帖子正文；默认只在详情/对话页启用，主页零扫描。",
                Config.KEY_SPAM_FILTER, false);
        spamHome = addSwitch(root, "主页也做关键词过滤",
                "会增加主页滚动时的开销；只有打开上面的开关才有效。",
                Config.KEY_SPAM_HOME, false);
        blockHomeReselect = addSwitch(root, "阻止首页重选刷新",
                "点底栏 Home 时不再自动刷新（对应 Re:X 的 Home reselect guard）。",
                Config.KEY_BLOCK_HOME_RESELECT, true);
        blockRefresh = addSwitch(root, "阻止回前台自动刷新（实验）",
                "从后台/锁屏回来时，拦截 VIEWPORT_AWARE_AUTO_REFRESH；首次启动放行，避免空白信息流。",
                Config.KEY_BLOCK_REFRESH, false);

        TextView keywordLabel = new TextView(this);
        keywordLabel.setText("关键词（每行一个，# 开头为注释）");
        keywordLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        keywordLabel.setPadding(0, dp(16), 0, dp(6));
        root.addView(keywordLabel);

        keywords = new EditText(this);
        keywords.setGravity(Gravity.TOP | Gravity.START);
        keywords.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        keywords.setMinLines(6);
        keywords.setHint("例如：\n加V\n私信领福利\nhttps://t.me/");
        keywords.setText(preferences.getString(Config.KEY_KEYWORDS, ""));
        root.addView(keywords, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = new Button(this);
        save.setText("保存关键词");
        save.setOnClickListener(view -> {
            String value = keywords.getText() == null ? "" : keywords.getText().toString();
            preferences.edit().putString(Config.KEY_KEYWORDS, value).apply();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp(12);
        root.addView(save, saveParams);

        Button reset = new Button(this);
        reset.setText("恢复默认");
        reset.setOnClickListener(view -> {
            preferences.edit().clear().apply();
            videoButton.setChecked(true);
            adFilter.setChecked(true);
            spamFilter.setChecked(false);
            spamHome.setChecked(false);
            blockHomeReselect.setChecked(true);
            blockRefresh.setChecked(false);
            keywords.setText("");
            Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetParams.topMargin = dp(4);
        root.addView(reset, resetParams);

        setContentView(scroll);
        XHookApplication.whenServiceBound(this::onServiceReady);
    }

    private Switch addSwitch(LinearLayout parent,
                             String title,
                             String description,
                             String key,
                             boolean fallback) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        titleView.setPadding(0, dp(14), 0, 0);
        parent.addView(titleView);

        Switch toggle = new Switch(this);
        toggle.setChecked(preferences.getBoolean(key, fallback));
        toggle.setText(title);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (loading) {
                return;
            }
            preferences.edit().putBoolean(key, checked).apply();
        });
        parent.addView(toggle);

        TextView descriptionView = new TextView(this);
        descriptionView.setText(description);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        descriptionView.setPadding(0, 0, 0, dp(4));
        parent.addView(descriptionView);
        return toggle;
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private SharedPreferences localPreferences() {
        return getSharedPreferences(Runtime.PREFS, MODE_PRIVATE);
    }

    private void onServiceReady() {
        if (statusView == null) {
            return;
        }
        SharedPreferences remote = XHookApplication.remotePreferences(Runtime.PREFS);
        if (remote == null) {
            statusView.setText("LibXposed Service 未连接：配置暂存本地；请确认模块已在 Vector 启用");
            return;
        }
        copyLocalToRemoteIfEmpty(remote);
        preferences = remote;
        reloadFromPrefs();
        statusView.setText("已连接 LibXposed Service：配置会实时同步到 X 进程");
    }

    private void copyLocalToRemoteIfEmpty(SharedPreferences remote) {
        if (remote.contains(Config.KEY_VIDEO_BUTTON)
                || remote.contains(Config.KEY_BLOCK_REFRESH)) {
            return;
        }
        SharedPreferences local = localPreferences();
        SharedPreferences.Editor editor = remote.edit();
        editor.putBoolean(Config.KEY_VIDEO_BUTTON,
                local.getBoolean(Config.KEY_VIDEO_BUTTON, true));
        editor.putBoolean(Config.KEY_AD_FILTER,
                local.getBoolean(Config.KEY_AD_FILTER, true));
        editor.putBoolean(Config.KEY_SPAM_FILTER,
                local.getBoolean(Config.KEY_SPAM_FILTER, false));
        editor.putBoolean(Config.KEY_SPAM_HOME,
                local.getBoolean(Config.KEY_SPAM_HOME, false));
        editor.putBoolean(Config.KEY_BLOCK_HOME_RESELECT,
                local.getBoolean(Config.KEY_BLOCK_HOME_RESELECT, true));
        editor.putBoolean(Config.KEY_BLOCK_REFRESH,
                local.getBoolean(Config.KEY_BLOCK_REFRESH, true));
        editor.putString(Config.KEY_KEYWORDS,
                local.getString(Config.KEY_KEYWORDS, ""));
        editor.apply();
    }

    private void reloadFromPrefs() {
        loading = true;
        try {
            videoButton.setChecked(preferences.getBoolean(Config.KEY_VIDEO_BUTTON, true));
            adFilter.setChecked(preferences.getBoolean(Config.KEY_AD_FILTER, true));
            spamFilter.setChecked(preferences.getBoolean(Config.KEY_SPAM_FILTER, false));
            spamHome.setChecked(preferences.getBoolean(Config.KEY_SPAM_HOME, false));
            blockHomeReselect.setChecked(
                    preferences.getBoolean(Config.KEY_BLOCK_HOME_RESELECT, true));
            blockRefresh.setChecked(preferences.getBoolean(Config.KEY_BLOCK_REFRESH, true));
            keywords.setText(preferences.getString(Config.KEY_KEYWORDS, ""));
        } finally {
            loading = false;
        }
    }
}
