package com.douai.module.feature;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostAdaptation;
import com.ss.android.ugc.aweme.im.share.aweme.only.sharepanel.ui.ShareFunctionAdapter;
import com.ss.android.ugc.aweme.im.share.aweme.only.sharepanel.ui.SharePanelDialog;
import com.ss.android.ugc.aweme.im.share.aweme.only.sharepanel.ui.WithPadSharePanelDialog;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ShareDownloadEntryTest {
    public static final class Package { public String identifier = "12345"; public Bundle extras = new Bundle(); }
    public static final class Work { public String aweme_id = "12345"; }
    public static final class Adapter extends ShareFunctionAdapter { }
    private Activity activity;
    private Dialog sheet;
    private DownloadActionHook downloads;
    private PlaybackSourceCaptureHook sources;
    private ShareDownloadEntryHook entry;
    private final Map<Method, XposedInterface.Hooker> installed = new HashMap<>();

    @Before public void setup() throws Exception {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_412);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        var preferences = activity.getPreferences(Context.MODE_PRIVATE);
        preferences.edit().putBoolean("download_enabled", true).commit();
        XposedInterface framework = (XposedInterface)Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method)arguments[0];
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                installed.put(target, (XposedInterface.Hooker)values[0]);
                                return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, call, args) -> null);
                            });
                });
        downloads = DownloadActionHook.install(framework, Native.class.getMethod("moduleName"),
                Native.class.getMethod("LJJI", Context.class, ViewGroup.class), Native.class.getMethod("LJJIJIIJIL", View.class),
                FeatureSettings.readFrom(preferences), null);
        downloads.setOptions(new LocalOptions(Map.of()));
        sources = new PlaybackSourceCaptureHook(null);
        entry = ShareDownloadEntryHook.install(framework, getClass().getClassLoader(), sources, downloads, null);
    }
    @After public void cleanup() {
        if (entry != null) entry.close(); if (downloads != null) downloads.close(); if (sources != null) sources.close();
        if (sheet != null) sheet.dismiss(); if (activity != null) activity.finish(); HostAdaptation.setCurrentProfile(null);
    }
    public static final class Native {
        public String moduleName() { return "LppDownloadModule"; }
        public View LJJI(Context context, ViewGroup parent) { return new View(context); }
        public void LJJIJIIJIL(View view) { }
    }

    @Test public void phoneAndPadShareRowsUseTheSameDownloadAction() throws Throwable {
        SharePanelDialog phone = new SharePanelDialog(activity); sheet = phone;
        Adapter adapter = showRows(phone); phone.sharePackage = new Package();
        assertNotNull(button()); click(button()); assertTrue(phone.isShowing());
        sources.rememberWork(new Work()); click(button()); assertFalse(phone.isShowing());
        reachedDownloader();
        WithPadSharePanelDialog pad = new WithPadSharePanelDialog(activity); sheet = pad;
        adapter = showRows(pad); pad.sharePackage = new Package(); sources.rememberWork(new Work());
        click(button()); assertFalse("Pad and phone must share the same row action", pad.isShowing());
        entry.close(); bind(adapter); assertNull(button());
    }

    @Test public void disabledDownloadKeepsTheSharedRowAndExplainsTheSwitch() throws Throwable {
        activity.getPreferences(Context.MODE_PRIVATE).edit().putBoolean("download_enabled", false).commit();
        downloads.setSettings(FeatureSettings.readFrom(activity.getPreferences(Context.MODE_PRIVATE)));
        WithPadSharePanelDialog pad = new WithPadSharePanelDialog(activity); sheet = pad; showRows(pad);
        click(button()); assertTrue(pad.isShowing()); assertEquals("请先在融合总菜单开启下载功能。", ShadowToast.getTextOfLatestToast());
        assertEquals(entry.describeDiagnostics(), "请先在融合总菜单开启下载功能。", ShadowToast.getTextOfLatestToast());
    }

    @Test public void ordinaryListsDoNotReceiveTheModuleRow() throws Throwable {
        sheet = new Dialog(activity); HorizontalScrollView functions = new HorizontalScrollView(activity);
        LinearLayout rows = new LinearLayout(activity); rows.addView(functions); sheet.setContentView(rows); sheet.show();
        Adapter adapter = new Adapter(); adapter.d = functions; bind(adapter);
        assertNull(button());
    }

    private Adapter showRows(SharePanelDialog panel) throws Throwable {
        LinearLayout rows = new LinearLayout(activity); rows.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView functions = new HorizontalScrollView(activity); rows.addView(functions);
        panel.setContentView(rows); Adapter adapter = new Adapter(); adapter.d = functions; panel.p = adapter;
        panel.show();
        Method show=android.app.Dialog.class.getDeclaredMethod("show");
        XposedInterface.Hooker shown=installed.get(show); assertNotNull(shown);
        XposedInterface.Chain showChain=(XposedInterface.Chain)Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, arguments)-> method.getName().equals("getThisObject") ? panel : null);
        shown.intercept(showChain); bind(adapter); return adapter;
    }
    private void bind(Object adapter) throws Throwable {
        Method target = ShareFunctionAdapter.class.getDeclaredMethod("setData", java.util.List.class);
        XposedInterface.Hooker hook = installed.get(target); assertNotNull(hook);
        XposedInterface.Chain chain = (XposedInterface.Chain)Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, arguments) -> method.getName().equals("getThisObject") ? adapter : null);
        hook.intercept(chain);
    }
    private View button() { return sheet.getWindow().getDecorView().findViewWithTag(ShareDownloadEntryHook.ENTRY_TAG); }
    private static void click(View row) {
        Object action = row.getTag(ShareDownloadEntryHook.ACTION_TAG);
        assertTrue(action instanceof Runnable); ((Runnable)action).run();
        ((Runnable)action).run();
    }
    private void reachedDownloader() { Bundle status = new Bundle(); downloads.appendDiagnostics(status); assertEquals("没有可保存的媒体来源", status.getString("download_entry_stage")); }
}
