package local.codex.innocent.sharefix;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Bilibili-Innocent 分享净化补丁（合并进原模块 APK 的第二入口）。
 *
 * 背景：B 站 9.18 把分享框架迁到 kntr.common.share，统一数据模型是 Cn1.c
 * （旧类 com.bilibili.lib.sharewrapper.online.api.ShareClickResult 仅剩 supermenu 在用）。
 * 原模块的 share_purify 只 hook 旧类，新分享面板（微信/QQ/复制/系统等全渠道）走
 * ShareContentPipeline -> Cn1.c，导致「净化分享链接」「小程序转直链」空转。
 *
 * 本补丁在 Cn1.c 的反序列化构造器出口统一改写，覆盖所有渠道：
 *  - purify_share_content：净化 link 字段，并净化 content 文案里的 B 站链接；
 *  - share_mini_program_direct_link：share_mode 为 6(微信小程序)/7(QQ小程序) 时
 *    强制改为 3(普通 Link)，标题为空时用文案/链接兜底，避免空标题。
 *
 * 开关复用原模块在 Vector 的同一份远程配置（group=hook_config），
 * 通过 API 102 的 getRemotePreferences 读取，无需独立设置页。
 */
public final class ShareFixEntry extends XposedModule {
    private static final String TAG = "BiliInnocentShareFix";
    private static final String TARGET_PKG = "tv.danmaku.bili";
    private static final String MODEL_CLASS = "Cn1.c";
    private static final String PREF_GROUP = "hook_config";
    private static final String KEY_PURIFY = "purify_share_content";
    private static final String KEY_MINI = "share_mini_program_direct_link";

    // Cn1.c 字段：g=share_mode, b=content, c=link, e=title
    private static final int SHARE_MODE_LINK = 3;
    private static final int SHARE_MODE_WX_MINI = 6;
    private static final int SHARE_MODE_QQ_MINI = 7;

    private final AtomicBoolean mModelHooked = new AtomicBoolean(false);
    private volatile HookHandle mLoadClassHandle;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (param == null || !TARGET_PKG.equals(param.getPackageName())) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        if (cl == null) {
            return;
        }
        boolean purify = readPref(KEY_PURIFY);
        boolean mini = readPref(KEY_MINI);
        if (!purify && !mini) {
            Log.i(TAG, "both share switches off, skip install");
            return;
        }
        Log.i(TAG, "attach purify=" + purify + " miniProgram=" + mini);
        installWhenAvailable(cl, purify, mini);
    }

    private boolean readPref(String key) {
        try {
            SharedPreferences prefs = getRemotePreferences(PREF_GROUP);
            return prefs != null && prefs.getBoolean(key, false);
        } catch (Throwable t) {
            Log.w(TAG, "read remote pref failed: " + key, t);
            return false;
        }
    }

    /** Cn1.c 是分享发起时才加载的类，先用 loadClass 兜底，加载到再装构造器 hook。 */
    private void installWhenAvailable(final ClassLoader cl, final boolean purify,
                                      final boolean mini) {
        try {
            Class<?> model = Class.forName(MODEL_CLASS, false, cl);
            installModelHook(model, purify, mini);
        } catch (Throwable ignored) {
            // 类尚未加载，挂 loadClass 等待
        }
        if (mModelHooked.get()) {
            return;
        }
        if (mLoadClassHandle != null) {
            return;
        }
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
            mLoadClassHandle = hook(loadClass)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            if (!mModelHooked.get() && result instanceof Class
                                    && MODEL_CLASS.equals(((Class<?>) result).getName())) {
                                installModelHook((Class<?>) result, purify, mini);
                                HookHandle handle = mLoadClassHandle;
                                if (handle != null && mModelHooked.get()) {
                                    handle.unhook();
                                    mLoadClassHandle = null;
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "loadClass intercept failed", t);
                        }
                        return result;
                    });
            Log.i(TAG, "loadClass watcher armed for " + MODEL_CLASS);
        } catch (Throwable t) {
            mLoadClassHandle = null;
            Log.w(TAG, "arm loadClass watcher failed", t);
        }
    }

    private void installModelHook(Class<?> modelClass, final boolean purify,
                                  final boolean mini) {
        if (!mModelHooked.compareAndSet(false, true)) {
            return;
        }
        try {
            Constructor<?> ctor = findModelConstructor(modelClass);
            if (ctor == null) {
                Log.w(TAG, "model constructor not found; adapter mismatch");
                mModelHooked.set(false);
                return;
            }
            final Field fMode = modelClass.getDeclaredField("g");
            final Field fContent = modelClass.getDeclaredField("b");
            final Field fLink = modelClass.getDeclaredField("c");
            final Field fTitle = modelClass.getDeclaredField("e");
            fMode.setAccessible(true);
            fContent.setAccessible(true);
            fLink.setAccessible(true);
            fTitle.setAccessible(true);

            hook(ctor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object obj = chain.proceed();
                        try {
                            rewrite(obj, purify, mini, fMode, fContent, fLink, fTitle);
                        } catch (Throwable t) {
                            Log.w(TAG, "rewrite share model failed", t);
                        }
                        return obj;
                    });
            Log.i(TAG, "Cn1.c constructor hook installed");
        } catch (Throwable t) {
            mModelHooked.set(false);
            Log.w(TAG, "install model hook failed", t);
        }
    }

    private Constructor<?> findModelConstructor(Class<?> modelClass) {
        for (Constructor<?> c : modelClass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 13 && p[0] == int.class && p[1] == Integer.class
                    && p[7] == Integer.class && p[12] == Boolean.class) {
                c.setAccessible(true);
                return c;
            }
        }
        return null;
    }

    private void rewrite(Object obj, boolean purify, boolean mini,
                         Field fMode, Field fContent, Field fLink, Field fTitle)
            throws IllegalAccessException {
        if (purify) {
            Object linkVal = fLink.get(obj);
            if (linkVal instanceof String) {
                String purified = LinkPurifier.purifyLink((String) linkVal);
                if (purified != null) {
                    fLink.set(obj, purified);
                }
            }
            Object contentVal = fContent.get(obj);
            if (contentVal instanceof String) {
                String purified = LinkPurifier.purifyContent((String) contentVal);
                if (purified != null) {
                    fContent.set(obj, purified);
                }
            }
        }
        if (mini) {
            Object modeVal = fMode.get(obj);
            if (modeVal instanceof Integer) {
                int mode = (Integer) modeVal;
                if (mode == SHARE_MODE_WX_MINI || mode == SHARE_MODE_QQ_MINI) {
                    fMode.set(obj, SHARE_MODE_LINK);
                    ensureTitle(obj, fTitle, fContent, fLink);
                    Log.i(TAG, "mini program(" + mode + ") converted to direct link");
                }
            }
        }
    }

    /** 小程序改直链后标题可能为空，用文案/链接兜底并截断到 120，对齐原模块行为。 */
    private void ensureTitle(Object obj, Field fTitle, Field fContent, Field fLink)
            throws IllegalAccessException {
        Object titleVal = fTitle.get(obj);
        if (titleVal instanceof String && !((String) titleVal).trim().isEmpty()) {
            return;
        }
        String fallback = null;
        Object contentVal = fContent.get(obj);
        if (contentVal instanceof String && !((String) contentVal).trim().isEmpty()) {
            fallback = ((String) contentVal).trim();
        } else {
            Object linkVal = fLink.get(obj);
            if (linkVal instanceof String) {
                fallback = (String) linkVal;
            }
        }
        if (fallback != null && !fallback.isEmpty()) {
            if (fallback.length() > 120) {
                fallback = fallback.substring(0, 120);
            }
            fTitle.set(obj, fallback);
        }
    }
}
