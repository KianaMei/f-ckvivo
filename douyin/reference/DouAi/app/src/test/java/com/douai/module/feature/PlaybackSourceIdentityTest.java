package com.douai.module.feature;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class PlaybackSourceIdentityTest {
    public static final class Model { public Reference vodVideoRef = new Reference(); }
    public static final class Reference { public String mVideoId, mFormat = "mp4"; }
    public static final class Info {
        public String mMainUrl;
        public int mMediaType;
        public boolean mEncrypt;
        Info(String url, int mediaType) { mMainUrl = url; mMediaType = mediaType; }
    }
    public static final class Work {
        @SerializedName("aweme_id") public String id;
        @SerializedName("video") public Video video = new Video();
        Work(String id, String vid) { this.id = id; video.play_addr.uri = vid; }
    }
    public static final class Video { public Url play_addr = new Url(); }
    public static final class Url { public String uri; }
    private Model model(String vid) { Model model = new Model(); model.vodVideoRef.mVideoId = vid; return model; }

    @Test public void preloadingAnotherVideoCannotReplaceTheSelectedWorkAndAnEmptyResultClearsOnlyItsOwnVid() {
        try (PlaybackSourceCaptureHook sources = new PlaybackSourceCaptureHook(null)) {
            Work first = new Work("12345", "vid-A"), next = new Work("67890", "vid-B");
            sources.capture(model("vid-A"), List.of(new Info("https://example.test/first.mp4", 0)));
            sources.capture(model("vid-B"), List.of(new Info("https://example.test/preload.mp4", 0)));
            assertEquals(List.of("https://example.test/first.mp4"), sources.playbackUrlsFor(first));
            assertEquals(List.of("https://example.test/preload.mp4"), sources.playbackUrlsFor(next));
            assertTrue(sources.playbackUrlsFor(new Work("99999", "unknown")).isEmpty());
            sources.capture(model("vid-A"), List.of());
            assertTrue(sources.playbackUrlsFor(first).isEmpty());
            assertEquals(1, sources.playbackUrlsFor(next).size());
        }
    }

    @Test public void audioSplitAndEncryptedEntriesAreNeverReturnedAsACompleteVideo() {
        try (PlaybackSourceCaptureHook sources = new PlaybackSourceCaptureHook(null)) {
            Work work = new Work("12345", "vid-A");
            Model model = model("vid-A");
            sources.capture(model, List.of(new Info("https://example.test/audio.m4a", 1),
                    new Info("https://example.test/silent.mp4", 0)));
            assertTrue(sources.playbackUrlsFor(work).isEmpty());
            Info encrypted = new Info("https://example.test/encrypted.mp4", 0);
            encrypted.mEncrypt = true;
            sources.capture(model, List.of(encrypted));
            assertTrue(sources.playbackUrlsFor(work).isEmpty());
            sources.capture(model, List.of(new Info("https://example.test/clear.mp4", 0)));
            assertEquals(List.of("https://example.test/clear.mp4"), sources.playbackUrlsFor(work));
            model.vodVideoRef.mFormat = "dash";
            sources.capture(model, List.of(new Info("https://example.test/fragment.mp4", 0)));
            assertTrue(sources.playbackUrlsFor(work).isEmpty());
        }
    }
}
