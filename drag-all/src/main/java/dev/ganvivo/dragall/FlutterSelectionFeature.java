package dev.ganvivo.dragall;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.libxposed.api.XposedModule;

final class FlutterSelectionFeature {
    static final String EXTRA = "dev.ganvivo.dragall.selection";
    private static final int CATCH_SUCCESS = 21;
    private static final int CATCH_FAILED = 22;
    private static final int DOWNLOAD_SUCCESS = 24;
    private static final String REQUEST_ID = "widget_req_id_key";
    private final Class<?> flutterView;
    private final Class<?> bridgeClass;
    private final Field semantics;
    private final Field bridge;
    private final Field hitOrder;
    private final Field virtualId;
    private final Field label;
    private final Method hasFlag;
    private final PiliBroImageSource images;
    private final Diagnostics diagnostics;
    private volatile PiliBroPausedFrame frames;
    private volatile Selection selection;

    private FlutterSelectionFeature(ClassLoader loader, Diagnostics diagnostics) throws Throwable {
        flutterView = Reflection.load(loader, "zq");
        bridgeClass = Reflection.load(loader, "io.flutter.view.b");
        bridge = Reflection.field(flutterView, "w", bridgeClass);
        semantics = Reflection.field(bridgeClass, "g", HashMap.class);
        Class<?> node = Reflection.load(loader, "n0");
        hitOrder = Reflection.field(node, "X", java.util.ArrayList.class);
        virtualId = Reflection.field(node, "b", int.class);
        label = Reflection.field(node, "p", String.class);
        hasFlag = Reflection.method(node, "h", boolean.class, int.class);
        images = new PiliBroImageSource(loader, diagnostics);
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
        Class<?> dragManager = Reflection.load(loader, "com.vivo.vdragdrop.manager.DragManager");
        Method receive = Reflection.method(dragManager, "handleMessageUnchecked", void.class, int.class, Bundle.class);
        Field activeRequest = Reflection.field(dragManager, "mRequestId", String.class);
        Class<?> jni = Reflection.load(loader, "io.flutter.embedding.engine.FlutterJNI");
        Field delegate = Reflection.field(jni, "accessibilityDelegate", Reflection.load(loader, "bq"));
        Method semanticsEnabled = Reflection.method(jni, "setSemanticsEnabled", void.class, boolean.class);
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
                if (!requests.active() || message.what != 20) return chain.proceed();
                try (ParcelFileDescriptor descriptor = feature.attach(message)) {
                    return chain.proceed();
                }
            });
            group.hook(receive, chain -> {
                int action = (int) chain.getArg(0);
                if (!config.enabled(Config.DRAG) || action < CATCH_SUCCESS || action > DOWNLOAD_SUCCESS) {
                    return chain.proceed();
                }
                Bundle data = (Bundle) chain.getArg(1);
                CharSequence replyRequest = data == null ? null : data.getCharSequence(REQUEST_ID);
                if (!TextUtils.isEmpty(replyRequest)
                        && !TextUtils.equals(replyRequest, (String) activeRequest.get(chain.getThisObject()))) {
                    return null;
                }
                Selection current = feature.selection;
                if (action != CATCH_FAILED && (action != CATCH_SUCCESS || !matchesReply(data, current)
                        || !feature.frameIsCurrent(current))) {
                    diagnostics.hit("flutter_reply_rejected", "no matching source for this press");
                    return chain.proceed(new Object[]{CATCH_FAILED, data == null ? new Bundle() : data});
                }
                if (action == CATCH_SUCCESS && current.frame != null) {
                    data.putBoolean(PiliBroPausedFrame.REPLY_FRAME, true);
                }
                return chain.proceed();
            });
            group.hook(semanticsEnabled, chain -> {
                if (!Boolean.TRUE.equals(chain.getArg(0)) && config.enabled(Config.DRAG)
                        && delegate.get(chain.getThisObject()) != null) return chain.proceed(new Object[]{true});
                return chain.proceed();
            });
            feature.images.install(group);
            group.scope(request, requests, config, Config.DRAG);
            group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
        try {
            feature.frames = PiliBroPausedFrame.install(module, loader, diagnostics,
                    data -> config.enabled(Config.DRAG) && feature.mayStartFrame(data));
        } catch (Throwable failure) {
            diagnostics.failure("PiliBro paused frame not installed", failure);
        }
        return group;
    }

    private boolean mayStartFrame(Bundle data) {
        Selection current = selection;
        return current != null && current.frame != null && matchesReply(data, current) && frameIsCurrent(current);
    }

    private boolean frameIsCurrent(Selection current) {
        return current.frame == null || frames != null && frames.isCurrent(current.frame);
    }

    private static boolean matchesReply(Bundle data, Selection current) {
        if (data == null || current == null || !current.held) return false;
        String request = current.request;
        if (request == null || !request.contentEquals(data.getCharSequence(REQUEST_ID, ""))) return false;
        Object payload = data.get("drag_clip_data_key");
        if (!(payload instanceof ClipData clip) || clip.getItemCount() != 1) return false;
        ClipData.Item item = clip.getItemAt(0);
        if (current.image) {
            return request.equals(data.getString(PiliBroImageSource.KIND)) && item.getUri() != null
                    && "content".equals(item.getUri().getScheme())
                    && "com.vivo.globaldragdrop.fileprovider".equals(item.getUri().getAuthority())
                    && clip.getDescription().hasMimeType("image/*");
        }
        return clip.getDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                && item.getUri() == null && item.getIntent() == null && TextUtils.equals(current.text, item.getText());
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
        AccessibilityNodeProvider provider = (AccessibilityNodeProvider) bridge.get(view);
        if (provider == null) return;
        Map<?, ?> nodes = (Map<?, ?>) semantics.get(provider);
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible)) return;
        int[] screen = new int[2];
        view.getLocationOnScreen(screen);
        int[] window = new int[2];
        view.getLocationInWindow(window);
        visible.offset(screen[0] - window[0], screen[1] - window[1]);
        PointF point = new PointF(event.getRawX(), event.getRawY());
        Selection picked = pick(new Surface(view, provider, nodes), nodes.get(0), point, visible, 0);
        if (picked == null && frames != null) {
            try {
                PiliBroPausedFrame.Press frame = frames.pick(activity, view, visible, point, provider, nodes);
                if (frame != null) picked = new Selection(point, frame);
            } catch (Throwable failure) {
                diagnostics.failure("PiliBro player bounds unavailable", failure);
            }
        }
        diagnostics.hit("flutter_pick", "nodes=" + nodes.size() + " found=" + (picked != null)
                + (picked == null ? "" : " image=" + picked.image + " chars=" + (picked.text == null ? 0 : picked.text.length())
                + " rect=" + picked.bounds.toShortString()));
        selection = picked;
    }

    private Selection pick(Surface surface, Object semantic, PointF point, Rect container, int depth) throws Throwable {
        if (semantic == null || depth >= 64 || (boolean) Reflection.invoke(hasFlag, semantic, 14)) return null;
        AccessibilityNodeInfo node = surface.provider.createAccessibilityNodeInfo(virtualId.getInt(semantic));
        if (node == null) return null;
        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.intersect(container) || !bounds.contains((int) point.x, (int) point.y)
                    || !node.isVisibleToUser()) return null;
            if (node.isPassword()) return new Selection(point, bounds, null, false, null);
            String nodeLabel = (String) label.get(semantic);
            boolean gridImage = nodeLabel != null && nodeLabel.startsWith("图片，第 ") && nodeLabel.endsWith(" 张");
            List<?> children = (List<?>) hitOrder.get(semantic);
            if (!gridImage) {
                for (Object child : children) {
                    Selection picked = pick(surface, child, point, bounds, depth + 1);
                    if (picked != null) return picked;
                }
            }
            if (gridImage || TextUtils.equals("android.widget.ImageView", node.getClassName())) {
                return new Selection(point, bounds, null, true, gridImage ? surface : surface.asGallery());
            }
            CharSequence text = node.getText();
            if (text == null && children.isEmpty() && !node.isClickable() && !node.isScrollable()
                    && TextUtils.equals("android.view.View", node.getClassName())) text = nodeLabel;
            if (text != null && text.length() <= 20000 && !text.toString().trim().isEmpty()) {
                return new Selection(point, bounds, text.toString(), false, null);
            }
            return node.isClickable() || nodeLabel != null && !nodeLabel.isEmpty()
                    ? new Selection(point, bounds, null, false, null) : null;
        } finally {
            node.recycle();
        }
    }

    private ParcelFileDescriptor attach(Message message) {
        Bundle original = message.getData();
        if (!Config.FLUTTER_SOURCE.contentEquals(original.getCharSequence("widget_package_key", ""))) return null;
        Bundle payload = new Bundle();
        ParcelFileDescriptor descriptor = null;
        Selection current = selection;
        PointF point = original.getParcelable("widget_type_point");
        String request = original.getCharSequence(REQUEST_ID, "").toString();
        if (current != null && current.held && point != null && !request.isEmpty()
                && SystemClock.uptimeMillis() - current.created < 5000
                && Math.hypot(point.x - current.point.x, point.y - current.point.y) < 24) {
            payload.putParcelable("bounds", new Rect(current.bounds));
            if (current.image) {
                try {
                    if (current.frame != null) {
                        descriptor = frames.open(current.frame, () -> selection == current && current.held);
                    } else {
                        Surface surface = current.surface;
                        descriptor = images.open(surface.view, surface.provider, surface.nodes, current.prePressNodes,
                                surface.gallery, () -> selection == current && current.held);
                    }
                } catch (IOException failure) {
                    diagnostics.hit("pilibro_image_unavailable", "source file could not be opened");
                }
                if (descriptor != null) {
                    current.request = request;
                    payload.putString("kind", PiliBroImageSource.KIND);
                    payload.putParcelable("file", descriptor);
                }
            } else if (current.text != null) {
                current.request = request;
                payload.putString("text", current.text);
            }
        }
        Bundle data = new Bundle(original);
        data.putBundle(EXTRA, payload);
        message.setData(data);
        diagnostics.hit("flutter_request", "text=" + payload.containsKey("text") + " image=" + (descriptor != null));
        return descriptor;
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

    private record Surface(View view, AccessibilityNodeProvider provider, Map<?, ?> nodes, boolean gallery) {
        Surface(View view, AccessibilityNodeProvider provider, Map<?, ?> nodes) {
            this(view, provider, nodes, false);
        }

        Surface asGallery() {
            return new Surface(view, provider, nodes, true);
        }
    }

    private static final class Selection {
        final long created = SystemClock.uptimeMillis();
        final PointF point;
        final Rect bounds;
        final boolean image;
        final String text;
        final Surface surface;
        final Set<?> prePressNodes;
        final PiliBroPausedFrame.Press frame;
        volatile boolean held = true;
        volatile String request;

        Selection(PointF point, Rect bounds, String text, boolean image, Surface surface) {
            this(point, bounds, text, image, surface, null);
        }

        Selection(PointF point, PiliBroPausedFrame.Press frame) {
            this(point, frame.bounds(), null, true, null, frame);
        }

        private Selection(PointF point, Rect bounds, String text, boolean image, Surface surface,
                PiliBroPausedFrame.Press frame) {
            this.point = point;
            this.bounds = bounds;
            this.image = image;
            this.text = text;
            this.surface = surface;
            this.frame = frame;
            prePressNodes = surface == null ? Set.of() : new HashSet<>(surface.nodes.keySet());
        }
    }
}
