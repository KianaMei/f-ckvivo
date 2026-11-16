package com.douai.module.feature;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class UnifiedMessageMedia412Test {
    @Test public void cdnMirrorsAreOneMediaItemAndOnlyExplicitMediaNodesAreRead() throws Exception {
        List<UnifiedMessageTools412Hook.Source> result = UnifiedMessageTools412Hook.mediaSources(5,
                "{\"url\":{\"url_list\":[\"https://a.example/em.gif\",\"https://b.example/em.gif\"]},\"avatar\":{\"url_list\":[\"https://x.example/avatar.jpg\"]}}", null);
        assertEquals(1, result.size());
        assertEquals("https://a.example/em.gif", result.get(0).path);
        assertEquals("image", result.get(0).kind);
    }
    @Test public void imageGalleryKeepsOrderAndVideoAndAudioHaveSeparateKinds() throws Exception {
        List<UnifiedMessageTools412Hook.Source> pictures = UnifiedMessageTools412Hook.mediaSources(2,
                "{\"images\":[{\"url_list\":[\"https://a.example/1.jpg\"]},{\"url_list\":[\"https://a.example/2.jpg\"]}]}", null);
        assertEquals(2, pictures.size());
        assertTrue(pictures.get(1).path.endsWith("2.jpg"));
        assertEquals("video", UnifiedMessageTools412Hook.mediaSources(27,
                "{\"url\":{\"url_list\":[\"https://a.example/v.mp4\"]}}", null).get(0).kind);
        assertEquals("audio", UnifiedMessageTools412Hook.mediaSources(17,
                "{\"resource_url\":{\"url_list\":[\"https://a.example/a.aac\"]}}", null).get(0).kind);
    }
    @Test public void nonMediaAndNonHttpPayloadsCannotBecomeDownloads() throws Exception {
        assertTrue(UnifiedMessageTools412Hook.mediaSources(7,
                "{\"text\":\"https://a.example/secret\",\"avatar\":{\"url_list\":[\"https://a.example/avatar.jpg\"]}}", null).isEmpty());
        assertTrue(UnifiedMessageTools412Hook.mediaSources(5,
                "{\"url\":{\"url_list\":[\"file:///private/data\",\"javascript:x\"]}}", null).isEmpty());
    }
    @Test public void mimeDetectionPreservesGifPngAndAudioContainers() {
        assertEquals("image/gif", UnifiedMessageTools412Hook.mime(new byte[]{'G','I','F','8'}, "image"));
        assertEquals("image/png", UnifiedMessageTools412Hook.mime(new byte[]{(byte)137,'P','N','G'}, "image"));
        assertEquals("audio/ogg", UnifiedMessageTools412Hook.mime(new byte[]{'O','g','g','S'}, "audio"));
    }
}
