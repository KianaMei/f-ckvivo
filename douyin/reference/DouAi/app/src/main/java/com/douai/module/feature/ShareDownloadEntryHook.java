package com.douai.module.feature;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Inserts an independent entry on the original host's share windows, including its newer panels. */
public final class ShareDownloadEntryHook implements AutoCloseable {
    static final String ENTRY_TAG = "douai.share_download";
    static final int ACTION_TAG = 0x41d0a001;
    private static final String[] WINDOW_TYPES = {
        "com.ss.android.ugc.aweme.shareplatform.ui.CommonShareDialogPanel",
        "com.ss.android.ugc.aweme.share.socialpanel.dialog.SocialActionsPanel",
        "com.ss.android.ugc.aweme.im.share.aweme.only.sharepanel.ui.SharePanelDialog",
        "com.ss.android.ugc.aweme.refactor.douyin.share.improve.CommonShareDialog",
        "com.ss.android.ugc.aweme.sharer.panelmodel.base.DialogPanel",
        "com.ss.android.ugc.aweme.sharer.ui.BottomSheetSharePanel",
        "com.ss.android.ugc.aweme.sharer.ui.SideslipSharePanel"
    };
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    // The values must not retain their weak Dialog keys through a View/listener/Window chain.
    private final Map<Dialog, Attachment> attachments = new WeakHashMap<>();
    private final Map<Dialog, WeakReference<Object>> suppliedPackages = new WeakHashMap<>();
    private final java.util.Set<View> insertedRows = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<View, WeakReference<Dialog>> rowDialogs = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PlaybackSourceCaptureHook sources;
    private final DownloadActionHook downloads;
    private final Consumer<String> log;
    private volatile boolean closed;
    private volatile String lastWindow = "尚未观察到窗口";
    private volatile String recentWindows = "尚未观察到窗口";
    private volatile String lastShareWindow = "尚未命中分享窗口";
    private volatile String stage = "已安装，等待打开分享功能列表";
    private volatile String lastFailure = "无";
    private volatile int matched, inserted, restored, clicks, windowsSeen;
    private volatile boolean packageReady, workReady, buttonVisible;

    ShareDownloadEntryHook(PlaybackSourceCaptureHook sources, DownloadActionHook downloads, Consumer<String> log) {
        this.sources = sources;
        this.downloads = downloads;
        this.log = log == null ? ignored -> { } : log;
    }

    public static ShareDownloadEntryHook install(XposedInterface framework, ClassLoader loader,
            PlaybackSourceCaptureHook sources, DownloadActionHook downloads, Consumer<String> log) throws Exception {
        ShareDownloadEntryHook feature = new ShareDownloadEntryHook(sources, downloads, log);
        try {
            feature.add(framework, Dialog.class.getDeclaredMethod("show"), "DouAi.ShareDownload.Show", chain -> {
                Object result = chain.proceed();
                feature.onDialogShown((Dialog)chain.getThisObject());
                return result;
            });
            feature.add(framework, Dialog.class.getDeclaredMethod("onStart"), "DouAi.ShareDownload.Start", chain -> {
                Object result = chain.proceed();
                feature.onDialogShown((Dialog)chain.getThisObject());
                return result;
            });
            feature.add(framework, Dialog.class.getDeclaredMethod("dismiss"), "DouAi.ShareDownload.Dismiss", chain -> {
                Object result = chain.proceed();
                feature.onDialogDismissed((Dialog)chain.getThisObject());
                return result;
            });
            Class<?> adapter = Class.forName(
                    "com.ss.android.ugc.aweme.im.share.aweme.only.sharepanel.ui.ShareFunctionAdapter", false, loader);
            feature.add(framework, adapter.getDeclaredMethod("setData", List.class), "DouAi.ShareDownload.Row", chain -> {
                Object result = chain.proceed();
                feature.onShareRowsBound(chain.getThisObject(), null);
                return result;
            });
            return feature;
        } catch (Exception | LinkageError failure) { feature.close(); throw failure; }
    }

    private void add(XposedInterface framework, Method method, String id, XposedInterface.Hooker callback) {
        XposedInterface.HookHandle handle = framework.hook(method).setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(callback);
        if (handle == null) throw new IllegalStateException("Empty share insertion handle");
        handles.add(handle);
    }

    void onDialogShown(Dialog dialog) {
        if (closed || dialog == null) return;
        rememberWindow(dialog);
        boolean known = hasType(dialog, "com.ss.android.ugc.aweme.im.share.aweme.only.sharepanel.ui.SharePanelDialog");
        if (!known) for (String name : WINDOW_TYPES) if (hasType(dialog, name)) { known = true; break; }
        if (!known) return;
        lastShareWindow = lastWindow;
        Object adapter = HostTargets.read(dialog, "p");
        if (adapter == null) adapter = HostTargets.read(dialog, "q");
        onShareRowsBound(adapter, dialog);
    }

    void onShareRowsBound(Object adapter, Dialog knownDialog) {
        if (closed || adapter == null) return;
        Object recycler = HostTargets.read(adapter, "d");
        if (!(recycler instanceof View) || !(((View)recycler).getParent() instanceof ViewGroup)) return;
        View rows = (View)recycler;
        ViewGroup parent = (ViewGroup)rows.getParent();
        Context context = rows.getContext();
        Dialog dialog = knownDialog != null ? knownDialog : currentShareDialog(rows);
        if (dialog == null) return;
        if (dialog != null) attachments.putIfAbsent(dialog, null);
        if (dialog != null) lastShareWindow = dialog.getClass().getName();
        View existing = parent.findViewWithTag(ENTRY_TAG);
        if (existing == null) {
            LinearLayout entry = createRow(context, () -> { });
            Runnable action = () -> {
                Dialog current = null;
                for (Dialog candidate : attachments.keySet()) if (candidate != null && candidate.isShowing()) { current = candidate; break; }
                save(current);
            };
            entry.setTag(ACTION_TAG, action);
            entry.setOnClickListener(view -> action.run());
            if (dialog != null) rowDialogs.put(entry, new WeakReference<>(dialog));
            ViewGroup.LayoutParams layout = parent instanceof LinearLayout
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            parent.addView(entry, parent.indexOfChild(rows), layout);
            insertedRows.add(entry);
            inserted++;
            matched++;
        }
        packageReady = sharePackage(dialog) != null;
        workReady = sharedWork(dialog, sharePackage(dialog)) != null;
        buttonVisible = parent.findViewWithTag(ENTRY_TAG) != null;
        stage = (buttonVisible ? "模块下载已加入分享功能列表上方" : "分享功能列表尚未显示") + "；绑定窗口：" + (dialog == null ? "无" : dialog.getClass().getSimpleName());
    }

    private LinearLayout createRow(Context context, Runnable action) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView entry = new TextView(context);
        entry.setText("模块下载");
        entry.setContentDescription("模块下载，保存当前分享作品的媒体");
        entry.setTextColor(Color.WHITE);
        entry.setTextSize(15);
        entry.setGravity(Gravity.CENTER);
        entry.setMinHeight(Math.round(42 * density));
        entry.setPadding(Math.round(16 * density), Math.round(8 * density), Math.round(16 * density), Math.round(8 * density));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff007f73);
        background.setCornerRadius(12 * density);
        entry.setBackground(background);
        LinearLayout row = new LinearLayout(context);
        row.setTag(ENTRY_TAG);
        row.setGravity(Gravity.CENTER);
        row.setPadding(Math.round(16 * density), Math.round(8 * density), Math.round(16 * density), Math.round(4 * density));
        row.addView(entry, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View.OnClickListener listener = view -> action.run();
        row.setTag(ACTION_TAG, action);
        row.setOnClickListener(listener);
        entry.setOnClickListener(listener);
        return row;
    }

    private Dialog dialogFor(View row) {
        WeakReference<Dialog> reference = rowDialogs.get(row);
        Dialog dialog = reference == null ? null : reference.get();
        return dialog != null && dialog.isShowing() ? dialog : currentShareDialog(row);
    }

    private Dialog currentShareDialog(View rows) {
        Context context = rows.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof android.app.Activity) {
                for (Dialog dialog : attachments.keySet()) {
                    if (dialog != null && dialog.isShowing() && dialog.getOwnerActivity() == context) return dialog;
                }
                return null;
            }
            context = ((android.content.ContextWrapper)context).getBaseContext();
        }
        return null;
    }

    private void rememberWindow(Dialog dialog) {
        String name = dialog.getClass().getName();
        lastWindow = name;
        windowsSeen++;
        String current = recentWindows;
        if (current.startsWith("尚未")) recentWindows = name;
        else if (!current.contains(name)) {
            String next = name + " | " + current;
            recentWindows = next.length() > 420 ? next.substring(0, 420) : next;
        }
    }

    void attach(Dialog dialog, Object suppliedPackage) {
        if (closed || dialog == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> attach(dialog, suppliedPackage));
            return;
        }
        try {
            Attachment state = attachments.get(dialog);
            if (!attachments.containsKey(dialog)) attachments.put(dialog, null);
            if (suppliedPackage != null) suppliedPackages.put(dialog, new WeakReference<>(suppliedPackage));
        } catch (RuntimeException | LinkageError failure) { warn(failure); }
    }

    private static boolean hasType(Object value, String name) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(name)) return true;
        return false;
    }

    private Object sharePackage(Dialog dialog) {
        if (hasType(dialog, WINDOW_TYPES[0]) || hasType(dialog, WINDOW_TYPES[4])) {
            Object model = HostTargets.read(dialog, "g");
            Object value = HostTargets.read(HostTargets.read(model, "m"), "LIZIZ");
            if (value == null) value = HostTargets.read(HostTargets.read(model, "o"), "LJFF");
            return value;
        }
        if (hasType(dialog, WINDOW_TYPES[1])) return HostTargets.read(HostTargets.read(dialog, "b"), "LJIIL");
        if (hasType(dialog, WINDOW_TYPES[2])) return HostTargets.invoke(dialog, "LJZL");
        if (hasType(dialog, WINDOW_TYPES[3])) return HostTargets.read(HostTargets.read(dialog, "e"), "b");
        if (hasType(dialog, "com.ss.android.ugc.aweme.sharer.ui.SkeletonShareDialog"))
            return HostTargets.read(HostTargets.read(dialog, "b"), "LJIIIIZZ");
        return null;
    }

    private Object sharedWork(Dialog dialog, Object sharePackage) {
        // SocialActionsPanel owns the exact Aweme even when the daily/story package has no identifier.
        if (hasType(dialog, WINDOW_TYPES[1])) {
            Object work = HostTargets.read(HostTargets.read(dialog, "b"), "LIZ");
            if (hasType(work, "com.ss.android.ugc.aweme.feed.model.Aweme")) return work;
        }
        Object identifier = HostTargets.read(sharePackage, "identifier");
        String id = identifier instanceof String ? ((String)identifier).trim() : "";
        Object work = sources == null || id.isEmpty() ? null : sources.workForId(id);
        Object extras = HostTargets.read(sharePackage, "extras");
        if (work == null && extras instanceof Bundle) {
            try {
                Object candidate = ((Bundle)extras).get("aweme");
                if (hasType(candidate, "com.ss.android.ugc.aweme.feed.model.Aweme")
                        && (id.isEmpty() || id.equals(PlaybackSourceCaptureHook.workId(candidate)))) work = candidate;
            } catch (RuntimeException ignored) { }
        }
        return work;
    }

    private FrameLayout buttonParent(Dialog dialog, ViewGroup decor) {
        int id = dialog.getContext().getResources().getIdentifier("design_bottom_sheet", "id", "com.ss.android.ugc.aweme");
        View sheet = id == 0 ? null : decor.findViewById(id);
        if (sheet instanceof FrameLayout) return (FrameLayout)sheet;
        // Decor is above asynchronous share content and does not depend on android.R.id.content's type.
        return decor instanceof FrameLayout ? (FrameLayout)decor : null;
    }

    private final class Attachment implements ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<Dialog> dialog;
        WeakReference<ViewGroup> observedRoot = new WeakReference<>(null);
        WeakReference<TextView> button = new WeakReference<>(null);
        WeakReference<Object> suppliedPackage = new WeakReference<>(null);
        int insertions;

        Attachment(Dialog dialog) { this.dialog = new WeakReference<>(dialog); }

        void ensureAttached() {
            Dialog owner = dialog.get();
            if (closed || owner == null) return;
            if (owner.getWindow() == null) { stage = "分享窗口尚未创建"; return; }
            View decorView = owner.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) { stage = "分享窗口根布局不可用"; return; }
            ViewGroup decor = (ViewGroup)decorView;
            if (observedRoot.get() != decor) {
                removeObserver(); observedRoot = new WeakReference<>(decor);
                decor.getViewTreeObserver().addOnGlobalLayoutListener(this);
            }
            FrameLayout parent = buttonParent(owner, decor);
            if (parent == null) { stage = "分享窗口无法承载按钮：" + decor.getClass().getName(); return; }
            TextView entry = button.get();
            if (entry == null || entry.getParent() != parent) {
                if (entry != null && entry.getParent() instanceof ViewGroup) ((ViewGroup)entry.getParent()).removeView(entry);
                Context context = owner.getContext();
                float density = context.getResources().getDisplayMetrics().density;
                entry = new TextView(context);
                entry.setTag(ENTRY_TAG);
                entry.setText("↓  模块下载");
                entry.setContentDescription("模块下载，保存当前分享作品的媒体");
                entry.setTextSize(15);
                entry.setTextColor(Color.WHITE);
                entry.setGravity(Gravity.CENTER);
                entry.setMinHeight(Math.round(46 * density));
                entry.setPadding(Math.round(20 * density), Math.round(8 * density), Math.round(20 * density), Math.round(8 * density));
                GradientDrawable background = new GradientDrawable();
                background.setColor(0xff007f73); background.setCornerRadius(12 * density);
                entry.setBackground(background);
                entry.setElevation(64 * density);
                entry.setOnClickListener(view -> save(owner));
                FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
                layout.setMargins(Math.round(12 * density), Math.round(8 * density), Math.round(16 * density), Math.round(66 * density));
                parent.addView(entry, layout);
                button = new WeakReference<>(entry);
                if (insertions++ == 0) inserted++; else restored++;
            }
            if (entry.getVisibility() != View.VISIBLE) entry.setVisibility(View.VISIBLE);
            if (parent.indexOfChild(entry) != parent.getChildCount() - 1) entry.bringToFront();
            placeInsideVisibleArea(owner, parent, entry);
            Object currentPackage = sharePackage(owner);
            if (currentPackage == null) currentPackage = suppliedPackage.get();
            packageReady = currentPackage != null;
            // Reflection here is limited to this window's data; no global 'current work' fallback.
            workReady = sharedWork(owner, currentPackage) != null;
            Rect visible = new Rect();
            buttonVisible = entry.isShown() && entry.getGlobalVisibleRect(visible) && !visible.isEmpty();
            stage = buttonVisible ? "模块下载按钮已显示" : "按钮已插入，等待分享布局显示";
        }

        private void placeInsideVisibleArea(Dialog owner, FrameLayout parent, TextView entry) {
            if (!(entry.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams)entry.getLayoutParams();
            Rect window = new Rect();
            parent.getWindowVisibleDisplayFrame(window);
            int[] origin = new int[2];
            parent.getLocationOnScreen(origin);
            int parentBottom = origin[1] + Math.max(parent.getHeight(), 1);
            int visibleBottom = window.bottom > 0 ? window.bottom : parentBottom;
            int bottomGap = Math.max(0, parentBottom - visibleBottom);
            float density = owner.getContext().getResources().getDisplayMetrics().density;
            int margin = Math.round(16 * density) + bottomGap;
            // Keep the floating action above the pad panel's bottom edge and system inset.
            if (layout.bottomMargin != margin || layout.gravity != (Gravity.END | Gravity.BOTTOM)) {
                layout.gravity = Gravity.END | Gravity.BOTTOM;
                layout.bottomMargin = margin;
                entry.setLayoutParams(layout);
            }
            entry.setTranslationZ(Math.max(entry.getTranslationZ(), 32 * density));
        }

        @Override public void onGlobalLayout() {
            try { ensureAttached(); } catch (RuntimeException | LinkageError failure) { warn(failure); }
        }

        void removeObserver() {
            ViewGroup root = observedRoot.get();
            if (root != null && root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            observedRoot.clear();
        }

        void detach() {
            removeObserver();
            TextView entry = button.get();
            if (entry != null) {
                entry.setOnClickListener(null);
                if (entry.getParent() instanceof ViewGroup) ((ViewGroup)entry.getParent()).removeView(entry);
            }
            button.clear(); suppliedPackage.clear();
        }
    }

    private void save(Dialog dialog) {
        if (closed || dialog == null || !dialog.isShowing()) { stage = "点击时分享窗口不可用"; return; }
        clicks++;
        if (!downloads.isEnabled()) {
            stage = "下载开关未开启";
            Toast.makeText(dialog.getContext(), "请先在融合总菜单开启下载功能。", Toast.LENGTH_LONG).show();
            return;
        }
        Object currentPackage = sharePackage(dialog);
        Object work = sharedWork(dialog, currentPackage);
        packageReady = currentPackage != null; workReady = work != null;
        if (work == null) {
            stage = "分享作品数据尚未就绪";
            Toast.makeText(dialog.getContext(), "当前分享作品数据尚未就绪，请回到作品播放后重新分享。", Toast.LENGTH_LONG).show();
            return;
        }
        Context owner = dialog.getOwnerActivity() == null ? dialog.getContext() : dialog.getOwnerActivity();
        stage = "已点击模块下载，交给内置下载器";
        dialog.dismiss();
        onDialogDismissed(dialog);
        downloads.openForWork(owner, work, () -> !closed);
    }

    void onDialogDismissed(Dialog dialog) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> onDialogDismissed(dialog)); return; }
        attachments.remove(dialog);
        suppliedPackages.remove(dialog);
        buttonVisible = false;
    }

    public String describeDiagnostics() {
        return "分享入口状态：" + stage + "\n最近窗口：" + lastWindow + "\n命中分享窗口：" + lastShareWindow
                + "\n最近窗口记录：" + recentWindows
                + "\n观察/命中/插入/恢复/点击：" + windowsSeen + "/" + matched + "/" + inserted + "/" + restored + "/" + clicks
                + "；分享包/作品就绪：" + packageReady + "/" + workReady
                + "\n分享入口最近异常：" + lastFailure;
    }

    public void appendDiagnostics(Bundle snapshot) {
        snapshot.putString("share_entry.stage", stage);
        snapshot.putString("share_entry.last_window", lastWindow);
        snapshot.putString("share_entry.matched_window", lastShareWindow);
        snapshot.putString("share_entry.recent_windows", recentWindows);
        snapshot.putString("share_entry.failure", lastFailure);
        snapshot.putInt("share_entry.windows_seen", windowsSeen);
        snapshot.putInt("share_entry.matched", matched); snapshot.putInt("share_entry.inserted", inserted);
        snapshot.putInt("share_entry.restored", restored); snapshot.putInt("share_entry.clicks", clicks);
        snapshot.putBoolean("share_entry.package_ready", packageReady); snapshot.putBoolean("share_entry.work_ready", workReady);
        snapshot.putBoolean("share_entry.visible", buttonVisible);
    }

    private void warn(Throwable failure) {
        lastFailure = failure.getClass().getSimpleName();
        stage = "分享入口处理失败：" + lastFailure;
        try { log.accept("Share download entry unavailable: " + lastFailure); } catch (RuntimeException ignored) { }
    }

    @Override public void close() {
        closed = true;
        for (View row : insertedRows) if (row.getParent() instanceof ViewGroup) ((ViewGroup)row.getParent()).removeView(row);
        insertedRows.clear();
        for (XposedInterface.HookHandle handle : handles) try { handle.unhook(); } catch (RuntimeException ignored) { }
        handles.clear();
        attachments.clear();
        suppliedPackages.clear();
    }
}
