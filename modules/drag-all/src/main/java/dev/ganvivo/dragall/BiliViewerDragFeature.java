package dev.ganvivo.dragall;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import io.github.libxposed.api.XposedModule;

/**
 * Bilibili image drag: a long press on an image view (comment thumbnails and the
 * classic pinch viewer) starts the vivo island drag. We hook the framework
 * dispatchTouchEvent and filter by view class, so Compose text handling and the
 * free-copy module's long-press territory stay untouched.
 */
final class BiliViewerDragFeature {
    private static final long HOLD_MS = 420L;
    private static final float SLOP_DP = 16f;
    private static final String[] TARGET_CLASSES = {
            "com.bilibili.app.comm.list.widget.image.ListPlaceHolderImageView",
            "com.bilibili.lib.imageviewer.widget.PinchImageView",
            "com.bilibili.lib.imageviewer.widget.PinchBiliImageView",
    };

    private BiliViewerDragFeature() {}

    static HookGroup install(XposedModule module, ClassLoader loader, FeatureConfig config,
            Diagnostics diagnostics) throws Throwable {
        java.lang.reflect.Method dispatch = android.view.View.class
                .getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
        Handler handler = new Handler(Looper.getMainLooper());
        float[] downX = new float[1];
        float[] downY = new float[1];
        float[] slop = new float[1];
        View[] armedView = new View[1];
        Runnable[] pending = new Runnable[1];
        HookGroup group = new HookGroup(module, diagnostics, "bili_viewer_drag");
        try {
            group.hook(dispatch, chain -> {
                MotionEvent event = (MotionEvent) chain.getArg(0);
                Object target = chain.getThisObject();
                if (event != null && target instanceof View && config.enabled(Config.DRAG)
                        && matches(target.getClass())) {
                    View view = (View) target;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            if (pending[0] != null) {
                                handler.removeCallbacks(pending[0]);
                                pending[0] = null;
                            }
                            armedView[0] = view;
                            downX[0] = event.getX();
                            downY[0] = event.getY();
                            slop[0] = SLOP_DP * view.getResources().getDisplayMetrics().density;
                            Runnable task = () -> {
                                View armed = armedView[0];
                                if (armed == null) return;
                                armedView[0] = null;
                                pending[0] = null;
                                trigger(armed, downX[0], downY[0], diagnostics);
                            };
                            pending[0] = task;
                            handler.postDelayed(task, HOLD_MS);
                            break;
                        case MotionEvent.ACTION_MOVE:
                            if (armedView[0] != null
                                    && (Math.abs(event.getX() - downX[0]) > slop[0]
                                    || Math.abs(event.getY() - downY[0]) > slop[0])) {
                                cancel(handler, pending, armedView);
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            cancel(handler, pending, armedView);
                            break;
                    }
                }
                return chain.proceed();
            });
            diagnostics.info("bili_viewer_drag deopt=" + module.deoptimize(dispatch));
            diagnostics.info("bili_viewer_drag installed");
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private static boolean matches(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            String name = current.getName();
            for (String target : TARGET_CLASSES) {
                if (target.equals(name)) return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static void cancel(Handler handler, Runnable[] pending, View[] armedView) {
        armedView[0] = null;
        if (pending[0] != null) {
            handler.removeCallbacks(pending[0]);
            pending[0] = null;
        }
    }

    private static void trigger(View view, float x, float y, Diagnostics diagnostics) {
        try {
            Class<?> managerClass = Class.forName("com.vivo.vdragdrop.manager.VivoDragManager");
            Object manager = managerClass.getDeclaredMethod("getInstance").invoke(null);
            long now = SystemClock.uptimeMillis();
            MotionEvent fakeDown = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            managerClass.getDeclaredMethod("triggerHandleDragAndDrop", View.class, MotionEvent.class)
                    .invoke(manager, view, fakeDown);
            fakeDown.recycle();
            diagnostics.hit("bili_viewer_drag_trigger", view.getClass().getSimpleName()
                    + " x=" + (int) x + " y=" + (int) y);
        } catch (Throwable failure) {
            diagnostics.failure("bili viewer drag trigger failed", failure);
        }
    }
}
