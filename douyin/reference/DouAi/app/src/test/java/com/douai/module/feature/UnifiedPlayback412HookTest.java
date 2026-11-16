package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import com.douai.module.config.ExtendedSettings;
import com.douai.module.config.LocalOptions;
import java.time.Duration;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class UnifiedPlayback412HookTest {
    private Activity activity;
    private Panel panel;
    private UnifiedPlayback412Hook hook;
    public static final class Work {
        String id = "first";
        public String getAid() { return id; }
        public boolean isLive() { return false; }
        public boolean isImage() { return false; }
    }
    public static final class Manager {
        int pauses;
        public void setSpeed(float speed) { }
        public void pause() { pauses++; }
    }
    public static final class Adapter { public int getCount() { return 4; } }
    public static final class Pager extends View {
        int position;
        boolean foreground = true;
        Pager(Context context) { super(context); }
        @Override public boolean isAttachedToWindow() { return foreground; }
        @Override public boolean isShown() { return foreground; }
        @Override public boolean hasWindowFocus() { return foreground; }
        public int getCurrentItem() { return position; }
        public Adapter getAdapter() { return new Adapter(); }
    }
    public static final class Panel {
        public Pager f;
        final Work work = new Work();
        final Manager manager = new Manager();
        int advances;
        public Work work() { return work; }
        public Manager manager() { return manager; }
        public void setCurrentItem(int target, boolean smooth) { f.position = target; advances++; }
    }
    public static final class Listener { public Panel a; }
    public static final class Flags { public boolean menu, click; }
    public static final class Event { public String id; }
    @Before public void setUp() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        panel = new Panel(); panel.f = new Pager(activity);
        var prefs = activity.getSharedPreferences("unified-playback-test", Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("auto_play", true).commit();
        hook = new UnifiedPlayback412Hook((Application) activity.getApplicationContext(),
                new LocalOptions(Map.of()), ExtendedSettings.readFrom(prefs),
                Listener.class.getField("a"), Panel.class.getField("f"), Flags.class.getField("menu"),
                Flags.class.getField("click"), Event.class.getField("id"), Panel.class.getMethod("work"),
                Panel.class.getMethod("setCurrentItem", int.class, boolean.class), Panel.class.getMethod("manager"),
                Manager.class.getMethod("setSpeed", float.class), Manager.class.getMethod("pause"),
                Work.class.getMethod("getAid"), Work.class.getMethod("isLive"), Work.class.getMethod("isImage"), ignored -> { });
    }
    @After public void cleanUp() { if (hook != null) hook.close(); if (activity != null) activity.finish(); }
    private void drain() { Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(150)); }
    @Test public void actualCompletionNavigatesOnceAndRejectedOldEventCannotAdvanceANewVideo() {
        hook.observe(panel, "onPlaying", "first"); drain();
        hook.observe(panel, "onPlayCompleted", "first"); drain();
        hook.observe(panel, "onPlayCompleted", "first"); drain();
        assertEquals(1, panel.advances);
        panel.work.id = "second";
        hook.observe(panel, "onPlayCompleted", "first"); drain();
        assertEquals(1, panel.advances);
        hook.observe(panel, "onPlaying", "second"); drain();
        hook.observe(panel, "onPlayCompleted", "second"); drain();
        assertEquals(2, panel.advances);
    }
    @Test public void backgroundTransitionCancelsDelayedCompletionAndCloseStopsFutureEvents() {
        hook.observe(panel, "onPlaying", "first"); drain();
        hook.observe(panel, "onPlayCompleted", "first");
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
        panel.f.foreground = false; drain();
        assertEquals(0, panel.advances);
        panel.f.foreground = true; hook.close();
        hook.observe(panel, "onPlayCompleted", "first"); drain();
        assertEquals(0, panel.advances);
    }
    @Test public void pauseOnCompleteUsesNativePauseAndSuppressesAutomaticNavigation() {
        hook.setOptions(new LocalOptions(Map.of("pause_on_complete", true)));
        hook.observe(panel, "onPlaying", "first"); drain();
        hook.observe(panel, "onPlayCompleted", "first"); drain();
        assertEquals(1, panel.manager.pauses);
        assertEquals(0, panel.advances);
    }
}
