package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import com.douai.module.config.ExtendedSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * 41.2 feed events and native touch adapter. This never enables the host AutoPlayViewModel:
 * completion advances the current pager once, subject to foreground/touch/comment checks.
 * Speed locking is provided by PlaybackBehaviorHooks; this class owns manual gesture speed.
 */
public final class UnifiedPlayback412Hook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    @FunctionalInterface public interface ActionHandler {
        boolean perform(String action, Object panel, View pager);
    }
    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BehaviorHookGroup hooks;
    private final Consumer<String> log;
    private final Field listenerPanel, pagerField, longEnabled, clickEnabled, eventId;
    private final Method currentAweme, nextItem, player, setSpeed, pause;
    private final Method aidGetter, liveGetter, imageGetter;
    private final UnifiedPlayback412Policy policy = new UnifiedPlayback412Policy();
    private final Map<View, Touch> touches = new WeakHashMap<>();
    private final ThreadLocal<Boolean> replay = new ThreadLocal<>();
    private volatile LocalOptions options;
    private volatile ExtendedSettings settings;
    private volatile boolean closed;
    private boolean touching;
    private WeakReference<Object> selectedPanel = new WeakReference<>(null);
    private WeakReference<View> selectedPager = new WeakReference<>(null);
    private Consumer<Boolean> playbackListener = ignored -> { };
    private ActionHandler actions = (action, panel, pager) -> false;
    private GestureSpeedOverride manualSpeed;
    private WeakReference<Object> manualPlayer = new WeakReference<>(null);
    private float manualBaseline = 1f;
    private HostPlaybackSpeedAccess speedReader;

    UnifiedPlayback412Hook(Application app, LocalOptions options, ExtendedSettings settings,
            Field listenerPanel, Field pagerField, Field longEnabled, Field clickEnabled, Field eventId,
            Method currentAweme, Method nextItem, Method player, Method setSpeed, Method pause,
            Method aidGetter, Method liveGetter, Method imageGetter, Consumer<String> log) {
        this.app = app; this.options = options; this.settings = settings;
        this.listenerPanel = listenerPanel; this.pagerField = pagerField;
        this.longEnabled = longEnabled; this.clickEnabled = clickEnabled;
        this.eventId = eventId;
        this.currentAweme = currentAweme; this.nextItem = nextItem; this.player = player;
        this.setSpeed = setSpeed; this.pause = pause; this.aidGetter = aidGetter;
        this.liveGetter = liveGetter; this.imageGetter = imageGetter;
        this.log = log == null ? ignored -> { } : log;
        hooks = new BehaviorHookGroup("unified_playback_412", this.log);
    }

    public static UnifiedPlayback412Hook install(XposedInterface framework, ClassLoader loader,
            Context context, ExtendedSettings settings, LocalOptions options, Consumer<String> log)
            throws ReflectiveOperationException {
        if (HostAdaptation.currentProfile() != HostAdaptation.PROFILE_412)
            throw new IllegalStateException("41.2 verified profile required");
        if (!(context.getApplicationContext() instanceof Application))
            throw new IllegalArgumentException("Host Application required");
        Class<?> panel = Class.forName("com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel", false, loader);
        Class<?> listener = Class.forName("X.10fQ", false, loader);
        Class<?> aweme = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader);
        Class<?> manager = Class.forName("com.ss.android.ugc.aweme.video.IPlayerManager", false, loader);
        Class<?> group = Class.forName("com.ss.android.ugc.aweme.feed.plato.core.FeedComponentGroup", false, loader);
        Class<?> touch = Class.forName("com.ss.android.ugc.aweme.feed.ui.LongPressLayout", false, loader);
        Field owner = listener.getDeclaredField("a"), pager = panel.getField("f");
        if (!owner.getType().getName().equals("com.ss.android.ugc.aweme.feed.panel.IBaseListFragmentPanel")
                || !View.class.isAssignableFrom(pager.getType())) throw new NoSuchFieldException("41.2 feed relationship");
        owner.setAccessible(true);
        Method accessor = exact(panel, "getCurrentAweme", aweme);
        Method player = exact(panel, "LLJL", manager);
        Method next = exact(panel, "setCurrentItem", void.class, int.class, boolean.class);
        Method speed = manager.getMethod("setSpeed", float.class);
        Method pause = manager.getMethod("LJJJJI");
        if (pause.getReturnType() != void.class || speed.getReturnType() != void.class)
            throw new NoSuchMethodException("41.2 player commands");
        Field longEnabled = touch.getDeclaredField("longPressEnable"), click = touch.getDeclaredField("enableLongClick");
        if (longEnabled.getType() != boolean.class || click.getType() != boolean.class)
            throw new NoSuchFieldException("41.2 native gesture flags");
        longEnabled.setAccessible(true); click.setAccessible(true);
        Class<?> playbackEvent = Class.forName("X.1VZk", false, loader);
        Field eventId = playbackEvent.getDeclaredField("LIZ");
        if (eventId.getType() != String.class) throw new NoSuchFieldException("41.2 playback source identity");
        eventId.setAccessible(true);
        UnifiedPlayback412Hook feature = new UnifiedPlayback412Hook((Application) context.getApplicationContext(),
                options, settings, owner, pager, longEnabled, click, eventId, accessor, next, player, speed, pause,
                exact(aweme, "getAid", String.class), exact(aweme, "isLive", boolean.class),
                exact(aweme, "isImage", boolean.class), log);
        try {
            for (String[] event : new String[][]{{"onPlaying", "X.1VWG"}, {"onResumePlay", "X.0zq4"},
                    {"onPausePlay", "X.0zq7"}, {"onPlayCompleted", "X.0zqE"}}) {
                String name = event[0];
                Method target = exact(listener, name, void.class, Class.forName(event[1], false, loader));
                feature.hooks.add(framework, target, chain -> {
                    Object panelOwner = null;
                    String sourceId = null;
                    try {
                        panelOwner = feature.listenerPanel.get(chain.getThisObject());
                        sourceId = (String) feature.eventId.get(chain.getArg(0));
                    } catch (ReflectiveOperationException | RuntimeException failure) { feature.failure(failure); }
                    Object result = chain.proceed();
                    if (panelOwner != null) feature.observe(panelOwner, name, sourceId);
                    return result;
                });
            }
            feature.hooks.add(framework, exact(group, "interceptDoubleClick", boolean.class,
                    MotionEvent.class, MotionEvent.class), feature::doubleTap);
            feature.hooks.add(framework, exact(touch, "dispatchTouchEvent", boolean.class, MotionEvent.class),
                    feature::touch);
            Class<?> speedPresenter = Class.forName("com.ss.android.ugc.aweme.control.speed.presenter.FeedLongPressFastSpeedPresenter", false, loader);
            feature.hooks.add(framework, exact(speedPresenter, "LLLLLLZ", void.class,
                    float.class, float.class, float.class), chain -> {
                // Blocking native fast speed should retain its ordinary long-press menu.
                return !feature.closed && feature.enabled("block_long_press_speed") ? null : chain.proceed();
            });
            for (String model : new String[]{
                    "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.AutoPlayViewModel",
                    "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autonext.AutoNextViewModel"}) {
                Class<?> autoPlay = Class.forName(model, false, loader);
                feature.hooks.add(framework, exact(autoPlay, "aD", boolean.class), chain -> {
                    Object result = chain.proceed();
                    ExtendedSettings current = feature.settings;
                    // Phone and pad paths have separate native models; neither may double-advance.
                    return !feature.closed && ((current != null && current.autoPlay)
                            || feature.enabled("pause_on_complete")) ? Boolean.FALSE : result;
                });
            }
            feature.app.registerActivityLifecycleCallbacks(feature);
            return feature;
        } catch (RuntimeException | ReflectiveOperationException | LinkageError failure) {
            feature.close(); throw failure;
        }
    }

    public static UnifiedPlayback412Hook install(XposedInterface framework, HostScanReport report,
            ClassLoader loader, Context context, ExtendedSettings settings, LocalOptions options,
            Consumer<String> log) throws ReflectiveOperationException {
        UnifiedPlayback412Hook hook = install(framework, loader, context, settings, options, log);
        try {
            hook.speedReader = HostPlaybackSpeedAccess.resolve(
                    HostTargets.method(report, "adv_gesture_player_get_speed", loader), report, loader);
        } catch (Exception | LinkageError failure) { hook.failure(failure); }
        return hook;
    }

    private static Method exact(Class<?> owner, String name, Class<?> result, Class<?>... args)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, args);
        if (method.getReturnType() != result) throw new NoSuchMethodException(name + " return type");
        method.setAccessible(true); return method;
    }

    public void setOptions(LocalOptions next) { options = next; }
    public void setSettings(ExtendedSettings next, LocalOptions opts) { settings = next; options = opts; }
    public void setPlaybackListener(Consumer<Boolean> listener) { playbackListener = listener == null ? ignored -> { } : listener; }
    public void setActionHandler(ActionHandler handler) { actions = handler == null ? (a, p, v) -> false : handler; }
    public boolean isActive() { return !closed && hooks.isActive(); }

    void observe(Object panel, String event, String eventSourceId) {
        main.post(() -> {
            if (closed) return;
            try {
                Object value = pagerField.get(panel);
                if (!(value instanceof View) || !foreground((View) value)) return;
                Object work = currentAweme.invoke(panel);
                if (work == null || Boolean.TRUE.equals(liveGetter.invoke(work))
                        || Boolean.TRUE.equals(imageGetter.invoke(work))) return;
                String aid = (String) aidGetter.invoke(work);
                if (aid == null || aid.isEmpty()) return;
                // Completion from an old video may arrive after its panel is rebound.
                if (eventSourceId != null && !eventSourceId.isEmpty() && !aid.equals(eventSourceId)) return;
                if ("onPlayCompleted".equals(event) && (eventSourceId == null || eventSourceId.isEmpty())) return;
                if (!aid.equals(policy.aid())) restoreManualSpeed();
                policy.selected(aid);
                selectedPanel = new WeakReference<>(panel); selectedPager = new WeakReference<>((View) value);
                if ("onPlayCompleted".equals(event)) {
                    if (enabled("pause_on_complete")) {
                        pause.invoke(player.invoke(panel)); playbackListener.accept(false);
                    } else complete(panel, (View) value, aid);
                } else playbackListener.accept(!"onPausePlay".equals(event));
            } catch (ReflectiveOperationException | RuntimeException failure) { failure(failure); }
        });
    }

    private void complete(Object panel, View pager, String aid) {
        main.postDelayed(() -> {
            if (closed || selectedPanel.get() != panel) return;
            try {
                Object work = currentAweme.invoke(panel);
                String actual = work == null ? null : (String) aidGetter.invoke(work);
                if (!aid.equals(actual)) return;
                int position = ((Number) call(pager, "getCurrentItem")).intValue();
                Object adapter = call(pager, "getAdapter");
                int count = adapter == null ? 0 : ((Number) call(adapter, "getCount")).intValue();
                boolean comments = enabled("pause_auto_on_comments") && commentsVisible(pager.getRootView(), 0);
                ExtendedSettings current = settings;
                int target = policy.completion(actual, current != null && current.autoPlay,
                        foreground(pager), touching, comments, position, count);
                if (target >= 0) nextItem.invoke(panel, target, true);
            } catch (ReflectiveOperationException | RuntimeException failure) { failure(failure); }
        }, 80);
    }

    private Object doubleTap(XposedInterface.Chain chain) throws Throwable {
        View pager = selectedPager.get(); Object panel = selectedPanel.get();
        if (closed || pager == null || panel == null || !foreground(pager)) return chain.proceed();
        MotionEvent event = (MotionEvent) chain.getArg(1);
        String action = action("double_tap", pager, event);
        if (!"none".equals(action) && perform(action, panel, pager)) return true;
        return enabled("disable_double_tap_like") ? Boolean.TRUE : chain.proceed();
    }

    private Object touch(XposedInterface.Chain chain) throws Throwable {
        if (closed || Boolean.TRUE.equals(replay.get())) return chain.proceed();
        View view = (View) chain.getThisObject(), pager = selectedPager.get();
        if (pager == null || !descendant(pager, view) || !foreground(pager)) {
            Touch stale = touches.remove(view);
            if (stale != null) restoreTouch(view, stale);
            touching = false;
            return chain.proceed();
        }
        MotionEvent event = (MotionEvent) chain.getArg(0); int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            Touch previous = touches.remove(view);
            if (previous != null) restoreTouch(view, previous);
            String selected = action("long_press", pager, event);
            Touch state = new Touch(view, event, longEnabled.getBoolean(view), clickEnabled.getBoolean(view));
            touches.put(view, state);
            if (!"none".equals(selected) || enabled(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS)) {
                longEnabled.setBoolean(view, false); clickEnabled.setBoolean(view, false);
                if (!"none".equals(selected)) {
                    state.timeout = () -> {
                        if (closed || touches.get(view) != state || !foreground(pager)) return;
                        state.handled = perform(selected, selectedPanel.get(), pager);
                        if (state.handled) {
                            restoreTouch(view, state);
                            cancelNative(view, state);
                        }
                    };
                    main.postDelayed(state.timeout, ViewConfiguration.getLongPressTimeout());
                }
            }
        } else {
            Touch state = touches.get(view);
            if (state != null && action == MotionEvent.ACTION_MOVE) {
                int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                if (Math.hypot(event.getRawX() - state.x, event.getRawY() - state.y) > slop)
                    state.cancel(main);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                touching = action == MotionEvent.ACTION_POINTER_DOWN;
                if (state != null) {
                    state.cancel(main); touches.remove(view);
                    longEnabled.setBoolean(view, state.longEnabled); clickEnabled.setBoolean(view, state.clickEnabled);
                    if (state.handled) return true;
                    if (action == MotionEvent.ACTION_UP && enabled("smart_swipe")) {
                        float delta = event.getRawY() - state.y;
                        if (Math.abs(delta) > view.getHeight() * .12f) policy.direction(delta > 0 ? -1 : 1);
                    }
                }
            }
        }
        return chain.proceed();
    }

    private void cancelNative(View view, Touch touch) {
        MotionEvent cancel = MotionEvent.obtain(touch.downAt, android.os.SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, 0, 0, 0);
        replay.set(true);
        try { view.dispatchTouchEvent(cancel); }
        finally { replay.remove(); cancel.recycle(); }
    }

    private String action(String prefix, View pager, MotionEvent event) {
        int[] location = new int[2]; pager.getLocationOnScreen(location);
        int region = FeedGestureToolsHook.selectedRegion(event.getRawX(), event.getRawY(),
                location[0], location[1], pager.getWidth(), pager.getHeight());
        return FeedGestureToolsHook.regionAction(options, prefix, region);
    }

    private boolean perform(String action, Object panel, View pager) {
        if (panel == null || pager == null || !foreground(pager)) return false;
        try {
            if ("copy_text".equals(action)) {
                Object work = currentAweme.invoke(panel);
                Object text = work == null ? null : work.getClass().getMethod("getDesc").invoke(work);
                if (!(text instanceof String) || ((String) text).isEmpty()) return false;
                ClipboardManager clipboard = (ClipboardManager) pager.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard == null) return false;
                clipboard.setPrimaryClip(ClipData.newPlainText("抖音文案", (String) text)); return true;
            }
            if ("speed".equals(action)) {
                if (manualSpeed != null) { restoreManualSpeed(); return true; }
                Object manager = player.invoke(panel);
                if (manager == null || speedReader == null) return false;
                manualBaseline = speedReader.read(manager);
                GestureSpeedOverride choice = new GestureSpeedOverride();
                float speed = PlaybackBehaviorHooks.playbackSpeed(options == null ? new LocalOptions(Collections.emptyMap()) : options);
                choice.rememberOriginal(manager, manualBaseline);
                choice.apply(speed, () -> setSpeed.invoke(manager, speed));
                manualSpeed = choice; manualPlayer = new WeakReference<>(manager); return true;
            }
            if ("menu".equals(action)) {
                Activity owner = activity(pager.getContext()); if (owner == null) return false;
                String[] ids = {"copy_text", "save_media", "open_comments", "toggle_components", "speed", "module_settings"};
                AlertDialog playbackMenu = new AlertDialog.Builder(owner).setTitle("播放快捷操作")
                        .setItems(new String[]{"复制文案", "保存媒体", "打开评论", "隐藏/显示组件", "倍速", "模块设置"},
                                (dialog, which) -> perform(ids[which], panel, pager)).setNegativeButton("取消", null).create();
                UnifiedPopupFilterHook.markModule(playbackMenu);
                playbackMenu.show();
                return true;
            }
            return actions.perform(action, panel, pager);
        } catch (ReflectiveOperationException | RuntimeException failure) { failure(failure); return false; }
    }

    private void restoreManualSpeed() {
        GestureSpeedOverride previous = manualSpeed; Object manager = manualPlayer.get();
        manualSpeed = null; manualPlayer.clear();
        if (previous == null) return;
        try { if (manager != null) previous.restore(() -> setSpeed.invoke(manager, previous.restorationSpeed(manager, manualBaseline))); }
        catch (ReflectiveOperationException | RuntimeException failure) { failure(failure); }
        finally { previous.close(); }
        if (!closed && manager != null && enabled("auto_speed")) {
            try { setSpeed.invoke(manager, PlaybackBehaviorHooks.playbackSpeed(options)); }
            catch (ReflectiveOperationException | RuntimeException failure) { failure(failure); }
        }
    }
    private static Object call(Object owner, String name) throws ReflectiveOperationException {
        Method method = owner.getClass().getMethod(name); method.setAccessible(true); return method.invoke(owner);
    }
    private void restoreTouch(View view, Touch touch) {
        touch.cancel(main);
        try { longEnabled.setBoolean(view, touch.longEnabled); clickEnabled.setBoolean(view, touch.clickEnabled); }
        catch (ReflectiveOperationException | RuntimeException failure) { failure(failure); }
    }
    private boolean enabled(String key) { LocalOptions value = options; return value != null && value.enabled(key); }
    private void failure(Throwable failure) { try { log.accept("41.2 playback: " + failure.getClass().getSimpleName()); } catch (Throwable ignored) { } }
    private static boolean foreground(View view) {
        Activity owner = activity(view.getContext());
        return view.isAttachedToWindow() && view.isShown() && view.hasWindowFocus()
                && owner != null && !owner.isFinishing() && !owner.isDestroyed();
    }
    private static Activity activity(Context context) {
        for (int i = 0; context != null && i < 12; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context base = ((ContextWrapper) context).getBaseContext(); if (base == context) return null; context = base;
        }
        return null;
    }
    private static boolean descendant(View root, View child) {
        for (View value = child; value != null; value = value.getParent() instanceof View ? (View) value.getParent() : null)
            if (value == root) return true;
        return false;
    }
    private static boolean commentsVisible(View view, int depth) {
        if (view == null || depth > 14 || !view.isShown()) return false;
        if (view.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("comment")
                && view.getHeight() > 200 && view.getWidth() > 200) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < Math.min(group.getChildCount(), 128); i++)
                if (commentsVisible(group.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }
    private static final class Touch {
        final float x, y; final long downAt; final boolean longEnabled, clickEnabled;
        Runnable timeout; boolean handled;
        Touch(View view, MotionEvent event, boolean longEnabled, boolean clickEnabled) {
            x = event.getRawX(); y = event.getRawY(); downAt = event.getDownTime();
            this.longEnabled = longEnabled; this.clickEnabled = clickEnabled;
        }
        void cancel(Handler main) { if (timeout != null) main.removeCallbacks(timeout); timeout = null; }
    }
    @Override public void onActivityPaused(Activity activity) {
        View pager = selectedPager.get(); if (pager == null || activity(pager.getContext()) != activity) return;
        touching = false; policy.clear(); selectedPanel.clear(); selectedPager.clear(); restoreManualSpeed();
        for (Map.Entry<View, Touch> entry : touches.entrySet()) restoreTouch(entry.getKey(), entry.getValue());
        touches.clear();
        playbackListener.accept(false);
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
    @Override public void close() {
        if (closed) return; closed = true; main.removeCallbacksAndMessages(null);
        hooks.close(); restoreManualSpeed();
        for (Map.Entry<View, Touch> entry : touches.entrySet()) restoreTouch(entry.getKey(), entry.getValue());
        touches.clear(); app.unregisterActivityLifecycleCallbacks(this);
        selectedPanel.clear(); selectedPager.clear(); policy.clear();
    }
}
