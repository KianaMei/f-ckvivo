package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import com.douai.module.config.LocalOptions;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class UnifiedPopupFilterHookTest {
    private Activity activity;
    private UnifiedPopupFilterHook hook;
    private final Harness harness = new Harness();
    @Before public void start() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        hook = UnifiedPopupFilterHook.install(harness.framework(), options(true, "推广"), ignored -> { });
    }
    @After public void stop() { if (hook != null) hook.close(); if (activity != null) activity.finish(); }
    private static LocalOptions options(boolean enabled, String words) {
        return new LocalOptions(Map.of(UnifiedPopupFilterHook.ENABLED, enabled, UnifiedPopupFilterHook.KEYWORDS, words));
    }
    private TextView text(String value) { TextView view = new TextView(activity); view.setText(value); return view; }

    @Test public void matchingHostDialogIsBlockedButExplicitlyMarkedModuleDialogAlwaysProceeds() throws Throwable {
        AlertDialog host = new AlertDialog.Builder(activity).setMessage("这是推广内容").create();
        assertEquals(0, harness.invoke(Dialog.class.getDeclaredMethod("show"), host));
        AlertDialog module = new AlertDialog.Builder(activity).setMessage("这是推广内容").create();
        module.create();
        UnifiedPopupFilterHook.markModule(module);
        assertEquals(UnifiedPopupFilterHook.MODULE_DIALOG_TAG, module.getWindow().getDecorView().getTag());
        assertEquals(1, harness.invoke(Dialog.class.getDeclaredMethod("show"), module));
        hook.setOptions(options(false, "推广"));
        assertEquals(1, harness.invoke(Dialog.class.getDeclaredMethod("show"), host));
        hook.setOptions(options(true, ""));
        assertEquals(1, harness.invoke(Dialog.class.getDeclaredMethod("show"), host));
    }
    @Test public void popupContentAndModuleAnchorsUseIdentityTagsInsteadOfGenericButtonWords() throws Throwable {
        hook.setOptions(options(true, "保存"));
        PopupWindow popup = new PopupWindow(text("保存 / 取消"), 100, 100);
        View anchor = new View(activity);
        Method show = PopupWindow.class.getDeclaredMethod("showAsDropDown", View.class);
        assertEquals(0, harness.invoke(show, popup, anchor));
        anchor.setTag("settings.option.saved");
        assertEquals(1, harness.invoke(show, popup, anchor));
        anchor.setTag(null); UnifiedPopupFilterHook.markModule(popup);
        assertEquals(1, harness.invoke(show, popup, anchor));
    }
    @Test public void reservedSettingsAndAiTagsProtectOwnedDialogTrees() {
        for (String marker : new String[]{"settings.search", "ai.chat", UnifiedPopupFilterHook.MODULE_DIALOG_TAG}) {
            LinearLayout root = new LinearLayout(activity); TextView content = text("推广");
            content.setTag(marker); root.addView(content);
            assertTrue(UnifiedPopupFilterHook.inspect(root).moduleOwned);
            assertFalse(UnifiedPopupFilterHook.shouldBlock(UnifiedPopupFilterHook.inspect(root), List.of("推广")));
        }
    }
    @Test public void oversizedTreesAndTextStayBoundedAndFailOpenRatherThanHidingUnknownContent() {
        LinearLayout large = new LinearLayout(activity);
        for (int i = 0; i < 1100; i++) large.addView(text("推广"));
        var nodes = UnifiedPopupFilterHook.inspect(large);
        assertTrue(nodes.nodes <= 1024); assertTrue(nodes.truncated);
        assertFalse(UnifiedPopupFilterHook.shouldBlock(nodes, List.of("推广")));
        var characters = UnifiedPopupFilterHook.inspect(text("推广" + "a".repeat(20000)));
        assertTrue(characters.text.length() <= 16384); assertTrue(characters.truncated);
        assertFalse(UnifiedPopupFilterHook.shouldBlock(characters, List.of("推广")));
    }
    @Test public void literalKeywordsDoNotActAsRegexAndHiddenTextDoesNotSuppressWindows() {
        TextView visible = text("普通内容");
        assertFalse(UnifiedPopupFilterHook.shouldBlock(UnifiedPopupFilterHook.inspect(visible), List.of(".*")));
        TextView hidden = text("推广"); hidden.setVisibility(View.GONE);
        LinearLayout root = new LinearLayout(activity); root.addView(visible); root.addView(hidden);
        assertFalse(UnifiedPopupFilterHook.shouldBlock(UnifiedPopupFilterHook.inspect(root), List.of("推广")));
        assertEquals(List.of("推广", "保存"), UnifiedPopupFilterHook.parseKeywords(" \n推广\n推广\n 保存 \r\n"));
    }
    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> installed = new LinkedHashMap<>();
        XposedInterface framework() {
            return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class}, (p, m, args) -> {
                if (!m.getName().equals("hook")) return null;
                Method target = (Method) args[0]; final XposedInterface.HookBuilder[] builder = new XposedInterface.HookBuilder[1];
                builder[0] = (XposedInterface.HookBuilder) Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                        new Class<?>[]{XposedInterface.HookBuilder.class}, (b, method, values) -> {
                            if (method.getName().equals("intercept")) {
                                installed.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (h, hm, ha) -> null);
                            }
                            return builder[0];
                        });
                return builder[0];
            });
        }
        int invoke(Method target, Object owner, Object... args) throws Throwable {
            AtomicInteger proceeded = new AtomicInteger();
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (p, method, values) -> {
                        switch (method.getName()) {
                            case "getThisObject": return owner;
                            case "getArg": return args[(Integer) values[0]];
                            case "getArgs": return java.util.Arrays.asList(args);
                            case "proceed": proceeded.incrementAndGet(); return null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            assertNotNull(target.toString(), installed.get(target)); installed.get(target).intercept(chain); return proceeded.get();
        }
    }
}
