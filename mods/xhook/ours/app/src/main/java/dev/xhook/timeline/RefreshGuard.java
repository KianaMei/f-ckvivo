package dev.xhook.timeline;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import dev.xhook.Config;
import dev.xhook.Runtime;

/**
 * 阻止 X 在回到前台时自动刷新首页 / 重选 Home 时自动刷新。
 *
 * <p>结构来自 Re:X 1.8.2 的 {@code kg0.o()}：</p>
 * <ul>
 *   <li>Home tabbed 类里唯一一个 {@code boolean(boolean, boolean)} 方法；
 *       Re:X 的 hooker 把第二个参数强制置为 false（{@code ja2} case 18）。</li>
 *   <li>timeline refresh repository 里 {@code void(Enum, UrtTimelineCursor)} 方法，
 *       枚举含 {@code AUTO_REFRESH} / {@code VIEWPORT_AWARE_AUTO_REFRESH}。</li>
 * </ul>
 *
 * <p>我们的策略比 Re:X 保守：Home 重选直接照抄“第二个参数置 false”；生命周期刷新只在
 * “刚回到前台且已有时间线内容”时拦截 {@code VIEWPORT_AWARE_AUTO_REFRESH}，首次启动放行，
 * 避免空白信息流。所有 hook 失败都静默。</p>
 */
public final class RefreshGuard {
    private static final String P_HOME = "com.x.home.tabbed.";
    private static final String P_REFRESH = "com.x.repositories.urt.";
    private static final String TYPE_AUTO = "AUTO_REFRESH";
    private static final String TYPE_VIEWPORT = "VIEWPORT_AWARE_AUTO_REFRESH";

    private static final AtomicLong LAST_LOG = new AtomicLong();
    private static volatile boolean installed;
    private static volatile boolean hasTimeline;
    private static volatile long lastResumeAt;
    private static volatile long lastPauseAt;

    private RefreshGuard() {
    }

    public static void install(Context context, ClassLoader classLoader, DexKitBridge bridge) {
        if (installed) {
            return;
        }
        installed = true;
        trackLifecycle(context);
        if (bridge == null) {
            Runtime.log("RefreshGuard: no DexKit bridge");
            return;
        }

        try {
            List<Method> home = findHomeReselect(bridge, classLoader);
            for (Method method : home) {
                hookHomeReselect(method);
            }
            List<Method> refresh = findRefreshMethods(bridge, classLoader);
            for (Method method : refresh) {
                hookRefresh(method);
            }
            Runtime.log("RefreshGuard installed: home=" + home.size()
                    + " refresh=" + refresh.size());
        } catch (Throwable throwable) {
            Runtime.error("RefreshGuard discovery failed", throwable);
        }
    }

    private static void trackLifecycle(Context context) {
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) {
            return;
        }
        ((Application) app).registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    }

                    @Override
                    public void onActivityStarted(Activity activity) {
                    }

                    @Override
                    public void onActivityResumed(Activity activity) {
                        lastResumeAt = SystemClock.elapsedRealtime();
                    }

                    @Override
                    public void onActivityPaused(Activity activity) {
                        lastPauseAt = SystemClock.elapsedRealtime();
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                    }

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                    }
                });
    }

    private static final String[] HOME_CLASSES = {
            // Re:X eg0.i / eg0.j 的 home_tabbed_classes（旧版 + 新版）
            "com.x.home.tabbed.n",
            "com.x.home.tabbed.o",
            "com.x.home.tabbed.k",
            "com.x.home.tabbed.j",
            "com.x.home.tabbed.i",
    };

    private static List<Method> findHomeReselect(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> result = new ArrayList<>();
        // Re:X fe2.l 的类级谓词：final、唯一 boolean(boolean,boolean)、有 kotlin.time.Instant 字段、
        // 某个字段类型带 (String,int)->int 方法。比单纯按签名找更准。
        if (bridge != null) {
            try {
                FindClass query = new FindClass();
                query.searchPackages("com.x.home.tabbed");
                ClassDataList classes = bridge.findClass(query);
                for (ClassData data : classes) {
                    String name = data.getName();
                    if (name == null || !name.startsWith(P_HOME)) {
                        continue;
                    }
                    try {
                        Class<?> type = Class.forName(name, false, classLoader);
                        if (!matchesHomeReselectClass(type)) {
                            continue;
                        }
                        for (Method method : type.getDeclaredMethods()) {
                            if (isHomeReselectMethod(method)) {
                                result.add(method);
                                Runtime.log("Home reselect predicate: " + name + "#" + method.getName());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable throwable) {
                Runtime.error("Home reselect class scan failed", throwable);
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        for (String name : HOME_CLASSES) {
            try {
                Class<?> type = Class.forName(name, false, classLoader);
                for (Method method : type.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers())
                            || Modifier.isAbstract(method.getModifiers())
                            || method.getReturnType() != boolean.class
                            || method.getParameterCount() != 2) {
                        continue;
                    }
                    Class<?>[] types = method.getParameterTypes();
                    if (types[0] == boolean.class && types[1] == boolean.class) {
                        result.add(method);
                        Runtime.log("Home reselect exact: " + name + "#" + method.getName());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        Runtime.log("Home reselect: exact classes miss, using structural query");
        try {
            MethodMatcher matcher = new MethodMatcher();
            matcher.declaredClass(P_HOME, StringMatchType.StartsWith, true);
            matcher.paramTypes("boolean", "boolean");
            matcher.returnType("boolean");
            FindMethod query = new FindMethod();
            query.matcher(matcher);
            query.searchPackages("com.x");
            MethodDataList list = bridge.findMethod(query);
            for (MethodData data : list) {
                if (!data.isMethod() || data.getParamCount() != 2) {
                    continue;
                }
                try {
                    Method method = data.getMethodInstance(classLoader);
                    if (method == null || Modifier.isStatic(method.getModifiers())
                            || Modifier.isAbstract(method.getModifiers())) {
                        continue;
                    }
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length == 2 && types[0] == boolean.class && types[1] == boolean.class
                            && method.getReturnType() == boolean.class) {
                        result.add(method);
                        if (result.size() >= 4) {
                            break;
                        }
                    }
                } catch (Throwable throwable) {
                    Runtime.error("RefreshGuard home resolve failed: " + data.getMethodSign(), throwable);
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("RefreshGuard home query failed", throwable);
        }
        return result;
    }

    private static boolean isHomeReselectMethod(Method method) {
        return !Modifier.isStatic(method.getModifiers())
                && !Modifier.isAbstract(method.getModifiers())
                && method.getReturnType() == boolean.class
                && method.getParameterCount() == 2
                && method.getParameterTypes()[0] == boolean.class
                && method.getParameterTypes()[1] == boolean.class;
    }

    private static boolean matchesHomeReselectClass(Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())
                || !Modifier.isFinal(type.getModifiers())) {
            return false;
        }
        int matches = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (isHomeReselectMethod(method)) {
                matches++;
            }
        }
        if (matches != 1) {
            return false;
        }
        boolean hasInstant = false;
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    && field.getType().getName().equals("kotlin.time.Instant")) {
                hasInstant = true;
                break;
            }
        }
        if (!hasInstant) {
            return false;
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            for (Method method : field.getType().getMethods()) {
                if (method.getReturnType() == int.class
                        && Arrays.equals(method.getParameterTypes(),
                        new Class[]{String.class, int.class})) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Method> findRefreshMethods(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> result = new ArrayList<>();
        try {
            MethodMatcher matcher = new MethodMatcher();
            matcher.declaredClass(P_REFRESH, StringMatchType.StartsWith, true);
            matcher.returnType("void");
            matcher.paramCount(2);
            FindMethod query = new FindMethod();
            query.matcher(matcher);
            query.searchPackages("com.x");
            MethodDataList list = bridge.findMethod(query);
            for (MethodData data : list) {
                if (!data.isMethod() || data.getParamCount() != 2) {
                    continue;
                }
                List<String> params = data.getParamTypeNames();
                if (params.size() != 2 || !isAutoRefreshEnum(params.get(0), classLoader)) {
                    continue;
                }
                try {
                    Method method = data.getMethodInstance(classLoader);
                    if (method == null || Modifier.isStatic(method.getModifiers())
                            || Modifier.isAbstract(method.getModifiers())) {
                        continue;
                    }
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length == 2 && types[0].isEnum()) {
                        result.add(method);
                        if (result.size() >= 6) {
                            break;
                        }
                    }
                } catch (Throwable throwable) {
                    Runtime.error("RefreshGuard refresh resolve failed: " + data.getMethodSign(), throwable);
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("RefreshGuard refresh query failed", throwable);
        }
        return result;
    }

    private static boolean isAutoRefreshEnum(String typeName, ClassLoader classLoader) {
        if (typeName == null) {
            return false;
        }
        try {
            Class<?> type = Class.forName(typeName, false, classLoader);
            if (!type.isEnum()) {
                return false;
            }
            boolean auto = false;
            boolean viewport = false;
            for (Object constant : type.getEnumConstants()) {
                String name = ((Enum<?>) constant).name();
                if (TYPE_AUTO.equals(name)) {
                    auto = true;
                } else if (TYPE_VIEWPORT.equals(name)) {
                    viewport = true;
                }
            }
            return auto && viewport;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void hookHomeReselect(Method method) {
        Runtime.hook(method, chain -> {
            boolean enabled = Runtime.bool(Config.KEY_BLOCK_HOME_RESELECT, true);
            try {
                log("Home reselect call enabled=" + enabled + " args=" + chain.getArgs());
            } catch (Throwable ignored) {
            }
            if (!enabled) {
                return chain.proceed();
            }
            try {
                Object[] args = chain.getArgs().toArray();
                if (args.length >= 2) {
                    // Re:X ja2 case 18 的做法：第二个 boolean 置 false
                    args[1] = Boolean.FALSE;
                    log("Home reselect guard: " + method.getName()
                            + " args0=" + args[0] + " -> false");
                    return chain.proceed(args);
                }
            } catch (Throwable throwable) {
                Runtime.error("Home reselect guard failed", throwable);
            }
            return chain.proceed();
        });
        Runtime.log("Home reselect hook: " + method.getDeclaringClass().getName()
                + "#" + method.getName()
                + " deopt=" + Runtime.deoptimize(method));
    }

    private static void hookRefresh(Method method) {
        Runtime.hook(method, chain -> {
            boolean enabled = Runtime.bool(Config.KEY_BLOCK_REFRESH, false);
            Object type = null;
            try {
                type = chain.getArg(0);
            } catch (Throwable ignored) {
            }
            String name = type instanceof Enum ? ((Enum<?>) type).name() : "";
            log("Refresh call type=" + name
                    + " enabled=" + enabled
                    + " resumed=" + justResumed()
                    + " hasTimeline=" + hasTimeline
                    + " method=" + method.getDeclaringClass().getSimpleName()
                    + "#" + method.getName());
            if (enabled && TYPE_VIEWPORT.equals(name) && justResumed() && hasTimeline) {
                log("Refresh guard: blocked " + name + " on resume ("
                        + method.getDeclaringClass().getSimpleName() + "#" + method.getName() + ")");
                return null;
            }
            try {
                Object result = chain.proceed();
                hasTimeline = true;
                return result;
            } catch (Throwable throwable) {
                hasTimeline = true;
                throw throwable;
            }
        });
        Runtime.log("Refresh repository hook: " + method.getDeclaringClass().getName()
                + "#" + method.getName()
                + " deopt=" + Runtime.deoptimize(method));
    }

    private static boolean justResumed() {
        long now = SystemClock.elapsedRealtime();
        return lastPauseAt > 0 && now - lastResumeAt < 10000L;
    }

    private static void log(String message) {
        long now = System.currentTimeMillis();
        long last = LAST_LOG.get();
        if (now - last > 3000L && LAST_LOG.compareAndSet(last, now)) {
            Runtime.log(message);
        }
    }
}
