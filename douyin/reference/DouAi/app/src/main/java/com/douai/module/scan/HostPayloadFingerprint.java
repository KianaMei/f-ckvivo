package com.douai.module.scan;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** ZIP-container-independent content evidence, with the Manifest pinned separately. */
final class HostPayloadFingerprint {
    private static final byte[] DOMAIN = "DouAi APK payload v1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_PAYLOAD_BYTES = 2L * 1024 * 1024 * 1024;
    final String manifestSha256;
    final String payloadSha256;
    final int entryCount;
    final long uncompressedBytes;

    private HostPayloadFingerprint(String manifest, String payload, int count, long bytes) {
        manifestSha256 = manifest; payloadSha256 = payload; entryCount = count; uncompressedBytes = bytes;
    }

    boolean matchesVerified412() {
        return HostAdaptation.VERIFIED_412_MANIFEST_SHA256.equals(manifestSha256)
                && HostAdaptation.VERIFIED_412_PAYLOAD_SHA256.equals(payloadSha256);
    }

    static HostPayloadFingerprint read(File apk) throws IOException {
        try (ZipFile archive = new ZipFile(apk)) {
            List<Item> items = new ArrayList<>();
            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String key = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
                validateName(name, key);
                if (!names.add(key)) throw new IOException("Duplicate APK entry: " + name);
                if (names.size() > MAX_ENTRIES) throw new IOException("Too many APK entries");
                if (entry.isDirectory()) {
                    if (entry.getSize() != 0) throw new IOException("APK directory contains payload: " + name);
                } else if (!isSigningEntry(name)) {
                    items.add(new Item(entry));
                }
            }
            // Explicit unsigned UTF-8 byte order agrees with the independent Python baseline.
            items.sort((left, right) -> compareNames(left.nameBytes, right.nameBytes));
            MessageDigest payload = digest();
            payload.update(DOMAIN);
            String manifest = null;
            int count = 0;
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            for (Item item : items) {
                MessageDigest content = digest();
                long size = 0;
                try (InputStream input = archive.getInputStream(item.entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        size += read;
                        if (size > MAX_PAYLOAD_BYTES - total) throw new IOException("APK payload is too large");
                        content.update(buffer, 0, read);
                    }
                }
                byte[] hash = content.digest();
                if ("AndroidManifest.xml".equals(item.entry.getName())) {
                    manifest = hex(hash);
                    continue;
                }
                updateInt(payload, item.nameBytes.length);
                payload.update(item.nameBytes);
                updateLong(payload, size);
                payload.update(hash);
                count++;
                total += size;
            }
            if (manifest == null) throw new IOException("Missing APK Manifest for payload verification");
            updateInt(payload, count);
            return new HostPayloadFingerprint(manifest, hex(payload.digest()), count, total);
        }
    }

    private static void validateName(String name, String key) throws IOException {
        if (key.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("Ambiguous APK entry name: " + name);
        for (String part : key.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part))
                throw new IOException("Ambiguous APK entry name: " + name);
        }
    }

    private static boolean isSigningEntry(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String leaf = name.substring("META-INF/".length());
        if (leaf.indexOf('/') >= 0) return false;
        leaf = leaf.toUpperCase(Locale.ROOT);
        return "MANIFEST.MF".equals(leaf) || leaf.endsWith(".SF") || leaf.endsWith(".RSA")
                || leaf.endsWith(".DSA") || leaf.endsWith(".EC");
    }

    private static int compareNames(byte[] left, byte[] right) {
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int difference = (left[i] & 255) - (right[i] & 255);
            if (difference != 0) return difference;
        }
        return Integer.compare(left.length, right.length);
    }

    private static void updateInt(MessageDigest digest, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    private static void updateLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return text.toString();
    }

    private static final class Item {
        final ZipEntry entry;
        final byte[] nameBytes;
        Item(ZipEntry entry) { this.entry = entry; nameBytes = entry.getName().getBytes(StandardCharsets.UTF_8); }
    }
}
