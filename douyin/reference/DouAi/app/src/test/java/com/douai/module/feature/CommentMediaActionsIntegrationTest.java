package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import com.douai.module.config.ExtendedSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsRepository;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import okhttp3.mockwebserver.*;
import static org.junit.Assert.*;

/** Exercise the real long-press menu callbacks, task queue and selected document destination. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class CommentMediaActionsIntegrationTest {
    @Before public void idle() { DownloadTaskTestIdle.beforeTest(); }
    @After public void finish() { DownloadTaskTestIdle.afterTest(); }
    @Test public void commentVideoMenuQueuesTheAliasVideo() throws Exception {
        saveViaMenu(false, "video/mp4", "/comment.mp4", "保存评论视频");
    }
    @Test public void commentVoiceMenuPreservesTheAudioContainer() throws Exception {
        saveViaMenu(true, "audio/ogg", "/comment.ogg", "保存评论语音");
    }
    private static void saveViaMenu(boolean audio, String mime, String path, String label) throws Exception {
        try (MockWebServer server = new MockWebServer(); var screen = Robolectric.buildActivity(Activity.class).setup()) {
            server.start(); server.enqueue(new MockResponse().addHeader("Content-Type", mime).setBody("comment-media-body"));
            Activity activity = screen.get();
            var docs = DownloadDocumentTreeTest.register();
            var prefs = activity.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE).commit();
            activity.getPreferences(0).edit().putBoolean("comment_media_tools", true).commit();
            Constructor<CommentToolsHook> constructor = CommentToolsHook.class.getDeclaredConstructor(ClassLoader.class,
                    Class.class, Class.class, Class.class, Field.class, Field.class, ExtendedSettings.class, Consumer.class);
            constructor.setAccessible(true);
            try (CommentToolsHook hook = constructor.newInstance(CommentMediaActionsIntegrationTest.class.getClassLoader(),
                    Item.class, Comment.class, null, Item.class.getField("k"), Params.class.getField("LJIIIIZZ"),
                    ExtendedSettings.readFrom(activity.getPreferences(0)), (Consumer<String>) ignored -> { })) {
                Comment comment = new Comment();
                if (audio) { comment.comment_audio = new Audio(); comment.comment_audio.content = "{\"main_url\":\"" + server.url(path) + "\",\"format\":\"ogg\"}"; }
                else { Alias alias = new Alias(); alias.alias_aweme = new Work(); alias.alias_aweme.video = new DownloadActionHookTest.VideoFixture();
                    alias.alias_aweme.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url(path).toString()); comment.video_list = List.of(alias); }
                Method menu = CommentToolsHook.class.getDeclaredMethod("showMenu", Context.class, Object.class, Object.class);
                menu.setAccessible(true); assertEquals(Boolean.TRUE, menu.invoke(hook, activity, comment, null));
                AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); int selected = -1;
                for (int i = 0; i < dialog.getListView().getCount(); i++) if (label.equals(dialog.getListView().getAdapter().getItem(i))) selected = i;
                assertTrue("Missing comment media action", selected >= 0);
                assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
                dialog.getListView().performItemClick(null, selected, selected);
                RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS); assertNotNull(request); assertEquals(path, request.getPath());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                do { ShadowLooper.idleMainLooper(); if (DownloadTasks.snapshots().stream().noneMatch(t -> "running".equals(t.status) || "queued".equals(t.status) || "cancelling".equals(t.status))) break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5)); } while (System.nanoTime() < deadline);
                assertEquals(1, docs.files.size()); String id = docs.files.keySet().iterator().next();
                assertEquals(mime, docs.types.get(id)); assertEquals("root", docs.parents.get(id));
                assertEquals("comment-media-body", Files.readString(docs.files.get(id).toPath())); assertEquals(1, server.getRequestCount());
            } finally { prefs.edit().remove(LocalOptions.DOWNLOAD_SAVE_TREE).commit(); }
        }
    }
    public static final class Comment { public String cid = "c-1"; public List<Alias> video_list; public Audio comment_audio; }
    public static final class Alias { public Work alias_aweme; }
    public static final class Work { public DownloadActionHookTest.VideoFixture video; }
    public static final class Audio { public String content; }
    public static final class Params { public Comment LJIIIIZZ; }
    public static final class Item { public Params k; }
}
