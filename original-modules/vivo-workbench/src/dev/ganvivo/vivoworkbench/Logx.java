package dev.ganvivo.vivoworkbench;

import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一 logcat 日志工具（严禁写任何设备文件）。
 */
final class Logx {

    static final String TAG = "VivoWorkbench";

    /** 安装细节/探针日志开关：默认关，只在专门排查时改成 true。 */
    static final boolean DEBUG = false;
    private static final ConcurrentHashMap<String, AtomicInteger> COUNTERS =
            new ConcurrentHashMap<String, AtomicInteger>();

    private Logx() {
    }

    static void i(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /** 仅调试模式输出的细节日志（hook 安装明细、ClassLoader 探测过程等）。 */
    static void d(String msg) {
        if (DEBUG) {
            i(msg);
        }
    }

    static void w(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    static void w(String msg, Throwable t) {
        try {
            Log.w(TAG, msg, t);
        } catch (Throwable ignored) {
        }
    }

    /** 对高频路径按 key 限流输出前 limit 次。 */
    static void iLimited(String key, int limit, String msg) {
        AtomicInteger c = COUNTERS.get(key);
        if (c == null) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger existing = COUNTERS.putIfAbsent(key, created);
            c = existing != null ? existing : created;
        }
        int n = c.incrementAndGet();
        if (n <= limit) {
            i(msg + " (#" + n + ")");
        }
    }
}
