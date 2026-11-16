package com.douai.module.legacy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Finds this module's real APK; never guesses from the host or a class-loader toString. */
final class LegacyModulePath {
    static final String RESOURCE = "assets/xposed_init";
    static final String ENTRY = "com.douai.module.legacy.LegacyModuleEntry";
    private static final int MAX_ENTRY_BYTES = 32 * 1024;

    private LegacyModulePath() { }

    static String resolve(String supplied, ClassLoader loader) {
        String verified = verify(supplied);
        if (verified != null || loader == null) return verified;
        try {
            Enumeration<URL> resources = loader.getResources(RESOURCE);
            while (resources.hasMoreElements()) {
                String path = archivePath(resources.nextElement());
                verified = verify(path);
                if (verified != null) return verified;
            }
        } catch (IOException | SecurityException ignored) { }
        return null;
    }

    private static String archivePath(URL resource) {
        if (resource == null) return null;
        String external = resource.toExternalForm();
        if (!external.startsWith("jar:file:")) return null;
        int boundary = external.indexOf("!/");
        if (boundary < 0) return null;
        try {
            URI archive = new URI(external.substring(4, boundary));
            return new File(archive).getPath();
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String verify(String path) {
        if (path == null || path.isEmpty()) return null;
        File archive = new File(path);
        if (!archive.isAbsolute() || !archive.isFile()) return null;
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(RESOURCE);
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_ENTRY_BYTES) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > MAX_ENTRY_BYTES) return null;
                    bytes.write(buffer, 0, count);
                }
            }
            for (String line : new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                if (ENTRY.equals(line.trim())) return archive.getCanonicalPath();
            }
        } catch (IOException | SecurityException ignored) { }
        return null;
    }
}
