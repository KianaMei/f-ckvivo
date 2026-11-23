package dev.ganvivo.dragall;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;

/**
 * Bilibili draws its comment list with Jetpack Compose. vivo records the pressed view at the tail of
 * View.dispatchTouchEvent, but AndroidComposeView overrides that method without calling super, so a
 * press that Compose handles leaves vivo holding whatever view an earlier gesture touched. This makes
 * the same call the framework makes for ordinary views, with the Compose host as the pressed view.
 *
 * Nothing here reads or copies content: timing and the drag itself stay with vivo. The picture of the
 * fullscreen viewer comes from BiliOriginalImageFeature; everything else is left to vivo's own Compose
 * collector, which the catcher process must allow for Bilibili (see CatcherFeature).
 */
final class BiliViewerDragFeature {
    static final String COMPOSE_HOST = "androidx.compose.ui.platform.AndroidComposeView";
    /** Compose surfaces under this view belong to the player and keep Bilibili's own gestures. */
    private static final String PLAYER_AREA = "video_area";
    private static final int UNRESOLVED = -1;
    private static volatile int playerAreaId = UNRESOLVED;

    private BiliViewerDragFeature() {}

    static HookGroup install(XposedModule module, ClassLoader loader, FeatureConfig config,
            Diagnostics diagnostics) throws Throwable {
        Method dispatch = Reflection.method(Reflection.load(loader, COMPOSE_HOST),
                "dispatchTouchEvent", boolean.class, MotionEvent.class);
        Class<?> managerClass = Reflection.load(loader, "com.vivo.vdragdrop.manager.VivoDragManager");
        Method manager = Reflection.method(managerClass, "getInstance", managerClass);
        Method report = Reflection.method(managerClass, "triggerHandleDragAndDrop", void.class,
                View.class, MotionEvent.class);
        HookGroup group = new HookGroup(module, diagnostics, "bili_compose_drag");
        try {
            group.hook(dispatch, chain -> {
                Object handled = chain.proceed();
                MotionEvent event = (MotionEvent) chain.getArg(0);
                Object target = chain.getThisObject();
                if (event == null || event.getActionMasked() != MotionEvent.ACTION_DOWN
                        || !(target instanceof View) || !config.enabled(Config.DRAG)) return handled;
                View host = (View) target;
                // Set after the dispatch, so a viewer page touched by this press is stamped no earlier.
                BiliOriginalImageFeature.pressDown = event.getDownTime();
                try {
                    String skipped = playerSurface(host);
                    if (skipped != null) {
                        diagnostics.hit("bili_compose_skip", skipped);
                        return handled;
                    }
                    Reflection.invoke(report, Reflection.invoke(manager, null), host, event);
                    diagnostics.hit("bili_compose_down", "handled=" + handled
                            + " size=" + host.getWidth() + "x" + host.getHeight());
                } catch (Throwable failure) {
                    diagnostics.failure("bili compose host not reported", failure);
                }
                return handled;
            });
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    /** Returns why this host must stay out of vivo's drag flow, or null when it may take part. */
    @SuppressLint("DiscouragedApi") // the id belongs to Bilibili's resources, so it can only be looked up by name
    private static String playerSurface(View host) {
        int playerArea = playerAreaId;
        if (playerArea == UNRESOLVED) {
            playerArea = host.getResources().getIdentifier(PLAYER_AREA, "id",
                    host.getContext().getPackageName());
            playerAreaId = playerArea;
        }
        if (playerArea == 0) {
            // Without the id we cannot tell player overlays from page content, so the whole player
            // page stays out, which is what the Activity-wide rule did before this feature existed.
            Activity activity = activity(host);
            return activity != null && DragFeature.isPlayerSurface(Config.BILI, activity.getClass().getName())
                    ? "player page, " + PLAYER_AREA + " id missing" : null;
        }
        ViewParent parent = host.getParent();
        for (int depth = 0; depth < 64 && parent instanceof View; depth++) {
            View ancestor = (View) parent;
            if (ancestor.getId() == playerArea) return "inside " + PLAYER_AREA;
            parent = ancestor.getParent();
        }
        return null;
    }

    private static Activity activity(View view) {
        Context context = view.getContext();
        for (int depth = 0; depth < 32 && context instanceof ContextWrapper; depth++) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
