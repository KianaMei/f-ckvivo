package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.TextView;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class DesktopSettingsScreenTest {
    private Activity activity;
    private ModuleSettingsScreen screen;
    private View home;
    private final MemoryStore store = new MemoryStore();

    @Before public void start() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        screen = ModuleSettingsScreen.desktop(activity, store, activity::finish);
        home = screen.createView(); activity.setContentView(home);
    }
    @After public void stop() { screen.close(); activity.finish(); }

    @Test public void allFeatureCategoriesAreReachableWithoutPretendingToBeTheHost() {
        assertFalse(store.isInHost());
        assertNotNull(home.findViewWithTag("settings.desktop.notice"));
        assertNotNull(home.findViewWithTag("settings.search"));
        for (String category : new String[]{"BASIC", "INTERFACE", "FILTER", "DOWNLOAD", "AUTOPLAY",
                "AUTO_REPLY", "AUTO_COMMENT", "COMMENT", "STREAK", "COMPONENT_HIDE", "MESSAGE",
                "SPOOF", "EXTENSION"}) {
            assertNotNull(category, home.findViewWithTag("settings.category." + category));
        }
        assertTrue(text(home).contains("没有回执时不能认定功能已启用"));
        assertNull(home.findViewWithTag("settings.category.ABOUT"));
    }

    @Test public void ordinaryFilterControlsCanBeSavedOffline() {
        View page = open("FILTER");
        Switch master = page.findViewWithTag("settings.toggle.过滤总开关");
        assertNotNull(master); master.setChecked(true);
        Switch ads = page.findViewWithTag("settings.toggle.去广告开关");
        assertNotNull(ads); ads.setChecked(true);
        assertTrue(store.features.feedFilterEnabled); assertTrue(store.features.hideAds);
        screen.dismissChildPages();
        ModuleSettingsScreen recreated = ModuleSettingsScreen.desktop(activity, store, null);
        View root = recreated.createView(); activity.setContentView(root);
        root.findViewWithTag("settings.category.FILTER").performClick();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(((Switch) dialog.getWindow().getDecorView().findViewWithTag("settings.toggle.过滤总开关")).isChecked());
        recreated.close();
    }

    @Test public void hostTasksAndDirectoryGrantsAreNotPerformedInTheModuleProcess() {
        View page = open("DOWNLOAD");
        page.findViewWithTag("settings.option.下载任务").performClick();
        assertTrue(ShadowToast.getTextOfLatestToast().contains("抖音进程"));
        page.findViewWithTag("settings.option.保存位置").performClick();
        assertTrue(ShadowToast.getTextOfLatestToast().contains("授权不能供抖音使用"));
        assertFalse(store.values.containsKey("download_directory_uri"));
    }

    @Test public void analyticsAndManualAiDoNotOpenASeparateFakeHostDataStore() {
        View page = open("EXTENSION");
        page.findViewWithTag("settings.option.本地数据分析").performClick();
        assertTrue(ShadowToast.getTextOfLatestToast().contains("浏览分析数据保存在抖音中"));
        assertFalse(new java.io.File(activity.getNoBackupFilesDir(), "douai-analytics-adapt412").exists());
        page.findViewWithTag("settings.option.AI 模型设置").performClick();
        assertNotNull(ShadowDialog.getLatestDialog());
    }

    @Test public void aDesktopUserListUsesManualIdsWithoutStartingTheHostDirectory() {
        View page = open("STREAK");
        ((Switch) page.findViewWithTag("settings.toggle.自动续火花")).setChecked(true);
        page.findViewWithTag("settings.option.选择用户").performClick();
        AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
        View root = picker.getWindow().getDecorView();
        assertNull(root.findViewWithTag("settings.followLists.members"));
        EditText ids = root.findViewWithTag("settings.desktop.userIds");
        assertNotNull(ids); ids.setText("123456，987654");
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(com.douai.module.feature.ReplyPolicy.targets("123456,987654"),
                com.douai.module.feature.ReplyPolicy.targets(store.getString("streak_targets", "")));
    }

    @Test public void desktopSpeechKeepsConfigurationButDefersHostPlaybackAndFileActions() {
        View page = open("COMMENT");
        page.findViewWithTag("settings.option.文本转语音设置").performClick();
        Dialog speech = ShadowDialog.getLatestDialog();
        View root = speech.getWindow().getDecorView();
        assertNotNull(root.findViewWithTag("speech.desktop.notice"));
        root.findViewWithTag("settings.option.试听").performClick();
        assertSame(speech, ShadowDialog.getLatestDialog());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("桌面可保存语音参数"));
    }

    private View open(String category) {
        home.findViewWithTag("settings.category." + category).performClick();
        Dialog dialog = ShadowDialog.getLatestDialog(); assertNotNull(dialog); assertTrue(dialog.isShowing());
        return dialog.getWindow().getDecorView();
    }
    private static String text(View view) {
        StringBuilder result = new StringBuilder(); if (view instanceof TextView) result.append(((TextView) view).getText());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) result.append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }
    private static final class MemoryStore implements SettingsStore {
        FeatureSettings features = FeatureSettings.defaults();
        final Map<String, Object> values = new HashMap<>();
        @Override public FeatureSettings load() { return features; }
        @Override public void save(FeatureSettings value) { features = value; }
        @Override public boolean getBoolean(String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback; }
        @Override public String getString(String key, String fallback) { Object value = values.get(key); return value instanceof String ? (String) value : fallback; }
        @Override public void putBoolean(String key, boolean value) { values.put(key, value); }
        @Override public void putString(String key, String value) { values.put(key, value); }
        @Override public Map<String, ?> snapshot() { return new HashMap<>(values); }
        @Override public void replaceSnapshot(Map<String, ?> before, Map<String, ?> after) { values.clear(); values.putAll(after); }
    }
}
