package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostAdaptation;
import com.ss.android.ugc.aweme.sharer.ui.SharePackage;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Drives the installed shared execute callback all the way to an actual SAF output file. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class Download412TransferIntegrationTest {
    @Before public void start() {
        DownloadTaskTestIdle.beforeTest();
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_412);
    }
    @After public void stop() {
        DownloadTaskTestIdle.afterTest();
        for (android.app.Dialog dialog : ShadowDialog.getShownDialogs()) if (dialog.isShowing()) dialog.dismiss();
        HostAdaptation.setCurrentProfile(null);
        ShadowLooper.idleMainLooper();
    }

    @Test public void sharedExecuteSelectionFetchesPlaybackAndCommitsExactBytesInTheSelectedTree() throws Throwable {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen(false)) {
            server.start();
            byte[] content = "actual-shared-playback-bytes".getBytes(StandardCharsets.UTF_8);
            server.enqueue(new MockResponse().addHeader("Content-Type", "video/mp4").setBody(new okio.Buffer().write(content)));
            MediaModel412SourceTest.Work work = new MediaModel412SourceTest.Work();
            work.video = new MediaModel412SourceTest.Video();
            work.video.a = new MediaModel412SourceTest.VideoUrls(server.url("/shared-playback.mp4").toString());

            screen.execute(work);
            assertEquals("The real shared execute hook must own the click", 0, screen.originalExecutions);
            assertNull("Opening categories must not submit a transfer", server.takeRequest(100, TimeUnit.MILLISECONDS));
            OfficialDownloadTestUi.chooseAction("保存视频");

            RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull("The selected playback source must reach the real HTTP transfer", request);
            assertEquals("/shared-playback.mp4", request.getPath());
            screen.awaitSaved(content);
            assertEquals(1, server.getRequestCount());
            assertEquals(0, screen.originalExecutions);
            Bundle diagnostics = screen.diagnostics();
            assertEquals(1, diagnostics.getInt("download_shared_clicks"));
            assertEquals(1, diagnostics.getInt("download_picker_opened"));
        }
    }

    @Test public void directSaveWithCleanRequiredAndDownOnlyWaitsForExplicitDownThenCommitsThatFile() throws Throwable {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen(true)) {
            server.start();
            byte[] content = "actual-explicit-down-bytes".getBytes(StandardCharsets.UTF_8);
            server.enqueue(new MockResponse().addHeader("Content-Type", "video/mp4").setBody(new okio.Buffer().write(content)));
            MediaModel412SourceTest.Work work = new MediaModel412SourceTest.Work();
            work.video = new MediaModel412SourceTest.Video();
            work.video.z = new MediaModel412SourceTest.UrlBase();
            work.video.z.downUrlList = List.of(server.url("/shared-official-down.mp4").toString());

            screen.execute(work);
            assertEquals("A Down-only item must not fall through to native denial", 0, screen.originalExecutions);
            assertNull("Direct mode must not silently relabel an official source as clean", server.takeRequest(100, TimeUnit.MILLISECONDS));
            assertTrue(screen.documents.files.isEmpty());
            assertEquals(0, screen.diagnostics().getInt("download_direct_requests"));
            OfficialDownloadTestUi.openGroup("视频");
            assertEquals("There is no clean playback source to save directly", -1, OfficialDownloadTestUi.indexOf("保存视频"));
            OfficialDownloadTestUi.chooseAction("选择编码 / 下载来源（Auto · H264 · H265 · Down）");
            assertNull("The source chooser itself must not download", server.takeRequest(100, TimeUnit.MILLISECONDS));
            OfficialDownloadTestUi.chooseAction("Down：官方下载源（可能带水印）");

            RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull("Explicit Down selection must reach the real HTTP transfer", request);
            assertEquals("/shared-official-down.mp4", request.getPath());
            screen.awaitSaved(content);
            assertEquals(1, server.getRequestCount());
            assertEquals(0, screen.originalExecutions);
            assertTrue(screen.diagnostics().getBoolean("download_shared_hook_installed"));
        }
    }

    public static final class NativeModule {
        public String moduleName() { return "LppDownloadModule"; }
        public View LJJI(Context context, ViewGroup parent) { return new View(context); }
        public void LJJIJIIJIL(View view) { }
    }
    public static final class SharedAction {
        public Object b;
        SharedAction(Object work) { b = work; }
        public void execute(Context context, SharePackage sharePackage) { }
    }

    private static final class Screen implements AutoCloseable {
        final ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        final DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        final Map<Method, XposedInterface.Hooker> installed = new HashMap<>();
        final DownloadActionHook hook;
        int originalExecutions;

        Screen(boolean direct) throws Exception {
            var preferences = activity.get().getPreferences(Context.MODE_PRIVATE);
            preferences.edit().clear().putBoolean("download_enabled", true).putBoolean("remove_watermark", true).commit();
            XposedInterface framework = framework();
            hook = DownloadActionHook.install(framework, NativeModule.class.getMethod("moduleName"),
                    NativeModule.class.getMethod("LJJI", Context.class, ViewGroup.class),
                    NativeModule.class.getMethod("LJJIJIIJIL", View.class), FeatureSettings.readFrom(preferences), ignored -> { });
            hook.setOptions(new LocalOptions(Map.of("download_method", "official", "remove_download_limits", true,
                    LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, direct,
                    LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE)));
            hook.installSharedDownloadEntry(framework, SharedAction.class.getMethod("execute", Context.class, SharePackage.class),
                    SharedAction.class.getField("b"));
        }

        void execute(Object work) throws Throwable {
            SharedAction owner = new SharedAction(work);
            Method target = SharedAction.class.getMethod("execute", Context.class, SharePackage.class);
            XposedInterface.Hooker callback = installed.get(target);
            assertNotNull("Drive the actual hook registered for shared execute", callback);
            Object[] arguments = {activity.get(), new SharePackage()};
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, values) -> {
                        if ("getThisObject".equals(method.getName())) return owner;
                        if ("getArg".equals(method.getName())) return arguments[(Integer) values[0]];
                        if ("proceed".equals(method.getName())) { originalExecutions++; return null; }
                        throw new UnsupportedOperationException(method.getName());
                    });
            callback.intercept(chain);
            ShadowLooper.idleMainLooper();
            // The production selection owns its action until close; the test harness
            // must not require the already-dismissed host panel to retain that action.
        }

        Bundle diagnostics() { Bundle result = new Bundle(); hook.appendDiagnostics(result); return result; }

        private XposedInterface framework() {
            return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                    (proxy, method, values) -> {
                        if (!"hook".equals(method.getName())) return null;
                        Method target = (Method) values[0];
                        return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                                (builder, operation, args) -> {
                                    if ("intercept".equals(operation.getName())) {
                                        installed.put(target, (XposedInterface.Hooker) args[0]);
                                        return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                                                (handle, call, input) -> null);
                                    }
                                    return builder;
                                });
                    });
        }

        void awaitSaved(byte[] expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            do {
                ShadowLooper.idleMainLooper();
                if (documents.files.size() == 1) {
                    var entry = documents.files.entrySet().iterator().next();
                    if (entry.getValue().isFile() && java.util.Arrays.equals(expected, Files.readAllBytes(entry.getValue().toPath()))) {
                        assertArrayEquals(expected, Files.readAllBytes(entry.getValue().toPath()));
                        assertEquals("root", documents.parents.get(entry.getKey()));
                        assertEquals("video/mp4", documents.types.get(entry.getKey()));
                        return;
                    }
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            } while (System.nanoTime() < deadline);
            fail("Shared execute selection did not commit the expected bytes to the selected document tree");
        }

        @Override public void close() {
            hook.close();
            activity.pause().stop().destroy();
        }
    }
}
