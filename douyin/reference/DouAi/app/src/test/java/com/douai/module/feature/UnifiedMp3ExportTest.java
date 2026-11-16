package com.douai.module.feature;

import android.content.Context;
import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.media.AudioFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import com.douai.module.config.LocalOptions;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class UnifiedMp3ExportTest {
    private final DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
    @After public void restore() { MediaStoreDownloader.connections = previous; }

    @Test public void actualMp3BytesSaveToSelectedTreeAndTriggerTheExistingSavedListener() throws Exception {
        var documents = DownloadDocumentTreeTest.register();
        source();
        byte[] mp3 = PcmMp3EncoderTest.shortSine();
        DownloadTransfer transfer = transfer();
        AtomicInteger saves = new AtomicInteger();
        transfer.savedListener = (destination, identifier, bytes, name) -> {
            assertEquals("document_tree", destination); assertEquals(mp3.length, bytes);
            assertEquals("original.mp3", name); saves.incrementAndGet();
        };
        AtomicReference<File> cache = new AtomicReference<>();
        String result = UnifiedMp3Export.save(context(), "https://media.test/source.mp4", "original.mp3",
                DownloadDocumentTreeTest.TREE, (input, output, control) -> {
                    cache.set(input.getParentFile());
                    assertEquals("source-video", Files.readString(input.toPath()));
                    Files.write(output.toPath(), mp3);
                }, transfer);
        assertEquals("媒体已保存到所选目录", result);
        String id = documents.names.keySet().iterator().next();
        assertEquals("audio/mpeg", documents.types.get(id));
        assertArrayEquals(mp3, Files.readAllBytes(documents.files.get(id).toPath()));
        assertFalse(cache.get().exists()); assertEquals(1, saves.get()); assertEquals(2, transfer.completedFiles);
    }

    @Test public void decodeFailureCancellationAndRenamedAacPublishNothingAndCleanOwnedCache() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            var documents = DownloadDocumentTreeTest.register(); source();
            DownloadTransfer transfer = transfer(); AtomicReference<File> cache = new AtomicReference<>();
            int selected = kind;
            assertThrows(Exception.class, () -> UnifiedMp3Export.save(context(), "https://media.test/source.mp4", "original.mp3",
                    DownloadDocumentTreeTest.TREE, (input, output, control) -> {
                        cache.set(input.getParentFile());
                        if (selected == 0) throw new IOException("device decode failed");
                        if (selected == 1) { control.cancel(); control.check(); }
                        Files.write(output.toPath(), new byte[]{(byte) 0xff, (byte) 0xf1, 1, 2, 3}); // ADTS AAC is not MP3.
                    }, transfer));
            assertTrue(documents.names.isEmpty()); assertFalse(cache.get().exists());
        }
    }

    @Test public void imageOnlyWrapperStillRejectsAudioAndPcmFormatsAreExplicit() throws Exception {
        File source = File.createTempFile("mp3-format-", ".mp3", context().getCacheDir());
        try {
            Files.write(source.toPath(), PcmMp3EncoderTest.shortSine());
            assertThrows(IllegalArgumentException.class, () -> MediaStoreDownloader.saveLocalImage(
                    context(), source, "a.mp3", "audio/mpeg", DownloadDocumentTreeTest.TREE, transfer()));
        } finally { source.delete(); }
        assertEquals(PcmMp3Encoder.Format.SIGNED_16_LE, UnifiedMp3Export.pcmEncoding(AudioFormat.ENCODING_PCM_16BIT));
        assertEquals(PcmMp3Encoder.Format.FLOAT_32_LE, UnifiedMp3Export.pcmEncoding(AudioFormat.ENCODING_PCM_FLOAT));
        assertThrows(IOException.class, () -> UnifiedMp3Export.pcmEncoding(AudioFormat.ENCODING_PCM_8BIT));
    }

    @Test @Config(sdk = 29) public void encodedAudioPublishesToMusicCollectionWithAudioMime() throws Exception {
        AudioProvider provider = new AudioProvider(); ProviderInfo info = new ProviderInfo(); info.authority = MediaStore.AUTHORITY;
        provider.attachInfo(context(), info); ShadowContentResolver.registerProviderInternal(info.authority, provider);
        byte[] mp3 = PcmMp3EncoderTest.shortSine();
        File source = File.createTempFile("encoded-audio-", ".mp3", context().getCacheDir());
        try {
            Files.write(source.toPath(), mp3); DownloadTransfer transfer = transfer(); transfer.name = "original.mp3";
            assertEquals("媒体已保存到 Music/DouAi", MediaStoreDownloader.saveLocalMedia(
                    context(), source, "original.mp3", "audio/mpeg", "", transfer));
            assertTrue(provider.published); assertArrayEquals(mp3, Files.readAllBytes(provider.file.toPath()));
            assertEquals(1, transfer.savedEvidenceCount); assertFalse(transfer.cancel());
        } finally { source.delete(); if (provider.file != null) provider.file.delete(); }
    }

    @Test public void originalMp3ChoiceIsExplicitAndOpeningOrCancellingNeverStartsADownload() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        MediaStoreDownloader.connections = uri -> { requests.incrementAndGet(); throw new AssertionError("No explicit download selection"); };
        var owner = Robolectric.buildActivity(Activity.class).setup();
        OfficialDownloadDialogTest.Work work = new OfficialDownloadDialogTest.Work();
        work.video = new DownloadActionHookTest.VideoFixture();
        work.video.play_addr = new DownloadActionHookTest.UrlFixture("https://media.test/actual-video.mp4");
        try (OfficialDownloadDialog dialog = new OfficialDownloadDialog(owner.get(), work,
                new LocalOptions(java.util.Map.of(LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE)),
                ignored -> { }, () -> true)) {
            dialog.show(); OfficialDownloadTestUi.openGroup("视频");
            assertTrue(OfficialDownloadTestUi.indexOf("保存视频") >= 0);
            assertTrue(OfficialDownloadTestUi.indexOf("提取视频原声 MP3") >= 0);
            OfficialDownloadTestUi.latest().cancel(); assertEquals(0, requests.get());
        } finally { owner.pause().stop().destroy(); }
    }

    public static final class AudioProvider extends ContentProvider {
        File file; boolean published;
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri collection, ContentValues values) {
            assertEquals(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, collection);
            assertEquals("Music/DouAi", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
            assertEquals("audio/mpeg", values.getAsString(MediaStore.MediaColumns.MIME_TYPE));
            assertEquals(Integer.valueOf(1), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            try { file = File.createTempFile("saved-audio-", ".mp3", context().getCacheDir()); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
            return ContentUris.withAppendedId(collection, 1);
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            assertTrue(file.length() > 0); assertEquals(Integer.valueOf(0), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            published = true; return 1;
        }
        @Override public int delete(Uri uri, String selection, String[] args) { if (file != null) file.delete(); return 1; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return "audio/mpeg"; }
    }
    private static Context context() { return RuntimeEnvironment.getApplication(); }
    private static DownloadTransfer transfer() throws Exception { DownloadTransfer value = new DownloadTransfer(); value.start(); return value; }
    private static void source() {
        MediaStoreDownloader.connections = uri -> new HttpURLConnection(new URL("https://media.test/source.mp4")) {
            private final byte[] data = "source-video".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            @Override public int getResponseCode() { return 200; }
            @Override public long getContentLengthLong() { return data.length; }
            @Override public String getContentType() { return "video/mp4"; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(data); }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
    }
}
