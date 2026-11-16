package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import com.douai.module.config.ComponentHideSettings.Component;
import io.github.libxposed.api.XposedInterface;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

public final class FeedCommentMenuHook implements AutoCloseable, Application.ActivityLifecycleCallbacks {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Activity, Touch> touches = new WeakHashMap<>();
    private final Function<Activity, View> roots;
    private final Consumer<Activity> openMenu;
    private final Consumer<String> log;
    private final ThreadLocal<Boolean> replay = new ThreadLocal<>();
    private XposedInterface.HookHandle handle;
    private Application application;
    private boolean closed;

    FeedCommentMenuHook(Function<Activity, View> roots, Consumer<Activity> openMenu, Consumer<String> log) {
        this.roots = roots; this.openMenu = openMenu;
        this.log = log == null ? ignored -> { } : log;
    }

    public static FeedCommentMenuHook install(XposedInterface framework, Application application,
            Function<Activity, View> roots, Consumer<Activity> openMenu, Consumer<String> log) throws NoSuchMethodException {
        FeedCommentMenuHook feature = new FeedCommentMenuHook(roots, openMenu, log);
        feature.application = application;
        feature.handle = framework.hook(Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class))
                .setPriority(XposedInterface.PRIORITY_HIGHEST).setId("DouAi.Feed.CommentMenu")
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(feature::intercept);
        if (feature.handle == null) throw new IllegalStateException("Empty comment gesture handle");
        application.registerActivityLifecycleCallbacks(feature);
        return feature;
    }

    Object intercept(XposedInterface.Chain chain) throws Throwable {
        if (closed || Boolean.TRUE.equals(replay.get())) return chain.proceed();
        Activity activity = (Activity)chain.getThisObject();
        MotionEvent event = (MotionEvent)chain.getArg(0);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            clear(activity);
            View root = roots.apply(activity);
            View button = findComment(root, event.getRawX(), event.getRawY(), new int[]{2048}, 0);
            if (button == null || activity.isFinishing() || activity.isDestroyed()) return chain.proceed();
            Touch touch = new Touch(event, root, button);
            touches.put(activity, touch);
            touch.timeout = () -> {
                if (closed || touches.get(activity) != touch) return;
                touch.handled = true;
                if (activity.isFinishing() || activity.isDestroyed() || roots.apply(activity) != root || !button.isShown()) return;
                try { openMenu.accept(activity); }
                catch (RuntimeException | LinkageError failure) {
                    try { log.accept("Comment menu unavailable: " + failure.getClass().getSimpleName()); }
                    catch (RuntimeException ignored) { }
                }
            };
            main.postDelayed(touch.timeout, ViewConfiguration.getLongPressTimeout());
            return true;
        }
        Touch touch = touches.get(activity);
        if (touch == null) return chain.proceed();
        if (touch.handled) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) clear(activity);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) { clear(activity); return true; }
        boolean moved = action == MotionEvent.ACTION_MOVE && Math.hypot(event.getRawX() - touch.down.getRawX(),
                event.getRawY() - touch.down.getRawY()) > ViewConfiguration.get(activity).getScaledTouchSlop();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_DOWN || moved) {
            main.removeCallbacks(touch.timeout);
            touches.remove(activity);
            replay.set(true);
            try { activity.dispatchTouchEvent(touch.down); }
            finally { replay.remove(); touch.down.recycle(); }
            return chain.proceed();
        }
        return true;
    }

    static View findComment(View view, float rawX, float rawY, int[] budget, int depth) {
        if (view == null || budget[0]-- <= 0 || depth > 32 || !view.isShown() || !view.isEnabled()
                || view instanceof android.widget.EditText) return null;
        Rect bounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains((int)rawX, (int)rawY)) return null;
        String name = "";
        try { if (view.getId() != View.NO_ID) name = view.getResources().getResourceEntryName(view.getId()); }
        catch (RuntimeException ignored) { }
        if (UnifiedUi412Hook.semanticComponent(name, String.valueOf(view.getContentDescription()),
                view.getClass().getName()) == Component.COMMENT) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                View found = findComment(group.getChildAt(index), rawX, rawY, budget, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void clear(Activity activity) {
        Touch touch = touches.remove(activity);
        if (touch != null) { main.removeCallbacks(touch.timeout); touch.down.recycle(); }
    }
    @Override public void close() {
        closed = true;
        if (handle != null) handle.unhook();
        if (application != null) application.unregisterActivityLifecycleCallbacks(this);
        for (Activity activity : new java.util.ArrayList<>(touches.keySet())) clear(activity);
    }
    @Override public void onActivityPaused(Activity activity) { clear(activity); }
    @Override public void onActivityDestroyed(Activity activity) { clear(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    private static final class Touch {
        final MotionEvent down;
        final View root, button;
        Runnable timeout;
        boolean handled;
        Touch(MotionEvent event, View root, View button) {
            down = MotionEvent.obtain(event); this.root = root; this.button = button;
        }
    }
}
