package com.douai.module.feature;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Proxy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class FeedCommentMenuHookTest {
    private Activity activity;
    private FrameLayout root;
    private TextView button;
    private FeedCommentMenuHook hook;
    private int menus, clicks, originalEvents;
    private long downAt;
    private float rawX, rawY;

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        root = new FrameLayout(activity);
        button = new TextView(activity);
        button.setContentDescription("评论128，按钮");
        button.setOnClickListener(ignored -> clicks++);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(100, 80);
        position.leftMargin = 100; position.topMargin = 200;
        root.addView(button, position); activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 320, 600);
        int[] location = new int[2]; button.getLocationOnScreen(location);
        rawX = location[0] + 20; rawY = location[1] + 20;
        hook = new FeedCommentMenuHook(ignored -> root, ignored -> menus++, null);
        downAt = SystemClock.uptimeMillis();
    }
    @After public void close() { hook.close(); activity.finish(); }

    @Test public void longPressConsumesTheWholeGestureBeforeAnyHostSpeedTouchAndOpensTheMenuOnce() throws Throwable {
        send(MotionEvent.ACTION_DOWN, rawX, rawY);
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout() + 20, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1, menus); assertEquals(0, originalEvents);
        send(MotionEvent.ACTION_UP, rawX, rawY);
        assertEquals(0, originalEvents); assertEquals(0, clicks);
    }

    @Test public void shortTapReplaysTheOriginalCommentClickAndMovingCancelsMenuRecognition() throws Throwable {
        send(MotionEvent.ACTION_DOWN, rawX, rawY);
        send(MotionEvent.ACTION_UP, rawX, rawY);
        ShadowLooper.idleMainLooper();
        assertEquals(1, clicks); assertEquals(0, menus);
        send(MotionEvent.ACTION_DOWN, rawX, rawY);
        send(MotionEvent.ACTION_MOVE, rawX + 80, rawY + 80);
        ShadowLooper.idleMainLooper(700, java.util.concurrent.TimeUnit.MILLISECONDS);
        send(MotionEvent.ACTION_UP, rawX + 80, rawY + 80);
        assertEquals(0, menus);
        button.setContentDescription("点赞");
        int before = originalEvents;
        send(MotionEvent.ACTION_DOWN, rawX, rawY);
        assertEquals(before + 1, originalEvents);
    }

    private void send(int action, float x, float y) throws Throwable {
        MotionEvent event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0);
        XposedInterface.Chain chain = (XposedInterface.Chain)Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getThisObject")) return activity;
                    if (method.getName().equals("getArg")) return event;
                    if (method.getName().equals("proceed")) { originalEvents++; return activity.dispatchTouchEvent(event); }
                    throw new UnsupportedOperationException(method.getName());
                });
        try { hook.intercept(chain); } finally { event.recycle(); }
    }
}
