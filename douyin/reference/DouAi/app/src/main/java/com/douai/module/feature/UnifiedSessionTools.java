package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.Toast;
import com.douai.module.config.SettingsStore;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;

/** One low-frequency timer for scheduled/idle exit, independent of obfuscated player internals. */
public final class UnifiedSessionTools implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    public static final String TIMER = "unified_exit_minutes";
    public static final String IDLE = "unified_idle_minutes";
    public static final String KILL = "unified_exit_stop_process";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Application application;
    private final SettingsStore store;
    private final XposedInterface.HookHandle touch;
    private WeakReference<Activity> active = new WeakReference<>(null);
    private long started = SystemClock.elapsedRealtime(), interacted = started;
    private String timerSetting = "0";
    private String idleSetting = "0";
    private boolean closed;
    private final Runnable check = this::check;
    private final Runnable stopProcess = this::stopProcess;

    private UnifiedSessionTools(Application application, SettingsStore store, XposedInterface framework) throws Exception {
        this.application = application; this.store = store;
        touch = framework.hook(Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class))
                .setId("DouAi.Unified.IdleClock").setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    MotionEvent event = (MotionEvent) chain.getArg(0);
                    if (event != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) interacted = SystemClock.elapsedRealtime();
                    return chain.proceed();
                });
        if (touch == null) throw new IllegalStateException("Idle clock hook unavailable");
        application.registerActivityLifecycleCallbacks(this);
        main.postDelayed(check, 30_000);
    }
    public static UnifiedSessionTools install(Application application, SettingsStore store, XposedInterface framework) throws Exception {
        if (!"com.ss.android.ugc.aweme".equals(application.getPackageName())) throw new IllegalArgumentException("Host context required");
        return new UnifiedSessionTools(application, store, framework);
    }
    public void setCurrentActivity(Activity activity) {
        if (!closed && active.get() == null && activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            active = new WeakReference<>(activity);
        }
    }
    private void stopProcess() { if (!closed && store.getBoolean(KILL, false)) android.os.Process.killProcess(android.os.Process.myPid()); }
    private void check() {
        if (closed) return;
        String next = store.getString(TIMER, "0");
        if (!next.equals(timerSetting)) { timerSetting = next; started = SystemClock.elapsedRealtime(); }
        String nextIdle = store.getString(IDLE, "0");
        if (!nextIdle.equals(idleSetting)) { idleSetting = nextIdle; interacted = SystemClock.elapsedRealtime(); }
        long now = SystemClock.elapsedRealtime();
        boolean expired = elapsed(now, started, minutes(timerSetting)) || elapsed(now, interacted, minutes(idleSetting));
        Activity activity = active.get();
        boolean stopInBackground = store.getBoolean(KILL, false);
        if (expired && (activity != null && !activity.isFinishing() || stopInBackground)) {
            started = interacted = now;
            if (activity != null && !activity.isFinishing()) {
                Toast.makeText(activity, "已到设置的退出时间", Toast.LENGTH_SHORT).show();
                activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            if (stopInBackground) main.postDelayed(stopProcess, 500);
        }
        main.postDelayed(check, 30_000);
    }
    static int minutes(String value) {
        try { int count = Integer.parseInt(value); return count >= 3 && count <= 1440 ? count : 0; }
        catch (RuntimeException invalid) { return 0; }
    }
    static boolean elapsed(long now, long previous, int minutes) { return minutes > 0 && now - previous >= minutes * 60_000L; }
    @Override public void onActivityResumed(Activity activity) { active = new WeakReference<>(activity); interacted = SystemClock.elapsedRealtime(); }
    @Override public void onActivityPaused(Activity activity) { if (active.get() == activity) active.clear(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { if (active.get() == activity) active.clear(); }
    @Override public void close() { closed = true; main.removeCallbacks(check); main.removeCallbacks(stopProcess); touch.unhook(); application.unregisterActivityLifecycleCallbacks(this); active.clear(); }
}
