package com.douai.module.feature;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import io.github.libxposed.api.XposedInterface;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.diagnostics.DouAiLog;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Adds an optional long-press menu to Douyin's native download row. */
public final class DownloadActionHook implements AutoCloseable {
    private final Method nativeClickMethod;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<View, WeakReference<Object>> rows = new WeakHashMap<>();
    private final Set<View> installedRows = Collections.newSetFromMap(new WeakHashMap<>());
    private final Consumer<String> log;
    private volatile boolean enabled;
    private volatile FeatureSettings settings;
    private volatile LocalOptions options = new LocalOptions(Collections.emptyMap());
    private AutoCloseable thirdPartyDownloadDialog;
    private volatile OfficialDownloadDialog officialDownloadDialog;
    private final java.util.concurrent.atomic.AtomicLong selectionGeneration = new java.util.concurrent.atomic.AtomicLong();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile int nativeClicks;
    private volatile int sharedClicks;
    private Field sharedWorkField;
    private volatile boolean sharedEntryInstalled;
    private volatile boolean playbackSourceAvailable;
    private volatile boolean downloadSourceAvailable;
    private volatile int pickerOpened;
    private volatile int directRequests;
    private volatile String downloadStage = "等待下载点击";
    private volatile String downloadDetail = "模块尚未收到下载点击；开启设置不代表下载动作已执行。";
    private volatile PlaybackSourceCaptureHook playbackSourceCapture;

    private DownloadActionHook(Method nativeClickMethod, FeatureSettings settings, Consumer<String> log) {
        this.nativeClickMethod = nativeClickMethod;
        this.settings = settings;
        this.enabled = settings.downloadEnabled;
        this.log = log;
    }

    /**
     * Installs an after-hook on the native item factory. Normal taps remain attached to Douyin's
     * original view; the custom menu calls the native click method for video and gallery actions.
     */
    public static DownloadActionHook install(XposedInterface framework,
                                             Method moduleNameMethod,
                                             Method viewFactoryMethod,
                                             Method nativeClickMethod,
                                             boolean enabled,
                                             Consumer<String> log) {
        FeatureSettings settings = new FeatureSettings(enabled, false, false,
                false, false, false, false, false, "", false, 0, false, 0,
                false, false, false, false, false);
        return install(framework, moduleNameMethod, viewFactoryMethod, nativeClickMethod,
                settings, log);
    }

    public static DownloadActionHook install(XposedInterface framework,
                                             Method moduleNameMethod,
                                             Method viewFactoryMethod,
                                             Method nativeClickMethod,
                                             FeatureSettings settings,
                                             Consumer<String> log) {
        validateMethods(moduleNameMethod, viewFactoryMethod, nativeClickMethod);
        if (framework == null) throw new IllegalArgumentException("framework is required");
        nativeClickMethod.setAccessible(true);

        DownloadActionHook hook = new DownloadActionHook(nativeClickMethod,
                settings == null ? FeatureSettings.defaults() : settings,
                log == null ? ignored -> { } : log);
        try {
            XposedInterface.HookHandle handle = framework.hook(viewFactoryMethod)
                    .setId("DouAi.DownloadAction.LongPressMenu")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::afterCreateView);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            XposedInterface.HookHandle clickHandle = framework.hook(nativeClickMethod)
                    .setId("DouAi.DownloadAction.LocalSave")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::beforeNativeClick);
            if (clickHandle == null) throw new IllegalStateException("Framework returned an empty download click handle");
            hook.handles.add(clickHandle);
            DouAiLog.featureState("download", hook.enabled, "ready", "installed", "hooks=menu_factory,native_click");
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException("Could not install native download menu hook", failure);
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        DouAiLog.featureState("download", enabled, "ready", "installed", "existing_rows=" + rows.size());
        if (!enabled) { closeThirdPartyDialog(); closeOfficialDialog(); }
        if (Looper.myLooper() == Looper.getMainLooper()) updateRows(enabled);
        else mainHandler.post(() -> updateRows(this.enabled));
    }

    public void setSettings(FeatureSettings settings) {
        this.settings = settings == null ? FeatureSettings.defaults() : settings;
        setEnabled(this.settings.downloadEnabled);
    }

    public void setOptions(LocalOptions options) {
        closeThirdPartyDialog();
        closeOfficialDialog();
        this.options = options == null ? new LocalOptions(Collections.emptyMap()) : options;
        DouAiLog.event("download", "configuration", "download_options_updated", "third_party=" + new ThirdPartyDownloadSettings(this.options).thirdParty
                + "; watermark_removal=" + settings.removeWatermark + "; custom_name=" + this.options.enabled("download_custom_name")
                + "; remove_limits=" + this.options.enabled("remove_download_limits") + "; tree_selected=" + !this.options.downloadSaveTree().isEmpty());
    }

    public void setPlaybackSourceCapture(PlaybackSourceCaptureHook capture) {
        playbackSourceCapture = capture;
    }

    /** The pinned 41.2 share download action is also used outside the long-press panel. */
    public void installSharedDownloadEntry(XposedInterface framework, Method execute, Field workField) throws Exception {
        if (sharedEntryInstalled) return;
        if (HostAdaptation.currentProfile() != HostAdaptation.PROFILE_412 || framework == null || execute == null || workField == null
                || !"execute".equals(execute.getName()) || execute.getParameterCount() != 2
                || execute.getParameterTypes()[0] != Context.class
                || !"com.ss.android.ugc.aweme.sharer.ui.SharePackage".equals(execute.getParameterTypes()[1].getName())
                || execute.getReturnType() != void.class || workField.getDeclaringClass() != execute.getDeclaringClass()
                || !"b".equals(workField.getName())) throw new IllegalArgumentException("Unexpected shared download action contract");
        workField.setAccessible(true);
        sharedWorkField = workField;
        XposedInterface.HookHandle handle = framework.hook(execute).setId("DouAi.DownloadAction.SharedSave")
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    if (!enabled) return chain.proceed();
                    sharedClicks++;
                    Object context = chain.getArg(0);
                    if (context instanceof Context) startUnifiedDownloadSafely((Context)context, chain.getThisObject());
                    else downloadState("下载入口不可用", "分享下载回调没有提供有效 Context。", "shared_context_missing");
                    return null;
                });
        if (handle == null) throw new IllegalStateException("Framework returned an empty shared-download handle");
        handles.add(handle);
        sharedEntryInstalled = true;
    }

    private Object beforeNativeClick(XposedInterface.Chain chain) throws Throwable {
        DouAiLog.event("download", "trigger", "native_download_click", "enabled=" + enabled);
        nativeClicks++;
        if (enabled && HostAdaptation.currentProfile() == HostAdaptation.PROFILE_412) {
            Object view = chain.getArg(0);
            if (view instanceof View) startUnifiedDownloadSafely(((View)view).getContext(), chain.getThisObject());
            else downloadState("下载入口不可用", "下载回调未提供有效视图。", "missing_click_view");
            return null;
        }
        if (enabled && new ThirdPartyDownloadSettings(options).thirdParty) {
            DouAiLog.event("download", "route", "selected_third_party_provider", "official_provider_invoked=false");
            Object view = chain.getArg(0);
            if (view instanceof View) {
                try {
                    startThirdParty(((View) view).getContext(), currentAweme(chain.getThisObject()), chain.getThisObject());
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    DouAiLog.failure("download", "read_third_party_source", failure);
                    showToast(((View) view).getContext(), "无法读取当前作品链接");
                }
            }
            return null;
        }
        if (!enabled || !(settings.removeWatermark || options.enabled("download_custom_name")
                || options.enabled("remove_download_limits") || !options.downloadSaveTree().isEmpty())) {
            DouAiLog.event("download", "route", !enabled ? "module_disabled" : "native_default_settings",
                    "host_native_action=true; host_result=unconfirmed");
            return chain.proceed();
        }
        Context context = null;
        Object selectedWork = null;
        List<MediaAsset> assets = Collections.emptyList();
        try {
            Object aweme = currentAweme(chain.getThisObject());
            selectedWork = aweme;
            Object view = chain.getArg(0);
            if (aweme != null && view instanceof View) {
                context = ((View) view).getContext();
                assets = mediaAssets(aweme, settings.removeWatermark, options);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            DouAiLog.failure("download", "read_media_sources", failure);
            safeLog("Local download source unavailable: " + failure.getMessage());
        }
        if (!assets.isEmpty()) {
            if (options.downloadSaveTree().isEmpty() && Build.VERSION.SDK_INT < 29
                    && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                DouAiLog.event("download", "route", "legacy_storage_permission_missing", "host_native_action=true; host_result=unconfirmed");
                return chain.proceed();
            }
            try {
                saveAssets(context, assets, selectedWork);
                safeLog("Queued " + assets.size() + " existing media resources from native download click");
            } catch (RuntimeException failure) {
                DouAiLog.failure("download", "enqueue_media", failure);
                safeLog("Local download queue failed: " + failure.getMessage());
                showToast(context, "创建下载任务失败");
            }
            return null;
        }
        if (!options.downloadSaveTree().isEmpty()) {
            DouAiLog.event("download", "skipped", "selected_tree_has_no_media_source", "task_created=false");
            if (context != null) showToast(context, "当前作品没有可保存到所选目录的媒体来源");
            return null;
        }
        DouAiLog.event("download", "route", "no_local_media_sources", "host_native_action=true; host_result=unconfirmed");
        return chain.proceed();
    }

    private void startUnifiedDownloadSafely(Context context, Object module) {
        try { startUnifiedDownload(context, module); }
        catch (RuntimeException | LinkageError failure) {
            closeOfficialDialog();
            DouAiLog.failure("download", "open_module_download", failure);
            downloadState("下载界面未能打开", "模块下载入口发生 " + failure.getClass().getSimpleName()
                    + "；请在模块主页复制下载诊断。", "official_picker_failed");
            showToast(context, downloadDetail);
        }
    }

    /** All 41.2 entry points share the source picker instead of falling back to host rejection. */
    private void startUnifiedDownload(Context context, Object module) {
        Object work;
        try { work = currentWork(module); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            DouAiLog.failure("download", "read_current_work", failure);
            downloadState("无法读取当前作品", "请重新打开当前作品的下载面板；可在模块主页复制下载诊断。", "current_work_unavailable");
            showToast(context, downloadDetail); return;
        }
        if (work == null) {
            downloadState("无法读取当前作品", "下载入口没有当前作品数据，请重新打开作品后重试。", "current_work_missing");
            showToast(context, downloadDetail); return;
        }
        String expectedId = awemeId(work);
        Object expectedWork = work;
        openWork(context, work, () -> {
            try {
                Object current = currentWork(module);
                return expectedId == null ? current == expectedWork : expectedId.equals(awemeId(current));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
        }, true);
    }

    public boolean isEnabled() { return enabled; }

    public void openForWork(Context context, Object work, java.util.function.BooleanSupplier valid) {
        if (!enabled || work == null || context == null) return;
        try { openWork(context, work, valid == null ? () -> true : valid, false); }
        catch (RuntimeException | LinkageError failure) {
            closeOfficialDialog();
            downloadState("下载界面未能打开", "请返回作品页面重试；" + failure.getClass().getSimpleName(), "injected_picker_failed");
            showToast(context, downloadDetail);
        }
    }

    private void openWork(Context context, Object work, java.util.function.BooleanSupplier valid, boolean configuredProvider) {
        closeThirdPartyDialog();
        closeOfficialDialog();
        if (!valid.getAsBoolean()) return;
        if (configuredProvider && new ThirdPartyDownloadSettings(options).thirdParty) {
            downloadState("第三方下载入口已接管", "使用配置的第三方解析服务。", "third_party_selected");
            thirdPartyDownloadDialog = ThirdPartyDownload.startOwned(context, work, options, this::safeLog,
                    () -> enabled && valid.getAsBoolean() && new ThirdPartyDownloadSettings(options).thirdParty);
            return;
        }
        if (options.downloadSaveTree().isEmpty() && Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            downloadState("缺少保存权限", "请授予抖音存储权限，或在抖音内选择保存文件夹。", "storage_permission_missing");
            showToast(context, downloadDetail); return;
        }
        // One snapshot controls this selection, including the FeatureSettings watermark option.
        LocalOptions selected = options.withOverrides(configuredProvider ? Map.of("remove_watermark", settings.removeWatermark)
                : Map.of("remove_watermark", settings.removeWatermark, "download_method", "official"));
        PlaybackSourceCaptureHook capture = playbackSourceCapture;
        List<String> captured = capture == null ? Collections.emptyList() : capture.playbackUrlsFor(work);
        playbackSourceAvailable = findPlaybackVideoUrl(work) != null;
        playbackSourceAvailable |= !captured.isEmpty();
        downloadSourceAvailable = UnifiedMediaActions.videoSource(work, UnifiedMediaActions.Encoding.DOWN) != null;
        Object images = jsonValue(work, "images");
        boolean hasImages = images instanceof Iterable<?> && ((Iterable<?>)images).iterator().hasNext();
        if (!playbackSourceAvailable && !downloadSourceAvailable && !hasImages
                && findAudioUrl(work) == null && UnifiedMediaActions.avatar(work) == null) {
            downloadState("没有可保存的媒体来源", "当前作品未提供可保存来源，也没有与该作品视频 ID 匹配的完整播放源。", "no_media_sources");
            showToast(context, "当前作品没有可保存的媒体地址"); return;
        }
        if (selected.officialDownloadDirect()) {
            List<MediaAsset> assets = mediaAssets(work, settings.removeWatermark, selected);
            if (assets.isEmpty() && !hasImages && !captured.isEmpty()) assets = List.of(new MediaAsset(captured.get(0),
                    MediaNaming.forAweme(work, selected, "video", 0, "mp4"), false));
            if (!assets.isEmpty()) {
                directRequests++;
                downloadState("已提交直接保存", "已读取 " + assets.size() + " 个媒体来源；实际保存结果请查看下载任务。", "direct_sources_ready");
                saveAssets(context, assets, work); return;
            }
            // A Down-only item can still be offered explicitly; it must not be called clean media.
            downloadState("等待选择媒体来源", "直接保存没有符合当前设置的来源，已打开来源选择。", "direct_source_missing_picker_available");
        } else downloadState("已打开媒体选择", "请在视频、图片、音频等分类中选择保存；实际结果请查看下载任务。", "official_picker_opened");
        long selectedGeneration = selectionGeneration.get();
        Activity activity = ownerActivity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            downloadState("下载界面不可用", "当前下载入口没有可显示选择器的抖音页面，请返回作品页面后重试。", "picker_activity_unavailable");
            showToast(context, downloadDetail); return;
        }
        OfficialDownloadDialog chooser = new OfficialDownloadDialog(activity, work, selected, this::safeLog, () -> {
            // Share actions can be temporary objects. The selection owns this reference until
            // close() clears its validity callback, so a dismissed host panel cannot invalidate
            // an otherwise live picker merely by releasing its own action instance.
            if (!enabled || selectionGeneration.get() != selectedGeneration
                    || activity.isFinishing() || activity.isDestroyed()
                    || configuredProvider && new ThirdPartyDownloadSettings(options).thirdParty) return false;
            return valid.getAsBoolean();
        });
        officialDownloadDialog = chooser;
        chooser.setPlaybackUrls(() -> captured);
        pickerOpened++;
        chooser.show();
    }

    private static Activity ownerActivity(Context context) {
        for (int depth = 0; context instanceof ContextWrapper && depth < 16; depth++) {
            if (context instanceof Activity) return (Activity)context;
            Context next = ((ContextWrapper)context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private void downloadState(String stage, String detail, String reason) {
        downloadStage = stage; downloadDetail = detail;
        DouAiLog.event("download", "entry_state", reason, "native_clicks=" + nativeClicks + "; picker_opened=" + pickerOpened
                + "; direct_requests=" + directRequests + "; host_fallback=false");
    }

    public void appendDiagnostics(Bundle snapshot) {
        snapshot.putInt("download_native_clicks", nativeClicks);
        snapshot.putInt("download_shared_clicks", sharedClicks);
        snapshot.putBoolean("download_shared_hook_installed", sharedEntryInstalled);
        snapshot.putBoolean("download_playback_source_available", playbackSourceAvailable);
        snapshot.putBoolean("download_official_source_available", downloadSourceAvailable);
        snapshot.putInt("download_picker_opened", pickerOpened);
        snapshot.putInt("download_direct_requests", directRequests);
        snapshot.putString("download_entry_stage", downloadStage);
        snapshot.putString("download_entry_detail", downloadDetail);
    }

    public String describeDiagnostics() {
        return "下载入口：" + downloadStage + "\n" + downloadDetail
                + "\n模块下载开关：" + enabled + "；分享入口已安装：" + sharedEntryInstalled
                + "\n长按面板点击：" + nativeClicks + "；分享面板点击：" + sharedClicks
                + "；选择器打开：" + pickerOpened + "；直接保存请求：" + directRequests
                + "\n最近作品来源：播放地址=" + playbackSourceAvailable + "，官方下载地址=" + downloadSourceAvailable
                + "\n直接保存：" + options.officialDownloadDirect() + "；去水印：" + settings.removeWatermark
                + "；本地下载限制选项：" + options.enabled("remove_download_limits");
    }

    private Object afterCreateView(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        if (!(result instanceof View)) return result;
        View row = (View) result;
        try {
            Object module = chain.getThisObject();
            if (row.isLongClickable()) {
                safeLog("Skipped download menu because the native row already has a long-click action");
                return result;
            }
            synchronized (rows) {
                rows.put(row, new WeakReference<>(module));
                if (enabled) attachMenu(row, module);
            }
        } catch (Throwable failure) {
            safeLog("Could not attach download menu: " + failure.getMessage());
        }
        return result;
    }

    private void updateRows(boolean attach) {
        synchronized (rows) {
            Iterator<Map.Entry<View, WeakReference<Object>>> iterator = rows.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<View, WeakReference<Object>> entry = iterator.next();
                View row = entry.getKey();
                Object module = entry.getValue().get();
                if (module == null) {
                    installedRows.remove(row);
                    iterator.remove();
                } else if (attach) {
                    attachMenu(row, module);
                } else {
                    detachMenu(row);
                }
            }
        }
    }

    private void attachMenu(View row, Object module) {
        if (installedRows.contains(row)) return;
        if (row.isLongClickable()) {
            safeLog("Skipped download menu because the native row gained a long-click action");
            return;
        }
        WeakReference<Object> weakModule = new WeakReference<>(module);
        try {
            row.setOnLongClickListener(view -> {
                Object currentModule = weakModule.get();
                return currentModule != null && showMenu(currentModule, view);
            });
            installedRows.add(row);
        } catch (Throwable failure) {
            safeLog("Could not install download menu listener: " + failure.getMessage());
        }
    }

    private void detachMenu(View row) {
        if (installedRows.remove(row)) {
            try {
                row.setOnLongClickListener(null);
                row.setLongClickable(false);
            } catch (Throwable failure) {
                safeLog("Could not remove download menu listener: " + failure.getMessage());
            }
        }
    }

    private boolean showMenu(Object module, View row) {
        if (!enabled) { DouAiLog.event("download_menu", "skipped", "disabled", "trigger=long_press"); return false; }
        Context context = row.getContext();
        try {
            Object aweme = currentAweme(module);
            if (aweme == null) { DouAiLog.event("download_menu", "skipped", "current_work_missing", "trigger=long_press"); return false; }
            List<MenuAction> actions = buildMenuActions(aweme);
            if (actions.isEmpty()) { DouAiLog.event("download_menu", "skipped", "no_available_actions", "trigger=long_press"); return false; }
            String[] labels = new String[actions.size()];
            for (int i = 0; i < actions.size(); i++) labels[i] = actions.get(i).label;
            AlertDialog downloadMenu = new AlertDialog.Builder(context)
                    .setTitle("下载选项")
                    .setItems(labels, (dialog, which) -> selectAction(
                            module, row, actions.get(which), context, aweme))
                    .setNegativeButton("取消", null)
                    .create();
            UnifiedPopupFilterHook.markModule(downloadMenu);
            downloadMenu.show();
            DouAiLog.event("download_menu", "shown", "long_press", "available_actions=" + actions.size());
            return true;
        } catch (Throwable failure) {
            DouAiLog.failure("download_menu", "show", failure);
            safeLog("Could not show native download menu: " + failure.getMessage());
            return false;
        }
    }

    private void selectAction(Object module, View row, MenuAction action, Context context,
                              Object aweme) {
        DouAiLog.event("download_menu", "selected", "user_choice", "kind=" + action.kind + "; business_result=pending");
        if (action.kind == MenuAction.THIRD_PARTY) {
            startThirdParty(context, aweme, module);
            return;
        }
        if (action.kind == MenuAction.NATIVE) {
            if (HostAdaptation.currentProfile() == HostAdaptation.PROFILE_412) {
                nativeClicks++;
                startUnifiedDownloadSafely(context, module); return;
            }
            try {
                invokeNativeDownload(nativeClickMethod, module, row);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                safeLog("Douyin native download action failed: " + failure.getMessage());
                showToast(context, "调用抖音原生下载失败");
            }
            return;
        }
        if (action.kind == MenuAction.AUDIO || action.kind == MenuAction.CLEAN_VIDEO
                || action.kind == MenuAction.PLAY_VIDEO) {
            enqueueDownload(context, action.url, action.fileName, action.kind == MenuAction.AUDIO, aweme);
        } else if (action.kind == MenuAction.GALLERY) {
            saveAssets(context, mediaAssets(aweme, settings.removeWatermark, options), aweme);
        } else if (action.kind == MenuAction.COPY_SOURCE) {
            copySourceData(context, aweme);
        }
    }

    private List<MenuAction> buildMenuActions(Object aweme) {
        ArrayList<MenuAction> actions = new ArrayList<>();
        if (new ThirdPartyDownloadSettings(options).thirdParty) {
            actions.add(new MenuAction("第三方下载", MenuAction.THIRD_PARTY, null, null));
            actions.add(new MenuAction("复制作品源数据", MenuAction.COPY_SOURCE, null, null));
            return actions;
        }
        int awemeType = readIntMethod(aweme, "getAwemeType", -1);
        String nativeLabel = HostAdaptation.currentProfile() == HostAdaptation.PROFILE_412 ? "打开融合媒体下载"
                : awemeType == 68 ? "使用抖音原生保存图集"
                : awemeType < 0 ? "使用抖音原生保存此作品" : "使用抖音原生保存视频";
        if (!options.downloadSaveTree().isEmpty()) nativeLabel = "保存当前作品到所选目录";
        actions.add(new MenuAction(nativeLabel, MenuAction.NATIVE, null, safeFileName(aweme)));

        String audioUrl = findAudioUrl(aweme);
        if (audioUrl != null) {
            actions.add(new MenuAction("下载音频", MenuAction.AUDIO, audioUrl,
                    MediaNaming.forAweme(aweme, options, "audio", 0, "mp3")));
        }
        String playbackUrl = findPlaybackVideoUrl(aweme);
        if (playbackUrl != null) {
            actions.add(new MenuAction("保存播放源视频", MenuAction.PLAY_VIDEO, playbackUrl,
                    MediaNaming.forAweme(aweme, options, "video", 0, "mp4")));
        }
        if (settings.removeWatermark) {
            String cleanUrl = findExplicitlyUnwatermarkedVideoUrl(aweme);
            if (cleanUrl != null) {
                actions.add(new MenuAction("下载已标记无水印的视频", MenuAction.CLEAN_VIDEO,
                        cleanUrl, MediaNaming.forAweme(aweme, options, "video", 0, "mp4")));
            }
        }
        if (awemeType == 68 && !mediaAssets(aweme, settings.removeWatermark, options).isEmpty()) {
            actions.add(new MenuAction(settings.removeWatermark ? "保存无水印图集" : "保存图集",
                    MenuAction.GALLERY, null, null));
        }
        actions.add(new MenuAction("复制作品源数据", MenuAction.COPY_SOURCE, null, null));
        return actions;
    }

    private static Object currentAweme(Object module) throws ReflectiveOperationException {
        String getter = com.douai.module.scan.HostResourceProfile.downloadAwemeGetter(
                com.douai.module.scan.HostAdaptation.currentProfile());
        Method method = findNoArgMethod(module.getClass(), getter);
        if (method == null) return null;
        method.setAccessible(true);
        return method.invoke(module);
    }

    private Object currentWork(Object owner) throws ReflectiveOperationException {
        Field field = sharedWorkField;
        return field != null && owner != null && field.getDeclaringClass().isInstance(owner) ? field.get(owner) : currentAweme(owner);
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // Search inherited panel-module classes for the active Aweme accessor.
            }
        }
        return null;
    }

    private static int readIntMethod(Object target, String name, int fallback) {
        try {
            Method method = target.getClass().getMethod(name);
            Object value = method.invoke(target);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private String findAudioUrl(Object aweme) {
        try {
            Object music = fieldByJsonName(aweme, "music");
            Object playUrl = music == null ? null : fieldByJsonName(music, "play_url");
            return firstUrl(playUrl);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Could not resolve audio URL: " + failure.getMessage());
            return null;
        }
    }

    private String findExplicitlyUnwatermarkedVideoUrl(Object aweme) {
        try {
            Object video = fieldByJsonName(aweme, "video");
            if (video == null || !Boolean.FALSE.equals(fieldByJsonNameValue(video, "has_watermark"))) {
                return null;
            }
            String playback = playbackUrl(video);
            return playback != null ? playback : firstUrl(fieldByJsonName(video, "download_addr"));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Could not resolve an explicitly unwatermarked video URL: " + failure.getMessage());
            return null;
        }
    }

    static final class MediaAsset {
        final String url;
        final String name;
        final boolean image;
        MediaAsset(String url, String name, boolean image) {
            this.url = url;
            this.name = name;
            this.image = image;
        }
    }

    static List<MediaAsset> mediaAssets(Object aweme, boolean clean, LocalOptions options) {
        ArrayList<MediaAsset> assets = new ArrayList<>();
        Object images = jsonValue(aweme, "images");
        if (images instanceof Iterable<?>) {
            int index = 0;
            for (Object image : (Iterable<?>) images) {
                index++;
                String url = clean ? firstUrlValue(jsonValue(image, "watermark_free_download_url_list")) : null;
                if (url == null) url = firstUrlValue(jsonValue(image, "url_list"));
                if (!clean && url == null) url = firstUrlValue(jsonValue(image, "download_url_list"));
                if (url == null) {
                    // Saving a partial gallery silently would lose the image order.
                    return Collections.emptyList();
                }
                assets.add(new MediaAsset(url,
                        MediaNaming.forAweme(aweme, options, "picture", index, imageExtension(url)), true));
            }
        }
        if (!assets.isEmpty()) return assets;
        String url = findPlaybackVideoUrl(aweme);
        if (url == null && !clean) {
            url = UnifiedMediaActions.videoSource(aweme, UnifiedMediaActions.Encoding.DOWN);
        }
        if (url != null) assets.add(new MediaAsset(url,
                MediaNaming.forAweme(aweme, options, "video", 0, "mp4"), false));
        return assets;
    }

    private void saveAssets(Context context, List<MediaAsset> assets, Object work) {
        AnalyticsActionCapture analytics = AnalyticsActionCapture.capture(context, work);
        if (!assets.isEmpty()) analytics.manual("download_request", "下载任务请求", "request");
        com.douai.module.analytics.LocalAnalytics.FlowOperation flow = analytics.operation("download");
        if (flow != null) { flow.stage("entry"); flow.stage("request"); }
        int taskIndex = 0;
        boolean anyAccepted = false;
        for (MediaAsset asset : assets) {
            taskIndex++;
            boolean accepted = asset.image
                    ? MediaStoreDownloader.downloadImage(context, asset.url, asset.name, options.downloadSaveTree(), this::safeLog, flow, taskIndex)
                    : MediaStoreDownloader.download(context, asset.url, asset.name, false, options.downloadSaveTree(), this::safeLog, flow, taskIndex);
            if (!accepted) showToast(context, "下载地址无效");
            anyAccepted |= accepted;
        }
        if (anyAccepted) analytics.manual("download_queued", "下载任务已加入", "accepted");
    }

    private static String imageExtension(String url) {
        String path;
        try { path = URI.create(url).getPath().toLowerCase(java.util.Locale.ROOT); }
        catch (IllegalArgumentException failure) { return "jpg"; }
        if (path == null) return "jpg";
        for (String extension : new String[]{"png", "webp", "gif", "heic"}) {
            if (path.endsWith("." + extension) || path.endsWith("~" + extension)) return extension;
        }
        return "jpg";
    }

    static Object jsonValue(Object target, String name) {
        if (target == null) return null;
        try { return fieldByJsonName(target, name); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    static String firstUrlValue(Object value) {
        Object raw = value instanceof Iterable<?> ? value : jsonValue(value, "url_list");
        if (!(raw instanceof Iterable<?>)) return null;
        for (Object item : (Iterable<?>) raw) {
            if (item instanceof String && MediaStoreDownloader.isHttpUrl((String) item)) return (String) item;
        }
        return null;
    }

    static String findPlaybackVideoUrl(Object aweme) {
        if (aweme == null) return null;
        try {
            Object video = fieldByJsonName(aweme, "video");
            return video == null ? null : playbackUrl(video);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String playbackUrl(Object video) throws ReflectiveOperationException {
        for (String key : new String[]{"play_addr", "play_addr_h264", "play_addr_265",
                "play_addr_bytevc1", "play_addr_lowbr"}) {
            String url = firstUrl(fieldByJsonName(video, key));
            if (url != null) return url;
        }
        // Douyin may supply playback sources only on its bitrate entries.
        Object bitRates = fieldByJsonName(video, "bit_rate");
        if (bitRates instanceof Iterable<?>) {
            for (Object bitRate : (Iterable<?>) bitRates) {
                if (bitRate == null) continue;
                for (String key : new String[]{"play_addr", "play_addr_265", "play_addr_bytevc1"}) {
                    String url = firstUrl(fieldByJsonName(bitRate, key));
                    if (url != null) return url;
                }
            }
        }
        return null;
    }

    private static Object fieldByJsonName(Object target, String jsonName)
            throws ReflectiveOperationException {
        Field field = findFieldByJsonName(target.getClass(), jsonName);
        if (field == null) return null;
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object fieldByJsonNameValue(Object target, String jsonName)
            throws ReflectiveOperationException {
        return fieldByJsonName(target, jsonName);
    }

    static Field findFieldByJsonName(Class<?> type, String jsonName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                String mapped = serializedName(field);
                if (jsonName.equals(mapped) || (mapped == null && jsonName.equals(field.getName()))) {
                    return field;
                }
            }
        }
        return null;
    }

    private static String serializedName(Field field) {
        for (java.lang.annotation.Annotation annotation : field.getAnnotations()) {
            if (!"com.google.gson.annotations.SerializedName".equals(
                    annotation.annotationType().getName())) continue;
            try {
                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                return value instanceof String ? (String) value : null;
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstUrl(Object urlModel) throws ReflectiveOperationException {
        Object rawUrls = urlModel == null ? null : fieldByJsonName(urlModel, "url_list");
        if (!(rawUrls instanceof Iterable<?>)) return null;
        for (Object item : (Iterable<?>) rawUrls) {
            if (!(item instanceof String)) continue;
            String value = (String) item;
            if (isHttpUrl(value)) return value;
        }
        return null;
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) return false;
        try {
            String scheme = URI.create(value).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void enqueueDownload(Context context, String url, String fileName, boolean audio, Object work) {
        AnalyticsActionCapture analytics = AnalyticsActionCapture.capture(context, work);
        analytics.manual("download_request", "下载任务请求", "request");
        com.douai.module.analytics.LocalAnalytics.FlowOperation flow = analytics.operation("download");
        if (flow != null) { flow.stage("entry"); flow.stage("request"); }
        if (!MediaStoreDownloader.download(context, url, fileName, audio,
                options.downloadSaveTree(), message -> safeLog(message), flow, 1)) {
            showToast(context, "下载地址无效");
        } else {
            analytics.manual("download_queued", "下载任务已加入", "accepted");
        }
    }

    private void copySourceData(Context context, Object aweme) {
        try {
            ClassLoader loader = aweme.getClass().getClassLoader();
            Class<?> gsonClass = Class.forName("com.google.gson.Gson", true, loader);
            Object gson = gsonClass.getConstructor().newInstance();
            String json = (String) gsonClass.getMethod("toJson", Object.class).invoke(gson, aweme);
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("系统剪贴板不可用");
            clipboard.setPrimaryClip(ClipData.newPlainText("DouAi Aweme", json));
            showToast(context, "作品数据已复制");
        } catch (Throwable failure) {
            safeLog("Could not copy Aweme source data: " + failure.getMessage());
            showToast(context, "复制作品数据失败");
        }
    }

    private static String safeFileName(Object aweme) {
        Object id = null;
        Object desc = null;
        try {
            id = fieldByJsonName(aweme, "aweme_id");
            desc = fieldByJsonName(aweme, "desc");
        } catch (ReflectiveOperationException ignored) {
            // Use a stable fallback if the host model changes.
        }
        String value = desc instanceof String ? (String) desc : "DouAi";
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (value.length() > 48) value = value.substring(0, 48);
        if (value.isEmpty()) value = "DouAi";
        String suffix = id == null ? "" : "_" + id;
        return (value + suffix).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    private static final class MenuAction {
        static final int NATIVE = 0;
        static final int AUDIO = 1;
        static final int CLEAN_VIDEO = 2;
        static final int COPY_SOURCE = 3;
        static final int PLAY_VIDEO = 4;
        static final int GALLERY = 5;
        static final int THIRD_PARTY = 6;

        final String label;
        final int kind;
        final String url;
        final String fileName;

        MenuAction(String label, int kind, String url, String fileName) {
            this.label = label;
            this.kind = kind;
            this.url = url;
            this.fileName = fileName;
        }
    }

    static void invokeNativeDownload(Method nativeClickMethod, Object module, View row)
            throws ReflectiveOperationException {
        try {
            nativeClickMethod.invoke(module, row);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    private void showToast(Context context, String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable failure) {
            safeLog("Could not display download status: " + failure.getMessage());
        }
    }

    private void startThirdParty(Context context, Object aweme, Object module) {
        closeThirdPartyDialog();
        String expectedId = awemeId(aweme);
        thirdPartyDownloadDialog = ThirdPartyDownload.startOwned(context, aweme, options, this::safeLog,
                () -> {
                    if (!enabled || !new ThirdPartyDownloadSettings(options).thirdParty) return false;
                    try {
                        Object current = currentWork(module);
                        return expectedId == null ? current == aweme : expectedId.equals(awemeId(current));
                    } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
                });
        if (thirdPartyDownloadDialog != null) {
            com.douai.module.analytics.LocalAnalytics.action(expectedId, "third_party_download", "第三方下载入口", "manual", "entry");
        }
    }

    private static String awemeId(Object aweme) {
        Object id = jsonValue(aweme, "aweme_id");
        if (id == null) id = HostTargets.invoke(aweme, "getAid");
        return id == null || id.toString().isEmpty() ? null : id.toString();
    }

    private void closeThirdPartyDialog() {
        AutoCloseable active = thirdPartyDownloadDialog;
        thirdPartyDownloadDialog = null;
        if (active != null) try { active.close(); }
        catch (Exception ignored) { safeLog("Could not close third-party download selection"); }
    }

    private void closeOfficialDialog() {
        selectionGeneration.incrementAndGet();
        OfficialDownloadDialog previous = officialDownloadDialog;
        officialDownloadDialog = null;
        if (previous != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) previous.close();
            else mainHandler.post(previous::close);
        }
    }

    @Override
    public void close() {
        enabled = false;
        closeThirdPartyDialog();
        closeOfficialDialog();
        updateRows(false);
        synchronized (rows) {
            rows.clear();
            installedRows.clear();
        }
        unhookAll(null);
    }

    private void unhookAll(Throwable originalFailure) {
        Throwable failure = originalFailure;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                handles.get(index).unhook();
            } catch (Throwable unhookFailure) {
                if (failure == null) failure = unhookFailure;
                else failure.addSuppressed(unhookFailure);
            }
        }
        handles.clear();
        if (originalFailure == null && failure != null) {
            safeLog("Download menu unhook failed: " + failure.getMessage());
        }
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not alter the host click path.
        }
    }

    private static void validateMethods(Method moduleName, Method viewFactory, Method nativeClick) {
        if (moduleName == null || viewFactory == null || nativeClick == null) {
            throw new IllegalArgumentException("All native download methods are required");
        }
        Class<?> owner = moduleName.getDeclaringClass();
        if (viewFactory.getDeclaringClass() != owner || nativeClick.getDeclaringClass() != owner) {
            throw new IllegalArgumentException("Native download methods must share one declaring class");
        }
        if (!"moduleName".equals(moduleName.getName())
                || moduleName.getParameterCount() != 0 || moduleName.getReturnType() != String.class) {
            throw new IllegalArgumentException("Unexpected native download moduleName signature");
        }
        HostAdaptation.Profile profile = HostAdaptation.currentProfile();
        if (profile != HostAdaptation.PROFILE_406 && profile != HostAdaptation.PROFILE_408
                && profile != HostAdaptation.PROFILE_410 && profile != HostAdaptation.PROFILE_412) {
            throw new IllegalArgumentException("Verified native download host profile is required");
        }
        String factoryName = profile == HostAdaptation.PROFILE_406 ? "LJJIFFI" : "LJJI";
        if (!factoryName.equals(viewFactory.getName()) || viewFactory.getParameterCount() != 2
                || viewFactory.getParameterTypes()[0] != Context.class
                || viewFactory.getParameterTypes()[1] != ViewGroup.class
                || !View.class.isAssignableFrom(viewFactory.getReturnType())) {
            throw new IllegalArgumentException("Unexpected native download item factory signature");
        }
        String clickName = profile == HostAdaptation.PROFILE_410 || profile == HostAdaptation.PROFILE_412
                ? "LJJIJIIJIL" : "LJJIJIL";
        if (!clickName.equals(nativeClick.getName()) || nativeClick.getParameterCount() != 1
                || nativeClick.getParameterTypes()[0] != View.class || nativeClick.getReturnType() != void.class) {
            throw new IllegalArgumentException("Unexpected native download click signature");
        }
    }
}
