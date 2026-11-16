package com.douai.module.feature;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UnifiedVideoQuality412Test {
    private static UnifiedVideoQuality412Hook.Stream stream(String id, boolean video, String codec, int height) {
        return new UnifiedVideoQuality412Hook.Stream(id, video, codec, height);
    }
    @Test public void audioUnknownTracksAndEachCodecRemainAvailableAtItsNativeHighestHeight() {
        assertEquals(List.of("audio", "avc1080", "hevc720", "unknown"), UnifiedVideoQuality412Hook.highest(List.of(
                stream("audio", false, "aac", 0), stream("avc360", true, "avc", 360),
                stream("avc1080", true, "avc", 1080), stream("hevc720", true, "hevc", 720),
                stream("hevc360", true, "hevc", 360), stream("unknown", true, "unknown", 0))));
    }
    @Test public void equalHeightVariantsStayInOrderAndNoUnsupportedStreamIsInvented() {
        assertEquals(List.of("one", "two"), UnifiedVideoQuality412Hook.highest(List.of(
                stream("one", true, "avc", 720), stream("two", true, "avc", 720))));
        assertTrue(UnifiedVideoQuality412Hook.highest(List.of()).isEmpty());
    }
    @Test public void fixedQualityUsesTheNearestExistingLowerHeightOrTheSmallestAboveIt() {
        List<UnifiedVideoQuality412Hook.Stream> choices = List.of(stream("360", true, "avc", 360),
                stream("720", true, "avc", 720), stream("2160", true, "avc", 2160));
        assertEquals(List.of("720"), UnifiedVideoQuality412Hook.select(choices, 1080));
        assertEquals(List.of("360"), UnifiedVideoQuality412Hook.select(choices, 240));
        assertEquals(List.of("2160"), UnifiedVideoQuality412Hook.select(choices, Integer.MAX_VALUE));
    }
}
