package com.douai.module.config;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WebDavSettingsTest {
    @Test public void httpsDirectoryIsNormalizedAndInsecureTransportNeedsOptIn() {
        assertEquals("https://example.com/dav/", WebDavSettings.validate("https://example.com/dav", false).toString());
        assertThrows(IllegalArgumentException.class, () -> WebDavSettings.validate("http://example.com/dav", false));
        assertEquals("http://example.com/dav/", WebDavSettings.validate("http://example.com/dav", true).toString());
        for (String url : new String[]{"file:///data/a", "https://user:secret@example.com/dav/", "https://example.com/?token=secret", "https://example.com/#fragment"}) {
            assertThrows(url, IllegalArgumentException.class, () -> WebDavSettings.validate(url, false));
        }
    }
    @Test public void backupOmissionDoesNotExposePasswordOrRestoreItToAnotherAccount() {
        assertEquals(ConfigSecrets.OMITTED, ConfigSecrets.redact(WebDavSettings.PASSWORD, "private"));
        java.util.Map<String, Object> before = new java.util.HashMap<>(), incoming = new java.util.HashMap<>();
        before.put(WebDavSettings.URL, "https://example.com/api/"); before.put(WebDavSettings.USER, "alice");
        incoming.putAll(before);
        assertTrue(ConfigSecrets.sameEndpoint(before, incoming, WebDavSettings.PASSWORD));
        incoming.put(WebDavSettings.USER, "bob");
        assertFalse(ConfigSecrets.sameEndpoint(before, incoming, WebDavSettings.PASSWORD));
    }
}
