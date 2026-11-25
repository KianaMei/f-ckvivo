package local.codex.vivoime;

import android.content.res.XmlResourceParser;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * vivo 输入法 Pro（com.vivo.ai.ime.nex）三处行为修正：
 *
 * 1. 英文 26 键 M 的 label_secondary 从 "\" 改为 "/"（中文 26 键、email、web 布局本来就是别的字符，
 *    条件 keycode == 41 && "\\".equals(g) 只命中英文 26 键）。
 * 2. 顶部工具栏从 5 个槽位放宽到 6 个：bj.a.q(boolean) 把用户工具截断到 Math.min(size, 5)，
 *    这里在返回列表的末尾 KEYBOARD_HIDE 之前，从完整列表 bj.a.v() 补入被截断的项。
 * 3. “文本编辑”工具（滑动移动光标 / 全选 / 复制 / 剪切 / 粘贴 / 删除）原本只在目标输入框
 *    回答 vivo 私有 getServedViewType() 时才显示；这里对 TEXT_EDITOR 恒放行并纠正已保存的
 *    occupy 标记，实际实现仍由宿主在真触控栏（ai.b）与 WebView 兜底（ai.m）之间自选。
 */
public final class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "VivoImeTweaks";
    private static final String TARGET_PACKAGE = "com.vivo.ai.ime.nex";
    private static final String KEY_PARSER = "ah.b";
    private static final String TOOLBAR_DATA = "bj.a";
    private static final int KEYCODE_M = 41;
    private static final int MAX_TOP_TOOLS = 6;
    private static final String TYPE_TEXT_EDITOR = "TEXT_EDITOR";

    private static Field keycodeField;
    private static Field secondaryField;
    private static Field typeField;
    private static Field locationField;
    private static Field occupyField;
    private static Method isTopToolMethod;
    private static Method isSupportedMethod;
    private static boolean installed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        // 只在主进程装：:vivoasr / :webView / :crash 不需要
        if (lpparam.processName != null && !TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }
        if (installed) {
            return;
        }
        installed = true;
        try {
            hookKeyLabels(lpparam.classLoader);
        } catch (Throwable t) {
            log("key label hook skipped", t);
        }
        try {
            hookTopToolBar(lpparam.classLoader);
        } catch (Throwable t) {
            log("toolbar hook skipped", t);
        }
        try {
            hookTouchBarEnable(lpparam.classLoader);
        } catch (Throwable t) {
            log("touchbar enable hook skipped", t);
        }
        try {
            hookTouchPad(lpparam.classLoader);
        } catch (Throwable t) {
            log("touchpad hook skipped", t);
        }
    }

    // ---- 文本编辑触控板：第三方输入框改走标准方向键 ----

    private static Method imeInputConnection;
    private static final Runnable LONG_PRESS = new Runnable() {
        @Override
        public void run() {
            if (padActive && !padMoved) {
                padSelecting = true;
                android.view.View v = padView;
                if (v != null) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                }
            }
        }
    };
    private static android.view.View padView;
    private static boolean padActive;
    private static boolean padMoved;
    private static boolean padSelecting;
    private static float padDownX;
    private static float padDownY;
    private static float padLastX;
    private static float padLastY;
    private static float padAccX;
    private static float padAccY;

    /**
     * 宿主触控板（TouchBarPanelView）在第三方输入框里走 ai.m：拖动靠 Jovi vivoassist 按屏幕坐标放光标，
     * 长按选字只对 vivo 原生 EditText（getServedViewType()==1）发 Shift+方向键，第三方退化成滚动，基本不可用。
     * 百度 / 微信等输入法的做法是直接发标准 DPAD 按键，对任何输入框都有效。这里照此处理：
     * 仅当目标输入框不是 vivo 原生类型时接管手势——拖动 = 方向键移光标，长按震动后拖动 = Shift+方向键选字；
     * vivo 原生输入框仍走宿主实现。
     */
    private static void hookTouchPad(ClassLoader cl) throws Throwable {
        Class<?> cacheClass = XposedHelpers.findClass("ol.q", cl);
        Method icGetter = cacheClass.getDeclaredMethod("a");
        if (!java.lang.reflect.Modifier.isStatic(icGetter.getModifiers())
                || !android.view.inputmethod.InputConnection.class.isAssignableFrom(icGetter.getReturnType())) {
            throw new NoSuchMethodException("ol.q.a() InputConnection");
        }
        icGetter.setAccessible(true);
        imeInputConnection = icGetter;
        Class<?> panel = XposedHelpers.findClass("com.vivo.ai.ime.touchBar.TouchBarPanelView", cl);
        Method onTouch = panel.getDeclaredMethod("onTouchEvent", android.view.MotionEvent.class);
        XposedBridge.hookMethod(onTouch, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    android.view.MotionEvent ev = (android.view.MotionEvent) param.args[0];
                    android.view.View view = (android.view.View) param.thisObject;
                    if (ev == null || view == null) {
                        return;
                    }
                    int action = ev.getActionMasked();
                    if (action == android.view.MotionEvent.ACTION_DOWN) {
                        padActive = false;
                        android.view.inputmethod.InputConnection ic = currentInputConnection();
                        if (ic == null || isVivoNativeField(ic)) {
                            return; // 交给宿主
                        }
                        padActive = true;
                        padView = view;
                        padMoved = false;
                        padSelecting = false;
                        padDownX = padLastX = ev.getX();
                        padDownY = padLastY = ev.getY();
                        padAccX = padAccY = 0f;
                        view.removeCallbacks(LONG_PRESS);
                        view.postDelayed(LONG_PRESS, 450L);
                        if (view.getParent() != null) {
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        param.setResult(Boolean.TRUE);
                        return;
                    }
                    if (!padActive) {
                        return;
                    }
                    param.setResult(Boolean.TRUE);
                    if (action == android.view.MotionEvent.ACTION_MOVE) {
                        float density = view.getResources().getDisplayMetrics().density;
                        float x = ev.getX();
                        float y = ev.getY();
                        if (!padMoved) {
                            int slop = android.view.ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                            if (Math.abs(x - padDownX) < slop && Math.abs(y - padDownY) < slop) {
                                return;
                            }
                            padMoved = true;
                            view.removeCallbacks(LONG_PRESS);
                            padLastX = x;
                            padLastY = y;
                            return;
                        }
                        padAccX += x - padLastX;
                        padAccY += y - padLastY;
                        padLastX = x;
                        padLastY = y;
                        float stepX = 12f * density;
                        float stepY = 36f * density;
                        android.view.inputmethod.InputConnection ic = currentInputConnection();
                        if (ic == null) {
                            return;
                        }
                        int budget = 24;
                        while (Math.abs(padAccX) >= stepX && budget-- > 0) {
                            boolean right = padAccX > 0;
                            sendArrow(ic, right ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                                    : android.view.KeyEvent.KEYCODE_DPAD_LEFT, padSelecting);
                            padAccX += right ? -stepX : stepX;
                        }
                        while (Math.abs(padAccY) >= stepY && budget-- > 0) {
                            boolean down = padAccY > 0;
                            sendArrow(ic, down ? android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                    : android.view.KeyEvent.KEYCODE_DPAD_UP, padSelecting);
                            padAccY += down ? -stepY : stepY;
                        }
                    } else if (action == android.view.MotionEvent.ACTION_UP
                            || action == android.view.MotionEvent.ACTION_CANCEL) {
                        view.removeCallbacks(LONG_PRESS);
                        padActive = false;
                        padSelecting = false;
                        padView = null;
                    }
                } catch (Throwable ignored) {
                    // 出错时不吞事件，交还宿主
                }
            }
        });
        log("touchpad hook installed");
    }

    private static android.view.inputmethod.InputConnection currentInputConnection() {
        try {
            return (android.view.inputmethod.InputConnection) imeInputConnection.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 与宿主同口径：反射调 getServedViewType()，== 1 才是 vivo 原生 EditText。 */
    private static boolean isVivoNativeField(android.view.inputmethod.InputConnection ic) {
        try {
            Method m = ic.getClass().getMethod("getServedViewType");
            Object r = m.invoke(ic);
            return r instanceof Integer && ((Integer) r) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sendArrow(android.view.inputmethod.InputConnection ic, int code, boolean shift) {
        long t = android.os.SystemClock.uptimeMillis();
        int meta = shift ? (android.view.KeyEvent.META_SHIFT_ON | android.view.KeyEvent.META_SHIFT_LEFT_ON) : 0;
        int flags = android.view.KeyEvent.FLAG_SOFT_KEYBOARD | android.view.KeyEvent.FLAG_KEEP_TOUCH_MODE;
        int dev = android.view.KeyCharacterMap.VIRTUAL_KEYBOARD;
        if (shift) {
            ic.sendKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta, dev, 0, flags));
        }
        ic.sendKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0, meta, dev, 0, flags));
        ic.sendKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0, meta, dev, 0, flags));
        if (shift) {
            ic.sendKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0, dev, 0, flags));
        }
    }

    /**
     * 文本编辑图标置灰的根因：bj.f.n()（isEnable）对 TEXT_EDITOR 调 ol.q.c(jd.a, InputMethodService)，
     * 其结果 = loadStateSupport && isTypeEnable(c) && isTypeSupport(b)。后两项来自对目标输入框
     * 反射调 vivo 私有 getServedViewType()：只有 vivo 框架原生 EditText 返回 1，第三方
     * （元宝 / WebView / Flutter 等）一律 -100，于是图标 alpha 0.3 且点不动。
     * 这里只放开这两项“输入框类型”判定，仍保留宿主自己的布局判定（全屏抽取、悬浮、
     * 多窗、TalkBack、游戏画中画等），实现仍由 ai.c.g() 在 ai.b / ai.m 之间自选。
     */
    private static void hookTouchBarEnable(ClassLoader cl) throws Throwable {
        final Class<?> cacheClass = XposedHelpers.findClass("ol.q", cl);
        final Field typeSupport = cacheClass.getDeclaredField("b");
        final Field typeEnable = cacheClass.getDeclaredField("c");
        if (typeSupport.getType() != boolean.class || typeEnable.getType() != boolean.class) {
            throw new NoSuchFieldException("ol.q.b/c not boolean");
        }
        typeSupport.setAccessible(true);
        typeEnable.setAccessible(true);
        Method target = null;
        for (Method m : cacheClass.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if ("c".equals(m.getName()) && m.getReturnType() == boolean.class && p.length == 2
                    && android.inputmethodservice.InputMethodService.class.isAssignableFrom(p[1])) {
                target = m;
                break;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException("ol.q.c(jd.a, InputMethodService)");
        }
        final Method original = target;
        XposedBridge.hookMethod(original, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!Boolean.FALSE.equals(param.getResult()) || param.thisObject == null) {
                        return;
                    }
                    Object self = param.thisObject;
                    boolean oldSupport = typeSupport.getBoolean(self);
                    boolean oldEnable = typeEnable.getBoolean(self);
                    if (oldSupport && oldEnable) {
                        return; // 是布局判定拒绝的，尊重宿主
                    }
                    Object again;
                    typeSupport.setBoolean(self, true);
                    typeEnable.setBoolean(self, true);
                    try {
                        again = XposedBridge.invokeOriginalMethod(original, self, param.args);
                    } finally {
                        typeSupport.setBoolean(self, oldSupport);
                        typeEnable.setBoolean(self, oldEnable);
                    }
                    if (Boolean.TRUE.equals(again)) {
                        param.setResult(Boolean.TRUE);
                    }
                } catch (Throwable ignored) {
                    // 保持宿主原结果
                }
            }
        });
        log("touchbar enable hook installed");
    }

    /** 英文 26 键 M：label_secondary "\" -> "/"。 */
    private static void hookKeyLabels(ClassLoader cl) throws Throwable {
        final Class<?> keyClass = XposedHelpers.findClass("uc.f", cl);
        keycodeField = keyClass.getField("keycode");
        secondaryField = keyClass.getField("g");

        Class<?> parser = XposedHelpers.findClass(KEY_PARSER, cl);
        XposedBridge.hookAllMethods(parser, "f", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args == null || param.args.length != 2) {
                        return;
                    }
                    if (!(param.args[0] instanceof XmlResourceParser)) {
                        return;
                    }
                    Object key = param.getResult();
                    if (key == null || !keyClass.isInstance(key)) {
                        return;
                    }
                    if (keycodeField.getInt(key) != KEYCODE_M) {
                        return;
                    }
                    Object secondary = secondaryField.get(key);
                    if (!"\\".equals(secondary)) {
                        return;
                    }
                    secondaryField.set(key, "/");
                } catch (Throwable ignored) {
                    // 单个键解析失败不影响宿主
                }
            }
        });
        log("key label hook installed");
    }

    /** 顶部工具栏：5 个用户槽位 -> MAX_TOP_TOOLS，并让“文本编辑”常驻。 */
    private static void hookTopToolBar(ClassLoader cl) throws Throwable {
        final Class<?> toolClass = XposedHelpers.findClass("bj.f", cl);
        typeField = toolClass.getField("d");
        locationField = toolClass.getField("q");
        occupyField = toolClass.getField("p");
        isTopToolMethod = toolClass.getMethod("h");
        isSupportedMethod = toolClass.getMethod("p");

        // ① 文本编辑的“场景支持”判定来自目标输入框的 getServedViewType()，第三方应用一律 -100。
        //   这里对 TEXT_EDITOR 恒放行，让宿主自己选真触控栏或 WebView 兜底实现。
        Set<?> pHooked = XposedBridge.hookAllMethods(toolClass, "p", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args != null && param.args.length != 0) {
                        return;
                    }
                    if (param.thisObject == null || !Boolean.FALSE.equals(param.getResult())) {
                        return;
                    }
                    if (isTextEditor(param.thisObject)) {
                        param.setResult(Boolean.TRUE);
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        Class<?> data = XposedHelpers.findClass(TOOLBAR_DATA, cl);

        // ② 顶部列表里没文本编辑就插一个，有就把 occupy 纠正为 true。
        final Object textEditor = Enum.valueOf((Class) typeField.getType(), TYPE_TEXT_EDITOR);
        Set<?> vHooked = XposedBridge.hookAllMethods(data, "v", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object result = param.getResult();
                    if (!(result instanceof List)) {
                        return;
                    }
                    List<Object> list = (List<Object>) result;
                    Object found = null;
                    for (Object item : list) {
                        if (item != null && isTextEditor(item)) {
                            occupyField.setBoolean(item, true);
                            found = item;
                        }
                    }
                    if (found == null) {
                        Object item = XposedHelpers.newInstance(toolClass, textEditor);
                        occupyField.setBoolean(item, true);
                        locationField.setInt(item, 1);
                        list.add(Math.max(0, list.size() - 1), item);
                    }
                    // 去掉当前场景不可用的工具（如未授权的“文本扫描”），避免占着槽位却显示空白
                    for (int i = list.size() - 1; i >= 0; i--) {
                        Object item = list.get(i);
                        if (item == null || isTextEditor(item)) {
                            continue;
                        }
                        if (!occupyField.getBoolean(item)
                                || !Boolean.TRUE.equals(isSupportedMethod.invoke(item))) {
                            list.remove(i);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        // ③ 顶部列表截断到 5：补回被截断的项。
        Set<?> qHooked = XposedBridge.hookAllMethods(data, "q", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args == null || param.args.length != 1) {
                        return;
                    }
                    if (!Boolean.FALSE.equals(param.args[0])) {
                        return; // 只处理 q(false)（运行期 / 定制页的真实列表）
                    }
                    Object result = param.getResult();
                    if (!(result instanceof List)) {
                        return;
                    }
                    List<?> list = (List<?>) result;
                    if (list.size() < 2 || containsSplit(list)) {
                        return;
                    }
                    if (list.size() - 2 >= MAX_TOP_TOOLS) {
                        return;
                    }
                    Object full = XposedHelpers.callMethod(param.thisObject, "v");
                    if (!(full instanceof List)) {
                        return;
                    }
                    ArrayList<Object> out = new ArrayList<Object>(list);
                    Object tail = out.remove(out.size() - 1);
                    Set<Object> types = new HashSet<Object>();
                    for (Object item : out) {
                        Object type = item == null ? null : typeField.get(item);
                        if (type != null) {
                            types.add(type);
                        }
                    }
                    int visible = 0;
                    for (int i = 1; i < out.size(); i++) {
                        Object item = out.get(i);
                        if (item != null && occupyField.getBoolean(item)
                                && Boolean.TRUE.equals(isSupportedMethod.invoke(item))) {
                            visible++;
                        }
                    }
                    for (Object candidate : (List<?>) full) {
                        if (visible >= MAX_TOP_TOOLS) {
                            break;
                        }
                        if (candidate == null || !toolClass.isInstance(candidate)) {
                            continue;
                        }
                        if (!Boolean.TRUE.equals(isTopToolMethod.invoke(candidate))) {
                            continue;
                        }
                        if (!Boolean.TRUE.equals(isSupportedMethod.invoke(candidate))) {
                            continue;
                        }
                        Object type = typeField.get(candidate);
                        if (type == null || types.contains(type)) {
                            continue;
                        }
                        locationField.setInt(candidate, 1);
                        out.add(candidate);
                        types.add(type);
                        visible++;
                    }
                    out.add(tail);
                    param.setResult(out);
                } catch (Throwable ignored) {
                    // 保持宿主原列表
                }
            }
        });
        log("toolbar hook installed p=" + pHooked.size() + " v=" + vHooked.size() + " q=" + qHooked.size());
    }

    private static boolean isTextEditor(Object item) {
        try {
            Object type = typeField.get(item);
            return type instanceof Enum && TYPE_TEXT_EDITOR.equals(((Enum<?>) type).name());
        } catch (Throwable ignored) {
            return false;
        }
    }
    private static boolean containsSplit(List<?> list) {
        try {
            for (Object item : list) {
                Object type = item == null ? null : typeField.get(item);
                if (type instanceof Enum) {
                    String name = ((Enum<?>) type).name();
                    if (name.startsWith("SPLIT")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static void log(String message, Throwable t) {
        XposedBridge.log(TAG + ": " + message + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
    }
}
