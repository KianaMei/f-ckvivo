package dev.xhook.media;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import dev.xhook.Config;
import dev.xhook.Runtime;

/**
 * 在 X 的 Activity 上叠一个下载按钮。
 *
 * <p>用悬浮 View 而不是改 X 的播放器布局：只依赖 Activity/DecorView，不依赖 X 的混淆类。
 * 按钮只在最近 20 秒内抓到过视频地址时显示。</p>
 */
public final class PlayerButtons {
    private static final String TAG_VIEW = "xhook_download";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean installed;
    private static WeakReference<Activity> foreground = new WeakReference<>(null);

    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            try {
                refreshVisibility();
            } catch (Throwable ignored) {
            }
            MAIN.postDelayed(this, 800L);
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
                Activity current = foreground.get();
                if (current == activity) {
                    foreground = new WeakReference<>(null);
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
                Activity current = foreground.get();
                if (current == activity) {
                    foreground = new WeakReference<>(null);
                }
            }
        });
        MAIN.post(REFRESH);
        Runtime.log("PlayerButtons installed");
    }

    private static void refreshVisibility() {
        Activity activity = foreground.get();
        if (activity == null) {
            return;
        }
        View button = findButton(activity);
        if (button == null) {
            if (Runtime.bool(Config.KEY_VIDEO_BUTTON, true)) {
                addButton(activity);
            }
            return;
        }
        boolean enabled = Runtime.bool(Config.KEY_VIDEO_BUTTON, true);
        button.setVisibility(enabled && MediaCapture.hasRecent()
                ? View.VISIBLE : View.GONE);
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
            button.setText("↓");
            button.setTextColor(Color.WHITE);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            button.setGravity(Gravity.CENTER);
            button.setElevation(dp(activity, 6f));

            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0xCC1D9BF0);
            background.setStroke((int) dp(activity, 1f), 0x66FFFFFF);
            button.setBackground(background);

            int size = (int) dp(activity, 48f);
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(size, size);
            layoutParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            layoutParams.bottomMargin = (int) dp(activity, 110f);
            layoutParams.leftMargin = (int) dp(activity, 96f);
            root.addView(button, layoutParams);

            button.setVisibility(View.GONE);
            button.setOnClickListener(view -> {
                MediaCapture.Entry entry = MediaCapture.latest();
                if (entry == null) {
                    Toast.makeText(activity, "还没识别到视频，先播放几秒", Toast.LENGTH_SHORT).show();
                    return;
                }
                VideoDownloader.start(activity, entry, Quality.Level.ORIGINAL);
            });
            button.setOnLongClickListener(view -> {
                showQualityDialog(activity);
                return true;
            });
        } catch (Throwable throwable) {
            Runtime.error("add button failed", throwable);
        }
    }

    private static void showQualityDialog(Activity activity) {
        String[] items = {"下载原画（最高可用）", "下载高清（短边 ≤720）", "下载标清（短边 ≤480）"};
        Quality.Level[] levels = {Quality.Level.ORIGINAL, Quality.Level.HD, Quality.Level.SD};
        new AlertDialog.Builder(activity)
                .setTitle("视频下载")
                .setItems(items, (dialog, which) -> {
                    MediaCapture.Entry entry = MediaCapture.latest();
                    if (entry == null) {
                        Toast.makeText(activity, "还没识别到视频，先播放几秒", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    VideoDownloader.start(activity, entry, levels[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static float dp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
