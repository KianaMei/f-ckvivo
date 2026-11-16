package com.douai.module.feature;

import android.app.Dialog;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;
import com.douai.module.config.LocalOptions;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Opt-in literal keyword filtering for host windows; owned module windows always pass through. */
public final class UnifiedPopupFilterHook implements AutoCloseable {
    public static final String ENABLED = "unified_popup_filter_enabled";
    public static final String KEYWORDS = "unified_popup_keywords";
    public static final String MODULE_DIALOG_TAG = "douai.module.dialog";
    static final int MAX_NODES = 1024, MAX_TEXT = 16 * 1024;
    private static final Map<Object, Boolean> MODULE_WINDOWS = Collections.synchronizedMap(new WeakHashMap<>());
    private final BehaviorHookGroup hooks;
    private volatile LocalOptions options;
    private volatile List<String> words = Collections.emptyList();
    private volatile boolean closed;

    private UnifiedPopupFilterHook(LocalOptions options, Consumer<String> log) {
        hooks = new BehaviorHookGroup("unified_popup_filter", log == null ? ignored -> { } : log);
        setOptions(options);
    }

    /** Call before show(); do not create decor early or interfere with requestWindowFeature(). */
    public static void markModule(Dialog dialog) {
        if (dialog == null) return;
        MODULE_WINDOWS.put(dialog, true);
        if (dialog.getWindow() != null && dialog.getWindow().peekDecorView() != null)
            dialog.getWindow().peekDecorView().setTag(MODULE_DIALOG_TAG);
    }
    public static void markModule(PopupWindow popup) {
        if (popup == null) return;
        MODULE_WINDOWS.put(popup, true);
        if (popup.getContentView() != null) popup.getContentView().setTag(MODULE_DIALOG_TAG);
    }

    public static UnifiedPopupFilterHook install(XposedInterface framework, LocalOptions options,
                                                 Consumer<String> log) throws NoSuchMethodException {
        UnifiedPopupFilterHook feature = new UnifiedPopupFilterHook(options, log);
        try {
            feature.hooks.add(framework, Dialog.class.getDeclaredMethod("show"), chain -> {
                Object owner = chain.getThisObject();
                if (!(owner instanceof Dialog)) return chain.proceed();
                Dialog dialog = (Dialog) owner;
                if (MODULE_WINDOWS.containsKey(owner)) {
                    Object result = chain.proceed();
                    markModule(dialog); // Decor now exists; identity already protected the entire show call.
                    return result;
                }
                if (!feature.enabled()) return chain.proceed();
                try {
                    // AlertDialog builds its content in onCreate rather than Builder.create().
                    dialog.create();
                    if (dialog.getWindow() != null && feature.block(dialog.getWindow().getDecorView())) return null;
                } catch (RuntimeException | LinkageError unavailable) { /* Keep the host window on inspection failure. */ }
                return chain.proceed();
            });
            List<Method> popupMethods = new ArrayList<>();
            popupMethods.add(PopupWindow.class.getDeclaredMethod("showAtLocation", View.class, int.class, int.class, int.class));
            popupMethods.add(PopupWindow.class.getDeclaredMethod("showAtLocation", IBinder.class, int.class, int.class, int.class));
            popupMethods.add(PopupWindow.class.getDeclaredMethod("showAsDropDown", View.class));
            popupMethods.add(PopupWindow.class.getDeclaredMethod("showAsDropDown", View.class, int.class, int.class));
            popupMethods.add(PopupWindow.class.getDeclaredMethod("showAsDropDown", View.class, int.class, int.class, int.class));
            for (Method method : popupMethods) feature.hooks.add(framework, method, chain -> {
                Object owner = chain.getThisObject();
                if (!(owner instanceof PopupWindow) || !feature.enabled() || MODULE_WINDOWS.containsKey(owner)) return chain.proceed();
                try {
                    Object anchor = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                    if (anchor instanceof View && ownedAnchor((View) anchor)) return chain.proceed();
                    if (feature.block(((PopupWindow) owner).getContentView())) return null;
                } catch (RuntimeException | LinkageError unavailable) { /* A failed read never hides the popup. */ }
                return chain.proceed();
            });
            return feature;
        } catch (NoSuchMethodException | RuntimeException | LinkageError failure) { feature.close(); throw failure; }
    }

    public void setOptions(LocalOptions next) {
        options = next;
        words = parseKeywords(next == null ? "" : next.text(KEYWORDS, ""));
    }
    public boolean isActive() { return !closed && hooks.isActive(); }
    private boolean enabled() { LocalOptions current = options; return !closed && current != null && current.enabled(ENABLED) && !words.isEmpty(); }
    private boolean block(View root) { return shouldBlock(inspect(root), words); }

    static List<String> parseKeywords(String source) {
        if (source == null || source.length() > MAX_TEXT) return Collections.emptyList();
        List<String> words = new ArrayList<>();
        for (String line : source.split("\\r?\\n", -1)) {
            String word = line.trim();
            if (!word.isEmpty() && word.length() <= 256 && !words.contains(word)) words.add(word);
            if (words.size() == 128) break;
        }
        return Collections.unmodifiableList(words);
    }

    static boolean shouldBlock(Inspection inspection, List<String> keywords) {
        if (inspection == null || inspection.moduleOwned || inspection.truncated) return false;
        for (String word : keywords) if (!word.isEmpty() && inspection.text.contains(word)) return true;
        return false;
    }

    static final class Inspection {
        final String text;
        final boolean moduleOwned, truncated;
        final int nodes;
        Inspection(String text, boolean owned, boolean truncated, int nodes) {
            this.text = text; this.moduleOwned = owned; this.truncated = truncated; this.nodes = nodes;
        }
    }
    private static final class Node {
        final View view; final boolean visible;
        Node(View view, boolean visible) { this.view = view; this.visible = visible; }
    }
    static Inspection inspect(View root) {
        if (root == null) return new Inspection("", false, false, 0);
        ArrayDeque<Node> pending = new ArrayDeque<>(); pending.add(new Node(root, true));
        StringBuilder text = new StringBuilder(); int count = 0; boolean truncated = false;
        while (!pending.isEmpty()) {
            if (count >= MAX_NODES) { truncated = true; break; }
            Node next = pending.removeLast(); View view = next.view; count++;
            if (moduleTag(view.getTag())) return new Inspection("", true, false, count);
            boolean visible = next.visible && view.getVisibility() == View.VISIBLE;
            if (visible && view instanceof TextView) {
                CharSequence value = ((TextView) view).getText();
                if (value != null && value.length() > 0) {
                    int available = MAX_TEXT - text.length();
                    if (available <= 0) truncated = true;
                    else {
                        int length = Math.min(available, value.length()); text.append(value, 0, length);
                        if (length < value.length()) truncated = true;
                        if (text.length() < MAX_TEXT) text.append('\n');
                    }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                int childCount = group.getChildCount();
                int available = MAX_NODES - count - pending.size();
                if (childCount > available) truncated = true;
                for (int i = Math.min(childCount, Math.max(0, available)) - 1; i >= 0; i--) {
                    View child = group.getChildAt(i); if (child != null) pending.add(new Node(child, visible));
                }
            }
        }
        return new Inspection(text.toString(), false, truncated, count);
    }

    private static boolean moduleTag(Object tag) {
        if (!(tag instanceof String)) return false;
        String value = (String) tag;
        return MODULE_DIALOG_TAG.equals(value) || value.startsWith("settings.")
                || value.startsWith("ai.") || value.startsWith("douai.module.");
    }
    private static boolean ownedAnchor(View anchor) {
        View current = anchor;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (moduleTag(current.getTag())) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }
    @Override public void close() { if (closed) return; closed = true; hooks.close(); }
}
