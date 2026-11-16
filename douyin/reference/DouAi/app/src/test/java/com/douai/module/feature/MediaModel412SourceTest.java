package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import com.douai.module.config.LocalOptions;
import com.google.gson.annotations.SerializedName;
import java.nio.file.Files;
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
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/**
 * Keeps the actual 41.2 serialized media roles in the route-to-save regression.
 * Original DEX contracts separately pin Video.z=new_download_addr, Video.y=bit_rate,
 * and VideoUrlModel's inherited UrlModel URL list. This test never executes host code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class MediaModel412SourceTest {
    public static final class Work {
        @SerializedName("aweme_id") public String aid = "412-work";
        @SerializedName("video") public Video video;
        public Control videoControl = new Control();
    }
    public static final class Control {
        public Boolean allowDownload = false;
        public Boolean downloadIgnoreVisibility = false;
        public int preventDownloadType = 3;
    }
    public static final class Video {
        @SerializedName("play_addr") public VideoUrls a;
        @SerializedName("play_addr_265") public VideoUrls b;
        @SerializedName("play_addr_h264") public VideoUrls d;
        @SerializedName("download_addr") public UrlBase v;
        @SerializedName("new_download_addr") public UrlBase z;
        @SerializedName("bit_rate") public List<BitRate> y;
    }
    public static class UrlBase {
        @SerializedName("url_list") public List<String> urlList;
        @SerializedName("download_url_list") public List<String> downUrlList;
        UrlBase(String... urls) { urlList = List.of(urls); }
    }
    public static final class VideoUrls extends UrlBase {
        VideoUrls(String... urls) { super(urls); }
    }
    public static final class BitRate {
        @SerializedName("play_addr") public UrlBase playAddr;
        @SerializedName("is_h265") public int isH265;
        BitRate(String source) { playAddr = new UrlBase(source); }
    }

    @Before public void start() { DownloadTaskTestIdle.beforeTest(); }
    @After public void finish() { DownloadTaskTestIdle.afterTest(); }

    @Test public void newDownloadAddressIsReadableForPresentationButDoesNotPretendToBeCleanPlayback() throws Exception {
        Work work = work();
        work.video.z = new UrlBase("https://media.example/official-new.mp4");
        assertEquals("The explicit Down selector already understands this actual 41.2 source",
                "https://media.example/official-new.mp4",
                UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.DOWN));
        assertNull("An official download source has no implicit clean-playback guarantee",
                UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.AUTO));
        try (MediaModelHook hook = new MediaModelHook(options(), ignored -> { })) {
            hook.apply(work);
            assertTrue("An existing new_download_addr must be recognized before local presentation flags are enabled",
                    work.videoControl.allowDownload);
            assertTrue(work.videoControl.downloadIgnoreVisibility);
            assertEquals(0, work.videoControl.preventDownloadType);
            assertTrue("Enabling local presentation must not relabel Down as clean media",
                    DownloadActionHook.mediaAssets(work, true, options()).isEmpty());
        }
        assertFalse(work.videoControl.allowDownload);
        assertFalse(work.videoControl.downloadIgnoreVisibility);
        assertEquals(3, work.videoControl.preventDownloadType);
    }

    @Test public void bitrateOnlyPlaybackUsesInheritedAnnotatedUrlListAndRestoresFlags() throws Exception {
        Work work = work();
        work.video.a = new VideoUrls();
        work.video.y = List.of(new BitRate("https://media.example/bitrate-only.mp4"));
        assertEquals("https://media.example/bitrate-only.mp4", DownloadActionHook.findPlaybackVideoUrl(work));
        try (MediaModelHook hook = new MediaModelHook(options(), ignored -> { })) {
            hook.apply(work);
            assertTrue(work.videoControl.allowDownload);
            assertEquals(0, work.videoControl.preventDownloadType);
            List<DownloadActionHook.MediaAsset> sources = DownloadActionHook.mediaAssets(work, true, options());
            assertEquals(1, sources.size());
            assertEquals("https://media.example/bitrate-only.mp4", sources.get(0).url);
        }
        assertFalse(work.videoControl.allowDownload);
        assertEquals(3, work.videoControl.preventDownloadType);
    }

    @Test public void explicitDownCanReadItsDownloadUrlListWithoutAdvertisingItAsPlayback() {
        Work work = work();
        work.video.z = new UrlBase();
        work.video.z.downUrlList = List.of("https://media.example/down-list.mp4");
        assertEquals("The explicit official source can use its supplied download_url_list",
                "https://media.example/down-list.mp4",
                UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.DOWN));
        assertNull("download_url_list must never be silently promoted to clean playback",
                UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.AUTO));
    }

    @Test public void missingAndNonHttpSourcesNeverAuthorizeLocalPresentation() throws Exception {
        Work work = work();
        work.video.z = new UrlBase("file:///private/video.mp4");
        try (MediaModelHook hook = new MediaModelHook(options(), ignored -> { })) {
            hook.apply(work);
            assertFalse(work.videoControl.allowDownload);
            assertFalse(work.videoControl.downloadIgnoreVisibility);
            assertEquals(3, work.videoControl.preventDownloadType);
        }
    }

    @Test public void choosingNewDownloadAddressCommitsExactlyThatSourceToTheChosenDirectory() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            server.enqueue(new MockResponse().addHeader("Content-Type", "video/mp4").setBody("new-download-body"));
            Work work = work();
            work.video.z = new UrlBase();
            work.video.z.downUrlList = List.of(server.url("/new-download.mp4").toString());
            try (OfficialDownloadDialog session = screen.open(work)) {
                OfficialDownloadTestUi.openGroup("视频");
                assertEquals("A Down-only work must wait for an explicit source choice", -1,
                        OfficialDownloadTestUi.indexOf("保存视频"));
                OfficialDownloadTestUi.chooseAction("选择编码 / 下载来源（Auto · H264 · H265 · Down）");
                assertNull("Opening the chooser must not start a task", server.takeRequest(100, TimeUnit.MILLISECONDS));
                OfficialDownloadTestUi.chooseAction("Down：官方下载源（可能带水印）");
                RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
                assertNotNull("Explicit Down selection must reach the real transfer", request);
                assertEquals("/new-download.mp4", request.getPath());
                screen.awaitSaved("new-download-body");
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test public void choosingBitrateOnlyPlaybackCommitsTheSuppliedUrlWithoutAnOfficialUrl() throws Exception {
        try (MockWebServer server = new MockWebServer(); Screen screen = new Screen()) {
            server.start();
            server.enqueue(new MockResponse().addHeader("Content-Type", "video/mp4").setBody("bitrate-body"));
            Work work = work();
            work.video.a = new VideoUrls();
            work.video.y = List.of(new BitRate(server.url("/bitrate.mp4").toString()));
            try (OfficialDownloadDialog session = screen.open(work)) {
                OfficialDownloadTestUi.chooseAction("保存视频");
                RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
                assertNotNull("Bitrate-only playback must reach the transfer", request);
                assertEquals("/bitrate.mp4", request.getPath());
                screen.awaitSaved("bitrate-body");
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    private static Work work() { Work work = new Work(); work.video = new Video(); return work; }
    private static LocalOptions options() {
        return new LocalOptions(Map.of("download_method", "official", "remove_watermark", true,
                "remove_download_limits", true, LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE));
    }
    private static final class Screen implements AutoCloseable {
        final ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        final DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        OfficialDownloadDialog open(Object work) {
            OfficialDownloadDialog session = new OfficialDownloadDialog(activity.get(), work, options(), ignored -> { }, () -> true);
            session.show(); return session;
        }
        void awaitSaved(String body) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            do {
                ShadowLooper.idleMainLooper();
                if (documents.files.size() == 1) {
                    var entry = documents.files.entrySet().iterator().next();
                    if (entry.getValue().isFile() && body.equals(Files.readString(entry.getValue().toPath()))) {
                        assertEquals("root", documents.parents.get(entry.getKey()));
                        assertEquals("video/mp4", documents.types.get(entry.getKey()));
                        return;
                    }
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            } while (System.nanoTime() < deadline);
            fail("The chosen source was not committed to the selected document tree");
        }
        @Override public void close() { activity.pause().stop().destroy(); }
    }
}
