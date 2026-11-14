import io

p = 'src/local/codex/fanqiefold/MainHook.java'
s = io.open(p, encoding='utf-8').read()

# ---- 1) 常量 + 内屏阈值
old = '''    private static final boolean SPOOF_MODEL = false;
    private static final String SPOOF_MANUFACTURER = "samsung";
    private static final String SPOOF_MODEL_NAME = "SM-X910";'''
new = '''    private static final boolean SPOOF_MODEL = false;
    private static final String SPOOF_MANUFACTURER = "samsung";
    private static final String SPOOF_MODEL_NAME = "SM-X910";

    /**
     * 折叠屏内外屏分界。实测 X Fold6：
     *   内屏（展开）2312x2504 @462dpi -> smallestWidthDp=801, screenWidthDp≈801/807
     *   外屏（折叠）1120x2528 @520dpi -> smallestWidthDp=345, screenWidthDp≈345
     * 小于这里的阈值就当外屏，模块完全不做任何修改。
     */
    private static final int INNER_MIN_SMALLEST_WIDTH_DP = 600;
    private static final int INNER_MIN_WIDTH_PIXELS = 1600;'''
assert old in s
s = s.replace(old, new, 1)

# ---- 2) 全局状态字段
old = '''    private static final int MAX_LOG_KEYS = 400;'''
new = '''    private static final int MAX_LOG_KEYS = 400;
    private static volatile Context APP_CONTEXT;
    private static volatile String LAST_SCREEN_STATE = "";'''
assert old in s
s = s.replace(old, new, 1)

# ---- 3) 屏幕状态判定
anchor = '''    // ------------------------------------------------------- L1 ssconfig 开关'''
gate = '''    // ------------------------------------------------------------- 内外屏判定

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

    // ------------------------------------------------------- L1 ssconfig 开关'''
assert anchor in s
s = s.replace(anchor, gate, 1)

# ---- 4) L1 getConfig 按内屏拦截
old = '''                    if (!isInnerScreen()) {
                        return;   // 外屏：原样返回，完全不动
                    }
                    param.setResult(XposedHelpers.newInstance(model, trueArgs));'''
if old not in s:
    old2 = '''                    param.setResult(XposedHelpers.newInstance(model, trueArgs));'''
    new2 = '''                    if (!isInnerScreen()) {
                        return;   // 外屏：原样返回，完全不动
                    }
                    param.setResult(XposedHelpers.newInstance(model, trueArgs));'''
    assert old2 in s
    s = s.replace(old2, new2, 1)

# ---- 5) 模型构造强制 true 按内屏
old = '''                protected void beforeHookedMethod(MethodHookParam param) {
                    for (int i = 0; i < param.args.length; i++) {
                        param.args[i] = Boolean.TRUE;
                    }
                }'''
new = '''                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isInnerScreen()) {
                        return;
                    }
                    for (int i = 0; i < param.args.length; i++) {
                        param.args[i] = Boolean.TRUE;
                    }
                }'''
assert old in s
s = s.replace(old, new, 1)

# ---- 6) S1 xm5.d2
old = '''            Object instance = XposedHelpers.getStaticObjectField(splitConfig, "b");
            XposedHelpers.setBooleanField(instance, "a", true);'''
new = '''            Object instance = XposedHelpers.getStaticObjectField(splitConfig, "b");
            if (isInnerScreen()) {
                XposedHelpers.setBooleanField(instance, "a", true);
            }'''
assert old in s
s = s.replace(old, new, 1)

old = '''                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        XposedHelpers.setBooleanField(param.thisObject, "a", true);
                    } catch (Throwable ignored) {
                    }
                }'''
new = '''                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isInnerScreen()) {
                        return;
                    }
                    try {
                        XposedHelpers.setBooleanField(param.thisObject, "a", true);
                    } catch (Throwable ignored) {
                    }
                }'''
assert old in s
s = s.replace(old, new, 1)

# ---- 7) S2 b5
old = '''                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        XposedHelpers.setStaticBooleanField(b5, "b", true);
                    } catch (Throwable ignored) {
                    }
                }'''
new = '''                protected void afterHookedMethod(MethodHookParam param) {
                    // b5.a(Configuration)：只有内屏才把静态 'b' 立起来
                    if (!isInnerConfiguration((Configuration) param.args[0])) {
                        return;
                    }
                    try {
                        XposedHelpers.setStaticBooleanField(b5, "b", true);
                    } catch (Throwable ignored) {
                    }
                }'''
assert old in s
s = s.replace(old, new, 1)

old = '''            XposedHelpers.findAndHookMethod(b5, "b", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return Boolean.TRUE;
                }
            });
            log("S2 util.b5 double-page switch forced true");'''
new = '''            XposedHelpers.findAndHookMethod(b5, "b", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isInnerScreen()) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
            log("S2 util.b5 double-page switch gated by inner screen");'''
assert old in s
s = s.replace(old, new, 1)

# ---- 8) S3 r0
old = '''                        float widthDp = (Float) param.args[0];
                        float heightDp = (Float) param.args[1];
                        logLimited("S3 r0 widthDp=" + widthDp + " heightDp=" + heightDp
                            + " original=" + param.getResult() + " -> true",
                            "r0:" + widthDp + "x" + heightDp);
                        param.setResult(Boolean.TRUE);'''
new = '''                        float widthDp = (Float) param.args[0];
                        float heightDp = (Float) param.args[1];
                        boolean inner = isInnerScreen();
                        logLimited("S3 r0 widthDp=" + widthDp + " heightDp=" + heightDp
                            + " inner=" + inner + " original=" + param.getResult()
                            + (inner ? " -> true" : " (outer, untouched)"),
                            "r0:" + widthDp + "x" + heightDp + ":" + inner);
                        if (inner) {
                            param.setResult(Boolean.TRUE);
                        }'''
assert old in s
s = s.replace(old, new, 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("part1 ok")
