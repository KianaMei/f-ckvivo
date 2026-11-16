package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Connects verified feed gestures to the shared download/settings and semantic comment surfaces. */
public final class UnifiedActions412 implements AutoCloseable {
    private final Supplier<LocalOptions> options;
    private final Consumer<Activity> settings;
    private final Supplier<UnifiedUi412Hook> ui;
    private final Consumer<String> log;
    private AutoCloseable download;
    private boolean closed;
    public UnifiedActions412(Supplier<LocalOptions> options, Consumer<Activity> settings,
                             Supplier<UnifiedUi412Hook> ui, Consumer<String> log) {
        this.options = options; this.settings = settings; this.ui = ui; this.log = log;
    }
    public boolean perform(String action, Object panel, View pager) {
        if (closed || pager == null || !pager.isAttachedToWindow()) return false;
        Activity activity = activity(pager.getContext());
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        try {
            if ("module_settings".equals(action)) { settings.accept(activity); return true; }
            if ("toggle_components".equals(action)) {
                UnifiedUi412Hook hook = ui.get();
                if (hook == null) return false;
                hook.setComponentsTemporarilyShown(!hook.areComponentsTemporarilyShown()); return true;
            }
            if ("open_comments".equals(action)) {
                List<View> found = new ArrayList<>(); collectComment(pager, found);
                return found.size() == 1 && found.get(0).performClick();
            }
            if (!"save_media".equals(action)) return false;
            Object work = panel.getClass().getMethod("getCurrentAweme").invoke(panel);
            if (work == null) return false;
            String id = String.valueOf(HostTargets.invoke(work, "getAid"));
            LocalOptions snapshot = options.get();
            java.util.function.BooleanSupplier valid = () -> !closed && pager.isAttachedToWindow()
                    && id.equals(String.valueOf(HostTargets.invoke(HostTargets.invoke(panel, "getCurrentAweme"), "getAid")));
            closeDownload();
            if (new ThirdPartyDownloadSettings(snapshot).thirdParty) {
                download = ThirdPartyDownload.startOwned(activity, work, snapshot, log, valid);
            } else if (snapshot.officialDownloadDirect()) {
                List<DownloadActionHook.MediaAsset> assets = DownloadActionHook.mediaAssets(work,
                        snapshot.enabled("remove_watermark"), snapshot);
                for (DownloadActionHook.MediaAsset asset : assets) {
                    if (!valid.getAsBoolean()) return false;
                    if (asset.image) MediaStoreDownloader.downloadImage(activity, asset.url, asset.name, snapshot.downloadSaveTree(), log);
                    else MediaStoreDownloader.download(activity, asset.url, asset.name, false, snapshot.downloadSaveTree(), log);
                }
                return !assets.isEmpty();
            } else {
                OfficialDownloadDialog chooser = new OfficialDownloadDialog(activity, work, snapshot, log, valid);
                download = chooser; chooser.show();
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            log.accept("Unified gesture action unavailable: " + failure.getClass().getSimpleName()); return false;
        }
    }
    private static void collectComment(View view, List<View> found) {
        if (found.size() > 1 || !view.isShown() || !view.isEnabled()) return;
        String resource = "";
        try { if (view.getId() != View.NO_ID) resource = view.getResources().getResourceEntryName(view.getId()); }
        catch (RuntimeException ignored) { }
        CharSequence description = view.getContentDescription();
        if (UnifiedUi412Hook.semanticComponent(resource, description == null ? "" : description.toString(), view.getClass().getName()) == Component.COMMENT) {
            List<View> actionable = new ArrayList<>(); collectClick(view, actionable);
            if (actionable.size() == 1) { found.add(actionable.get(0)); return; }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) collectComment(((ViewGroup)view).getChildAt(i), found);
    }
    private static void collectClick(View view, List<View> found) {
        if (!view.isEnabled() || !view.isShown() || found.size() > 1) return;
        if (view.isClickable()) { found.add(view); return; }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) collectClick(((ViewGroup)view).getChildAt(i), found);
    }
    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) { if (context instanceof Activity) return (Activity)context; context = ((ContextWrapper)context).getBaseContext(); }
        return null;
    }
    private void closeDownload() { if (download != null) { try { download.close(); } catch (Exception ignored) { } download = null; } }
    @Override public void close() { closed = true; closeDownload(); }
}
