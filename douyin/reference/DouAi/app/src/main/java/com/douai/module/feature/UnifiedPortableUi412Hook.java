package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.douai.module.config.AiModelSettings;
import com.douai.module.config.CommentAiSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SidebarSettings;
import com.douai.module.ui.ModuleTheme;
import com.douai.module.ui.PortableWorkAiDialog;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Main-page edge sidebar and manual AI entry independent of private sidebar/AI scene factories. */
public final class UnifiedPortableUi412Hook implements AutoCloseable {
    private static final String HOME = "com.ss.android.ugc.aweme.main.MainActivity";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<View, WeakReference<Object>> holders = new WeakHashMap<>();
    private final Map<View, WeakReference<View>> buttons = new WeakHashMap<>();
    private final Supplier<AiModelSettings> models;
    private final BiConsumer<Activity, String> launcher;
    private final Consumer<String> log;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile LocalOptions options;
    private volatile boolean closed;
    private WeakReference<Object> currentWork = new WeakReference<>(null);
    private Method awemeGetter;
    private Dialog sidebar;
    private Dialog preparation;
    private Dialog activeAnalysis;
    private MediaReadCancellation mediaCancellation;
    private Future<?> preparing;
    private float downX, downY;
    private boolean edgeGesture, claimed;

    private UnifiedPortableUi412Hook(LocalOptions options, Supplier<AiModelSettings> models,
                                    BiConsumer<Activity, String> launcher, Consumer<String> log) {
        this.options = options; this.models = models; this.launcher = launcher;
        this.log = log == null ? ignored -> { } : log;
    }

    public static UnifiedPortableUi412Hook install(XposedInterface framework, ClassLoader loader,
                                                   LocalOptions options, Supplier<AiModelSettings> models,
                                                   BiConsumer<Activity, String> launcher,
                                                   Consumer<String> log) throws Exception {
        UnifiedPortableUi412Hook hook = new UnifiedPortableUi412Hook(options == null
                ? new LocalOptions(Collections.emptyMap()) : options, models, launcher, log);
        try {
            for (String name : new String[]{"VideoViewHolder", "FeedImageViewHolder"}) {
            Class<?> holder = Class.forName("com.ss.android.ugc.aweme.feed.adapter." + name, false, loader);
            Field imageRoot = name.equals("FeedImageViewHolder") ? holder.getDeclaredField("f") : null;
            Method root = imageRoot == null ? holder.getDeclaredMethod("LJIIJ") : null;
            if (imageRoot != null) {
                if (imageRoot.getType() != FrameLayout.class) throw new IllegalStateException("Image card root changed");
                imageRoot.setAccessible(true);
            } else root.setAccessible(true);
            Method aweme = holder.getDeclaredMethod("getAweme"); aweme.setAccessible(true);
            hook.add(framework, holder.getDeclaredMethod("onViewHolderSelected", int.class), chain -> {
                Object result = chain.proceed();
                Object work = aweme.invoke(chain.getThisObject());
                Object card = imageRoot == null ? root.invoke(chain.getThisObject()) : imageRoot.get(chain.getThisObject());
                hook.currentWork = new WeakReference<>(work);
                if (card instanceof View) {
                    hook.holders.put((View) card, new WeakReference<>(chain.getThisObject()));
                    hook.main.post(() -> hook.bindWorkEntry((View) card));
                }
                return result;
            });
            }
            // Only the named MainActivity instance can claim the narrow left-edge gesture.
            Class<?> mainActivity = Class.forName(HOME, false, loader);
            hook.add(framework, mainActivity.getMethod("dispatchTouchEvent", MotionEvent.class), hook::dispatch);
            return hook;
        } catch (Throwable failure) {
            hook.close(); if (failure instanceof Exception) throw (Exception) failure; throw failure;
        }
    }

    public void setOptions(LocalOptions next) {
        options = next == null ? new LocalOptions(Collections.emptyMap()) : next;
        main.post(() -> {
            if (!options.enabled(SidebarSettings.ENABLED) && sidebar != null) sidebar.dismiss();
            if (!options.enabled(CommentAiSettings.TAKEOVER)) {
                cancelPreparation(); if (activeAnalysis != null) activeAnalysis.dismiss(); activeAnalysis = null;
            }
            for (View card : new ArrayList<>(holders.keySet())) bindWorkEntry(card);
        });
    }

    public boolean isActive() { return !closed && !handles.isEmpty(); }

    private Object dispatch(XposedInterface.Chain chain) throws Throwable {
        Object owner = chain.getThisObject();
        if (closed || !(owner instanceof Activity) || !HOME.equals(owner.getClass().getName())
                || !options.enabled(SidebarSettings.ENABLED) || launcher == null) return chain.proceed();
        MotionEvent event = (MotionEvent) chain.getArg(0);
        if (event == null || event.getPointerCount() != 1) { edgeGesture = claimed = false; return chain.proceed(); }
        float density = ((Activity) owner).getResources().getDisplayMetrics().density;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downY = event.getY();
                edgeGesture = downX <= 24 * density; claimed = false; break;
            case MotionEvent.ACTION_MOVE:
                if (claimed) return true;
                if (edgeGesture && eligibleSwipe(event.getX() - downX, event.getY() - downY, density)) {
                    claimed = true; edgeGesture = false;
                    MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                    Object[] args = chain.getArgs().toArray(); args[0] = cancel;
                    try { chain.proceed(args); } finally { cancel.recycle(); }
                    showSidebar((Activity) owner); return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean consumed = claimed; edgeGesture = claimed = false;
                if (consumed) return true; break;
            default: break;
        }
        return chain.proceed();
    }

    static boolean eligibleSwipe(float horizontal, float vertical, float density) {
        return density > 0 && horizontal >= 88 * density && horizontal > Math.abs(vertical) * 2.2f;
    }

    public void showSidebar(Activity activity) {
        if (closed || !options.enabled(SidebarSettings.ENABLED) || activity == null || activity.isFinishing()) return;
        if (sidebar != null && sidebar.isShowing()) return;
        Dialog dialog = new Dialog(activity, ModuleTheme.dialogTheme(activity)); dialog.setOwnerActivity(activity);
        UnifiedPopupFilterHook.markModule(dialog);
        LinearLayout list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL);
        int unit = Math.max(1, Math.round(activity.getResources().getDisplayMetrics().density));
        list.setPadding(16 * unit, 32 * unit, 16 * unit, 16 * unit);
        TextView title = entry(activity, "统一模块", unit); title.setTextSize(20); list.addView(title);
        for (SidebarSettings.Entry item : SidebarSettings.selected(options)) {
            TextView row = entry(activity, item.title, unit); row.setContentDescription(item.title);
            row.setOnClickListener(view -> { dialog.dismiss(); if (!closed) launcher.accept(activity, item.id); });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        if (options.enabled(CommentAiSettings.TAKEOVER)) {
            TextView analyze = entry(activity, "AI 解析当前作品", unit);
            analyze.setOnClickListener(view -> { dialog.dismiss(); showWorkAnalysis(activity, currentWork.get()); });
            list.addView(analyze);
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(list); dialog.setContentView(scroll);
        sidebar = dialog; dialog.setOnDismissListener(ignored -> { if (sidebar == dialog) sidebar = null; });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) { window.setGravity(Gravity.START); window.setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels * 4 / 5, 360 * unit), -1); }
        ModuleTheme.bind(scroll);
    }

    private static TextView entry(Context context, String title, int unit) {
        TextView row = new TextView(context); row.setText(title); row.setTextSize(16); row.setTextColor(ModuleTheme.color(context, 0xff1f272d));
        row.setPadding(10 * unit, 16 * unit, 10 * unit, 16 * unit); return row;
    }

    private void bindWorkEntry(View card) {
        if (closed || !(card instanceof FrameLayout)) return;
        WeakReference<View> reference = buttons.get(card); View existing = reference == null ? null : reference.get();
        if (!options.enabled(CommentAiSettings.TAKEOVER)) {
            if (existing != null && existing.getParent() instanceof ViewGroup) ((ViewGroup) existing.getParent()).removeView(existing);
            buttons.remove(card); return;
        }
        if (existing != null) return;
        TextView button = new TextView(card.getContext()); button.setText("AI 解析"); button.setTag("douai.work_ai");
        button.setTextColor(Color.WHITE); button.setBackgroundColor(0x88000000); button.setTextSize(12);
        int unit = Math.max(1, Math.round(card.getResources().getDisplayMetrics().density));
        button.setPadding(10 * unit, 7 * unit, 10 * unit, 7 * unit);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        params.topMargin = 124 * unit; params.rightMargin = 12 * unit; ((FrameLayout) card).addView(button, params);
        WeakReference<View> weakCard = new WeakReference<>(card);
        button.setOnClickListener(view -> {
            View active = weakCard.get(); if (active == null || !active.isShown()) return;
            WeakReference<Object> owner = holders.get(active); Object holder = owner == null ? null : owner.get();
            try { if (holder != null) showWorkAnalysis(view.getContext(), holder.getClass().getMethod("getAweme").invoke(holder)); }
            catch (ReflectiveOperationException unavailable) { toast(view.getContext(), "当前作品已变化"); }
        });
        buttons.put(card, new WeakReference<>(button));
    }

    public void showWorkAnalysis(Context context, Object work) { analyze(context, work, null); }

    public void openCommentAnalysis(Context context, Object comment) {
        Object work = currentWork.get();
        Object expected = DownloadActionHook.jsonValue(comment, "aweme_id");
        Object actual = DownloadActionHook.jsonValue(work, "aweme_id");
        if (expected == null || actual == null || !expected.toString().equals(actual.toString())) work = null;
        analyze(context, work, comment);
    }

    private void analyze(Context context, Object work, Object comment) {
        Activity activity = activity(context);
        if (closed || !options.enabled(CommentAiSettings.TAKEOVER) || activity == null) return;
        AiModelSettings selected;
        try {
            selected = models.get();
            String preferred = options.text(CommentAiSettings.MODEL_ID, "");
            if (!preferred.isEmpty()) selected = selected.activate(preferred);
            selected.selected().validate(true);
        } catch (RuntimeException unavailable) { toast(context, "请先在模块设置中配置 AI 模型"); return; }
        cancelPreparation();
        String document = work == null ? "未取得当前作品，只分析本次选择的评论文字。" : CommentAiAnalysisSource.document(work);
        Object rawCommentText = comment == null ? null : DownloadActionHook.jsonValue(comment, "text");
        String commentText = rawCommentText instanceof String ? (String) rawCommentText : "";
        if (commentText.length() > 6000) commentText = commentText.substring(0, 6000);
        if (comment != null) document += "\n本次手动选中的评论（待分析资料）：\n" + commentText;
        String prompt = comment == null ? "请结合提供的作品文字和抽样画面解析这条作品，提炼主要内容。"
                : "请解析本次选中的评论及其语境，并给出几条自然、友善的回复建议；不要自动发布。";
        CommentAiAnalysisMedia.Source media = work == null ? null : CommentAiAnalysisMedia.capture(work);
        List<String> selectedCommentImages;
        try { selectedCommentImages = comment == null ? Collections.emptyList() : AutomationReplyMedia.commentImageUrls(comment); }
        catch (RuntimeException unavailable) { selectedCommentImages = Collections.emptyList(); }
        List<String> commentImages = selectedCommentImages;
        MediaReadCancellation cancellation = new MediaReadCancellation(30_000); mediaCancellation = cancellation;
        AlertDialog waiting = new AlertDialog.Builder(activity, ModuleTheme.alertTheme(activity))
                .setTitle("正在准备 AI 解析资料").setView(new ProgressBar(activity))
                .setNegativeButton("取消", (dialog, which) -> cancelPreparation()).create();
        UnifiedPopupFilterHook.markModule(waiting);
        preparation = waiting; waiting.setOnCancelListener(ignored -> cancelPreparation()); waiting.show();
        AiModelSettings snapshot = selected; String fixedDocument = document;
        preparing = worker.submit(() -> {
            List<ChatImage> images = Collections.emptyList(); String source = fixedDocument;
            try {
                if (!commentImages.isEmpty()) {
                    cancellation.start(); images = AutomationImages.load(commentImages, cancellation);
                    source += "\n本次附图来自本次手动选择的评论；没有提供该作品的视频画面。";
                } else if (media != null) {
                    CommentAiAnalysisMedia.Result loaded = CommentAiAnalysisMedia.load(activity.getApplicationContext(), media, cancellation);
                    images = loaded.images; source += "\n" + loaded.document;
                }
                cancellation.check();
                List<ChatImage> fixedImages = images; String finalSource = source;
                main.post(() -> {
                    if (closed || mediaCancellation != cancellation || !options.enabled(CommentAiSettings.TAKEOVER) || activity.isFinishing()) return;
                    preparation = null; mediaCancellation = null; preparing = null; waiting.dismiss();
                    try {
                        DouyinSearch search = null;
                        if (options.enabled(CommentAiSettings.SEARCH)) {
                            search = HostDouyinSearch.create(activity.getClass().getClassLoader(),
                                    com.douai.module.scan.HostAdaptation.currentProfile());
                        }
                        activeAnalysis = PortableWorkAiDialog.show(activity, snapshot, finalSource, prompt, fixedImages, search);
                    }
                    catch (ReflectiveOperationException | RuntimeException unavailable) { toast(activity, "无法打开 AI 解析界面"); }
                });
            } catch (Exception unavailable) {
                main.post(() -> { if (mediaCancellation == cancellation) { cancelPreparation(); toast(activity, "AI 资料读取未完成，请重试"); } });
            }
        });
    }

    private void cancelPreparation() {
        MediaReadCancellation cancellation = mediaCancellation; mediaCancellation = null;
        if (cancellation != null) cancellation.cancel();
        Future<?> task = preparing; preparing = null; if (task != null) task.cancel(true);
        Dialog dialog = preparation; preparation = null; if (dialog != null) dialog.dismiss();
    }
    private static Activity activity(Context context) { for (int i = 0; i < 12 && context != null; i++) { if (context instanceof Activity) return (Activity) context; if (!(context instanceof ContextWrapper)) break; Context next = ((ContextWrapper) context).getBaseContext(); if (next == context) break; context = next; } return null; }
    private void toast(Context context, String text) { main.post(() -> { if (!closed) Toast.makeText(context, text, Toast.LENGTH_LONG).show(); }); }
    private void add(XposedInterface framework, Method method, XposedInterface.Hooker callback) { XposedInterface.HookHandle h = framework.hook(method).setId("DouAi.Unified412.Portable." + handles.size()).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(callback); if (h == null) throw new IllegalStateException("Missing portable hook"); handles.add(h); }
    @Override public void close() { closed = true; worker.shutdownNow(); for (XposedInterface.HookHandle h : handles) try { h.unhook(); } catch (RuntimeException ignored) { } handles.clear(); main.post(() -> { cancelPreparation(); if (activeAnalysis != null) activeAnalysis.dismiss(); if (sidebar != null) sidebar.dismiss(); for (WeakReference<View> ref : buttons.values()) { View v = ref.get(); if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v); } buttons.clear(); holders.clear(); }); }
}
