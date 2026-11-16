package com.douai.module.feature;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public final class PortableMotionPhotoTest {
    @Test public void createsReadableJpegAndPreservesTheExactAppendedMp4() throws Exception {
        File directory = Files.createTempDirectory("douai-motion-test").toFile();
        try {
            File still = new File(directory, "still.jpg"), movie = new File(directory, "movie.mp4"), motion = new File(directory, "motion.jpg");
            try (var input = getClass().getResourceAsStream("/motion-photo/still.jpg")) { assertNotNull(input); Files.copy(input, still.toPath()); }
            byte[] mp4;
            try (var input = getClass().getResourceAsStream("/motion-photo/movie.mp4")) { assertNotNull(input); mp4 = input.readAllBytes(); }
            Files.write(movie.toPath(), mp4);
            PortableMotionPhoto.write(still, movie, motion);
            assertTrue(PortableMotionPhoto.isMotionPhoto(motion));
            byte[] output = Files.readAllBytes(motion.toPath());
            assertEquals(0xff, output[0] & 255); assertEquals(0xd8, output[1] & 255);
            assertArrayEquals(mp4, java.util.Arrays.copyOfRange(output, output.length - mp4.length, output.length));
            // Re-encoding a motion photo must strip its earlier XMP and video tail.
            File second = new File(directory, "second.jpg"); PortableMotionPhoto.write(motion, movie, second);
            assertEquals(motion.length(), second.length()); assertTrue(PortableMotionPhoto.isMotionPhoto(second));
            File artifact = new File("build/test-artifacts/portable-motion.jpg"); artifact.getParentFile().mkdirs();
            Files.copy(motion.toPath(), artifact.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { for (File file : directory.listFiles()) file.delete(); directory.delete(); }
    }
    @Test public void invalidInputsCannotLeaveAnOutputOrOverwriteExistingFiles() throws Exception {
        File directory = Files.createTempDirectory("douai-motion-invalid").toFile();
        try {
            File still = new File(directory, "not-jpeg"), movie = new File(directory, "movie.mp4"), output = new File(directory, "motion.jpg");
            Files.write(still.toPath(), new byte[]{1, 2, 3});
            Files.write(movie.toPath(), new byte[]{0,0,0,16,'f','t','y','p','i','s','o','m',0,0,0,0});
            assertThrows(java.io.IOException.class, () -> PortableMotionPhoto.write(still, movie, output)); assertFalse(output.exists());
            Files.write(output.toPath(), new byte[]{9});
            assertThrows(java.io.IOException.class, () -> PortableMotionPhoto.write(still, movie, output));
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(output.toPath()));
        } finally { for (File file : directory.listFiles()) file.delete(); directory.delete(); }
    }
}
