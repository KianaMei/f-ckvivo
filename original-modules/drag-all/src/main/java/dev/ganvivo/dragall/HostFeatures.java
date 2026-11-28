package dev.ganvivo.dragall;

import android.content.Context;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import io.github.libxposed.api.XposedModule;

final class HostFeatures {
    private final XposedModule module;
    private final Context context;
    private final FeatureConfig config;
    private final Diagnostics diagnostics;
    private final Map<String, Map<Class<?>, HookGroup>> installed = new HashMap<>();

    HostFeatures(XposedModule module, Context context, FeatureConfig config, Diagnostics diagnostics) {
        this.module = module;
        this.context = context;
        this.config = config;
        this.diagnostics = diagnostics;
    }

    synchronized void installMissing(ClassLoader loader) {
        if (Config.FLUTTER_SOURCE.equals(context.getPackageName())) {
            attempt("flutter_selection", "io.flutter.view.b", loader,
                    () -> FlutterSelectionFeature.install(module, loader, config, diagnostics));
            return;
        }
        if (Config.BILI.equals(context.getPackageName())) {
            attempt("bili_compose_drag", BiliViewerDragFeature.COMPOSE_HOST, loader,
                    () -> BiliViewerDragFeature.install(module, loader, config, diagnostics));
            attempt("bili_original", BiliOriginalImageFeature.resolveGestureAnchor(loader), loader,
                    () -> BiliOriginalImageFeature.install(module, loader, context, config, diagnostics));
            return;
        }
        if (Config.TWITTER.equals(context.getPackageName())) {
            attempt("x_compose_drag", XComposeDragFeature.COMPOSE_VIEW, loader,
                    () -> XComposeDragFeature.install(module, loader, config, diagnostics));
            return;
        }
        if (Config.CATCHER.equals(context.getPackageName())) {
            attempt(Config.DRAG, "com.vivo.accessibilityenhance.dc.wa", loader,
                    () -> CatcherFeature.install(module, loader, context, config, diagnostics));
            return;
        }
        attempt(Config.DRAG, "vb.p", loader,
                () -> DragFeature.install(module, loader, config, diagnostics));
        attempt("selected_content", "yb.f", loader,
                () -> SelectedContentFeature.install(module, loader, context, config, diagnostics));
        attempt("native_selection", "vb.p", loader,
                () -> NativeSelectionFeature.install(module, loader, context, config, diagnostics));
        attempt(Config.APPS, "jh.z", loader,
                () -> SettingsListFeature.install(module, loader, context, config, diagnostics));
        attempt(Config.SHARE, "com.vivo.intentservice.bean.TransIslandIntentionBean", loader,
                () -> ShareFeature.install(module, loader, context, config, diagnostics));
        attempt(Config.GLASS, "com.vivo.transisland.ui.SpringTransIslandView", loader,
                () -> IslandGlassFeature.install(module, loader, config, diagnostics));
    }

    private void attempt(String feature, String anchorName, ClassLoader loader, Installer installer) {
        try {
            Class<?> anchor = Reflection.load(loader, anchorName);
            Map<Class<?>, HookGroup> byClass = installed.computeIfAbsent(feature,
                    ignored -> new IdentityHashMap<>());
            if (byClass.containsKey(anchor)) return;
            HookGroup group = installer.install();
            byClass.put(anchor, group);
        } catch (Throwable failure) {
            diagnostics.failure(feature + " not installed; loader="
                    + Integer.toHexString(System.identityHashCode(loader)), failure);
        }
    }

    private interface Installer {
        HookGroup install() throws Throwable;
    }
}
