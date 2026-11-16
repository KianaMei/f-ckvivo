package com.douai.module.feature;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/** Standard TLS and arbitrary WebDAV methods, with no cross-origin credential redirects. */
final class WebDavClient {
    private static final OkHttpClient DEFAULT = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build();
    private final OkHttpClient client;
    WebDavClient() { this(DEFAULT); }
    WebDavClient(OkHttpClient client) { this.client = client; }

    void upload(URI collection, String username, String password, String name, String mime,
                long length, InputStream input, DownloadTransfer transfer) throws Exception {
        if (collection == null || name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) throw new IllegalArgumentException("WebDAV 文件名无效");
        URI folder = collection.resolve("DouAi/");
        Request.Builder create = request(folder, username, password).method("MKCOL", null);
        try (Response response = execute(create.build(), transfer)) {
            if (!response.isSuccessful() && response.code() != 405) {
                throw new IllegalStateException("WebDAV 创建目录失败：HTTP " + response.code());
            }
        }
        transfer.check();
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8.name()).replace("+", "%20");
        transfer.size(length);
        RequestBody body = new RequestBody() {
            @Override public boolean isOneShot() { return true; }
            @Override public MediaType contentType() { return MediaType.parse(mime == null ? "application/octet-stream" : mime); }
            @Override public long contentLength() { return length; }
            @Override public void writeTo(BufferedSink output) throws IOException {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    try { transfer.check(); }
                    catch (Exception cancelled) { throw new IOException("WebDAV 上传已停止", cancelled); }
                    output.write(buffer, 0, count);
                    try { transfer.advance(count); }
                    catch (Exception cancelled) { throw new IOException("WebDAV 上传已停止", cancelled); }
                }
            }
        };
        try (DownloadTransfer.Streams streams = transfer.streams()) {
            streams.add(input);
            try (Response response = execute(request(folder.resolve(encoded), username, password).put(body).build(), transfer)) {
                if (!response.isSuccessful()) throw new IllegalStateException("WebDAV 上传失败：HTTP " + response.code());
                transfer.evidence("webdav_response_accepted", "status=" + response.code() + "; read_back_verified=false");
            }
        } finally { transfer.onStop(null); }
        if (length >= 0 && transfer.bytes != length) throw new IllegalStateException("WebDAV 上传文件长度变化");
        transfer.check();
    }
    private static Request.Builder request(URI uri, String user, String password) {
        Request.Builder request = new Request.Builder().url(uri.toString());
        if (!user.isEmpty() || !password.isEmpty()) request.header("Authorization", Credentials.basic(user, password, StandardCharsets.UTF_8));
        return request;
    }
    private Response execute(Request request, DownloadTransfer transfer) throws Exception {
        transfer.check();
        Call call = client.newCall(request);
        transfer.onStop(call::cancel);
        return call.execute();
    }
}
