package dev.ganvivo.dragall;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.libxposed.api.XposedModule;

final class FlutterSelectionFeature {
    static final String EXTRA = "dev.ganvivo.dragall.selection";
    private final Class<?> flutterView;
    private final Class<?> bridgeClass;
    private final Field semantics;
    private final Diagnostics diagnostics;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private volatile Selection selection;

    private FlutterSelectionFeature(ClassLoader loader, Diagnostics diagnostics) throws Throwable {
        flutterView = Reflection.load(loader, "zq");
        bridgeClass = Reflection.load(loader, "io.flutter.view.b");
        semantics = Reflection.field(bridgeClass, "g", HashMap.class);
        this.diagnostics = diagnostics;
    }

    static HookGroup install(XposedModule module, ClassLoader loader,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        FlutterSelectionFeature feature = new FlutterSelectionFeature(loader, diagnostics);
        Method touch = Reflection.method(Activity.class, "dispatchTouchEvent", boolean.class, MotionEvent.class);
        Method request = Reflection.method(Reflection.load(loader, "com.vivo.vdragdrop.DragClientProxy"),
                "requestClipDataFromServer", void.class, String.class, String.class, String.class,
                String.class, String.class, PointF.class, String.class, Rect.class, List.class,
                int.class, String.class, Reflection.load(loader, "com.vivo.vdragdrop.bean.ViewTree"));
        Method send = Reflection.method(Messenger.class, "send", void.class, Message.class);
        CallerScope requests = new CallerScope();
        HookGroup group = new HookGroup(module, diagnostics, "flutter_selection");
        try {
            group.hook(touch, chain -> {
                if (config.enabled(Config.DRAG)) {
                    try {
                        feature.onTouch((Activity) chain.getThisObject(), (MotionEvent) chain.getArg(0));
                    } catch (Throwable failure) {
                        feature.selection = null;
                        diagnostics.failure("Flutter selection unavailable", failure);
                    }
                }
                return chain.proceed();
            });
            group.hook(send, chain -> {
                Message message = (Message) chain.getArg(0);
                if (requests.active() && message.what == 20) feature.attach(message);
                return chain.proceed();
            });
            group.scope(request, requests, config, Config.DRAG);
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            feature.images.shutdownNow();
            throw failure;
        }
    }

    private void onTouch(Activity activity, MotionEvent event) throws Throwable {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            Selection current = selection;
            if (current != null && (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL
                    || event.getPointerCount() > 1
                    || Math.hypot(event.getRawX() - current.point.x, event.getRawY() - current.point.y) > 32)) {
                current.held = false;
            }
            return;
        }
        selection = null;
        if ((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) return;
        View root = activity.getWindow().getDecorView();
        View view = findFlutter(root, new int[]{0});
        if (view == null) return;
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (!bridgeClass.isInstance(provider)) return;
        HashMap<?, ?> nodes = (HashMap<?, ?>) semantics.get(provider);
        Selection picked = null;
        int count = 0;
        Rect visible = new Rect();
        view.getGlobalVisibleRect(visible);
        PointF point = new PointF(event.getRawX(), event.getRawY());
        for (Object key : new ArrayList<>(nodes.keySet())) {
            if (++count > 2048 || !(key instanceof Integer)) break;
            AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo((Integer) key);
            if (node == null) continue;
            try {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.intersect(visible) || !bounds.contains((int) point.x, (int) point.y)
                        || !node.isVisibleToUser() || node.isPassword()) continue;
                boolean image = "android.widget.ImageView".contentEquals(node.getClassName());
                CharSequence content = node.getText();
                if (content == null || content.length() == 0) content = node.getContentDescription();
                String text = content == null ? "" : content.toString().trim();
                if (!image && (text.isEmpty() || text.length() > 20000)) continue;
                diagnostics.hit("flutter_candidate", "id=" + key + " image=" + image
                        + " chars=" + text.length() + " rect=" + bounds.toShortString());
                if (picked == null || area(bounds) < area(picked.bounds)
                        || (area(bounds) == area(picked.bounds) && image && !picked.image)) {
                    picked = new Selection(point, bounds, image, text);
                }
            } finally {
                node.recycle();
            }
        }
        diagnostics.hit("flutter_pick", "nodes=" + nodes.size() + " found=" + (picked != null)
                + (picked == null ? "" : " image=" + picked.image + " chars=" + picked.text.length()
                + " rect=" + picked.bounds.toShortString()));
        if (picked == null) return;
        selection = picked;
        if (picked.image) {
            SurfaceView surface = findSurface(view);
            Selection captured = picked;
            main.postDelayed(() -> capture(surface, captured), 140);
        }
    }

    private void capture(SurfaceView surface, Selection current) {
        if (surface == null || selection != current || !current.held || !surface.isAttachedToWindow()) return;
        int[] location = new int[2];
        surface.getLocationOnScreen(location);
        Rect crop = new Rect(current.bounds);
        crop.offset(-location[0], -location[1]);
        if (!crop.intersect(0, 0, surface.getWidth(), surface.getHeight())) return;
        float scale = Math.min(1, 1024f / Math.max(crop.width(), crop.height()));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(crop.width() * scale)),
                Math.max(1, Math.round(crop.height() * scale)), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(surface, crop, bitmap, result -> {
                if (result != PixelCopy.SUCCESS || selection != current || !current.held) {
                    bitmap.recycle();
                    return;
                }
                images.execute(() -> {
                    try {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output);
                        if (output.size() <= 350000) current.imageBytes = output.toByteArray();
                        diagnostics.hit("flutter_image", "size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                                + " bytes=" + output.size() + " ready=" + (current.imageBytes != null));
                    } finally {
                        bitmap.recycle();
                    }
                });
            }, main);
        } catch (RuntimeException failure) {
            bitmap.recycle();
            diagnostics.failure("Flutter image capture unavailable", failure);
        }
    }

    private void attach(Message message) {
        Bundle original = message.getData();
        if (!Config.FLUTTER_SOURCE.contentEquals(original.getCharSequence("widget_package_key", ""))) return;
        Bundle payload = new Bundle();
        Selection current = selection;
        PointF point = original.getParcelable("widget_type_point");
        if (current != null && point != null && SystemClock.uptimeMillis() - current.created < 5000
                && Math.hypot(point.x - current.point.x, point.y - current.point.y) < 24) {
            payload.putParcelable("bounds", new Rect(current.bounds));
            if (!current.image) payload.putString("text", current.text);
            else if (current.imageBytes != null) payload.putByteArray("image", current.imageBytes);
        }
        Bundle data = new Bundle(original);
        data.putBundle(EXTRA, payload);
        message.setData(data);
        diagnostics.hit("flutter_request", "text=" + payload.containsKey("text")
                + " image=" + payload.containsKey("image"));
    }

    private View findFlutter(View root, int[] visited) {
        if (++visited[0] > 256) return null;
        if (flutterView.isInstance(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findFlutter(group.getChildAt(index), visited);
                if (found != null) return found;
            }
        }
        return null;
    }

    private SurfaceView findSurface(View view) {
        if (view instanceof SurfaceView) return (SurfaceView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                SurfaceView found = findSurface(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static long area(Rect bounds) {
        return (long) bounds.width() * bounds.height();
    }

    private static final class Selection {
        final long created = SystemClock.uptimeMillis();
        final PointF point;
        final Rect bounds;
        final boolean image;
        final String text;
        volatile boolean held = true;
        volatile byte[] imageBytes;

        Selection(PointF point, Rect bounds, boolean image, String text) {
            this.point = point;
            this.bounds = bounds;
            this.image = image;
            this.text = text;
        }
    }
}
