package com.douai.module.scan;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public final class HostPayload412Test {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static Map<String, byte[]> contents() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("AndroidManifest.xml", bytes("binary manifest fixture"));
        entries.put("classes.dex", bytes("first dex"));
        entries.put("classes2.dex", bytes("second dex"));
        entries.put("resources.arsc", bytes("resource table"));
        entries.put("res/layout/feed.xml", bytes("layout"));
        entries.put("assets/settings.json", bytes("settings"));
        entries.put("lib/arm64-v8a/libplayer.so", bytes("native"));
        entries.put("META-INF/services/example.Service", bytes("provider"));
        entries.put("META-INF/metadata.version", bytes("metadata"));
        entries.put("META-INF/CERT.SF", bytes("signature record"));
        entries.put("META-INF/CERT.RSA", bytes("signature block"));
        entries.put("META-INF/MANIFEST.MF", bytes("jar signature manifest"));
        return entries;
    }

    private File apk(Map<String, byte[]> entries, boolean stored, boolean reverse, String comment) throws Exception {
        File file = folder.newFile();
        List<String> names = new ArrayList<>(entries.keySet());
        if (reverse) Collections.reverse(names);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            zip.setComment(comment);
            for (String name : names) {
                byte[] data = entries.get(name);
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0);
                if (stored) {
                    CRC32 crc = new CRC32(); crc.update(data);
                    entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry); zip.write(data); zip.closeEntry();
            }
        }
        return file;
    }

    @Test public void resigningCompressionEntryOrderAndCommentDoNotChangePinnedContent() throws Exception {
        Map<String, byte[]> before = contents(), after = contents();
        after.remove("META-INF/CERT.RSA"); after.remove("META-INF/CERT.SF");
        after.put("META-INF/OTHER.EC", bytes("another signing key"));
        after.put("META-INF/OTHER.SF", bytes("another signing record"));
        after.put("META-INF/MANIFEST.MF", bytes("rewritten jar signature manifest"));
        HostPayloadFingerprint original = HostPayloadFingerprint.read(apk(before, true, false, "old"));
        HostPayloadFingerprint changedContainer = HostPayloadFingerprint.read(apk(after, false, true, "new"));
        assertEquals(original.payloadSha256, changedContainer.payloadSha256);
        assertEquals(original.manifestSha256, changedContainer.manifestSha256);
        assertEquals(8, changedContainer.entryCount);
        assertEquals(original.uncompressedBytes, changedContainer.uncompressedBytes);
    }

    @Test public void everyBusinessContentTypeIncludingMetaInfServicesChangesTheFingerprint() throws Exception {
        Map<String, byte[]> base = contents();
        String expected = HostPayloadFingerprint.read(apk(base, false, false, "")).payloadSha256;
        for (String name : List.of("classes.dex", "classes2.dex", "resources.arsc", "res/layout/feed.xml",
                "assets/settings.json", "lib/arm64-v8a/libplayer.so", "META-INF/services/example.Service", "META-INF/metadata.version")) {
            Map<String, byte[]> changed = new LinkedHashMap<>(base);
            changed.put(name, bytes("modified " + name));
            assertNotEquals("Changed content must be rejected: " + name, expected,
                    HostPayloadFingerprint.read(apk(changed, false, false, "")).payloadSha256);
        }
    }

    @Test public void addedRemovedAndRenamedEntriesCannotCollideWithOriginalPayload() throws Exception {
        Map<String, byte[]> base = contents();
        String expected = HostPayloadFingerprint.read(apk(base, false, false, "")).payloadSha256;
        Map<String, byte[]> added = new LinkedHashMap<>(base); added.put("classes57.dex", bytes("injected dex"));
        Map<String, byte[]> removed = new LinkedHashMap<>(base); removed.remove("classes2.dex");
        Map<String, byte[]> renamed = new LinkedHashMap<>(base); renamed.put("classes3.dex", renamed.remove("classes2.dex"));
        for (Map<String, byte[]> changed : List.of(added, removed, renamed)) {
            assertNotEquals(expected, HostPayloadFingerprint.read(apk(changed, true, true, "")).payloadSha256);
        }
    }

    @Test public void binaryManifestIsPinnedSeparatelyEvenWithIdenticalPayload() throws Exception {
        Map<String, byte[]> before = contents(), after = contents();
        after.put("AndroidManifest.xml", bytes("changed Application or factory"));
        HostPayloadFingerprint first = HostPayloadFingerprint.read(apk(before, true, false, ""));
        HostPayloadFingerprint second = HostPayloadFingerprint.read(apk(after, false, true, ""));
        assertEquals(first.payloadSha256, second.payloadSha256);
        assertNotEquals(first.manifestSha256, second.manifestSha256);
        assertFalse(first.matchesVerified412()); assertFalse(second.matchesVerified412());
    }

    @Test public void duplicateIncludedAndExcludedNamesAreRejectedBeforeHashing() throws Exception {
        for (String[] pair : List.of(new String[]{"classes.dex", "classez.dex"},
                new String[]{"META-INF/A.SF", "META-INF/B.SF"})) {
            Map<String, byte[]> data = contents();
            data.put(pair[0], bytes("first")); data.put(pair[1], bytes("second"));
            File file = apk(data, true, false, "");
            byte[] zip = Files.readAllBytes(file.toPath());
            replaceSameLength(zip, bytes(pair[1]), bytes(pair[0]));
            Files.write(file.toPath(), zip);
            IOException failure = assertThrows(IOException.class, () -> HostPayloadFingerprint.read(file));
            assertTrue(failure.getMessage(), failure.getMessage().contains("Duplicate"));
        }
    }

    @Test public void ambiguousPathsAndNonemptyDirectoryEntriesAreRejected() throws Exception {
        for (String name : List.of("assets/../data", "assets//data", "/classes.dex", "assets\\data", "assets/./data", "assets/")) {
            Map<String, byte[]> data = contents(); data.put(name, bytes("hidden content"));
            File file = apk(data, true, false, "");
            assertThrows(name, IOException.class, () -> HostPayloadFingerprint.read(file));
        }
    }

    @Test public void signatureLookingFilesOutsideDirectMetaInfRemainPayload() throws Exception {
        Map<String, byte[]> base = contents();
        for (String name : List.of("assets/CERT.RSA", "META-INF/services/CERT.SF", "META-INF/nested/MANIFEST.MF", "meta-inf/CERT.RSA")) {
            Map<String, byte[]> before = new LinkedHashMap<>(base); before.put(name, bytes("before"));
            Map<String, byte[]> after = new LinkedHashMap<>(before); after.put(name, bytes("after"));
            assertNotEquals(name, HostPayloadFingerprint.read(apk(before, true, false, "")).payloadSha256,
                    HostPayloadFingerprint.read(apk(after, true, false, "")).payloadSha256);
        }
    }

    @Test public void utf8NameOrderingMatchesAnIndependentPythonGoldenDigest() throws Exception {
        Map<String, byte[]> data = new LinkedHashMap<>();
        data.put("AndroidManifest.xml", bytes("manifest"));
        data.put("classes.dex", bytes("dex"));
        data.put("assets/中文.txt", bytes("中文"));
        data.put("assets/\ue000.txt", bytes("private"));
        data.put("assets/\ud800\udc00.txt", bytes("plane"));
        data.put("META-INF/services/demo.Type", bytes("provider"));
        data.put("META-INF/X.SF", bytes("ignored"));
        assertEquals("baf1d9503e8d00c58a9ad5336ef7095d06445e664fcbae24cf2acb6cdc0618fb",
                HostPayloadFingerprint.read(apk(data, false, true, "")).payloadSha256);
    }

    @Test public void sameDisplayed412VersionDoesNotAuthorizeAnUnknownDexPayload() throws Exception {
        Map<String, byte[]> data = contents();
        data.put("AndroidManifest.xml", BinaryHostManifestFixture.manifest(HostAdaptation.TARGET_PACKAGE,
                "41.2.0", 410200, true, -1L));
        HostApkProfile.Inspection receipt = HostApkProfile.inspect(apk(data, false, false, ""));
        assertNull(receipt.profile); assertEquals("content_rejected", receipt.verification);
        assertNotNull(receipt.rejection); assertFalse(receipt.payloadSha256.isEmpty());
    }

    @Test public void independentlyInspectedSampleMatchesBothPinnedFingerprints() throws Exception {
        File sample = sample();
        HostPayloadFingerprint fingerprint = HostPayloadFingerprint.read(sample);
        assertTrue(fingerprint.matchesVerified412());
        assertEquals(45_752, fingerprint.entryCount);
        assertEquals(842_410_520L, fingerprint.uncompressedBytes);
        assertEquals("full_apk_sha256", HostApkProfile.inspect(sample).verification);
    }

    @Test public void actual412ContainerOnlyChangeUsesFallbackWithoutAuthorizingUnknownContent() throws Exception {
        File changed = folder.newFile("same-payload-comment.apk");
        Files.copy(sample().toPath(), changed.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        replaceZipComment(changed, bytes("DouAi content verification test"));
        HostApkProfile.Inspection receipt = HostApkProfile.inspect(changed);
        assertSame(HostAdaptation.PROFILE_412, receipt.profile);
        assertNull(receipt.rejection); assertEquals("manifest_and_payload_sha256", receipt.verification);
        assertNotEquals(HostAdaptation.VERIFIED_412_APK_SHA256, receipt.sha256);
        assertEquals(HostAdaptation.VERIFIED_412_MANIFEST_SHA256, receipt.manifestSha256);
        assertEquals(HostAdaptation.VERIFIED_412_PAYLOAD_SHA256, receipt.payloadSha256);
    }

    private static File sample() {
        for (File directory = new File(System.getProperty("user.dir")).getAbsoluteFile(); directory != null; directory = directory.getParentFile()) {
            File sample = new File(directory, "analysis/host412/sample.apk");
            if (sample.isFile()) return sample;
        }
        Assume.assumeTrue("Original host APK is optional outside the analysis workspace", false);
        throw new AssertionError("unreachable");
    }

    private static void replaceSameLength(byte[] input, byte[] before, byte[] after) {
        assertEquals(before.length, after.length);
        int changes = 0;
        for (int offset = 0; offset <= input.length - before.length; offset++) {
            boolean match = true;
            for (int i = 0; i < before.length; i++) if (input[offset + i] != before[i]) { match = false; break; }
            if (match) { System.arraycopy(after, 0, input, offset, after.length); changes++; offset += before.length - 1; }
        }
        assertEquals("Both local and central ZIP entry names must change", 2, changes);
    }

    private static void replaceZipComment(File file, byte[] comment) throws IOException {
        try (RandomAccessFile archive = new RandomAccessFile(file, "rw")) {
            long start = Math.max(0, archive.length() - 65_557L);
            byte[] tail = new byte[(int) (archive.length() - start)];
            archive.seek(start); archive.readFully(tail);
            for (int i = tail.length - 22; i >= 0; i--) {
                if (tail[i] != 'P' || tail[i + 1] != 'K' || tail[i + 2] != 5 || tail[i + 3] != 6) continue;
                int oldLength = (tail[i + 20] & 255) | ((tail[i + 21] & 255) << 8);
                if (i + 22 + oldLength != tail.length) continue;
                archive.seek(start + i + 20); archive.write(comment.length & 255); archive.write(comment.length >>> 8);
                archive.write(comment); archive.setLength(start + i + 22 + comment.length); return;
            }
            throw new IOException("No ZIP end-of-central-directory found");
        }
    }
}
