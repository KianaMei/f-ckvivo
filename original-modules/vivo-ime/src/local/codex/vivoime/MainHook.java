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
