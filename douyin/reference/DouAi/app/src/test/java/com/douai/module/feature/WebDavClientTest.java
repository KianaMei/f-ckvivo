package com.douai.module.feature;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WebDavClientTest {
    @Test public void createsCollectionThenUploadsExactBytesWithEncodedName() throws Exception {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(201));
            byte[] bytes = "test-media-bytes".getBytes(StandardCharsets.UTF_8);
            DownloadTransfer transfer = new DownloadTransfer(); transfer.start(); transfer.file("中文 #.mp4", 1);
            new WebDavClient(new OkHttpClient.Builder().followRedirects(false).build()).upload(
                    server.url("/dav/").uri(), "alice", "private-password", "中文 #.mp4", "video/mp4",
                    bytes.length, new ByteArrayInputStream(bytes), transfer);
            RecordedRequest mkdir = server.takeRequest(5, TimeUnit.SECONDS), put = server.takeRequest(5, TimeUnit.SECONDS);
            assertEquals("MKCOL", mkdir.getMethod()); assertEquals("/dav/DouAi/", mkdir.getPath());
            assertEquals("PUT", put.getMethod()); assertTrue(put.getPath().contains("%E4%B8%AD%E6%96%87%20%23.mp4"));
            assertEquals(Credentials.basic("alice", "private-password"), put.getHeader("Authorization"));
            assertArrayEquals(bytes, put.getBody().readByteArray()); assertEquals(bytes.length, transfer.bytes);
        }
    }
    @Test public void existingCollectionIsAcceptedButRedirectNeverReceivesCredentials() throws Exception {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer redirect = new okhttp3.mockwebserver.MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(405));
            server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", redirect.url("/stolen")));
            DownloadTransfer transfer = new DownloadTransfer(); transfer.start();
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    new WebDavClient(new OkHttpClient.Builder().followRedirects(false).build()).upload(
                            server.url("/dav/").uri(), "user", "password", "a.mp4", "video/mp4", 1,
                            new ByteArrayInputStream(new byte[]{1}), transfer));
            assertTrue(failure.getMessage().contains("307"));
            assertFalse(failure.getMessage().contains("password")); assertEquals(0, redirect.getRequestCount());
        }
    }
    @Test public void cancellationPreventsAnyNetworkRequest() throws Exception {
        DownloadTransfer transfer = new DownloadTransfer(); transfer.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> new WebDavClient().upload(
                URI.create("https://example.com/dav/"), "", "", "a.mp4", "video/mp4", 0,
                new ByteArrayInputStream(new byte[0]), transfer));
    }
}
