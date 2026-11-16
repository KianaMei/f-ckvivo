package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostAdaptation;
import com.google.gson.annotations.SerializedName;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

/** Drive the actual installed native-click hook, rather than only testing the row factory. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
@LooperMode(LooperMode.Mode.PAUSED)
public final class Download412WiringTest {
    private ActivityController<Activity> activity;
    private final Module module = new Module();
    private final Map<Method, XposedInterface.Hooker> installed = new HashMap<>();
    private DownloadActionHook hook;
    private int originalClicks;

    @Before public void setup() {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_412);
        activity = Robolectric.buildActivity(Activity.class).setup();
    }

    @After public void close() {
        if (hook != null) hook.close();
        for (android.app.Dialog dialog : org.robolectric.shadows.ShadowDialog.getShownDialogs())
            if (dialog.isShowing()) dialog.dismiss();
        if (activity != null) activity.pause().stop().destroy();
        HostAdaptation.setCurrentProfile(null);
        ShadowLooper.idleMainLooper();
    }

    @Test public void enabledNativeClickOpensTheCompletePickerWithoutNeedingExtraSwitches() throws Throwable {
        install(true, false, Map.of());
        click();
        assertEquals("An enabled module must own the real native click", 0, originalClicks);
        assertPickerCategory("视频");
        assertFalse("The host's missing-link path must not run", "下载链接不存在".equals(ShadowToast.getTextOfLatestToast()));
    }

    @Test public void allDownloadSwitchesStillOfferAnExplicitDownSourceWhenOnlyTheNewAddressExists() throws Throwable {
        install(true, true, Map.of("remove_download_limits", true, "download_custom_name", true,
                LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, false));
        click();
        assertEquals("A missing clean playback source must not silently hand control back to host denial", 0, originalClicks);
        assertPickerCategory("视频");
        choose("视频");
        choose("选择编码 / 下载来源（Auto · H264 · H265 · Down）");
        AlertDialog sources = latest();
        boolean downAvailable = false;
        for (int i = 0; i < sources.getListView().getCount(); i++) {
            String label = sources.getListView().getAdapter().getItem(i).toString();
            if (label.startsWith("Down：")) {
                assertFalse("The real new_download_addr must be available", label.contains("未下发"));
                downAvailable = true;
            }
        }
        assertTrue("The native entry must expose the same source chooser as gestures", downAvailable);
    }

    @Test public void emptyMediaKeepsTheFailureInsideTheModuleRatherThanCallingTheHostDenial() throws Throwable {
        module.work.video = null;
        install(true, true, Map.of("remove_download_limits", true));
        click();
        assertEquals("The module should report missing sources itself", 0, originalClicks);
        assertEquals("当前作品没有可保存的媒体地址", ShadowToast.getTextOfLatestToast());
    }

    @Test public void thirdPartyModeNeverStartsTheOfficialPickerOrNativeAction() throws Throwable {
        install(true, true, Map.of("download_method", "third_party"));
        click();
        assertEquals(0, originalClicks);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog != null && dialog.isShowing() && dialog.getListView() != null) {
            for (int i = 0; i < dialog.getListView().getCount(); i++)
                assertFalse("Third-party routing must not expose the official media picker", "视频".equals(dialog.getListView().getAdapter().getItem(i)));
        }
    }

    @Test public void disablingTheModulePreservesTheExactNativeClick() throws Throwable {
        install(false, true, Map.of("remove_download_limits", true));
        click();
        assertEquals(1, originalClicks);
        assertEquals("下载链接不存在", ShadowToast.getTextOfLatestToast());
    }

    @Test public void sharePanelSaveLocalExecutesTheSamePickerWithoutTheHostPermissionRoute() throws Throwable {
        install(true, true, Map.of("remove_download_limits", true));
        SharedAction shared = installShared();
        sharedClick(shared, activity.get());
        assertEquals("Share-panel save must not bypass the new module route", 0, originalClicks);
        assertPickerCategory("视频");
        choose("视频");
        choose("选择编码 / 下载来源（Auto · H264 · H265 · Down）");
        assertTrue(latest().getListView().getAdapter().getItem(3).toString().startsWith("Down："));
        assertFalse(latest().getListView().getAdapter().getItem(3).toString().contains("未下发"));
    }

    @Test public void disablingTheModuleAlsoPreservesTheExactSharePanelAction() throws Throwable {
        install(false, true, Map.of("remove_download_limits", true));
        SharedAction shared = installShared();
        sharedClick(shared, activity.get());
        assertEquals(1, originalClicks);
        assertEquals("不允许下载", ShadowToast.getTextOfLatestToast());
    }

    @Test public void sharePanelWithoutAnActivityReportsItsOwnFailureAndNeverExecutesTheHostAction() throws Throwable {
        install(true, true, Map.of());
        SharedAction shared = installShared();
        sharedClick(shared, RuntimeEnvironment.getApplication());
        assertEquals("A UI failure must not fall through to the native denial action", 0, originalClicks);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("No picker should remain attached to an application context", dialog == null || !dialog.isShowing());
        Bundle diagnostics = new Bundle(); hook.appendDiagnostics(diagnostics);
        String status = diagnostics.getString("download_entry_stage", "");
        assertTrue("A failed UI must have a visible failure state: " + status,
                status.contains("无法") || status.contains("未能") || status.contains("失败")
                        || status.contains("不可用") || status.contains("缺少"));
    }

    @Test public void workerOptionUpdateInvalidatesAnOldSaveButtonBeforeItsQueuedUiClose() throws Throwable {
        install(true, false, Map.of());
        click();
        choose("视频");
        AlertDialog stale = latest();
        int save = -1;
        for (int i = 0; i < stale.getListView().getCount(); i++)
            if ("保存视频".equals(stale.getListView().getAdapter().getItem(i))) save = i;
        assertTrue("The old page must contain an actual save action", save >= 0);
        int queuedBefore = DownloadTasks.snapshots().size();
        AtomicReference<Throwable> updateFailure = new AtomicReference<>();
        Thread update = new Thread(() -> {
            try { hook.setOptions(new LocalOptions(Map.of(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, true))); }
            catch (Throwable failure) { updateFailure.set(failure); }
        }, "test-download-option-worker");
        update.start(); update.join(5000);
        assertFalse("Option publication should not wait on the UI thread", update.isAlive());
        assertNull("Worker publication must not touch UI state unsafely", updateFailure.get());
        assertTrue("UI close is queued; validity must already be revoked", stale.isShowing());
        stale.getListView().performItemClick(null, save, save);
        ShadowLooper.idleMainLooper();
        assertEquals("An obsolete save button must not submit a download", queuedBefore, DownloadTasks.snapshots().size());
        assertFalse(stale.isShowing());
        assertEquals(0, originalClicks);
    }

    private void install(boolean enabled, boolean clean, Map<String, Object> options) throws Exception {
        var preferences = activity.get().getPreferences(Context.MODE_PRIVATE);
        preferences.edit().clear().putBoolean("download_enabled", enabled).putBoolean("remove_watermark", clean).commit();
        hook = DownloadActionHook.install(framework(), Module.class.getMethod("moduleName"),
                Module.class.getMethod("LJJI", Context.class, ViewGroup.class),
                Module.class.getMethod("LJJIJIIJIL", View.class), FeatureSettings.readFrom(preferences), ignored -> { });
        hook.setOptions(new LocalOptions(options));
        assertEquals("Both factory and actual click must be intercepted", 2, installed.size());
    }

    private void click() throws Throwable {
        View row = new View(activity.get());
        XposedInterface.Hooker intercept = installed.get(Module.class.getMethod("LJJIJIIJIL", View.class));
        assertNotNull("A factory-only test would not exercise the user's click", intercept);
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, arguments) -> {
                    if ("getThisObject".equals(method.getName())) return module;
                    if ("getArg".equals(method.getName())) return ((Integer) arguments[0]) == 0 ? row : null;
                    if ("proceed".equals(method.getName())) {
                        originalClicks++;
                        Toast.makeText(activity.get(), "下载链接不存在", Toast.LENGTH_SHORT).show();
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        intercept.intercept(chain);
        ShadowLooper.idleMainLooper();
    }

    private SharedAction installShared() throws Exception {
        hook.installSharedDownloadEntry(framework(), SharedAction.class.getMethod("execute", Context.class,
                com.ss.android.ugc.aweme.sharer.ui.SharePackage.class), SharedAction.class.getField("b"));
        assertEquals("Share execution is a separately installed entry", 3, installed.size());
        return new SharedAction(module.work);
    }

    private void sharedClick(SharedAction action, Context source) throws Throwable {
        XposedInterface.Hooker intercept = installed.get(SharedAction.class.getMethod("execute", Context.class,
                com.ss.android.ugc.aweme.sharer.ui.SharePackage.class));
        assertNotNull(intercept);
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, arguments) -> {
                    if ("getThisObject".equals(method.getName())) return action;
                    if ("getArg".equals(method.getName())) return ((Integer) arguments[0]) == 0 ? source
                            : new com.ss.android.ugc.aweme.sharer.ui.SharePackage();
                    if ("proceed".equals(method.getName())) {
                        originalClicks++;
                        Toast.makeText(activity.get(), "不允许下载", Toast.LENGTH_SHORT).show();
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        intercept.intercept(chain);
        ShadowLooper.idleMainLooper();
    }

    private XposedInterface framework() {
        return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, arguments) -> {
                    if (!"hook".equals(method.getName())) return null;
                    Method target = (Method) arguments[0];
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, operation, values) -> {
                                if ("intercept".equals(operation.getName())) {
                                    installed.put(target, (XposedInterface.Hooker) values[0]);
                                    return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                            new Class<?>[]{XposedInterface.HookHandle.class}, (handle, call, args) -> null);
                                }
                                return builder;
                            });
                });
    }

    private static AlertDialog latest() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog); assertTrue(dialog.isShowing()); assertNotNull(dialog.getListView());
        return dialog;
    }
    private static void assertPickerCategory(String title) {
        AlertDialog dialog = latest(); boolean found = false;
        for (int i = 0; i < dialog.getListView().getCount(); i++)
            if (title.equals(dialog.getListView().getAdapter().getItem(i))) found = true;
        assertTrue("Missing complete official-picker category: " + title, found);
    }
    private static void choose(String title) {
        AlertDialog dialog = latest(); int selected = -1;
        for (int i = 0; i < dialog.getListView().getCount(); i++)
            if (dialog.getListView().getAdapter().getItem(i).toString().equals(title)) selected = i;
        assertTrue("Missing official-picker choice: " + title, selected >= 0);
        dialog.getListView().performItemClick(null, selected, selected);
        ShadowLooper.idleMainLooper();
    }

    public static final class Module {
        final Work work = new Work();
        public String moduleName() { return "LppDownloadModule"; }
        public View LJJI(Context context, ViewGroup parent) { return new TextView(context); }
        public void LJJIJIIJIL(View view) { }
        public Work LJIIJ() { return work; }
    }
    public static final class SharedAction {
        public Work b;
        SharedAction(Work work) { b = work; }
        public void execute(Context context, com.ss.android.ugc.aweme.sharer.ui.SharePackage sharePackage) { }
    }
    public static final class Work {
        @SerializedName("aweme_id") public String id = "412-work";
        @SerializedName("video") public Video video = new Video();
        public String getAid() { return id; }
        public int getAwemeType() { return 0; }
    }
    public static final class Video {
        // The original 41.2 DEX contract maps Video.z to new_download_addr.
        @SerializedName("new_download_addr") public Url z = new Url();
    }
    public static final class Url {
        @SerializedName("url_list") public List<String> sources = List.of("https://media.example/new-official.mp4");
    }
}
