package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.FrameLayout;

import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.config.ExtendedSettings;
import com.douai.module.config.LocalOptions;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** 41.2 overlay controls identified by semantics, without old numeric resources or private IM classes. */
public final class UnifiedUi412Hook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    private static final int MAX_NODES = 4096;
    private static final int MAX_DEPTH = 48;
    private static final EnumSet<Component> RIGHT = EnumSet.of(Component.AVATAR, Component.LIKE,
            Component.COMMENT, Component.COLLECT, Component.SHARE, Component.MUSIC_DISC);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<View, Original> originals = new WeakHashMap<>();
    private final Map<View, Monitor> monitors = new WeakHashMap<>();
    private final Map<View, WeakReference<Object>> cardHolders = new WeakHashMap<>();
    private final Map<View, WeakReference<View>> workButtons = new WeakHashMap<>();
    private final Consumer<String> log;
    private volatile ExtendedSettings settings;
    private volatile LocalOptions options;
    private volatile boolean temporarilyShown;
    private volatile boolean closed;
    private Application application;
    private Method currentAweme;
    private volatile BiConsumer<Context, Object> workInfoAction;
    private volatile Consumer<Object> workObserver = ignored -> { };

    private UnifiedUi412Hook(ExtendedSettings settings, LocalOptions options, Consumer<String> log) {
        this.settings = settings;
        this.options = options == null ? new LocalOptions(Collections.emptyMap()) : options;
        this.log = log == null ? ignored -> { } : log;
    }

    public static UnifiedUi412Hook install(XposedInterface framework, ClassLoader loader, Context context,
                                           ExtendedSettings settings, LocalOptions options,
                                           Consumer<String> log) throws Exception {
        UnifiedUi412Hook hook = new UnifiedUi412Hook(settings, options, log);
        try {
            Class<?> aweme = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader);
            hook.installHolder(framework, loader, aweme, "VideoViewHolder", "LJIIJ");
            hook.installHolder(framework, loader, aweme, "FeedImageViewHolder", "LJJIIJ");
            if (context != null && context.getApplicationContext() instanceof Application) {
                hook.application = (Application) context.getApplicationContext();
                hook.application.registerActivityLifecycleCallbacks(hook);
            }
            if (context instanceof Activity) hook.onActivityResumed((Activity) context);
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    private void installHolder(XposedInterface framework, ClassLoader loader, Class<?> aweme,
                               String shortName, String rootMethod) throws Exception {
            Class<?> holder = Class.forName("com.ss.android.ugc.aweme.feed.adapter." + shortName, false, loader);
            Field imageRoot = shortName.equals("FeedImageViewHolder") ? holder.getDeclaredField("f") : null;
            Method root = imageRoot == null ? holder.getDeclaredMethod(rootMethod) : null;
            if (imageRoot != null) {
                if (imageRoot.getType() != FrameLayout.class) throw new IllegalStateException("41.2 image root changed");
                imageRoot.setAccessible(true);
            } else {
                if (!View.class.isAssignableFrom(root.getReturnType())) throw new IllegalStateException("41.2 feed root contract changed");
                root.setAccessible(true);
            }
            Method getter = holder.getDeclaredMethod("getAweme");
            if (getter.getReturnType() != aweme) throw new IllegalStateException("41.2 current work changed");
            for (Method callback : new Method[]{holder.getDeclaredMethod("bind", aweme, int.class),
                    holder.getDeclaredMethod("onViewHolderSelected", int.class)}) {
                boolean selectedCallback = callback.getName().equals("onViewHolderSelected");
                XposedInterface.HookHandle handle = framework.hook(callback)
                        .setId("DouAi.Unified412.Ui." + shortName + "." + callback.getName())
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object view = imageRoot == null ? root.invoke(chain.getThisObject()) : imageRoot.get(chain.getThisObject());
                                if (view instanceof View) {
                                    cardHolders.put((View) view, new WeakReference<>(chain.getThisObject()));
                                    Object work = getter.invoke(chain.getThisObject());
                                    if (work != null) workObserver.accept(work);
                                    track((View) view, false, selectedCallback);
                                }
                            } catch (ReflectiveOperationException | RuntimeException failure) {
                                safeLog("Unified UI root unavailable: " + failure.getClass().getSimpleName());
                            }
                            return result;
                        });
                if (handle == null) throw new IllegalStateException("Missing unified UI hook handle");
                handles.add(handle);
            }
    }

    public void setSettings(ExtendedSettings settings) {
        this.settings = settings;
        refresh();
    }

    public void setOptions(LocalOptions options) {
        this.options = options == null ? new LocalOptions(Collections.emptyMap()) : options;
        refresh();
    }

    public boolean areComponentsTemporarilyShown() { return temporarilyShown; }

    /** Optional work information UI supplied by the unified feature owner. */
    public void setWorkInfoAction(BiConsumer<Context, Object> action) { workInfoAction = action; refresh(); }

    public void setWorkObserver(Consumer<Object> observer) {
        workObserver = observer == null ? ignored -> { } : observer;
    }

    public View visibleFeedRoot(Activity activity) {
        if (closed || activity.getWindow() == null) return null;
        View window = activity.getWindow().getDecorView();
        View selected = null;
        long largestArea = 0;
        for (View card : new ArrayList<>(cardHolders.keySet())) {
            if (card == null || !card.isAttachedToWindow() || !card.isShown() || card.getRootView() != window) continue;
            android.graphics.Rect visible = new android.graphics.Rect();
            if (!card.getGlobalVisibleRect(visible)) continue;
            long area = (long)visible.width() * visible.height();
            if (area > largestArea) { largestArea = area; selected = card; }
        }
        return selected != null ? selected : hasFeedTouch(window, new int[]{2048}, 0) ? window : null;
    }

    private static boolean hasFeedTouch(View view, int[] budget, int depth) {
        if (view == null || budget[0]-- <= 0 || depth > 32 || !view.isShown()) return false;
        if (view.getClass().getName().equals("com.ss.android.ugc.aweme.feed.ui.LongPressLayout")) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int index = 0; index < group.getChildCount(); index++)
                if (hasFeedTouch(group.getChildAt(index), budget, depth + 1)) return true;
        }
        return false;
    }

    public void setComponentsTemporarilyShown(boolean shown) {
        temporarilyShown = shown;
        refresh();
    }

    /** Connect UnifiedPlayback412Hook's state listener here. */
    public void onPlaybackState(boolean playing) {
        ExtendedSettings current = settings;
        if (current != null && current.components != null && current.components.showOnPause) {
            setComponentsTemporarilyShown(!playing);
        }
    }

    private void refresh() {
        main.post(() -> {
            if (closed) return;
            restoreAll();
            for (Monitor monitor : new ArrayList<>(monitors.values())) {
                monitor.targets.clear(); monitor.lastScan = 0; monitor.dirty = true; monitor.update();
            }
        });
    }

    private void track(View root, boolean window) {
        track(root, window, false);
    }

    private void track(View root, boolean window, boolean selectionChanged) {
        main.post(() -> {
            if (closed || root == null) return;
            ExtendedSettings current = settings;
            if (selectionChanged && !window && current != null && current.components != null && current.components.hideOnSwitch) {
                temporarilyShown = false;
            }
            Monitor monitor = monitors.get(root);
            if (monitor == null) {
                monitor = new Monitor(root, window);
                monitors.put(root, monitor);
                root.getViewTreeObserver().addOnGlobalLayoutListener(monitor);
                root.getViewTreeObserver().addOnPreDrawListener(monitor);
            }
            monitor.dirty = true;
            monitor.update();
        });
    }

    private int alpha(Component component) {
        ExtendedSettings current = settings;
        if (temporarilyShown || current == null) return 255;
        ComponentHideSettings components = current.components;
        if (components != null && components.configured) return components.enabled ? components.alpha(component) : 255;
        if (current.hideInterface && component != Component.TOP_NAV && component != Component.BOTTOM_NAV) return -1;
        return RIGHT.contains(component) ? Math.round(current.videoRightAlpha * 255f / 100f) : 255;
    }

    private void apply(View view, int value) {
        if (value == 255) { restore(view); return; }
        Original original = originals.computeIfAbsent(view, Original::new);
        view.setVisibility(value == -2 ? View.GONE : value == -1 ? View.INVISIBLE : original.visibility);
        view.setAlpha(value < 0 ? original.alpha : original.alpha * value / 255f);
        if (value < 0 || value == 0) { view.setClickable(false); view.setFocusable(false); }
        else { view.setClickable(original.clickable); view.setFocusable(original.focusable); }
    }

    private void restore(View view) {
        Original original = originals.remove(view);
        if (original != null) original.restore(view);
    }

    private void restoreAll() {
        for (Map.Entry<View, Original> entry : new ArrayList<>(originals.entrySet())) {
            if (entry.getKey() != null) entry.getValue().restore(entry.getKey());
        }
        originals.clear();
    }

    private void updateWorkButton(View root) {
        WeakReference<View> reference = workButtons.get(root);
        View button = reference == null ? null : reference.get();
        boolean enabled = options.enabled("unified_work_info_overlay") && workInfoAction != null;
        if (!enabled) {
            if (button != null && button.getParent() instanceof ViewGroup) ((ViewGroup) button.getParent()).removeView(button);
            workButtons.remove(root); return;
        }
        if (!(root instanceof FrameLayout)) return;
        if (button == null) {
            TextView text = new TextView(root.getContext());
            text.setText("作品详情"); text.setTextColor(android.graphics.Color.WHITE); text.setTextSize(12);
            text.setTag("douai.work_info"); text.setContentDescription("查看当前作品详情");
            int unit = Math.max(1, Math.round(root.getResources().getDisplayMetrics().density));
            text.setPadding(10 * unit, 7 * unit, 10 * unit, 7 * unit); text.setBackgroundColor(0x88000000);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.END);
            params.topMargin = 80 * unit; params.rightMargin = 12 * unit;
            ((FrameLayout) root).addView(text, params); workButtons.put(root, new WeakReference<>(text));
            WeakReference<View> card = new WeakReference<>(root);
            text.setOnClickListener(view -> {
                View activeCard = card.get();
                if (activeCard == null || !activeCard.isShown() || closed || !options.enabled("unified_work_info_overlay")) return;
                WeakReference<Object> holder = cardHolders.get(activeCard);
                Object owner = holder == null ? null : holder.get();
                BiConsumer<Context, Object> action = workInfoAction;
                if (owner != null && action != null) try {
                    Object work = owner.getClass().getMethod("getAweme").invoke(owner);
                    if (work != null) action.accept(view.getContext(), work);
                } catch (ReflectiveOperationException | RuntimeException failure) { safeLog("Work information unavailable"); }
            });
            button = text;
        }
        button.setVisibility(root.isShown() ? View.VISIBLE : View.GONE);
    }

    /** Captions are deliberately not classified by their visible text. */
    static Component semanticComponent(String resource, String description, String className) {
        String id = resource == null ? "" : resource.toLowerCase(Locale.ROOT);
        String desc = description == null ? "" : description.toLowerCase(Locale.ROOT);
        String type = className == null ? "" : className;
        if (id.equals("comment_container") || id.equals("comment_layout")) return Component.COMMENT;
        if (id.equals("share_container")) return Component.SHARE;
        if (id.equals("user_avatar") || id.contains("author_avatar")) return Component.AVATAR;
        if (id.contains("digg") || id.equals("like_button")) return Component.LIKE;
        if (id.contains("favorite") || id.contains("collect_button")) return Component.COLLECT;
        if (id.contains("music_disc") || type.endsWith("MusicDiscView")) return Component.MUSIC_DISC;
        if (id.contains("author_name") || id.equals("nickname")) return Component.AUTHOR;
        if (id.equals("desc") || id.equals("video_description")) return Component.DESCRIPTION;
        if (id.contains("music_info") || id.contains("music_name")) return Component.MUSIC_INFO;
        if (id.contains("feedback") || type.endsWith("FeedFeedbackView")) return Component.FEEDBACK;
        if (id.contains("bottom_tools") || id.equals("bottom_action_bar")) return Component.BOTTOM_TOOLS;
        if (id.contains("anchor_card") || type.endsWith("FeedAnchorView") || type.endsWith("PoiAnchorView")) return Component.CARD;
        if (id.contains("publish_time") || id.contains("publish_location")) return Component.TIME_LOCATION;
        if (id.contains("co_create") || id.contains("co_creation")) return Component.CO_CREATION;
        if (type.endsWith("LineProgressBar") || type.endsWith("FeedProgressBar")) return Component.PROGRESS;
        if (type.endsWith("MainTitleBar") || type.endsWith("MainTitleBarContainer")
                || type.endsWith("MainTabStripScrollView")) return Component.TOP_NAV;
        if (type.contains("homepage") && (type.endsWith("MainBottomTabView") || type.endsWith("MainBottomTabViewNew"))) return Component.BOTTOM_NAV;
        if (action(desc, "点赞", "未点赞", "已点赞", "取消点赞", "喜欢", "取消喜欢", "like", "unlike")) return Component.LIKE;
        if (action(desc, "评论", "打开评论", "comment")) return Component.COMMENT;
        if (action(desc, "收藏", "未收藏", "已收藏", "取消收藏", "collect", "favorite")) return Component.COLLECT;
        if (action(desc, "分享", "share")) return Component.SHARE;
        if (action(desc, "作者头像", "用户头像", "关注", "avatar", "follow")) return Component.AVATAR;
        if (action(desc, "背景音乐", "音乐转盘", "原声", "music")) return Component.MUSIC_DISC;
        if (action(desc, "全屏观看", "全屏播放", "fullscreen")) return Component.FULLSCREEN;
        if (action(desc, "不感兴趣", "反馈", "减少此类推荐")) return Component.FEEDBACK;
        return null;
    }

    private static boolean action(String value, String... labels) {
        for (String label : labels) {
            if (value.equals(label) || value.startsWith(label + "，") || value.startsWith(label + ",")
                    || value.startsWith(label + " ") || value.startsWith(label + "（")
                    || value.startsWith(label + "(") || value.startsWith(label + "按钮")) return true;
            if (value.startsWith(label) && value.length() > label.length()
                    && Character.isDigit(value.charAt(label.length()))) return true;
        }
        return false;
    }

    private final class Monitor implements ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> root;
        final boolean window;
        final Map<View, Integer> targets = new WeakHashMap<>();
        boolean dirty = true;
        long lastScan;
        Monitor(View root, boolean window) { this.root = new WeakReference<>(root); this.window = window; }
        @Override public void onGlobalLayout() { dirty = true; }
        @Override public boolean onPreDraw() { update(); return true; }
        void update() {
            View view = root.get();
            if (closed || view == null) return;
            if (!window) updateWorkButton(view);
            long now = SystemClock.uptimeMillis();
            if (dirty && now - lastScan >= 100) {
                List<View> previousTargets = new ArrayList<>(targets.keySet());
                targets.clear();
                collect(view, view, new int[]{MAX_NODES}, 0);
                // Reclassification must not toggle GONE targets back to VISIBLE on every layout.
                for (View previous : previousTargets) if (!targets.containsKey(previous)) restore(previous);
                dirty = false;
                lastScan = now;
            }
            for (Map.Entry<View, Integer> entry : new ArrayList<>(targets.entrySet())) {
                if (entry.getKey() != null) apply(entry.getKey(), entry.getValue());
            }
        }
        void collect(View view, View card, int[] budget, int depth) {
            if (view == null || budget[0]-- <= 0 || depth > MAX_DEPTH) return;
            Object tag = view.getTag();
            if (tag instanceof String && (((String) tag).startsWith("ai.chat") || ((String) tag).startsWith("douai"))) return;
            if (view instanceof EditText || view instanceof SurfaceView || view instanceof TextureView) return;
            String name = "";
            try { if (view.getId() != View.NO_ID) name = view.getResources().getResourceEntryName(view.getId()); }
            catch (android.content.res.Resources.NotFoundException ignored) { }
            Component component = semanticComponent(name, String.valueOf(view.getContentDescription()), view.getClass().getName());
            if (view != card && component != null && (!window || component == Component.TOP_NAV || component == Component.BOTTOM_NAV)) {
                int value = alpha(component);
                if (value != 255) targets.put(view, value);
            }
            if (window && view != card && view instanceof TextView && settings != null && settings.hideTopTabs) {
                String label = String.valueOf(((TextView) view).getText()).trim();
                for (String hidden : settings.hiddenTopTabs.split("[,，@\\n]+")) {
                    if (!hidden.trim().isEmpty() && hidden.trim().equals(label) && inTopBand(view, card)) targets.put(view, -2);
                }
            }
            if (!window && options.enabled("hide_camera_plus") && action(String.valueOf(view.getContentDescription()), "拍摄", "发布", "创建")) {
                if (view != card) targets.put(view, -1);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) collect(group.getChildAt(i), card, budget, depth + 1);
            }
        }
        void detach() {
            View view = root.get();
            if (view != null && view.getViewTreeObserver().isAlive()) {
                view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                view.getViewTreeObserver().removeOnPreDrawListener(this);
            }
        }
    }

    private static boolean inTopBand(View view, View window) {
        if (window.getHeight() <= 0) return false;
        int[] child = new int[2], root = new int[2];
        view.getLocationOnScreen(child); window.getLocationOnScreen(root);
        return child[1] >= root[1] && child[1] - root[1] < window.getHeight() / 4;
    }

    private static final class Original {
        final int visibility;
        final float alpha;
        final boolean clickable, focusable;
        Original(View view) { visibility = view.getVisibility(); alpha = view.getAlpha(); clickable = view.isClickable(); focusable = view.isFocusable(); }
        void restore(View view) { view.setVisibility(visibility); view.setAlpha(alpha); view.setClickable(clickable); view.setFocusable(focusable); }
    }

    @Override public void onActivityResumed(Activity activity) {
        if (activity.getClass().getName().equals("com.ss.android.ugc.aweme.main.MainActivity") && activity.getWindow() != null) {
            track(activity.getWindow().getDecorView(), true);
        }
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) {
        if (activity.getWindow() == null) return;
        main.post(() -> { Monitor monitor = monitors.remove(activity.getWindow().getDecorView()); if (monitor != null) monitor.detach(); });
    }
    @Override public void close() {
        closed = true;
        if (application != null) application.unregisterActivityLifecycleCallbacks(this);
        for (XposedInterface.HookHandle handle : handles) try { handle.unhook(); } catch (RuntimeException ignored) { }
        handles.clear();
        main.post(() -> {
            for (Monitor monitor : new ArrayList<>(monitors.values())) monitor.detach(); monitors.clear(); restoreAll();
            for (WeakReference<View> reference : workButtons.values()) { View button = reference.get(); if (button != null && button.getParent() instanceof ViewGroup) ((ViewGroup) button.getParent()).removeView(button); }
            workButtons.clear(); cardHolders.clear();
        });
    }
    private void safeLog(String value) { try { log.accept(value); } catch (RuntimeException ignored) { } }
}
