package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.douai.module.config.LocalOptions;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional manual follow confirmation, local hidden-emoji visibility and exact comment timestamps. */
public final class UnifiedInteraction412Hook implements AutoCloseable {
    public static final String CONFIRM_FOLLOW = "unified_confirm_follow";
    public static final String SHOW_HIDDEN_EMOJI = "unified_show_hidden_emoji";
    public static final String FULL_COMMENT_TIME = "unified_full_comment_time";
    public static final String FULL_MESSAGE_TIME = "unified_full_message_time";
    private static final ThreadLocal<Integer> AUTOMATIC = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CONFIRMED = new ThreadLocal<>();
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Set<String> supported = ConcurrentHashMap.newKeySet();
    private final Map<View, Integer> statuses = new WeakHashMap<>();
    private final Map<View, Long> touches = new WeakHashMap<>();
    private final Map<View, WeakReference<Object>> cards = new WeakHashMap<>();
    private final Map<View, WeakReference<AlertDialog>> confirming = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> log;
    private volatile LocalOptions options;
    private volatile boolean closed;

    UnifiedInteraction412Hook(LocalOptions options, Consumer<String> log) {
        this.options = options == null ? new LocalOptions(Collections.emptyMap()) : options;
        this.log = log == null ? ignored -> { } : log;
    }

    public static UnifiedInteraction412Hook install(XposedInterface framework, ClassLoader loader,
                                                     LocalOptions options, Consumer<String> log) {
        UnifiedInteraction412Hook hook = new UnifiedInteraction412Hook(options, log);
        hook.stage(CONFIRM_FOLLOW, () -> hook.follow(framework, loader));
        hook.stage(SHOW_HIDDEN_EMOJI, () -> {
            Class<?> emoji = Class.forName("com.ss.android.ugc.aweme.emoji.model.Emoji", false, loader);
            Method getter = emoji.getDeclaredMethod("LJIJ");
            Field hidden = emoji.getDeclaredField("hide");
            if (getter.getReturnType() != Boolean.class || hidden.getType() != Boolean.class) throw new IllegalStateException("Emoji visibility changed");
            hook.add(framework, getter, chain -> {
                Object result = chain.proceed();
                if (!hook.closed && hook.options.enabled(SHOW_HIDDEN_EMOJI) && Boolean.TRUE.equals(result)
                        && shouldExposeEmoji(true, true, knownEmojiAsset(chain.getThisObject()))) return Boolean.FALSE;
                return result;
            });
        });
        hook.stage(FULL_COMMENT_TIME, () -> {
            Class<?> formatter = Class.forName("com.ss.android.ugc.aweme.utils.TimeUtils", false, loader);
            hook.add(framework, formatter.getDeclaredMethod("LIZ", long.class, Context.class), chain -> {
                if (!hook.closed && hook.options.enabled(FULL_COMMENT_TIME) && fromComment()) {
                    String full = fullTimestamp(((Number)chain.getArg(0)).longValue(), TimeZone.getDefault());
                    if (full != null) return full;
                }
                return chain.proceed();
            });
        });
        return hook;
    }

    private void follow(XposedInterface framework, ClassLoader loader) throws Exception {
        Class<?> relation = Class.forName("com.ss.android.ugc.aweme.relation.view.FollowUserButton", false, loader);
        Class<?> dux = Class.forName("com.ss.android.ugc.aweme.feed.ui.wight.DuxButtonForFollowUser", false, loader);
        Method relationStatus = relation.getDeclaredMethod("getMCurrentStatus");
        for (Class<?> button : new Class<?>[]{relation, dux}) {
            add(framework, button.getDeclaredMethod("LIZIZ", int.class, int.class), chain -> {
                Object result = chain.proceed();
                statuses.put((View)chain.getThisObject(), ((Number)chain.getArg(0)).intValue());
                return result;
            });
        }
        Class<?> aweme = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader);
        for (String name : new String[]{"VideoViewHolder", "FeedImageViewHolder"}) {
            Class<?> holder = Class.forName("com.ss.android.ugc.aweme.feed.adapter." + name, false, loader);
            Field imageRoot = name.equals("FeedImageViewHolder") ? holder.getDeclaredField("f") : null;
            Method videoRoot = imageRoot == null ? holder.getDeclaredMethod("LJIIJ") : null;
            if (imageRoot != null) imageRoot.setAccessible(true); else videoRoot.setAccessible(true);
            add(framework, holder.getDeclaredMethod("bind", aweme, int.class), chain -> {
                Object result = chain.proceed();
                Object root = imageRoot == null ? videoRoot.invoke(chain.getThisObject()) : imageRoot.get(chain.getThisObject());
                if (root instanceof View) cards.put((View)root, new WeakReference<>(chain.getThisObject()));
                return result;
            });
        }
        // Both follow buttons declare onTouchEvent themselves. Hooking android.view.View.dispatchTouchEvent
        // charged every touch in the app (including feed scrolling) a hierarchy walk; hook only the buttons.
        for (Class<?> button : new Class<?>[]{relation, dux}) {
            add(framework, button.getDeclaredMethod("onTouchEvent", MotionEvent.class), chain -> {
                MotionEvent event = (MotionEvent)chain.getArg(0);
                if (!closed && options.enabled(CONFIRM_FOLLOW) && event != null
                        && (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_UP)) {
                    touches.put((View)chain.getThisObject(), SystemClock.uptimeMillis());
                }
                return chain.proceed();
            });
        }
        add(framework, View.class.getDeclaredMethod("performAccessibilityAction", int.class, android.os.Bundle.class), chain -> {
            if (options.enabled(CONFIRM_FOLLOW) && ((Number)chain.getArg(0)).intValue() == 16) {
                View nativeButton = nativeButton((View)chain.getThisObject());
                if (nativeButton != null) touches.put(nativeButton, SystemClock.uptimeMillis());
            }
            return chain.proceed();
        });
        add(framework, View.class.getDeclaredMethod("performClick"), chain -> {
            if (closed || !options.enabled(CONFIRM_FOLLOW) || automaticFollowActive() || Boolean.TRUE.equals(CONFIRMED.get())) return chain.proceed();
            View clicked = (View)chain.getThisObject(); View button = nativeButton(clicked);
            if (button == null || !button.isShown() || !button.isEnabled()) return chain.proceed();
            int status = status(button, relation, relationStatus);
            String uid = targetUid(button);
            Long touch = touches.get(button);
            boolean manual = touch != null && SystemClock.uptimeMillis() - touch <= 1500;
            if (!shouldConfirm(true, false, manual, status, uid)) return chain.proceed();
            if (confirming.containsKey(button)) return true;
            Activity activity = activity(clicked.getContext());
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return chain.proceed();
            AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("确认关注")
                    .setMessage("是否关注这位用户？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("关注", (ignored, which) -> {
                        if (closed || !options.enabled(CONFIRM_FOLLOW) || !clicked.isAttachedToWindow()
                                || !button.isShown() || status(button, relation, relationStatus) != 0
                                || !uid.equals(targetUid(button))) return;
                        CONFIRMED.set(Boolean.TRUE);
                        try { clicked.performClick(); } finally { CONFIRMED.remove(); }
                    }).create();
            confirming.put(button, new WeakReference<>(dialog));
            dialog.setOnDismissListener(ignored -> confirming.remove(button));
            UnifiedPopupFilterHook.markModule(dialog); dialog.show();
            return true;
        });
    }

    static boolean shouldConfirm(boolean enabled, boolean automatic, boolean manual, int status, String targetUid) {
        return enabled && !automatic && manual && status == 0 && targetUid != null && !targetUid.isEmpty();
    }

    private int status(View view, Class<?> relation, Method getter) {
        if (relation.isInstance(view)) try { return ((Number)getter.invoke(view)).intValue(); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return -1; }
        return statuses.getOrDefault(view, -1);
    }
    private String targetUid(View button) {
        if (button.getClass().getName().equals("com.ss.android.ugc.aweme.relation.view.FollowUserButton")) {
            Object user = HostTargets.read(button, "e"); Object uid = HostTargets.read(user, "uid");
            if (uid instanceof String && !((String)uid).isEmpty()) return (String)uid;
        }
        View current = button;
        for (int i = 0; current != null && i < 24; i++) {
            WeakReference<Object> reference = cards.get(current); Object holder = reference == null ? null : reference.get();
            if (holder != null) {
                Object work = HostTargets.invoke(holder, "getAweme"); Object user = HostTargets.read(work, "author");
                Object uid = HostTargets.read(user, "uid"); if (uid instanceof String) return (String)uid;
            }
            current = current.getParent() instanceof View ? (View)current.getParent() : null;
        }
        return "";
    }
    private static View nativeButton(View view) {
        for (int i = 0; view != null && i < 10; i++) {
            String type = view.getClass().getName();
            if (type.equals("com.ss.android.ugc.aweme.relation.view.FollowUserButton")
                    || type.equals("com.ss.android.ugc.aweme.feed.ui.wight.DuxButtonForFollowUser")) return view;
            view = view.getParent() instanceof View ? (View)view.getParent() : null;
        }
        return null;
    }

    public static boolean performAutomaticFollowClick(View view) {
        return automaticFollow(view::performClick);
    }
    static <T> T automaticFollow(Supplier<T> action) {
        Integer before = AUTOMATIC.get(); AUTOMATIC.set(before == null ? 1 : before + 1);
        try { return action.get(); }
        finally { if (before == null) AUTOMATIC.remove(); else AUTOMATIC.set(before); }
    }
    static boolean automaticFollowActive() { Integer depth = AUTOMATIC.get(); return depth != null && depth > 0; }

    private static boolean knownEmojiAsset(Object emoji) {
        for (String key : new String[]{"staticUrl", "animateUrl"}) {
            Object model = HostTargets.read(emoji, key); Object urls = HostTargets.invoke(model, "getUrlList");
            if (urls instanceof Iterable<?>) for (Object url : (Iterable<?>)urls) if (url instanceof String && MediaStoreDownloader.isHttpUrl((String)url)) return true;
        }
        return false;
    }
    static boolean shouldExposeEmoji(boolean enabled, Boolean hidden, boolean hasAsset) {
        return enabled && Boolean.TRUE.equals(hidden) && hasAsset;
    }
    private static boolean fromComment() {
        for (StackTraceElement item : Thread.currentThread().getStackTrace()) {
            if (item.getClassName().startsWith("com.ss.android.ugc.aweme.comment.")) return true;
        }
        return false;
    }
    static long epochMillis(long raw) {
        if (raw <= 0 || raw >= 100_000_000_000_000L) return -1;
        return raw < 100_000_000_000L ? raw * 1000L : raw;
    }
    static String fullTimestamp(long raw, TimeZone zone) {
        long millis = epochMillis(raw); if (millis < 0) return null;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.ROOT);
        format.setTimeZone(zone); return format.format(new Date(millis));
    }
    private static Activity activity(Context context) { for (int i = 0; context != null && i < 12; i++) { if (context instanceof Activity) return (Activity)context; if (!(context instanceof ContextWrapper)) break; Context next = ((ContextWrapper)context).getBaseContext(); if (next == context) break; context = next; } return null; }
    public void setOptions(LocalOptions next) {
        options = next == null ? new LocalOptions(Collections.emptyMap()) : next;
        if (!options.enabled(CONFIRM_FOLLOW)) main.post(this::dismissConfirmations);
    }
    public boolean supports(String key) { return !closed && supported.contains(key); }
    private void dismissConfirmations() {
        for (WeakReference<AlertDialog> ref : new ArrayList<>(confirming.values())) { AlertDialog dialog = ref.get(); if (dialog != null) dialog.dismiss(); }
        confirming.clear();
    }
    interface Stage { void run() throws Exception; }
    void stage(String key, Stage operation) {
        int before = handles.size(); supported.remove(key);
        try {
            operation.run();
            if (handles.size() == before) throw new IllegalStateException("No interaction handles installed");
            supported.add(key);
        } catch (Exception | LinkageError failure) {
            for (int i = handles.size() - 1; i >= before; i--) {
                XposedInterface.HookHandle handle = handles.remove(i);
                try { handle.unhook(); } catch (RuntimeException | LinkageError rollback) { failure.addSuppressed(rollback); }
            }
            try { log.accept(key + " unavailable: " + failure.getClass().getSimpleName()); } catch (RuntimeException ignored) { }
        }
    }
    void add(XposedInterface framework, Method target, XposedInterface.Hooker callback) { XposedInterface.HookHandle handle = framework.hook(target).setId("DouAi.Unified412.Interaction." + handles.size()).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(callback); if (handle == null) throw new IllegalStateException("Missing interaction hook"); handles.add(handle); }
    @Override public void close() { closed = true; supported.clear(); for (XposedInterface.HookHandle h : handles) try { h.unhook(); } catch (RuntimeException ignored) { } handles.clear(); touches.clear(); statuses.clear(); cards.clear(); main.post(this::dismissConfirmations); }
}
