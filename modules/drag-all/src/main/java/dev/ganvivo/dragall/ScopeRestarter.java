package dev.ganvivo.dragall;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ScopeRestarter {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private ScopeRestarter() {}

    static boolean isRunning() {
        return RUNNING.get();
    }

    static void restart(Context context, Consumer<Result> callback) {
        if (!RUNNING.compareAndSet(false, true)) return;
        Context application = context.getApplicationContext();
        new Thread(() -> {
            Result result;
            try {
                result = execute(application);
            } catch (Exception failure) {
                Log.w("DragAll", "scope restart failed", failure);
                result = new Result(false, "未能重启：" + failure.getMessage());
            } finally {
                RUNNING.set(false);
            }
            Result completed = result;
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(completed));
        }, "DragAllScopeRestart").start();
    }

    private static Result execute(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(Config.TARGET, 0);
        if (!Config.SUPPORTED_VERSION.equals(info.versionName)) {
            return new Result(false, "当前拖放直达版本不在适配范围内");
        }
        ApplicationInfo applicationInfo = info.applicationInfo;
        int user = android.os.Process.myUid() / 100000;
        if (applicationInfo == null || applicationInfo.uid % 100000 < 10000
                || applicationInfo.uid / 100000 != user) {
            return new Result(false, "应用身份校验失败，已取消");
        }
        byte[] script;
        try (InputStream input = context.getAssets().open("restart_scope.sh")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            script = bytes.toByteArray();
        }
        long deadline = SystemClock.elapsedRealtime() / 1000 + 35;
        String command = "/system/bin/sh -s -- restart " + applicationInfo.uid + " " + user + " " + deadline;
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream output = process.getInputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = output.read(buffer)) != -1) {
                    synchronized (captured) {
                        int remaining = 16384 - captured.size();
                        if (remaining > 0) captured.write(buffer, 0, Math.min(count, remaining));
                    }
                }
            } catch (Exception failure) {
                Log.w("DragAll", "scope restart output unavailable", failure);
            }
        }, "DragAllScopeOutput");
        reader.setDaemon(true);
        reader.start();
        try {
            try (OutputStream input = process.getOutputStream()) {
                input.write(script);
                input.flush();
            }
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroy();
                return new Result(false, "等待 root 授权或操作超时。请查看拖放直达状态，不会自动重试");
            }
            reader.join(1000);
            String output;
            synchronized (captured) {
                output = captured.toString(StandardCharsets.UTF_8.name());
            }
            Log.i("DragAll", "scope restart result: " + output);
            if (process.exitValue() == 0 && output.contains("DRAG_ALL_RESTART_OK")) {
                return new Result(true, "已重建拖放直达进程并打开设置页，请尝试拖拽");
            }
            if (output.contains("framework_changed")) {
                return new Result(false, "检测到框架进程变化，操作已停止，请保留现场日志");
            }
            if (output.contains("scope_unverified")) {
                return new Result(false, "未能确认作用域，请在 Vector 中启用模块，选择拖放直达与内容提取服务");
            }
            if (output.contains("request_expired")) {
                return new Result(false, "授权等待已超时，未继续操作");
            }
            return new Result(false, "操作未完成，请检查 drag-all 的 root 权限；详细原因见 DragAll 日志");
        } finally {
            process.destroy();
        }
    }

    static final class Result {
        final boolean success;
        final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
