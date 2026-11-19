package dev.ganvivo.dragall;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class PiliBroImageSource {
    static final String KIND = "pilibro_source_image";
    static final long MAX_BYTES = 64 * 1024 * 1024;
    private static final long MENU_TIMEOUT_MS = 550;
    private static final long FILE_TIMEOUT_MS = 3600;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Field shareManager;
    private final Class<?> managerType;
    private final Field methodName;
    private final Field methodArguments;
    private final Method complete;
    private final Method share;
    private final Field parent;
    private final Field virtualId;
    private final Diagnostics diagnostics;
    private Capture pending;

    PiliBroImageSource(ClassLoader loader, Diagnostics diagnostics) throws ReflectiveOperationException {
        Class<?> handler = Reflection.load(loader, "c6");
        Class<?> call = Reflection.load(loader, "o60");
        Class<?> result = Reflection.load(loader, "t60");
        shareManager = Reflection.field(handler, "i", Object.class);
        managerType = Reflection.load(loader, "sh0");
        methodName = Reflection.field(call, "a", String.class);
        methodArguments = Reflection.field(call, "b", Object.class);
        share = Reflection.method(handler, "onMethodCall", void.class, call, result);
        complete = result.getMethod("success", Object.class);
        Class<?> semantic = Reflection.load(loader, "n0");
        parent = Reflection.field(semantic, "V", semantic);
        virtualId = Reflection.field(semantic, "b", int.class);
        this.diagnostics = diagnostics;
    }

    void install(HookGroup group) {
        group.hook(share, chain -> {
            Capture capture = pending;
            if (capture == null || !capture.clicked || !managerType.isInstance(shareManager.get(chain.getThisObject()))
                    || !"share".equals(methodName.get(chain.getArg(0)))) return chain.proceed();
            Object arguments = methodArguments.get(chain.getArg(0));
            if (!(arguments instanceof Map<?, ?> parameters) || !parameters.containsKey("paths")) {
                return chain.proceed();
            }
            pending = null;
            try {
                if (!capture.active()) return chain.proceed();
                capture.file = cacheFile(capture.view.getContext(), parameters);
                Reflection.invoke(complete, chain.getArg(1), "dev.fluttercommunity.plus/share/unavailable");
                return null;
            } catch (IOException failure) {
                diagnostics.hit("pilibro_image_unavailable", "share file rejected");
                Reflection.invoke(complete, chain.getArg(1), "dev.fluttercommunity.plus/share/unavailable");
                return null;
            } finally {
                capture.done.countDown();
            }
        });
    }

    ParcelFileDescriptor open(View view, AccessibilityNodeProvider provider, Map<?, ?> nodes,
            Set<?> prePressNodes, boolean gallery, BooleanSupplier held) throws IOException {
        if (Looper.myLooper() == main.getLooper()) return null;
        Capture capture = new Capture(view, provider, nodes, prePressNodes, gallery, held);
        main.post(() -> {
            if (!capture.active() || (pending != null && pending.view.isAttachedToWindow())) {
                capture.done.countDown();
                return;
            }
            pending = capture;
            selectShare(capture);
        });
        try {
            if (!capture.done.await(FILE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    || capture.file == null || !held.getAsBoolean()) return null;
            return ParcelFileDescriptor.open(capture.file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            capture.closed = true;
        }
    }

    private void selectShare(Capture capture) {
        if (pending != capture) return;
        if (capture.active() && capture.view.isAttachedToWindow() && capture.view.hasWindowFocus()) {
            try {
                Integer shareId = shareOption(capture);
                if (shareId != null) {
                    capture.clicked = true;
                    if (capture.provider.performAction(shareId, AccessibilityNodeInfo.ACTION_CLICK, null)) return;
                } else if (SystemClock.uptimeMillis() < capture.menuDeadline) {
                    main.postDelayed(() -> selectShare(capture), 32);
                    return;
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                diagnostics.failure("PiliBro image menu unavailable", failure);
            }
        }
        pending = null;
        capture.done.countDown();
    }

    private Integer shareOption(Capture capture) throws IllegalAccessException {
        Map<Integer, Menu> newMenus = new HashMap<>();
        for (Object key : new ArrayList<>(capture.nodes.keySet())) {
            if (!(key instanceof Integer id) || capture.prePressNodes.contains(id)) continue;
            AccessibilityNodeInfo node = capture.provider.createAccessibilityNodeInfo(id);
            if (node == null) continue;
            try {
                if (!node.isVisibleToUser() || !node.isClickable()) continue;
                CharSequence description = node.getText();
                if (description == null || description.length() == 0) description = node.getContentDescription();
                if (description == null) continue;
                String label = description.toString();
                if (!label.equals("分享") && !label.equals("保存图片") && !label.equals("复制链接")) continue;
                Object semantic = capture.nodes.get(id);
                int route = id;
                for (int depth = 0; semantic != null && depth < 64; depth++) {
                    int ancestor = virtualId.getInt(semantic);
                    if (capture.prePressNodes.contains(ancestor)) break;
                    route = ancestor;
                    semantic = parent.get(semantic);
                }
                Menu menu = newMenus.computeIfAbsent(route, ignored -> new Menu());
                if (label.equals("分享")) {
                    menu.ambiguous |= menu.share != null;
                    menu.share = id;
                } else if (label.equals("保存图片")) menu.save = true;
                else menu.copy = true;
            } finally {
                node.recycle();
            }
        }
        Integer share = null;
        for (Menu menu : newMenus.values()) {
            if (menu.ambiguous || menu.share == null || !menu.save || capture.gallery && !menu.copy) continue;
            if (share != null) return null;
            share = menu.share;
        }
        return share;
    }

    private static final class Menu {
        Integer share;
        boolean ambiguous;
        boolean save;
        boolean copy;
    }

    private static File cacheFile(Context context, Map<?, ?> parameters) throws IOException {
        if (!(parameters.get("paths") instanceof List<?> paths) || paths.size() != 1
                || !(paths.get(0) instanceof String path)) return null;
        File cache = context.getCacheDir().getCanonicalFile();
        File file = new File(path).getCanonicalFile();
        if (!file.toPath().startsWith(cache.toPath()) || !file.isFile() || file.length() <= 0
                || file.length() > MAX_BYTES) return null;
        return file;
    }

    private static final class Capture {
        final View view;
        final AccessibilityNodeProvider provider;
        final Map<?, ?> nodes;
        final Set<?> prePressNodes;
        final boolean gallery;
        final BooleanSupplier held;
        final long menuDeadline = SystemClock.uptimeMillis() + MENU_TIMEOUT_MS;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean closed;
        boolean clicked;
        File file;

        Capture(View view, AccessibilityNodeProvider provider, Map<?, ?> nodes,
                Set<?> prePressNodes, boolean gallery, BooleanSupplier held) {
            this.view = view;
            this.provider = provider;
            this.nodes = nodes;
            this.prePressNodes = prePressNodes;
            this.gallery = gallery;
            this.held = held;
        }

        boolean active() {
            return !closed && held.getAsBoolean();
        }
    }
}
