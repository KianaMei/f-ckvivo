package dev.xhook;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;

/**
 * 进程内共享的框架接口、日志和配置读取。
 *
 * <p>所有 hook 失败都必须被吞掉：模块坏了最多是功能不生效，不能让 X 崩在用户手里。</p>
 */
public final class Runtime {
    public static final String TAG = "XHook";
    public static final String PREFS = "xhook";

    private static volatile XposedInterface api;

    private Runtime() {
    }

    public static void attach(XposedInterface instance) {
        api = instance;
    }

    public static XposedInterface api() {
        return api;
    }

    public static void log(String message) {
        XposedInterface instance = api;
        if (instance != null) {
            try {
                instance.log(Log.INFO, TAG, message);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.i(TAG, message);
    }

    public static void error(String message, Throwable throwable) {
        XposedInterface instance = api;
        if (instance != null) {
            try {
                instance.log(Log.ERROR, TAG, message, throwable);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.e(TAG, message, throwable);
    }

    public static XposedInterface.HookHandle hook(Executable executable, XposedInterface.Hooker hooker) {
        XposedInterface instance = api;
        if (instance == null || executable == null) {
            return null;
        }
        try {
            return instance.hook(executable).intercept(hooker);
        } catch (Throwable throwable) {
            error("hook failed: " + executable, throwable);
            return null;
        }
    }

    /** 让 ART 丢掉已内联的副本；小方法（boolean 判断/刷新枚举）很容易被内联。 */
    public static boolean deoptimize(Executable executable) {
        XposedInterface instance = api;
        if (instance == null || executable == null) {
            return false;
        }
        try {
            return instance.deoptimize(executable);
        } catch (Throwable throwable) {
            error("deoptimize failed: " + executable, throwable);
            return false;
        }
    }

    /** 目标进程里读取模块 App 通过 LibXposed Service 写入的 Remote Preferences。 */
    private static SharedPreferences prefs() {
        XposedInterface instance = api;
        if (instance == null) {
            return null;
        }
        try {
            return instance.getRemotePreferences(PREFS);
        } catch (Throwable throwable) {
            error("remote preferences unavailable", throwable);
            return null;
        }
    }

    public static boolean bool(String key, boolean fallback) {
        SharedPreferences preferences = prefs();
        if (preferences == null) {
            return fallback;
        }
        try {
            return preferences.getBoolean(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static String string(String key, String fallback) {
        SharedPreferences preferences = prefs();
        if (preferences == null) {
            return fallback;
        }
        try {
            String value = preferences.getString(key, fallback);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
