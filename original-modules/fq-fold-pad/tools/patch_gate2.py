import io

p = 'src/local/codex/fanqiefold/MainHook.java'
s = io.open(p, encoding='utf-8').read()

# ---------------------------------------------------------------- L2
old = '''    private static void installActivityHooks() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        dumpEnvironmentOnce(activity);
                        forceLandscape(activity);
                    } catch (Throwable t) {
                        log("onCreate hook: " + t);
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        forceLandscape((Activity) param.thisObject);
                    } catch (Throwable t) {
                        log("onResume hook: " + t);
                    }
                }
            });
            if (FORCE_LANDSCAPE) {
                XposedBridge.hookAllMethods(Activity.class, "setRequestedOrientation", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int original = param.args[0] instanceof Integer ? (Integer) param.args[0] : -1;
                        if (original != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                            param.args[0] = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                        }
                    }
                });
            }
            log("L2 activity hooks installed (forceLandscape=" + FORCE_LANDSCAPE + ")");
        } catch (Throwable t) {
            log("L2 install failed: " + t);
        }
    }

    private static void forceLandscape(Activity activity) {
        if (!FORCE_LANDSCAPE || activity == null) {
            return;
        }
        try {
            if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                log("L2 " + activity.getClass().getName() + " -> SENSOR_LANDSCAPE");
            }
        } catch (Throwable t) {
            log("L2 forceLandscape: " + t);
        }
    }'''

new = '''    private static void installActivityHooks() {
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
    }'''
assert old in s
s = s.replace(old, new, 1)

# ---------------------------------------------------------------- L3
old = '''    private static void normalizeOrientation(Object maybeConfiguration) {
        if (!(maybeConfiguration instanceof Configuration)) {
            return;
        }
        Configuration configuration = (Configuration) maybeConfiguration;
        if (configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        }
    }'''
new = '''    /**
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
    }'''
assert old in s
s = s.replace(old, new, 1)

# ---------------------------------------------------------------- L4
old = '''                            String key = String.valueOf(param.args[0]);
                            boolean original = Boolean.TRUE.equals(param.getResult());
                            if (original || !isPadKey(key)) {'''
new = '''                            String key = String.valueOf(param.args[0]);
                            boolean original = Boolean.TRUE.equals(param.getResult());
                            if (original || !isPadKey(key) || !isInnerScreen()) {'''
assert old in s
s = s.replace(old, new, 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("part2 ok")
