package com.douai.module.ui;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.douai.module.scan.HostAdaptation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

/** No original host scroll hierarchy or successful host identity is required for startup diagnostics. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class HostStartupFallbackTest {
    private Activity activity;
    private HostUiController controller;
    @Before public void start() {
        HostAdaptation.setCurrentProfile(null);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(new FrameLayout(activity));
    }
    @After public void stop() {
        if (controller != null) controller.close();
        if (activity != null) activity.finish();
        HostAdaptation.setCurrentProfile(null);
        idle();
    }
    private Application app() { return RuntimeEnvironment.getApplication(); }
    private static void idle() { Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle(); }
    private View root() { return activity.findViewById(android.R.id.content); }
    private View open() { return root().findViewWithTag("douai.startup.open"); }

    @Test public void rejectedHostStillHasACopyableDiagnosticEntryAndNoOperationalSettingsOrAi() throws Exception {
        controller = new HostUiController(app(), ignored -> { }, false);
        controller.setHostAuthorization(false, "宿主文件不匹配", "版本字段相同，但 APK 身份检查未通过；业务功能未安装。");
        controller.onActivityResumed(activity); idle();
        assertNotNull(open()); assertEquals("融合诊断", ((TextView)open()).getText().toString());
        assertNull(root().findViewWithTag("DouAi.HostSettingsEntry"));
        open().performClick(); idle();
        Dialog diagnostic = ShadowDialog.getLatestDialog(); assertTrue(diagnostic.isShowing());
        assertTrue(allText(diagnostic.getWindow().getDecorView()).contains("宿主文件不匹配"));
        assertNull(field("settingsScreen")); assertNull(field("aiMessageEntry"));
        assertFalse(controller.startupSnapshot().getBoolean("startup_host_authorized"));
        ((android.app.AlertDialog)diagnostic).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        idle(); // AlertDialog dispatches its button listener through the main looper.
        ClipboardManager clipboard = (ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(clipboard.getPrimaryClip());
        assertTrue(clipboard.getPrimaryClip().getItemAt(0).getText().toString().contains("业务功能未安装"));
        assertNull(HostAdaptation.currentProfile());
    }

    @Test public void successfulCachedScanKeepsAWorkingSettingsEntryWithoutARecognizedSettingsScroll() throws Exception {
        AdaptationAcknowledgment acknowledgment = new AdaptationAcknowledgment(app()); acknowledgment.acknowledge("fallback-reviewed");
        controller = new HostUiController(app(), ignored -> { }, true);
        controller.complete(true, "rules=242, unique=242", "fallback-reviewed");
        controller.onActivityResumed(activity); idle();
        assertNotNull(open()); assertEquals("融合设置", ((TextView)open()).getText().toString());
        assertNull(root().findViewWithTag("DouAi.HostSettingsEntry"));
        open().performClick(); idle();
        assertNotNull(field("settingsScreen"));
        Dialog settings = (Dialog)field("settingsDialog"); assertNotNull(settings); assertTrue(settings.isShowing());
    }

    @Test public void floatingEntrySurvivesAContentRebuildAndOneDismissDoesNotKeepComingBack() throws Exception {
        controller = new HostUiController(app(), ignored -> { }, false);
        controller.onActivityResumed(activity); idle();
        View first = open(); assertNotNull(first);
        activity.setContentView(new FrameLayout(activity));
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout(); idle();
        assertNotNull(open()); assertNotSame(first, open());
        for (int index = 0; index < 4; index++) {
            controller.onActivityResumed(activity);
            activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout(); idle();
        }
        assertEquals(1, tagged(root(), "douai.startup.fallback"));
        root().findViewWithTag("douai.startup.dismiss").performClick(); idle(); assertNull(open());
        controller.onActivityResumed(activity); idle(); assertNull(open());
        assertTrue(controller.startupSnapshot().getBoolean("startup_floating_entry_dismissed"));
        controller.requestOpenSettings(); idle(); assertNotNull(open());
        assertFalse(controller.startupSnapshot().getBoolean("startup_floating_entry_dismissed"));
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
    }

    @Test public void failedScanRemainsInspectableAfterItsProgressDialogIsClosed() throws Exception {
        controller = new HostUiController(app(), ignored -> { }, false);
        controller.beginScan(); controller.onScanStarted(57); controller.onDexScanned(3, 57, "classes3.dex");
        controller.onActivityResumed(activity); idle();
        assertEquals(3, controller.startupSnapshot().getInt("startup_scan_completed_dex"));
        assertEquals(57, controller.startupSnapshot().getInt("startup_scan_total_dex"));
        controller.fail("扫描失败：当前 APK 的关键规则缺失"); idle();
        Dialog progress = ShadowDialog.getLatestDialog(); assertTrue(progress.isShowing());
        View close = findText(progress.getWindow().getDecorView(), "关闭"); assertNotNull(close); close.performClick(); idle();
        assertFalse(progress.isShowing()); assertNotNull(open());
        open().performClick(); idle();
        assertTrue(allText(ShadowDialog.getLatestDialog().getWindow().getDecorView()).contains("关键规则缺失"));
        assertNull(field("settingsScreen")); assertTrue(controller.startupSnapshot().getBoolean("startup_scan_failed"));
    }

    @Test public void aRequestBeforeActivityResumeIsDeliveredAsDiagnosticsWithoutAuthorizingTheHost() throws Exception {
        controller = new HostUiController(app(), ignored -> { }, false);
        controller.requestOpenSettings(); idle(); assertNull(open());
        controller.onActivityResumed(activity); idle();
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        assertNull(field("settingsScreen")); assertFalse(controller.startupSnapshot().getBoolean("startup_host_authorized"));
        controller.close();
        assertNull(open()); assertFalse(ShadowDialog.getLatestDialog().isShowing());
    }

    private Object field(String name) throws Exception { Field value = HostUiController.class.getDeclaredField(name); value.setAccessible(true); return value.get(controller); }
    private static int tagged(View view, String tag) {
        int result = tag.equals(view.getTag()) ? 1 : 0;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup)view).getChildCount(); index++) result += tagged(((ViewGroup)view).getChildAt(index), tag);
        return result;
    }
    private static String allText(View view) {
        StringBuilder result = new StringBuilder(view instanceof TextView ? ((TextView)view).getText().toString() : "");
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup)view).getChildCount(); index++) result.append('\n').append(allText(((ViewGroup)view).getChildAt(index)));
        return result.toString();
    }
    private static View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText())) return view;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup)view).getChildCount(); index++) { View found = findText(((ViewGroup)view).getChildAt(index), text); if (found != null) return found; }
        return null;
    }
}
