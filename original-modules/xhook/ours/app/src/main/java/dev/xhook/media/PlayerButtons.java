package dev.xhook.media;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import dev.xhook.Config;
import dev.xhook.Runtime;

/**
 * 在 X 的 Activity 上叠一个可拖拽、带实时进度的视频下载按钮。
 *
 * <p>与 LiquidTab 协同要点：</p>
 * <ul>
 *   <li>仅在屏幕存在可见视频播放器（{@link PlayerTracker#hasVisibleVideo}）或活跃下载任务时显示，
 *       离开视频页立刻隐藏，不在普通时间线残留；</li>
 *   <li>默认停靠在右侧安全区内（相对位置 {@code x=1.0, y=0.62}，右边距 16dp、底部安全区 156dp），
 *       主动避开 LiquidTab 的底部悬浮玻璃栏与右下角 {@code XFloatingFab}；</li>
 *   <li>支持按住自由拖拽并按窗口相对比例记忆位置，且在 {@code GONE -> VISIBLE} 时强制按真实尺寸重算坐标，
 *       彻底防止按钮被算到屏幕右边界外。</li>
 * </ul>
 */
public final class PlayerButtons {
    private static final String TAG_VIEW = "xhook_download";
    private static final String UI_PREFS = "xhook_ui_pos";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View, DragController> CONTROLLERS = new WeakHashMap<>();

    private static volatile boolean installed;
    private static volatile String statusLabel = "↓";
    private static WeakReference<Activity> foreground = new WeakReference<>(null);

    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            try {
                refreshVisibility();
            } catch (Throwable ignored) {
            }
            if (foreground.get() != null) {
                MAIN.postDelayed(this, 600L);
            }
        }
    };

    private PlayerButtons() {
    }

    public static void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        Application application;
        Context app = context.getApplicationContext();
        if (app instanceof Application) {
            application = (Application) app;
        } else if (context instanceof Application) {
            application = (Application) context;
        } else {
            Runtime.error("PlayerButtons: no Application context", new IllegalStateException());
            return;
        }

        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
                foreground = new WeakReference<>(activity);
                if (Runtime.bool(Config.KEY_VIDEO_BUTTON, true)) {
                    addButton(activity);
                }
                MAIN.removeCallbacks(REFRESH);
                MAIN.post(REFRESH);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (foreground.get() == activity) {
                    foreground = new WeakReference<>(null);
                    MAIN.removeCallbacks(REFRESH);
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (foreground.get() == activity) {
                    foreground = new WeakReference<>(null);
                    MAIN.removeCallbacks(REFRESH);
                }
            }
        });
        Runtime.log("PlayerButtons installed");
    }

    static void updateStatus(String label) {
        statusLabel = (label == null || label.isEmpty()) ? "↓" : label;
        MAIN.post(() -> {
            Activity activity = foreground.get();
            if (activity == null) {
                return;
            }
            View view = findButton(activity);
            if (view instanceof TextView) {
                applyLabel((TextView) view, statusLabel);
            }
        });
    }

    private static void applyLabel(TextView button, String label) {
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() <= 1 ? 20f : 11f);
    }

    private static void refreshVisibility() {
        Activity activity = foreground.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        View button = findButton(activity);
        boolean enabled = Runtime.bool(Config.KEY_VIDEO_BUTTON, true);
        if (button == null) {
            if (enabled) {
                addButton(activity);
            }
            return;
        }
        boolean show = enabled && (VideoDownloader.hasActiveTasks()
                || PlayerTracker.hasVisibleVideo(activity));
        int targetVis = show ? View.VISIBLE : View.GONE;
        if (button.getVisibility() != targetVis) {
            button.setVisibility(targetVis);
            if (targetVis == View.VISIBLE) {
                DragController controller = CONTROLLERS.get(button);
                if (controller != null) {
                    controller.restore();
                }
                button.bringToFront();
            }
        }
        if (button instanceof TextView) {
            applyLabel((TextView) button, statusLabel);
        }
    }

    private static View findButton(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            return decor.findViewWithTag(TAG_VIEW);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addButton(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) {
                return;
            }
            if (decor.findViewWithTag(TAG_VIEW) != null) {
                return;
            }
            ViewGroup root = (ViewGroup) decor;
            TextView button = new TextView(activity);
            button.setTag(TAG_VIEW);
            button.setTextColor(Color.WHITE);
            button.setGravity(Gravity.CENTER);
            button.setElevation(dp(activity, 8f));
            button.setContentDescription("保存视频，长按选画质，拖动可调整位置");
            applyLabel(button, statusLabel);

            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0xDD1D9BF0);
            background.setStroke((int) dp(activity, 1.25f), 0x99FFFFFF);
            button.setBackground(background);

            int size = (int) dp(activity, 46f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            root.addView(button, lp);
            button.setVisibility(View.GONE);

            button.setOnClickListener(view -> triggerDownload(activity, Quality.Level.ORIGINAL));
            button.setOnLongClickListener(view -> {
                showQualityDialog(activity);
                return true;
            });

            DragController controller = new DragController(activity, root, button, size);
            CONTROLLERS.put(button, controller);
            controller.attach();
        } catch (Throwable throwable) {
            Runtime.error("add button failed", throwable);
        }
    }

    private static void triggerDownload(Activity activity, Quality.Level level) {
        MediaCapture.Entry entry = PlayerTracker.resolveCurrentEntry(activity);
        if (entry == null) {
            Toast.makeText(activity, "还没识别到当前视频，先播放几秒", Toast.LENGTH_SHORT).show();
            return;
        }
        VideoDownloader.start(activity, entry, level);
    }

    private static void showQualityDialog(Activity activity) {
        String[] items = {"下载原画（最高可用）", "下载高清（短边 ≤720）", "下载标清（短边 ≤480）"};
        Quality.Level[] levels = {Quality.Level.ORIGINAL, Quality.Level.HD, Quality.Level.SD};
        new AlertDialog.Builder(activity)
                .setTitle("XHook 视频下载")
                .setItems(items, (dialog, which) -> triggerDownload(activity, levels[which]))
                .setNegativeButton("取消", null)
                .show();
    }

    private static float dp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /** 拖拽并按屏幕相对比例记忆位置，避开顶部状态栏与底部导航栏区域。 */
    private static final class DragController implements View.OnTouchListener {
        private final ViewGroup root;
        private final View button;
        private final SharedPreferences prefs;
        private final int fallbackSize;
        private final int slop;
        private final int topSafe;
        private final int bottomSafe;
        private final int sidePad;
        private final Runnable longPressTask;

        private float downX;
        private float downY;
        private int startLeft;
        private int startTop;
        private boolean dragging;
        private boolean longPressed;
        private int lastRootW;
        private int lastRootH;

        DragController(Context context, ViewGroup root, View button, int fallbackSize) {
            this.root = root;
            this.button = button;
            this.fallbackSize = fallbackSize;
            this.prefs = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
            this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
            this.topSafe = (int) dp(context, 80f);
            this.bottomSafe = (int) dp(context, 156f);
            this.sidePad = (int) dp(context, 16f);
            this.longPressTask = () -> {
                if (!dragging && button.isAttachedToWindow()) {
                    longPressed = true;
                    button.performLongClick();
                }
            };
        }

        void attach() {
            button.setOnTouchListener(this);
            root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (root.getWidth() != lastRootW || root.getHeight() != lastRootH) {
                    restore();
                }
            });
            button.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (!dragging && (r - l) > 0 && (r - l) != (or - ol)) {
                    restore();
                }
            });
            root.post(this::restore);
        }

        private int buttonWidth() {
            int w = button.getWidth();
            if (w > 0) {
                return w;
            }
            ViewGroup.LayoutParams lp = button.getLayoutParams();
            return lp != null && lp.width > 0 ? lp.width : fallbackSize;
        }

        private int buttonHeight() {
            int h = button.getHeight();
            if (h > 0) {
                return h;
            }
            ViewGroup.LayoutParams lp = button.getLayoutParams();
            return lp != null && lp.height > 0 ? lp.height : fallbackSize;
        }

        private int minX() {
            return sidePad;
        }

        private int maxX() {
            return Math.max(minX(), root.getWidth() - buttonWidth() - sidePad);
        }

        private int minY() {
            return Math.min(topSafe, maxY());
        }

        private int maxY() {
            return Math.max(topSafe, root.getHeight() - buttonHeight() - bottomSafe);
        }

        private void moveTo(int x, int y) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
            int cx = Math.max(minX(), Math.min(maxX(), x));
            int cy = Math.max(minY(), Math.min(maxY(), y));
            if (lp.leftMargin != cx || lp.topMargin != cy) {
                lp.leftMargin = cx;
                lp.topMargin = cy;
                button.setLayoutParams(lp);
            }
        }

        void restore() {
            if (root.getWidth() <= 0 || root.getHeight() <= 0) {
                return;
            }
            lastRootW = root.getWidth();
            lastRootH = root.getHeight();
            float rx = clamp01(prefs.getFloat("btn_x", 1.0f));
            float ry = clamp01(prefs.getFloat("btn_y", 0.62f));
            int x = Math.round(minX() + rx * (maxX() - minX()));
            int y = Math.round(minY() + ry * (maxY() - minY()));
            moveTo(x, y);
        }

        private static float clamp01(float value) {
            if (Float.isNaN(value) || value < 0f) {
                return 0f;
            }
            return Math.min(1f, value);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
                    startLeft = lp.leftMargin;
                    startTop = lp.topMargin;
                    dragging = false;
                    longPressed = false;
                    button.postDelayed(longPressTask, ViewConfiguration.getLongPressTimeout());
                    root.requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && !longPressed && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        button.removeCallbacks(longPressTask);
                    }
                    if (dragging) {
                        moveTo(startLeft + Math.round(dx), startTop + Math.round(dy));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    root.requestDisallowInterceptTouchEvent(false);
                    button.removeCallbacks(longPressTask);
                    if (longPressed) {
                        return true;
                    }
                    if (!dragging) {
                        v.performClick();
                        return true;
                    }
                    FrameLayout.LayoutParams end = (FrameLayout.LayoutParams) button.getLayoutParams();
                    int spanX = Math.max(1, maxX() - minX());
                    int spanY = Math.max(1, maxY() - minY());
                    prefs.edit()
                            .putFloat("btn_x", clamp01((float) (end.leftMargin - minX()) / spanX))
                            .putFloat("btn_y", clamp01((float) (end.topMargin - minY()) / spanY))
                            .apply();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    root.requestDisallowInterceptTouchEvent(false);
                    button.removeCallbacks(longPressTask);
                    if (dragging) {
                        restore();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}
