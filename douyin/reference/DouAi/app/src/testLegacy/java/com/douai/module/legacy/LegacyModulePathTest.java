package com.douai.module.legacy;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public final class LegacyModulePathTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private File apk(String name, String entry) throws Exception {
        File file = new File(folder.getRoot(), name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(LegacyModulePath.RESOURCE));
            zip.write(entry.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }
    private static URL resource(File apk) throws Exception {
        return new URL("jar:" + apk.toURI().toASCIIString() + "!/" + LegacyModulePath.RESOURCE);
    }
    private static ClassLoader resources(URL... urls) {
        return new ClassLoader(null) {
            @Override public Enumeration<URL> getResources(String name) {
                assertEquals(LegacyModulePath.RESOURCE, name);
                return Collections.enumeration(List.of(urls));
            }
        };
    }

    @Test public void suppliedModuleApkIsPreferredOverClassLoaderCandidates() throws Exception {
        File supplied = apk("supplied.apk", LegacyModulePath.ENTRY + "\n");
        File fallback = apk("fallback.apk", LegacyModulePath.ENTRY + "\n");
        assertEquals(supplied.getCanonicalPath(), LegacyModulePath.resolve(supplied.getPath(), resources(resource(fallback))));
    }
    @Test public void missingZygotePathUsesEncodedClassLoaderApkWithMatchingEntry() throws Exception {
        File own = apk("模块 with spaces.apk", LegacyModulePath.ENTRY + "\r\n");
        assertEquals(own.getCanonicalPath(), LegacyModulePath.resolve(null, resources(resource(own))));
    }
    @Test public void unrelatedParentResourcesAreSkippedAndInvalidSuppliedApkDoesNotWin() throws Exception {
        File other = apk("other.apk", "some.other.Module\n");
        File own = apk("own.apk", LegacyModulePath.ENTRY + "\n");
        assertEquals(own.getCanonicalPath(), LegacyModulePath.resolve(other.getPath(), resources(resource(other), resource(own))));
    }
    @Test public void missingOrNonArchiveCandidatesFailExplicitly() throws Exception {
        File other = apk("other.apk", "some.other.Module\n");
        assertNull(LegacyModulePath.resolve("relative.apk", resources(resource(other))));
        assertNull(LegacyModulePath.resolve(null, resources(new URL("https://example.invalid/module.apk"))));
        assertNull(LegacyModulePath.resolve(null, null));
    }
}
