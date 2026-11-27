package local.codex.vivoime;

import android.content.res.XmlResourceParser;
import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.InputConnection;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * vivo 输入法 Pro（com.vivo.ai.ime.nex）行为修正（兼容 3.9.5 与 3.9.3）：
 *
 * 1. 英文 26 键 M 的 label_secondary 从 "\" 改为 "/"（条件 keycode == 41 && "\\".equals(g) 只命中英文 26 键）。
 * 2. 顶部工具栏从 5 个槽位放宽到 6 个，并让“文本编辑”常驻且不再被第三方输入框置灰。
 * 3. 文本编辑触控板在第三方输入框（非 vivo 原生 EditText）接管手势发送标准 DPAD 方向键与长按选字。
 *
 * 所有混淆类名/字段/方法均按版本 Profile 做严格签名校验后才挂载，不猜测 hook。
 */
public final class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "VivoImeTweaks";
    private static final String TARGET_PACKAGE = "com.vivo.ai.ime.nex";
    private static final int KEYCODE_M = 41;
    private static final int MAX_TOP_TOOLS = 6;
    private static final String TYPE_TEXT_EDITOR = "TEXT_EDITOR";

    private static final class KeyLabelProfile {
        final String label;
        final String parserClass;
        final String parseMethod;
        final String keyClass;
        final String keycodeField;
        final String secondaryField;

        KeyLabelProfile(String label, String parserClass, String parseMethod,
                String keyClass, String keycodeField, String secondaryField) {
            this.label = label;
            this.parserClass = parserClass;
            this.parseMethod = parseMethod;
            this.keyClass = keyClass;
            this.keycodeField = keycodeField;
            this.secondaryField = secondaryField;
        }
    }

    private static final class ToolBarProfile {
        final String label;
        final String toolClass;
        final String dataClass;
        final String typeField;
        final String occupyField;
        final String locationField;
        final String isTopToolMethod;
        final String isSupportedMethod;
        final String composeTopMethod;
        final String fullTopMethod;

        ToolBarProfile(String label, String toolClass, String dataClass,
                String typeField, String occupyField, String locationField,
                String isTopToolMethod, String isSupportedMethod,
                String composeTopMethod, String fullTopMethod) {
            this.label = label;
            this.toolClass = toolClass;
            this.dataClass = dataClass;
            this.typeField = typeField;
            this.occupyField = occupyField;
            this.locationField = locationField;
            this.isTopToolMethod = isTopToolMethod;
            this.isSupportedMethod = isSupportedMethod;
            this.composeTopMethod = composeTopMethod;
            this.fullTopMethod = fullTopMethod;
        }
    }

    private static final class TouchBarCacheProfile {
        final String label;
        final String cacheClass;
        final String icGetter;
        final String typeSupportField;
        final String typeEnableField;
        final String isSupportMethod;

        TouchBarCacheProfile(String label, String cacheClass, String icGetter,
                String typeSupportField, String typeEnableField, String isSupportMethod) {
            this.label = label;
            this.cacheClass = cacheClass;
            this.icGetter = icGetter;
            this.typeSupportField = typeSupportField;
            this.typeEnableField = typeEnableField;
            this.isSupportMethod = isSupportMethod;
        }
    }

    private static final KeyLabelProfile[] KEY_LABEL_PROFILES = {
        new KeyLabelProfile("3.9.5", "ah.b", "f", "uc.f", "keycode", "g"),
        new KeyLabelProfile("3.9.3", "ch.a", "f", "yc.d", "keycode", "g"),
    };

    private static final ToolBarProfile[] TOOLBAR_PROFILES = {
        new ToolBarProfile("3.9.5", "bj.f", "bj.a", "d", "p", "q", "h", "p", "q", "v"),
        new ToolBarProfile("3.9.3", "dj.e", "dj.b", "b", "n", "o", "m", "u", "r", "w"),
    };

    private static final TouchBarCacheProfile[] TOUCHBAR_CACHE_PROFILES = {
        new TouchBarCacheProfile("3.9.5", "ol.q", "a", "b", "c", "c"),
        new TouchBarCacheProfile("3.9.3", "pl.v", "a", "b", "c", "c"),
    };

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

    private static final class ResolvedTouchBarCache {
        final TouchBarCacheProfile profile;
        final Method icGetter;
        final Field typeSupport;
        final Field typeEnable;
        final Method isSupport;

        ResolvedTouchBarCache(TouchBarCacheProfile profile, Method icGetter,
                Field typeSupport, Field typeEnable, Method isSupport) {
            this.profile = profile;
            this.icGetter = icGetter;
            this.typeSupport = typeSupport;
            this.typeEnable = typeEnable;
            this.isSupport = isSupport;
        }
    }

    private static ResolvedTouchBarCache resolveTouchBarCache(ClassLoader cl) throws NoSuchMethodException {
        for (TouchBarCacheProfile p : TOUCHBAR_CACHE_PROFILES) {
            try {
                Class<?> cacheClass = XposedHelpers.findClass(p.cacheClass, cl);
                Method icGetter = cacheClass.getDeclaredMethod(p.icGetter);
                if (!Modifier.isStatic(icGetter.getModifiers())
                        || !InputConnection.class.isAssignableFrom(icGetter.getReturnType())) {
                    continue;
                }
                Field typeSupport = cacheClass.getDeclaredField(p.typeSupportField);
                Field typeEnable = cacheClass.getDeclaredField(p.typeEnableField);
                if (typeSupport.getType() != boolean.class || typeEnable.getType() != boolean.class) {
                    continue;
                }
                Method isSupport = null;
                for (Method m : cacheClass.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (p.isSupportMethod.equals(m.getName())
                            && m.getReturnType() == boolean.class
                            && params.length == 2
                            && InputMethodService.class.isAssignableFrom(params[1])) {
                        isSupport = m;
                        break;
                    }
                }
                if (isSupport == null) {
                    continue;
                }
                icGetter.setAccessible(true);
                typeSupport.setAccessible(true);
                typeEnable.setAccessible(true);
                return new ResolvedTouchBarCache(p, icGetter, typeSupport, typeEnable, isSupport);
            } catch (Throwable ignored) {
                // 尝试下一套版本 Profile
            }
        }
        throw new NoSuchMethodException("TouchBarCache profile not matched");
    }

    private static void hookTouchPad(ClassLoader cl) throws Throwable {
        ResolvedTouchBarCache resolved = resolveTouchBarCache(cl);
        imeInputConnection = resolved.icGetter;
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
                        InputConnection ic = currentInputConnection();
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
                        InputConnection ic = currentInputConnection();
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
        log("touchpad hook installed (" + resolved.profile.label + "/" + resolved.profile.cacheClass + ")");
    }

    private static InputConnection currentInputConnection() {
        try {
            return (InputConnection) imeInputConnection.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 与宿主同口径：反射调 getServedViewType()，== 1 才是 vivo 原生 EditText。 */
    private static boolean isVivoNativeField(InputConnection ic) {
        try {
            Method m = ic.getClass().getMethod("getServedViewType");
            Object r = m.invoke(ic);
            return r instanceof Integer && ((Integer) r) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sendArrow(InputConnection ic, int code, boolean shift) {
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

    private static void hookTouchBarEnable(ClassLoader cl) throws Throwable {
        final ResolvedTouchBarCache resolved = resolveTouchBarCache(cl);
        final Field typeSupport = resolved.typeSupport;
        final Field typeEnable = resolved.typeEnable;
        final Method original = resolved.isSupport;
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
        log("touchbar enable hook installed (" + resolved.profile.label + "/" + resolved.profile.cacheClass + ")");
    }

    /** 英文 26 键 M：label_secondary "\" -> "/"。 */
    private static void hookKeyLabels(ClassLoader cl) throws Throwable {
        for (KeyLabelProfile p : KEY_LABEL_PROFILES) {
            try {
                final Class<?> keyClass = XposedHelpers.findClass(p.keyClass, cl);
                Field fCode = keyClass.getField(p.keycodeField);
                Field fSec = keyClass.getField(p.secondaryField);
                if (fCode.getType() != int.class || fSec.getType() != String.class) {
                    continue;
                }
                Class<?> parser = XposedHelpers.findClass(p.parserClass, cl);
                Method parseTarget = null;
                for (Method m : parser.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (p.parseMethod.equals(m.getName())
                            && params.length == 2
                            && XmlResourceParser.class.isAssignableFrom(params[0])
                            && m.getReturnType() == keyClass) {
                        parseTarget = m;
                        break;
                    }
                }
                if (parseTarget == null) {
                    continue;
                }
                keycodeField = fCode;
                secondaryField = fSec;
                XposedBridge.hookMethod(parseTarget, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
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
                log("key label hook installed (" + p.label + "/" + p.parserClass + "->" + p.keyClass + ")");
                return;
            } catch (Throwable ignored) {
                // 尝试下一套版本 Profile
            }
        }
        throw new NoSuchMethodException("KeyLabel profile not matched");
    }

    /** 顶部工具栏：5 个用户槽位 -> MAX_TOP_TOOLS，并让“文本编辑”常驻。 */
    private static void hookTopToolBar(ClassLoader cl) throws Throwable {
        for (ToolBarProfile p : TOOLBAR_PROFILES) {
            try {
                final Class<?> toolClass = XposedHelpers.findClass(p.toolClass, cl);
                Field fType = toolClass.getField(p.typeField);
                Field fLoc = toolClass.getField(p.locationField);
                Field fOcc = toolClass.getField(p.occupyField);
                if (!fType.getType().isEnum()
                        || fLoc.getType() != int.class
                        || fOcc.getType() != boolean.class) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                final Object textEditor = Enum.valueOf((Class) fType.getType(), TYPE_TEXT_EDITOR);
                Constructor<?> ctor = toolClass.getConstructor(fType.getType());
                Method mTop = toolClass.getMethod(p.isTopToolMethod);
                Method mSup = toolClass.getMethod(p.isSupportedMethod);
                if (mTop.getReturnType() != boolean.class || mSup.getReturnType() != boolean.class) {
                    continue;
                }
                Class<?> dataClass = XposedHelpers.findClass(p.dataClass, cl);
                Method mCompose = dataClass.getDeclaredMethod(p.composeTopMethod, boolean.class);
                Method mFull = dataClass.getDeclaredMethod(p.fullTopMethod);
                if (!List.class.isAssignableFrom(mCompose.getReturnType())
                        || !List.class.isAssignableFrom(mFull.getReturnType())) {
                    continue;
                }

                typeField = fType;
                locationField = fLoc;
                occupyField = fOcc;
                isTopToolMethod = mTop;
                isSupportedMethod = mSup;
                final Constructor<?> toolCtor = ctor;
                final Method fullMethod = mFull;
                fullMethod.setAccessible(true);

                // ① 文本编辑“场景支持”恒放行
                XposedBridge.hookMethod(mSup, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
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

                // ② 顶部列表里没文本编辑就插一个，有就把 occupy 纠正为 true，并去掉不可用空白槽位
                XposedBridge.hookMethod(mFull, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            if (!(result instanceof List)) {
                                return;
                            }
                            @SuppressWarnings("unchecked")
                            List<Object> list = (List<Object>) result;
                            Object found = null;
                            for (Object item : list) {
                                if (item != null && isTextEditor(item)) {
                                    occupyField.setBoolean(item, true);
                                    found = item;
                                }
                            }
                            if (found == null) {
                                Object item = toolCtor.newInstance(textEditor);
                                occupyField.setBoolean(item, true);
                                locationField.setInt(item, 1);
                                list.add(Math.max(0, list.size() - 1), item);
                            }
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

                // ③ 顶部列表截断到 5：补回被截断的项
                XposedBridge.hookMethod(mCompose, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!Boolean.FALSE.equals(param.args[0])) {
                                return;
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
                            Object full = fullMethod.invoke(param.thisObject);
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

                log("toolbar hook installed (" + p.label + "/" + p.dataClass + "," + p.toolClass + ")");
                return;
            } catch (Throwable ignored) {
                // 尝试下一套版本 Profile
            }
        }
        throw new NoSuchMethodException("ToolBar profile not matched");
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
