package com.douai.module.feature;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import com.douai.module.config.LocalOptions;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class UnifiedMessageSaved412Test {
    private final UnifiedMessageTools412Hook.SavedOutput original = UnifiedMessageTools412Hook.savedOutput;
    private UnifiedMessageTools412Hook feature;
    private Provider provider;
    private File input;
    @Before public void prepare() throws Exception {
        provider = new Provider(); provider.output = File.createTempFile("message-saved", ".gif");
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo(); info.authority = "media";
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        ShadowContentResolver.registerProviderInternal("media", provider);
        input = File.createTempFile("message-source", ".gif");
        Files.write(input.toPath(), new byte[]{'G','I','F','8','9','a',1,2,3,4});
        feature = new UnifiedMessageTools412Hook(new LocalOptions(Collections.singletonMap(UnifiedMessageTools412Hook.MEDIA_TOOLS, true)), null);
    }
    @After public void cleanup() { UnifiedMessageTools412Hook.savedOutput = original; feature.close(); input.delete(); provider.output.delete(); }
    @Test public void savedUriIsEmittedExactlyOnceAfterClosedBytesAndPublication() throws Exception {
        AtomicInteger events = new AtomicInteger();
        UnifiedMessageTools412Hook.savedOutput = (context, destination, uri, size, name) -> {
            assertTrue(provider.published);
            assertEquals(input.length(), provider.output.length());
            assertEquals(input.length(), size);
            assertEquals("mediastore", destination);
            assertEquals("content://media/external/downloads/88", uri);
            assertTrue(name.endsWith(".gif")); events.incrementAndGet();
        };
        UnifiedMessageTools412Hook.Saved result = feature.copy(RuntimeEnvironment.getApplication(),
                new UnifiedMessageTools412Hook.Source(input.getAbsolutePath(), "image"), 1, 0);
        assertEquals("image/gif", result.mime); assertEquals(1, events.get()); assertEquals(0, provider.deletes);
    }
    @Test public void failedPublicationDeletesTheIncompleteUriAndNeverEmitsSaved() throws Exception {
        AtomicInteger events = new AtomicInteger(); provider.rejectPublish = true;
        UnifiedMessageTools412Hook.savedOutput = (context, destination, uri, size, name) -> events.incrementAndGet();
        assertThrows(IllegalStateException.class, () -> feature.copy(RuntimeEnvironment.getApplication(),
                new UnifiedMessageTools412Hook.Source(input.getAbsolutePath(), "image"), 1, 0));
        assertEquals(0, events.get()); assertEquals(1, provider.deletes);
    }
    public static final class Provider extends ContentProvider {
        File output; boolean published, rejectPublish; int deletes;
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri uri, ContentValues values) {
            assertEquals(Integer.valueOf(1), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            return Uri.parse("content://media/external/downloads/88");
        }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
            assertEquals(Integer.valueOf(0), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            if (rejectPublish) return 0; published = true; return 1;
        }
        @Override public int delete(Uri uri, String where, String[] args) { deletes++; return 1; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
        }
        @Override public Cursor query(Uri uri, String[] columns, String where, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return "image/gif"; }
    }
}
