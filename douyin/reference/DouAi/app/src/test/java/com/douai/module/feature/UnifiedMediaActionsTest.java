package com.douai.module.feature;

import com.google.gson.annotations.SerializedName;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class UnifiedMediaActionsTest {
    @Test public void explicitEncodingUsesOnlyTheSuppliedMatchingSource() throws Exception {
        JSONObject work = work(new JSONObject().put("play_addr", urls("auto.mp4"))
                .put("play_addr_h264", urls("h264.mp4")).put("play_addr_265", urls("h265.mp4"))
                .put("download_addr", urls("down.mp4")));
        assertEquals(cdn("auto.mp4"), UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.AUTO));
        assertEquals(cdn("h264.mp4"), UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.H264));
        assertEquals(cdn("h265.mp4"), UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.H265));
        assertEquals(cdn("down.mp4"), UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.DOWN));
    }
    @Test public void anUnmarkedAutoSourceNeverPretendsToBeTheRequestedCodec() throws Exception {
        JSONObject work = work(new JSONObject().put("play_addr", urls("unknown.mp4")));
        assertNull(UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.H264));
        assertNull(UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.H265));
        assertNull(UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.DOWN));
        JSONObject marked = work(new JSONObject().put("play_addr", urls("hevc.mp4")).put("is_h265", 1));
        assertEquals(cdn("hevc.mp4"), UnifiedMediaActions.videoSource(marked, UnifiedMediaActions.Encoding.H265));
        assertNull(UnifiedMediaActions.videoSource(marked, UnifiedMediaActions.Encoding.H264));
    }
    @Test public void markedBitratesAndTheBytevcSourceRetainTheirRoles() throws Exception {
        JSONObject video = new JSONObject().put("bit_rate", new JSONArray()
                .put(new JSONObject().put("play_addr", urls("hevc.mp4")).put("is_h265", 1))
                .put(new JSONObject().put("play_addr", urls("avc.mp4")).put("is_h265", 0)));
        assertEquals(cdn("avc.mp4"), UnifiedMediaActions.videoSource(work(video), UnifiedMediaActions.Encoding.H264));
        assertEquals(cdn("hevc.mp4"), UnifiedMediaActions.videoSource(work(video), UnifiedMediaActions.Encoding.H265));
        assertEquals(cdn("bytevc.mp4"), UnifiedMediaActions.videoSource(work(new JSONObject().put("play_addr_bytevc1", urls("bytevc.mp4"))), UnifiedMediaActions.Encoding.H265));
    }
    public static final class ObfuscatedWork {
        @SerializedName("video") public ObfuscatedVideo x = new ObfuscatedVideo();
    }
    public static final class ObfuscatedVideo {
        @SerializedName("play_addr_h264") public ObfuscatedUrls d = new ObfuscatedUrls();
    }
    public static final class ObfuscatedUrls {
        @SerializedName("url_list") public List<String> z = List.of(cdn("original.mp4"));
    }
    @Test public void runtimeJsonAnnotationsResolveObfuscatedModelFields() {
        assertEquals(cdn("original.mp4"), UnifiedMediaActions.videoSource(new ObfuscatedWork(), UnifiedMediaActions.Encoding.H264));
    }
    @Test public void authorAvatarUsesLargerWithoutEditingItsResizeQuery() throws Exception {
        String source = cdn("portrait.webp?width=1080&token=original");
        JSONObject author = new JSONObject().put("avatar_larger", new JSONObject().put("url_list", new JSONArray().put(source)))
                .put("avatar_thumb", urls("small.jpg"));
        UnifiedMediaActions.Source avatar = UnifiedMediaActions.avatar(new JSONObject().put("author", author));
        assertEquals(source, avatar.url); assertEquals("webp", avatar.extension); assertEquals("image/webp", avatar.mime);
        assertTrue(avatar.image);
        assertNull(UnifiedMediaActions.avatar(new JSONObject().put("author", new JSONObject().put("avatar_thumb", urls("small.jpg")))));
    }
    @Test public void commentVideosUseTheAliasWorkAndDeduplicateItsSameSource() throws Exception {
        JSONObject alias = new JSONObject().put("alias_aweme", work(new JSONObject().put("download_addr", urls("comment.mp4"))));
        JSONObject comment = new JSONObject().put("video_list", new JSONArray().put(alias).put(alias));
        List<UnifiedMediaActions.Source> found = UnifiedMediaActions.commentVideos(comment);
        assertEquals(1, found.size()); assertEquals(cdn("comment.mp4"), found.get(0).url);
        assertEquals("video/mp4", found.get(0).mime);
    }
    @Test public void commentAudioDecodesARealPlaybackUrlAndPreservesContainer() throws Exception {
        String original = cdn("voice?expires=42");
        String encoded = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
        JSONObject playback = new JSONObject().put("data", new JSONObject().put("Format", "mp4")
                .put("MainPlayUrl", encoded).put("BackupPlayUrl", cdn("backup?expires=42")));
        List<UnifiedMediaActions.Source> found = UnifiedMediaActions.commentAudio(audio(playback.toString()));
        assertEquals(1, found.size()); assertEquals(original, found.get(0).url);
        assertEquals("m4a", found.get(0).extension); assertEquals("audio/mp4", found.get(0).mime);
    }
    @Test public void vidOnlyAndPlaylistOrImageMetadataNeverBecomeAnAudioDownload() throws Exception {
        assertTrue(UnifiedMediaActions.commentAudio(new JSONObject().put("comment_audio", new JSONObject().put("vid", "v123"))).isEmpty());
        assertTrue(UnifiedMediaActions.commentAudio(audio(new JSONObject().put("url", cdn("stream.m3u8")).toString())).isEmpty());
        assertTrue(UnifiedMediaActions.commentAudio(audio(new JSONObject().put("url", cdn("cover.jpg")).toString())).isEmpty());
        assertTrue(UnifiedMediaActions.commentAudio(audio(new JSONObject().put("vid", "v123").put("text", cdn("unrelated.mp3")).toString())).isEmpty());
    }
    @Test public void unknownAudioIsNotRenamedToMp3AndDeepPayloadIsRejected() throws Exception {
        List<UnifiedMediaActions.Source> found = UnifiedMediaActions.commentAudio(audio(new JSONObject().put("main_url", cdn("voice?id=1")).toString()));
        assertEquals("audio", found.get(0).extension); assertEquals("application/octet-stream", found.get(0).mime);
        String nested = new JSONObject().put("main_url", cdn("deep.mp3")).toString();
        for (int i = 0; i < 20; i++) nested = "{\"nested\":" + nested + "}";
        assertTrue(UnifiedMediaActions.commentAudio(audio(nested)).isEmpty());
    }
    private static JSONObject audio(String content) throws Exception { return new JSONObject().put("comment_audio", new JSONObject().put("content", content)); }
    private static JSONObject work(JSONObject video) throws Exception { return new JSONObject().put("video", video); }
    private static JSONObject urls(String path) throws Exception { return new JSONObject().put("url_list", new JSONArray().put(cdn(path))); }
    private static String cdn(String path) { return "https://media.example/" + path; }
}
