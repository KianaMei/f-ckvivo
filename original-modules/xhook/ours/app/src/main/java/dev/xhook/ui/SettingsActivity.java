package dev.xhook.ui;

import android.app.Activity;
import android.app.AlertDialog;
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
    private static final String[] DOMAIN_LABELS = {
            "保持原域名（x.com）",
            "fxtwitter.com（帖子内嵌预览）",
            "vxtwitter.com（帖子内嵌预览）",
            "fixupx.com（帖子内嵌预览）",
            "twitter.com"
    };
    private static final String[] DOMAIN_VALUES = {
            "",
            "fxtwitter.com",
            "vxtwitter.com",
            "fixupx.com",
            "twitter.com"
    };

    private SharedPreferences preferences;
    private TextView statusView;
    private boolean loading;

    private Switch videoButton;
    private Switch adFilter;
    private Switch hideUpsells;
    private Switch hideGrokPrompts;
    private Switch cleanShareLinks;
    private Button shareDomainButton;
    private Switch expandTco;
    private Switch blockHomeReselect;
    private Switch blockRefresh;
    private Switch spamFilter;
    private Switch spamHome;
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
        title.setText("XHook 0.4.0");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("在 LSPosed / Vector 中启用本模块，作用域勾选 com.twitter.android，重启 X 生效。\n"
                + "只 hook X 主进程；零权限、无上报、与 LiquidTab 零重叠。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        hint.setPadding(0, dp(8), 0, dp(10));
        root.addView(hint);

        statusView = new TextView(this);
        statusView.setText("正在连接 LibXposed Service…");
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        statusView.setTextColor(Color.DKGRAY);
        statusView.setPadding(0, 0, 0, dp(8));
        root.addView(statusView);

        addSection(root, "去广告与净化");
        adFilter = addSwitch(root, "三重去广告（推广帖 + RTB + SSP）",
                "数据层 itemBuilder 零空行拦截 + Google SSP 开关关闭 + 渲染层兜底。",
                Config.KEY_AD_FILTER, true);
        hideUpsells = addSwitch(root, "隐藏 Premium 升级推销",
                "关闭顶栏升级胶囊、启动付费墙、侧边栏认证卡片与发帖 Boost 提示。",
                Config.KEY_HIDE_UPSELLS, true);
        hideGrokPrompts = addSwitch(root, "隐藏帖子内 Grok 按钮与提示",
                "隐藏帖子下方的 Ask Grok / 分析按钮与 Imagine CTA（保留底栏 Grok Tab 以兼容 LiquidTab）。",
                Config.KEY_HIDE_GROK_PROMPTS, false);

        addSection(root, "视频下载与媒体");
        videoButton = addSwitch(root, "视频悬浮下载按钮（精准跟踪 + 可拖拽）",
                "仅在屏幕存在可见视频时显示，支持按住拖动记忆位置、多任务排队与实时进度；长按选画质。",
                Config.KEY_VIDEO_BUTTON, true);

        addSection(root, "链接与隐私");
        cleanShareLinks = addSwitch(root, "清理复制/分享链接追踪参数",
                "自动剥离 x.com / twitter.com 分享链接里的 s、t、utm_* 等追踪参数。",
                Config.KEY_CLEAN_SHARE_LINKS, true);

        shareDomainButton = new Button(this);
        updateDomainButtonText(preferences.getString(Config.KEY_SHARE_DOMAIN, ""));
        shareDomainButton.setOnClickListener(view -> showDomainDialog());
        LinearLayout.LayoutParams domLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        domLp.topMargin = dp(4);
        domLp.bottomMargin = dp(8);
        root.addView(shareDomainButton, domLp);

        expandTco = addSwitch(root, "外跳 t.co 短链直达",
                "点击外部 t.co 短链时后台解开 3xx 重定向并剥离追踪参数再打开目标应用。",
                Config.KEY_EXPAND_TCO, true);

        addSection(root, "时间线刷新守卫");
        blockHomeReselect = addSwitch(root, "阻止首页重选 / 顶栏双击刷新",
                "阻止点底栏 Home（<=12.43）或双击首页顶栏（12.45+）触发意外刷新跳顶。",
                Config.KEY_BLOCK_HOME_RESELECT, true);
        blockRefresh = addSwitch(root, "阻止回前台自动刷新",
                "从后台/锁屏回来且已有时间线时，拦截 VIEWPORT_AWARE 与无游标 AUTO_REFRESH；冷启放行。",
                Config.KEY_BLOCK_REFRESH, false);

        addSection(root, "机器人 / 垃圾帖规则过滤");
        spamFilter = addSwitch(root, "启用关键词 / 正则过滤",
                "在数据层零空行移除匹配帖子；默认仅过滤详情/对话回复，主页零扫描。",
                Config.KEY_SPAM_FILTER, false);
        spamHome = addSwitch(root, "主页时间线也启用规则过滤",
                "开启后主页与回复都会按下方规则过滤（仅在上方开关打开时生效）。",
                Config.KEY_SPAM_HOME, false);

        TextView keywordLabel = new TextView(this);
        keywordLabel.setText("规则（每行一条：普通词 / 正则 /regex/i / 屏蔽 @user / 白名单 !@user / # 注释）");
        keywordLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        keywordLabel.setPadding(0, dp(10), 0, dp(6));
        root.addView(keywordLabel);

        keywords = new EditText(this);
        keywords.setGravity(Gravity.TOP | Gravity.START);
        keywords.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        keywords.setMinLines(6);
        keywords.setHint("例如：\n加V\n/t\\.me\\/|私信领福利/i\n!@trusted_author");
        keywords.setText(preferences.getString(Config.KEY_KEYWORDS, ""));
        root.addView(keywords, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = new Button(this);
        save.setText("保存规则");
        save.setOnClickListener(view -> {
            String value = keywords.getText() == null ? "" : keywords.getText().toString();
            preferences.edit().putString(Config.KEY_KEYWORDS, value).apply();
            Toast.makeText(this, "规则已保存并同步", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp(12);
        root.addView(save, saveParams);

        Button reset = new Button(this);
        reset.setText("恢复默认");
        reset.setOnClickListener(view -> {
            preferences.edit().clear().apply();
            reloadFromPrefs();
            Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetParams.topMargin = dp(4);
        root.addView(reset, resetParams);

        setContentView(scroll);
        XHookApplication.whenServiceBound(this::onServiceReady);
    }

    private void addSection(LinearLayout parent, String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        header.setTextColor(0xFF1D9BF0);
        header.setPadding(0, dp(18), 0, dp(2));
        parent.addView(header);
    }

    private Switch addSwitch(LinearLayout parent,
                             String title,
                             String description,
                             String key,
                             boolean fallback) {
        Switch toggle = new Switch(this);
        toggle.setChecked(preferences.getBoolean(key, fallback));
        toggle.setText(title);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        toggle.setPadding(0, dp(10), 0, dp(2));
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
        descriptionView.setTextColor(Color.DKGRAY);
        descriptionView.setPadding(0, 0, 0, dp(4));
        parent.addView(descriptionView);
        return toggle;
    }

    private void showDomainDialog() {
        String current = preferences.getString(Config.KEY_SHARE_DOMAIN, "");
        int checked = 0;
        for (int i = 0; i < DOMAIN_VALUES.length; i++) {
            if (DOMAIN_VALUES[i].equalsIgnoreCase(current)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("分享链接域名替换")
                .setSingleChoiceItems(DOMAIN_LABELS, checked, (dialog, which) -> {
                    String val = DOMAIN_VALUES[which];
                    preferences.edit().putString(Config.KEY_SHARE_DOMAIN, val).apply();
                    updateDomainButtonText(val);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateDomainButtonText(String value) {
        String label = DOMAIN_LABELS[0];
        for (int i = 0; i < DOMAIN_VALUES.length; i++) {
            if (DOMAIN_VALUES[i].equalsIgnoreCase(value)) {
                label = DOMAIN_LABELS[i];
                break;
            }
        }
        shareDomainButton.setText("分享域名替换：" + label);
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
            statusView.setText("LibXposed Service 未连接：配置暂存本地；请确认模块已在 LSPosed 启用");
            return;
        }
        copyLocalToRemoteIfEmpty(remote);
        preferences = remote;
        reloadFromPrefs();
        statusView.setText("已连接 LibXposed Service：配置会实时同步到 X 进程");
    }

    private void copyLocalToRemoteIfEmpty(SharedPreferences remote) {
        if (remote.contains(Config.KEY_VIDEO_BUTTON)
                || remote.contains(Config.KEY_AD_FILTER)) {
            return;
        }
        SharedPreferences local = localPreferences();
        SharedPreferences.Editor editor = remote.edit();
        editor.putBoolean(Config.KEY_VIDEO_BUTTON, local.getBoolean(Config.KEY_VIDEO_BUTTON, true));
        editor.putBoolean(Config.KEY_AD_FILTER, local.getBoolean(Config.KEY_AD_FILTER, true));
        editor.putBoolean(Config.KEY_HIDE_UPSELLS, local.getBoolean(Config.KEY_HIDE_UPSELLS, true));
        editor.putBoolean(Config.KEY_HIDE_GROK_PROMPTS, local.getBoolean(Config.KEY_HIDE_GROK_PROMPTS, false));
        editor.putBoolean(Config.KEY_CLEAN_SHARE_LINKS, local.getBoolean(Config.KEY_CLEAN_SHARE_LINKS, true));
        editor.putString(Config.KEY_SHARE_DOMAIN, local.getString(Config.KEY_SHARE_DOMAIN, ""));
        editor.putBoolean(Config.KEY_EXPAND_TCO, local.getBoolean(Config.KEY_EXPAND_TCO, true));
        editor.putBoolean(Config.KEY_BLOCK_HOME_RESELECT, local.getBoolean(Config.KEY_BLOCK_HOME_RESELECT, true));
        editor.putBoolean(Config.KEY_BLOCK_REFRESH, local.getBoolean(Config.KEY_BLOCK_REFRESH, false));
        editor.putBoolean(Config.KEY_SPAM_FILTER, local.getBoolean(Config.KEY_SPAM_FILTER, false));
        editor.putBoolean(Config.KEY_SPAM_HOME, local.getBoolean(Config.KEY_SPAM_HOME, false));
        editor.putString(Config.KEY_KEYWORDS, local.getString(Config.KEY_KEYWORDS, ""));
        editor.apply();
    }

    private void reloadFromPrefs() {
        loading = true;
        try {
            videoButton.setChecked(preferences.getBoolean(Config.KEY_VIDEO_BUTTON, true));
            adFilter.setChecked(preferences.getBoolean(Config.KEY_AD_FILTER, true));
            hideUpsells.setChecked(preferences.getBoolean(Config.KEY_HIDE_UPSELLS, true));
            hideGrokPrompts.setChecked(preferences.getBoolean(Config.KEY_HIDE_GROK_PROMPTS, false));
            cleanShareLinks.setChecked(preferences.getBoolean(Config.KEY_CLEAN_SHARE_LINKS, true));
            updateDomainButtonText(preferences.getString(Config.KEY_SHARE_DOMAIN, ""));
            expandTco.setChecked(preferences.getBoolean(Config.KEY_EXPAND_TCO, true));
            blockHomeReselect.setChecked(preferences.getBoolean(Config.KEY_BLOCK_HOME_RESELECT, true));
            blockRefresh.setChecked(preferences.getBoolean(Config.KEY_BLOCK_REFRESH, false));
            spamFilter.setChecked(preferences.getBoolean(Config.KEY_SPAM_FILTER, false));
            spamHome.setChecked(preferences.getBoolean(Config.KEY_SPAM_HOME, false));
            keywords.setText(preferences.getString(Config.KEY_KEYWORDS, ""));
        } finally {
            loading = false;
        }
    }
}
