package com.douai.module.feature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PcmMp3EncoderTest {
    @Test public void stereoSineProducesContiguousRealMp3FramesAcrossUnalignedPcmChunks() throws Exception {
        int frames = 44100 * 2;
        ByteBuffer pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            short sample = (short) Math.round(Math.sin(i * 2 * Math.PI * 440 / 44100) * 16000);
            pcm.putShort(sample).putShort(sample);
        }
        byte[] encoded = encode(pcm.array(), 44100, 2, PcmMp3Encoder.Format.SIGNED_16_LE, 317);
        assertTrue(encoded.length > 30_000);
        assertTrue(countLayer3Frames(encoded, 44100, 2) >= 70);
        String sample = System.getProperty("douai.mp3.sample");
        if (sample != null) Files.write(Path.of(sample), encoded);
    }

    @Test public void floatMonoClipsOutOfRangeSamplesAndStillProducesARealMonoStream() throws Exception {
        ByteBuffer pcm = ByteBuffer.allocate(48000 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 48000; i++) pcm.putFloat(i == 0 ? Float.NaN : i == 1
                ? Float.POSITIVE_INFINITY : i == 2 ? -2f : (float) Math.sin(i * 2 * Math.PI * 220 / 48000) * .5f);
        byte[] encoded = encode(pcm.array(), 48000, 1, PcmMp3Encoder.Format.FLOAT_32_LE, 997);
        assertTrue(countLayer3Frames(encoded, 48000, 1) >= 35);
    }

    @Test public void incompleteOrEmptyPcmIsRejectedInsteadOfPublishingAnEmptyOrRenamedFile() throws Exception {
        try (PcmMp3Encoder empty = new PcmMp3Encoder(new ByteArrayOutputStream(), 44100, 2)) {
            assertThrows(IOException.class, empty::finish);
        }
        try (PcmMp3Encoder partial = new PcmMp3Encoder(new ByteArrayOutputStream(), 44100, 2)) {
            partial.write(ByteBuffer.wrap(new byte[]{1, 2, 3}), PcmMp3Encoder.Format.SIGNED_16_LE);
            assertThrows(IOException.class, partial::finish);
        }
        assertThrows(IllegalArgumentException.class, () -> new PcmMp3Encoder(new ByteArrayOutputStream(), 44100, 6));
    }

    static byte[] shortSine() throws Exception {
        ByteBuffer pcm = ByteBuffer.allocate(4410 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4410; i++) pcm.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / 44100) * 16000));
        return encode(pcm.array(), 44100, 1, PcmMp3Encoder.Format.SIGNED_16_LE, 1024);
    }
    private static byte[] encode(byte[] pcm, int rate, int channels, PcmMp3Encoder.Format format, int chunk) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PcmMp3Encoder encoder = new PcmMp3Encoder(output, rate, channels)) {
            for (int offset = 0; offset < pcm.length; offset += chunk)
                encoder.write(ByteBuffer.wrap(pcm, offset, Math.min(chunk, pcm.length - offset)), format);
            encoder.finish();
            assertEquals(pcm.length / (format.bytes * channels), encoder.framesWritten());
            assertEquals(output.size(), encoder.bytesWritten());
        }
        return output.toByteArray();
    }

    private static int countLayer3Frames(byte[] bytes, int expectedRate, int channels) {
        int count = 0, offset = 0;
        int[] mpeg1Rates = {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320};
        int[] mpeg2Rates = {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160};
        while (offset + 4 <= bytes.length) {
            int header = (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16
                    | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
            assertEquals("Every encoded frame has MPEG synchronization", 0xffe00000, header & 0xffe00000);
            assertEquals("The stream is Layer III", 1, header >>> 17 & 3);
            int version = header >>> 19 & 3;
            assertNotEquals(1, version);
            int rateIndex = header >>> 10 & 3, bitrateIndex = header >>> 12 & 15;
            assertTrue(rateIndex < 3); assertTrue(bitrateIndex > 0 && bitrateIndex < 15);
            int rate = new int[]{44100, 48000, 32000}[rateIndex] / (version == 3 ? 1 : version == 2 ? 2 : 4);
            assertEquals(expectedRate, rate);
            assertEquals(channels == 1, (header >>> 6 & 3) == 3);
            int bitrate = (version == 3 ? mpeg1Rates : mpeg2Rates)[bitrateIndex];
            int length = (version == 3 ? 144000 : 72000) * bitrate / rate + (header >>> 9 & 1);
            assertTrue("Frame payload is complete", offset + length <= bytes.length);
            offset += length; count++;
        }
        assertEquals("Output ends exactly on a frame boundary", bytes.length, offset);
        return count;
    }
}
