package com.douai.module.ui;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class HostSettingsEntryTest {
    @Test
    public void insertsOnceIntoSettingsScrollAndOpensTheSameInHostPanel() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(new View(activity));
        content.addView(new View(activity));
        scroll.addView(content);
        activity.setContentView(scroll);
        AtomicInteger clicks = new AtomicInteger();
        View entry = HostSettingsEntry.attach(activity, ScrollView.class, clicks::incrementAndGet);
        assertNotNull(entry);
        assertSame(entry, content.getChildAt(1));
        entry.performClick();
        assertEquals(1, clicks.get());
        assertSame(entry, HostSettingsEntry.attach(activity, ScrollView.class, clicks::incrementAndGet));
        assertEquals(3, content.getChildCount());
        activity.finish();
    }

    @Test
    public void refusesAmbiguousHostScrollContainers() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);
        root.addView(new ScrollView(activity));
        root.addView(new ScrollView(activity));
        activity.setContentView(root);
        assertNull(HostSettingsEntry.attach(activity, ScrollView.class,
                () -> fail("No entry should be installed")));
        assertEquals(2, root.getChildCount());
        activity.finish();
    }

    @Test public void neutralSingleChildWrappersDoNotHideTheSettingsEntry() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            ScrollView scroll = new ScrollView(activity);
            android.widget.FrameLayout wrapper = new android.widget.FrameLayout(activity);
            LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
            content.addView(new TextView(activity)); wrapper.addView(content); scroll.addView(wrapper);
            activity.setContentView(scroll);
            View entry = HostSettingsEntry.attach(activity, ScrollView.class, () -> { });
            assertNotNull(entry); assertEquals("融合设置", ((TextView)entry).getText().toString());
            assertSame(content, entry.getParent()); assertEquals(2, content.getChildCount());
        } finally { activity.finish(); }
    }

    @Test public void nonLinearOrAmbiguousInnerLayoutsAreLeftIntactForTheFloatingFallback() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            ScrollView scroll = new ScrollView(activity);
            android.widget.FrameLayout wrapper = new android.widget.FrameLayout(activity);
            wrapper.addView(new View(activity)); wrapper.addView(new View(activity)); scroll.addView(wrapper);
            activity.setContentView(scroll);
            assertNull(HostSettingsEntry.attach(activity, ScrollView.class, () -> fail("Unsafe insertion")));
            assertEquals(2, wrapper.getChildCount());
        } finally { activity.finish(); }
    }
}
