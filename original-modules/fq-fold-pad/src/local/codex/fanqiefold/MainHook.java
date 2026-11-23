package local.codex.fanqiefold;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 番茄小说折叠屏双列：强制平板判定 + 强制横屏判定。
 *
 * 目标包：com.dragon.read（7.4.3.32 实测类名）
 *
 *   L1 平板/折叠判定  ssconfig 里的 *PadConfig / FoldConfig / PadDoublePage Provider 全部改成 true
 *   L2 真实横屏       Activity#setRequestedOrientation 锁死 SENSOR_LANDSCAPE，窗口真的变宽
 *   L3 横屏"视图"     Configuration.orientation 一律读成 LANDSCAPE
 *   L4 开关服务       CSSReaderApi.readerSwitchService() 的 boolean(String) 里 pad/land 类开关强制 true
 *
 * 关键 settings key（从 7.4.3.32 dex 里找到的）：
 *   is_android_pad_config_v615 / android_pad_config_v623 / android_pad_fit_config_v615
 *   android_pad_double_page_v621  <-- 双页（双列）开关
 */
public final class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FqFoldPad";
    private static final String TARGET_PACKAGE = "com.dragon.read";

    private static final boolean FORCE_SETTINGS = true;
    private static final boolean FORCE_LANDSCAPE = true;
    private static final boolean FORCE_CONFIG_LANDSCAPE = true;
    private static final boolean FORCE_DISPLAY_ROTATION_90 = false;
    private static final boolean FORCE_SWITCH_SERVICE = true;
    private static final boolean FORCE_READER_SPLIT = true;
    private static final boolean SCAN_DEX = true;
    private static final boolean SPOOF_MODEL = false;
    private static final String SPOOF_MANUFACTURER = "samsung";
    private static final String SPOOF_MODEL_NAME = "SM-X910";

    /**
     * 折叠屏内外屏分界。实测 X Fold6：
     *   内屏（展开）2312x2504 @462dpi -> smallestWidthDp=801, screenWidthDp≈801/807
     *   外屏（折叠）1120x2528 @520dpi -> smallestWidthDp=345, screenWidthDp≈345
     * 小于这里的阈值就当外屏，模块完全不做任何修改。
     */
    private static final int INNER_MIN_SMALLEST_WIDTH_DP = 600;
    private static final int INNER_MIN_WIDTH_PIXELS = 1600;

    /** ssconfig Provider（getConfig() 返回一个全 boolean 构造的 model） */
    private static final String[] SETTINGS_TARGETS = {
        // 平板 / 折叠 / 适配
        "com.dragon.read.base.ssconfig.settings.interfaces.IAndroidPadConfig$$Impl",
        "com.dragon.read.base.ssconfig.settings.interfaces.IIsPadConfig$$Impl",
        "com.dragon.read.base.ssconfig.settings.interfaces.IAndroidPadFitConfig$$Impl",
        "com.dragon.read.base.ssconfig.settings.interfaces.IAndroidFoldConfig$$Impl",
        // 平板双页（key: android_pad_double_page_v621）—— 注意：enable=true 会让折叠屏走
        // reader_split_config 分支，反而关掉双列，所以这里单独处理成 (false, true)
        "com.dragon.read.base.ssconfig.template.IAndroidPadDoublePage$$Impl",
        // 平板适配二期（key 未确认，一起打开）
        "com.dragon.base.ssconfig.template.IPadFitPhaseTwoV691$$Impl",
    };

    private static final String[] PAD_KEY_HINTS = {
        "pad", "tablet", "landscape", "fold", "big_screen", "wide_screen",
        "screen_mode", "double_column", "two_column", "double_page",
    };

    private static final AtomicBoolean ENV_DUMPED = new AtomicBoolean(false);
    private static final AtomicBoolean DEX_SCANNED = new AtomicBoolean(false);
    private static final Set<String> LOGGED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Set<String> HOOKED_SERVICES = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final int MAX_LOG_KEYS = 400;
    private static volatile Context APP_CONTEXT;
    private static volatile String LAST_SCREEN_STATE = "";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET_PACKAGE.equals(lp.packageName)) {
            return;
        }
        log("=== loaded in " + lp.processName + " (sdk=" + Build.VERSION.SDK_INT + ") ===");

        if (FORCE_SETTINGS) {
            int ok = 0;
            for (String className : SETTINGS_TARGETS) {
                if (forceSettings(lp.classLoader, className)) {
                    ok++;
                }
            }
            log("L1 settings forced " + ok + "/" + SETTINGS_TARGETS.length);
        }

        if (SPOOF_MODEL) {
            spoofModel();
        }

        installActivityHooks();
        installConfigurationHooks();
        if (FORCE_DISPLAY_ROTATION_90) {
            installRotationHook();
        }
        if (FORCE_SWITCH_SERVICE) {
            installSwitchServiceHooks(lp.classLoader);
        }
        installReaderSplitHooks(lp.classLoader);
        startEnvDump();
        if (SCAN_DEX) {
            startDexScan(lp);
        }
    }

    // ------------------------------------------------------------- 内外屏判定

    /** 应用上下文：优先取当前 Application，拿不到就返回 null（此时视为外屏，完全不介入）。 */
    private static Context appContext() {
        Context cached = APP_CONTEXT;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object application = current.invoke(null);
            if (application instanceof Context) {
                APP_CONTEXT = (Context) application;
                return APP_CONTEXT;
            }
        } catch (Throwable ignored) {
            // 还没到 Application 阶段
        }
        return null;
    }

    /** 折叠屏是否处于展开（内屏）状态。外屏一律返回 false，模块啥也不干。 */
    private static boolean isInnerScreen() {
        Context context = appContext();
        if (context == null) {
            return false;
        }
        try {
            return isInnerConfiguration(context.getResources().getConfiguration())
                || isInnerMetrics(context.getResources().getDisplayMetrics());
        } catch (Throwable t) {
            logLimited("gate error: " + t, "gate:" + t);
            return false;
        }
    }

    private static boolean isInnerConfiguration(Configuration configuration) {
        if (configuration == null) {
            return false;
        }
        return configuration.smallestScreenWidthDp >= INNER_MIN_SMALLEST_WIDTH_DP
            || configuration.screenWidthDp >= INNER_MIN_SMALLEST_WIDTH_DP;
    }

    private static boolean isInnerMetrics(DisplayMetrics metrics) {
        if (metrics == null) {
            return false;
        }
        int shorter = Math.min(metrics.widthPixels, metrics.heightPixels);
        int dp = (int) (shorter / metrics.density);
        return shorter >= INNER_MIN_WIDTH_PIXELS && dp >= INNER_MIN_SMALLEST_WIDTH_DP;
    }

    /** 屏幕状态变化时打一条日志（每次切换只打一次），便于排查内/外屏行为。 */
    private static void logScreenState(String where) {
        Context context = appContext();
        if (context == null) {
            return;
        }
        try {
            Configuration configuration = context.getResources().getConfiguration();
            boolean inner = isInnerScreen();
            String state = (inner ? "INNER " : "OUTER ") + configuration.screenWidthDp
                + "x" + configuration.screenHeightDp + "dp sw"
                + configuration.smallestScreenWidthDp + "dp";
            if (!state.equals(LAST_SCREEN_STATE)) {
                LAST_SCREEN_STATE = state;
                log("GATE " + state + " -> " + (inner ? "apply module hooks" : "leave untouched")
                    + "  @" + where);
            }
        } catch (Throwable t) {
            logLimited("gate log error: " + t, "gatelog:" + t);
        }
    }

    // ------------------------------------------------------- L1 ssconfig 开关

    private static boolean forceSettings(ClassLoader classLoader, String className) {
        try {
            Class<?> provider = XposedHelpers.findClass(className, classLoader);
            return forceSettings(provider, className);
        } catch (Throwable t) {
            log("L1 miss " + simple(className) + ": " + t);
            return false;
        }
    }

    private static boolean forceSettings(Class<?> provider, String label) {
        try {
            Method getConfig = null;
            for (Method m : provider.getDeclaredMethods()) {
                if ("getConfig".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    getConfig = m;
                    break;
                }
            }
            if (getConfig == null) {
                log("L1 skip " + simple(label) + ": no getConfig()");
                return false;
            }

            final Class<?> model = getConfig.getReturnType();
            if (model == boolean.class || model == Boolean.class) {
                XposedHelpers.findAndHookMethod(provider, "getConfig", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return Boolean.TRUE;
                    }
                });
                log("L1 hooked " + simple(label) + ".getConfig() -> true");
                return true;
            }

            Constructor<?> ctor = pickAllBooleanCtor(model);
            if (ctor == null) {
                log("L1 skip " + simple(model.getName()) + ": no all-boolean ctor");
                return false;
            }
            final Class<?>[] types = ctor.getParameterTypes();
            final Object[] trueArgs = new Object[types.length];
            Arrays.fill(trueArgs, Boolean.TRUE);
            // AndroidPadDoublePage(enable, isDefault2col)：enable=false 才能让 util.b5 走"启用"分支
            if ("AndroidPadDoublePage".equals(simple(model.getName())) && types.length == 2) {
                trueArgs[0] = Boolean.FALSE;
            }

            // 内部任何 new Model(...) 也强制 true
            Object[] hookParams = new Object[types.length + 1];
            System.arraycopy(types, 0, hookParams, 0, types.length);
            hookParams[types.length] = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isInnerScreen()) {
                        return;
                    }
                    for (int i = 0; i < param.args.length; i++) {
                        param.args[i] = Boolean.TRUE;
                    }
                }
            };
            XposedHelpers.findAndHookConstructor(model, hookParams);

            XposedHelpers.findAndHookMethod(provider, "getConfig", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object original = param.getResult();
                    logLimited("L1 observed " + simple(model.getName()) + " = " + original,
                        "obs:" + model.getName() + ":" + String.valueOf(original));
                    if (!isInnerScreen()) {
                        return;   // 外屏：原样返回，完全不动
                    }
                    param.setResult(XposedHelpers.newInstance(model, trueArgs));
                }
            });
            log("L1 hooked " + simple(label) + ".getConfig() -> new "
                + simple(model.getName()) + "(" + types.length + "x true)");
            return true;
        } catch (Throwable t) {
            log("L1 fail " + simple(label) + ": " + t);
            return false;
        }
    }

    private static Constructor<?> pickAllBooleanCtor(Class<?> model) {
        Constructor<?> best = null;
        for (Constructor<?> ctor : model.getDeclaredConstructors()) {
            Class<?>[] types = ctor.getParameterTypes();
            if (types.length == 0) {
                continue;
            }
            boolean allBoolean = true;
            for (Class<?> type : types) {
                if (type != boolean.class) {
                    allBoolean = false;
                    break;
                }
            }
            if (!allBoolean) {
                continue;
            }
            if (best == null || types.length < best.getParameterTypes().length) {
                best = ctor;
            }
        }
        return best;
    }

    // ------------------------------------------------- 阅读器双列（分栏）引擎

    /**
     * 实测（7.4.3.32）双列链路：
     *   DefaultFrameController.R1()  ->  readerConfig.r0(widthDp, heightDp)
     *   r0 实现 uo6.s.r0(F,F)Z = b5.b() && (widthDp >= heightDp || (isFold && readerSplitCfg.a))
     *   DefaultFrameController.p2() = q && xt7.e.n(getPageTurnMode())   // q 由 r0 置位
     *
     * 方形折叠屏展开态 widthDp(801) < heightDp(867)，fold 分支又依赖服务端配置
     * reader_split_config_v733（默认 false），所以永远判竖屏、不出双列。
     */
    private static void installReaderSplitHooks(ClassLoader cl) {
        if (!FORCE_READER_SPLIT) {
            return;
        }

        // reader_split_config_v733 -> xm5.d2.a（唯一字段），默认 false，强制 true
        try {
            final Class<?> splitConfig = XposedHelpers.findClass("xm5.d2", cl);
            Object instance = XposedHelpers.getStaticObjectField(splitConfig, "b");
            if (isInnerScreen()) {
                XposedHelpers.setBooleanField(instance, "a", true);
            }
            // 构造也可能被 settings 框架重新 new 出来，一并钉住
            XposedBridge.hookAllConstructors(splitConfig, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isInnerScreen()) {
                        return;
                    }
                    try {
                        XposedHelpers.setBooleanField(param.thisObject, "a", true);
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("S1 reader_split_config_v733 (xm5.d2.a) forced true");
        } catch (Throwable t) {
            log("S1 reader_split_config hook failed: " + t);
        }

        // util.b5：静态 b = 双列可用；b() = b && prefs(is_reader_split_enable)
        try {
            final Class<?> b5 = XposedHelpers.findClass("com.dragon.read.util.b5", cl);
            XposedBridge.hookAllMethods(b5, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // b5.a(Configuration)：只有内屏才把静态 'b' 立起来
                    if (!isInnerConfiguration((Configuration) param.args[0])) {
                        return;
                    }
                    try {
                        XposedHelpers.setStaticBooleanField(b5, "b", true);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedHelpers.findAndHookMethod(b5, "b", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isInnerScreen()) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
            log("S2 util.b5 double-page switch gated by inner screen");
        } catch (Throwable t) {
            log("S2 util.b5 hook failed: " + t);
        }

        // 决定性一刀：readerConfig.r0(widthDp, heightDp) 恒 true
        try {
            final Class<?> configImpl = XposedHelpers.findClass("uo6.s", cl);
            XposedHelpers.findAndHookMethod(configImpl, "r0", float.class, float.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        float widthDp = (Float) param.args[0];
                        float heightDp = (Float) param.args[1];
                        boolean inner = isInnerScreen();
                        logLimited("S3 r0 widthDp=" + widthDp + " heightDp=" + heightDp
                            + " inner=" + inner + " original=" + param.getResult()
                            + (inner ? " -> true" : " (outer, untouched)"),
                            "r0:" + widthDp + "x" + heightDp + ":" + inner);
                        if (inner) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });
            log("S3 readerConfig.r0() forced true (uo6.s)");
        } catch (Throwable t) {
            log("S3 r0 hook failed: " + t);
        }

        // 诊断：看引擎最终是否真的按双列排版（p2 = q && 左右翻页模式）
        try {
            final Class<?> frameController =
                XposedHelpers.findClass("com.dragon.reader.lib.support.DefaultFrameController", cl);
            XposedBridge.hookAllMethods(frameController, "R1", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object splitFlag = XposedHelpers.getBooleanField(param.thisObject, "q");
                        Object p2 = XposedHelpers.callMethod(param.thisObject, "p2");
                        Object client = XposedHelpers.callMethod(param.thisObject, "U");
                        Object config = XposedHelpers.callMethod(client, "getReaderConfig");
                        Object pageTurnMode = XposedHelpers.callMethod(config, "getPageTurnMode");
                        logLimited("S4 frameController q=" + splitFlag + " p2=" + p2
                            + " pageTurnMode=" + pageTurnMode,
                            "p2:" + p2 + ":" + pageTurnMode);
                    } catch (Throwable t) {
                        log("S4 diagnose failed: " + t);
                    }
                }
            });
            log("S4 frame controller diagnostics installed");
        } catch (Throwable t) {
            log("S4 frame controller hook failed: " + t);
        }
    }

    // -------------------------------------------------------------- L2 横屏

    private static void installActivityHooks() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        dumpEnvironmentOnce(activity);
                        logScreenState("onCreate " + activity.getClass().getName());
                        applyOrientation(activity);
                    } catch (Throwable t) {
                        log("onCreate hook: " + t);
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        applyOrientation((Activity) param.thisObject);
                    } catch (Throwable t) {
                        log("onResume hook: " + t);
                    }
                }
            });
            // 折叠/展开时系统会派发配置变化：内屏锁横屏、外屏恢复原状
            XposedBridge.hookAllMethods(Activity.class, "onConfigurationChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        logScreenState("onConfigurationChanged " + activity.getClass().getName());
                        applyOrientation(activity);
                    } catch (Throwable t) {
                        log("onConfigurationChanged hook: " + t);
                    }
                }
            });
            if (FORCE_LANDSCAPE) {
                XposedBridge.hookAllMethods(Activity.class, "setRequestedOrientation", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 外屏：原样放行，绝不改 App 自己的方向请求
                        if (!isInnerScreen()) {
                            return;
                        }
                        int original = param.args[0] instanceof Integer ? (Integer) param.args[0] : -1;
                        // 只拦竖屏类请求；横屏/未指定等保持原样
                        if (original == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            || original == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            || original == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                            || original == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT) {
                            param.args[0] = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                        }
                    }
                });
            }
            log("L2 activity hooks installed (forceLandscape=" + FORCE_LANDSCAPE + ", gated by inner screen)");
        } catch (Throwable t) {
            log("L2 install failed: " + t);
        }
    }

    /** 内屏：锁横屏；外屏：把模块自己下过的横屏请求撤销，恢复 App 原本行为。 */
    private static void applyOrientation(Activity activity) {
        if (!FORCE_LANDSCAPE || activity == null) {
            return;
        }
        try {
            boolean inner = isInnerScreen();
            int current = activity.getRequestedOrientation();
            if (inner) {
                if (current != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    log("L2 " + activity.getClass().getName() + " -> SENSOR_LANDSCAPE (inner)");
                }
            } else if (current == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                log("L2 " + activity.getClass().getName()
                    + " -> UNSPECIFIED (outer, restore manifest value)");
            }
        } catch (Throwable t) {
            log("L2 applyOrientation: " + t);
        }
    }

    // --------------------------------------------------------- L3 横屏视图

    private static void installConfigurationHooks() {
        if (!FORCE_CONFIG_LANDSCAPE) {
            return;
        }
        try {
            Class<?> configuration = Class.forName("android.content.res.Configuration");

            XposedBridge.hookAllMethods(configuration, "setTo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    normalizeOrientation(param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(configuration, "updateFrom", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    normalizeOrientation(param.thisObject);
                }
            });

            Class<?> resources = Class.forName("android.content.res.Resources");
            XposedBridge.hookAllMethods(resources, "getConfiguration", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    normalizeOrientation(param.getResult());
                }
            });

            log("L3 configuration hooks installed");
        } catch (Throwable t) {
            log("L3 install failed: " + t);
        }
    }

    /**
     * 把 Configuration.orientation 归一化：
     *   内屏 -> LANDSCAPE（番茄的横屏前提）
     *   外屏 -> 按真实宽高算出来的方向（通常是 PORTRAIT），顺带把内屏留下的脏值修回去
     * 这里的判断只看这个 Configuration 自己的尺寸，不依赖全局上下文，串屏也不会串味。
     */
    private static void normalizeOrientation(Object maybeConfiguration) {
        if (!(maybeConfiguration instanceof Configuration)) {
            return;
        }
        Configuration configuration = (Configuration) maybeConfiguration;
        int desired;
        if (isInnerConfiguration(configuration)) {
            desired = Configuration.ORIENTATION_LANDSCAPE;
        } else if (configuration.screenWidthDp > 0 && configuration.screenHeightDp > 0) {
            desired = configuration.screenWidthDp >= configuration.screenHeightDp
                ? Configuration.ORIENTATION_LANDSCAPE
                : Configuration.ORIENTATION_PORTRAIT;
        } else {
            return;
        }
        if (configuration.orientation != desired) {
            configuration.orientation = desired;
        }
    }

    private static void installRotationHook() {
        try {
            XposedBridge.hookAllMethods(Display.class, "getRotation", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return Surface.ROTATION_90;
                }
            });
            log("L3b Display.getRotation() -> ROTATION_90");
        } catch (Throwable t) {
            log("L3b install failed: " + t);
        }
    }

    // ------------------------------------------------------- L4 开关服务

    private static void installSwitchServiceHooks(ClassLoader classLoader) {
        try {
            Class<?> api = XposedHelpers.findClass("com.dragon.community.api.CSSReaderApi", classLoader);
            Object impl = api.getField("IMPL").get(null);
            if (impl == null) {
                log("L4 CSSReaderApi.IMPL is null");
                return;
            }
            int installed = 0;
            for (Method method : impl.getClass().getMethods()) {
                if (!"readerSwitchService".equals(method.getName())
                    || method.getParameterTypes().length != 0) {
                    continue;
                }
                XposedHelpers.findAndHookMethod(method.getDeclaringClass(), method.getName(), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object service = param.getResult();
                        if (service != null) {
                            hookSwitchService(service.getClass());
                        }
                    }
                });
                installed++;
            }
            log("L4 readerSwitchService accessors hooked: " + installed);
        } catch (Throwable t) {
            log("L4 switch service unavailable: " + t);
        }
    }

    private static void hookSwitchService(final Class<?> serviceClass) {
        if (!HOOKED_SERVICES.add(serviceClass.getName())) {
            return;
        }
        int installed = 0;
        for (final Method method : serviceClass.getMethods()) {
            if (method.getReturnType() != boolean.class) {
                continue;
            }
            if (method.getParameterTypes().length != 1 || method.getParameterTypes()[0] != String.class) {
                continue;
            }
            try {
                XposedHelpers.findAndHookMethod(
                    method.getDeclaringClass(),
                    method.getName(),
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String key = String.valueOf(param.args[0]);
                            boolean original = Boolean.TRUE.equals(param.getResult());
                            if (original || !isPadKey(key) || !isInnerScreen()) {
                                logLimited("L4 seen " + key + " = " + original, "seen:" + key + original);
                                return;
                            }
                            param.setResult(Boolean.TRUE);
                            logLimited("L4 forced " + key + " false->true", "forced:" + key);
                        }
                    }
                );
                installed++;
            } catch (Throwable t) {
                log("L4 hook " + method + " failed: " + t);
            }
        }
        log("L4 service " + simple(serviceClass.getName()) + " boolean(String) hooks=" + installed);
    }

    private static boolean isPadKey(String key) {
        String lower = key.toLowerCase();
        for (String hint : PAD_KEY_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 环境诊断

    private static void startEnvDump() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 150; i++) {
                    try {
                        Class<?> activityThread = Class.forName("android.app.ActivityThread");
                        Method current = activityThread.getDeclaredMethod("currentApplication");
                        current.setAccessible(true);
                        Object application = current.invoke(null);
                        if (application instanceof Context) {
                            dumpEnvironmentOnce((Context) application);
                            return;
                        }
                    } catch (Throwable ignored) {
                        // 继续等
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "fq-fold-env");
        thread.setDaemon(true);
        thread.start();
    }

    private static void dumpEnvironmentOnce(Context context) {
        if (!ENV_DUMPED.compareAndSet(false, true)) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("env brand=").append(Build.BRAND)
                .append(" manufacturer=").append(Build.MANUFACTURER)
                .append(" model=").append(Build.MODEL)
                .append(" device=").append(Build.DEVICE);

            Configuration configuration = context.getResources().getConfiguration();
            sb.append("\n  config orientation=").append(orientationName(configuration.orientation))
                .append(" screenWidthDp=").append(configuration.screenWidthDp)
                .append(" screenHeightDp=").append(configuration.screenHeightDp)
                .append(" smallestWidthDp=").append(configuration.smallestScreenWidthDp)
                .append(" densityDpi=").append(configuration.densityDpi);

            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            sb.append("\n  metrics ").append(metrics.widthPixels).append("x").append(metrics.heightPixels)
                .append(" density=").append(metrics.density);

            android.view.WindowManager windowManager =
                (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager.getDefaultDisplay();
            sb.append("\n  display rotation=").append(display.getRotation());
            DisplayMetrics real = new DisplayMetrics();
            display.getMetrics(real);
            sb.append(" appArea=").append(real.widthPixels).append("x").append(real.heightPixels);

            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
                sb.append("\n  windowBounds=").append(bounds.width()).append("x").append(bounds.height());
            }
            sb.append("\n  tabletFeature=").append(context.getPackageManager()
                .hasSystemFeature("android.hardware.type.tablet"));
            log(sb.toString());
        } catch (Throwable t) {
            log("env dump failed: " + t);
        }
    }

    private static String orientationName(int orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return "LANDSCAPE";
        }
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            return "PORTRAIT";
        }
        return "UNDEFINED(" + orientation + ")";
    }

    // -------------------------------------------------------------- 机型伪装

    private static void spoofModel() {
        try {
            setStaticField(Build.class, "MANUFACTURER", SPOOF_MANUFACTURER);
            setStaticField(Build.class, "BRAND", SPOOF_MANUFACTURER);
            setStaticField(Build.class, "MODEL", SPOOF_MODEL_NAME);
            setStaticField(Build.class, "DEVICE", SPOOF_MODEL_NAME.toLowerCase());
            log("spoofed model -> " + SPOOF_MANUFACTURER + " " + SPOOF_MODEL_NAME);
        } catch (Throwable t) {
            log("spoof failed: " + t);
        }
    }

    private static void setStaticField(Class<?> owner, String name, String value) throws Exception {
        java.lang.reflect.Field field = owner.getField(name);
        java.lang.reflect.Field modifiers = java.lang.reflect.Field.class.getDeclaredField("modifiers");
        modifiers.setAccessible(true);
        modifiers.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
        field.setAccessible(true);
        field.set(null, value);
    }

    // ---------------------------------------------------------------- dex 扫描

    private static void startDexScan(final XC_LoadPackage.LoadPackageParam lp) {
        if (!DEX_SCANNED.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<String> apks = new ArrayList<String>();
                    if (lp.appInfo != null) {
                        if (lp.appInfo.sourceDir != null) {
                            apks.add(lp.appInfo.sourceDir);
                        }
                        if (lp.appInfo.splitSourceDirs != null) {
                            Collections.addAll(apks, lp.appInfo.splitSourceDirs);
                        }
                    }

                    Set<String> padLike = new LinkedHashSet<String>();
                    Set<String> orientationLike = new LinkedHashSet<String>();
                    int scanned = 0;
                    for (String apk : apks) {
                        DexFile dexFile = null;
                        try {
                            dexFile = new DexFile(apk);
                            Enumeration<String> entries = dexFile.entries();
                            while (entries.hasMoreElements()) {
                                String name = entries.nextElement();
                                scanned++;
                                String simple = simple(name);
                                if (simple.endsWith("$$Impl") && (simple.contains("PadConfig")
                                    || simple.contains("IsPadConfig") || simple.contains("FoldConfig")
                                    || simple.contains("PadDoublePage") || simple.contains("PadFit"))) {
                                    padLike.add(name);
                                } else if (simple.contains("DoublePage") || simple.contains("Landscape")) {
                                    orientationLike.add(name);
                                }
                            }
                        } finally {
                            if (dexFile != null) {
                                dexFile.close();
                            }
                        }
                    }

                    log("scan classes=" + scanned + " padLike=" + padLike.size()
                        + " orientationLike=" + orientationLike.size());
                    for (String name : padLike) {
                        if (!name.contains("$$")) {
                            continue;
                        }
                        forceSettings(lp.classLoader, name);
                    }
                    int printed = 0;
                    for (String name : orientationLike) {
                        if (printed++ >= 60) {
                            log("scan: ...(truncated)");
                            break;
                        }
                        log("scan: " + name);
                    }
                } catch (Throwable t) {
                    log("dex scan failed: " + t);
                }
            }
        }, "fq-fold-dexscan");
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------ utils

    private static String simple(String className) {
        int index = className.lastIndexOf('.');
        return index < 0 ? className : className.substring(index + 1);
    }

    private static void logLimited(String message, String key) {
        if (LOGGED.size() >= MAX_LOG_KEYS) {
            return;
        }
        if (LOGGED.add(key)) {
            log(message);
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
