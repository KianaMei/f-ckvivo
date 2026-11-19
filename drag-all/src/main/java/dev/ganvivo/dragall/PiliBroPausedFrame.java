package dev.ganvivo.dragall;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import io.github.libxposed.api.XposedModule;

final class PiliBroPausedFrame {
    static final String REPLY_FRAME = "dev.ganvivo.dragall.paused_frame";
    private static final long MAX_PIXELS = 3840L * 2160;
    private static final long COPY_TIMEOUT_MS = 700;
    private static final float FIXED_PROGRESS_HEIGHT_DP = 20.905f;
    private static final float THIN_PROGRESS_HEIGHT_DP = 5f;
    private static final int COLLAPSED_HEADER_HEIGHT_DP = 56;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Long, Output> outputs = new HashMap<>();
    private final Diagnostics diagnostics;
    private final Method hasFlag;
    private final Field parent;
    private final Field label;
    private final Field localTop;
    private final Field localBottom;
    private volatile Output soleOutput;
    private volatile Playback playback;

    private PiliBroPausedFrame(ClassLoader loader, Diagnostics diagnostics) throws ReflectiveOperationException {
        Class<?> node = Reflection.load(loader, "n0");
        hasFlag = Reflection.method(node, "h", boolean.class, int.class);
        parent = Reflection.field(node, "V", node);
        label = Reflection.field(node, "p", String.class);
        localTop = Reflection.field(node, "Q", float.class);
        localBottom = Reflection.field(node, "S", float.class);
        this.diagnostics = diagnostics;
    }

    static PiliBroPausedFrame install(XposedModule module, ClassLoader loader, Diagnostics diagnostics,
            Predicate<Bundle> mayStartDrag) throws Throwable {
        PiliBroPausedFrame feature = new PiliBroPausedFrame(loader, diagnostics);
        Class<?> handler = Reflection.load(loader, "j40");
        Class<?> manager = Reflection.load(loader, "kt");
        Class<?> videoOutput = Reflection.load(loader, "zr0");
        Class<?> call = Reflection.load(loader, "o60");
        Class<?> result = Reflection.load(loader, "t60");
        Method onCall = Reflection.method(handler, "onMethodCall", void.class, call, result);
        Method setState = Reflection.method(MediaSession.class, "setPlaybackState", void.class, PlaybackState.class);
        Method trigger = Reflection.method(Reflection.load(loader, "com.vivo.vdragdrop.manager.DragManager"),
                "triggerDrag", void.class, Bundle.class);
        Method argument = Reflection.method(call, "a", Object.class, String.class);
        Field callName = Reflection.field(call, "a", String.class);
        Field activity = Reflection.field(handler, "k", Activity.class);
        Field outputManager = Reflection.field(handler, "i", manager);
        Field outputMap = Reflection.field(manager, "h", Object.class);
        Field outputSurface = Reflection.field(videoOutput, "c", Surface.class);
        HookGroup group = new HookGroup(module, diagnostics, "pilibro_paused_frame");
        try {
            group.hook(trigger, chain -> {
                Bundle data = (Bundle) chain.getArg(0);
                if (data != null && data.getBoolean(REPLY_FRAME) && !mayStartDrag.test(data)) return null;
                return chain.proceed();
            });
            group.hook(setState, chain -> {
                Object original = chain.proceed();
                feature.playback = new Playback((MediaSession) chain.getThisObject(), (PlaybackState) chain.getArg(0));
                return original;
            });
            group.hook(onCall, chain -> {
                Object methodCall = chain.getArg(0);
                String name = (String) callName.get(methodCall);
                if (!"VideoOutputManager.Create".equals(name) && !"VideoOutputManager.CreateSurface".equals(name)
                        && !"VideoOutputManager.SetSurfaceTextureSize".equals(name)
                        && !"VideoOutputManager.Dispose".equals(name)) return chain.proceed();
                Object handleValue = Reflection.invoke(argument, methodCall, "handle");
                if (!(handleValue instanceof String handleText)) return chain.proceed();
                long handle = Long.parseLong(handleText);
                if ("VideoOutputManager.Dispose".equals(name)) {
                    feature.recordOutput(handle, null);
                    return chain.proceed();
                }
                Object original = chain.proceed();
                Object currentManager = outputManager.get(chain.getThisObject());
                Object entry = ((Map<?, ?>) outputMap.get(currentManager)).get(handle);
                Surface surface = entry == null ? null : (Surface) outputSurface.get(entry);
                Activity owner = (Activity) activity.get(null);
                if ("VideoOutputManager.SetSurfaceTextureSize".equals(name)) {
                    int width = Integer.parseInt((String) Reflection.invoke(argument, methodCall, "width"));
                    int height = Integer.parseInt((String) Reflection.invoke(argument, methodCall, "height"));
                    feature.recordOutput(handle, new Output(owner, surface, width, height));
                } else {
                    feature.recordOutput(handle, new Output(owner, surface, 0, 0));
                }
                return original;
            });
            group.commit();
            return feature;
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private synchronized void recordOutput(long handle, Output output) {
        if (output == null) outputs.remove(handle);
        else if (!output.equals(outputs.get(handle))) outputs.put(handle, output);
        soleOutput = outputs.size() == 1 ? outputs.values().iterator().next() : null;
    }

    Press pick(Activity activity, View view, Rect visible, PointF point, AccessibilityNodeProvider provider,
            Map<?, ?> nodes) throws Throwable {
        Output output = soleOutput;
        Playback state = playback;
        if (output == null || output.activity != activity || !output.ready() || !isPaused(state)
                || activity.isInPictureInPictureMode() || !view.hasWindowFocus()) return null;
        List<Rect> back = new ArrayList<>();
        List<Rect> settings = new ArrayList<>();
        List<Rect> play = new ArrayList<>();
        List<Rect> fullscreen = new ArrayList<>();
        List<ProgressMark> progress = new ArrayList<>();
        int inspected = 0;
        for (Map.Entry<?, ?> entry : nodes.entrySet()) {
            if (++inspected > 1024) return null;
            if (!(entry.getKey() instanceof Integer id) || entry.getValue() == null) continue;
            Object semantic = entry.getValue();
            AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(id);
            if (node == null) continue;
            try {
                if (!node.isVisibleToUser()) continue;
                if ("进度条".equals(label.get(semantic))) {
                    float heightDp = localBottom.getFloat(semantic) - localTop.getFloat(semantic);
                    if (isVideoProgressHeight(heightDp) && isDisplayed(semantic)) {
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        if (Rect.intersects(visible, bounds)) progress.add(new ProgressMark(bounds, heightDp));
                    }
                    continue;
                }
                CharSequence description = node.getTooltipText();
                if (TextUtils.isEmpty(description)) description = node.getContentDescription();
                if (TextUtils.isEmpty(description)) continue;
                List<Rect> marks = switch (description.toString().trim()) {
                    case "返回" -> back;
                    case "更多设置" -> settings;
                    case "播放", "暂停" -> play;
                    case "全屏", "退出全屏" -> fullscreen;
                    default -> null;
                };
                if (marks == null || !isDisplayed(semantic)) continue;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (visible.contains(bounds) && !bounds.isEmpty() && !marks.contains(bounds)) marks.add(bounds);
            } finally {
                node.recycle();
            }
        }
        Rect bounds = playerBody(visible, back, settings, play, fullscreen);
        String anchor = bounds == null ? "none" : "controls";
        if (bounds == null && settings.isEmpty() && play.isEmpty() && fullscreen.isEmpty() && progress.size() == 1) {
            bounds = progressBody(visible, view.getRootWindowInsets(), progress.get(0));
            if (bounds != null) anchor = "progress";
        }
        boolean hit = bounds != null && bounds.contains((int) point.x, (int) point.y);
        diagnostics.hit("pilibro_paused_pick", "anchor=" + anchor + " video=" + hit);
        return hit ? new Press(output, state, view, bounds) : null;
    }

    private boolean isDisplayed(Object semantic) throws Throwable {
        for (int depth = 0; semantic != null && depth < 64; depth++) {
            if ((boolean) Reflection.invoke(hasFlag, semantic, 14)) return false;
            semantic = parent.get(semantic);
        }
        return semantic == null;
    }

    static Rect playerBody(Rect visible, List<Rect> back, List<Rect> settings, List<Rect> play, List<Rect> fullscreen) {
        Rect header = controlRow(back, settings);
        Rect footer = controlRow(play, fullscreen);
        if (header == null || footer == null) return null;
        int rowHeight = Math.max(header.height(), footer.height());
        Rect body = new Rect(Math.max(header.left, footer.left), header.bottom,
                Math.min(header.right, footer.right), footer.top - rowHeight / 3);
        return body.height() >= rowHeight && body.width() >= rowHeight * 2 && body.intersect(visible) ? body : null;
    }

    private static Rect controlRow(List<Rect> left, List<Rect> right) {
        Rect found = null;
        for (Rect start : left) {
            for (Rect end : right) {
                if (start.right > end.left || Math.abs(start.centerY() - end.centerY())
                        > Math.min(start.height(), end.height()) / 2) continue;
                if (found != null) return null;
                found = new Rect(start);
                found.union(end);
            }
        }
        return found;
    }

    private static boolean isVideoProgressHeight(float heightDp) {
        return Math.abs(heightDp - FIXED_PROGRESS_HEIGHT_DP) < 0.25f
                || Math.abs(heightDp - THIN_PROGRESS_HEIGHT_DP) < 0.25f;
    }

    private static Rect progressBody(Rect visible, WindowInsets windowInsets, ProgressMark progress) {
        if (windowInsets == null) return null;
        Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Rect bar = progress.bounds;
        Rect body = new Rect(Math.max(visible.left + insets.left, bar.left), visible.top + insets.top,
                Math.min(visible.right - insets.right, bar.right), Math.min(visible.bottom - insets.bottom, bar.top));
        float minSize = COLLAPSED_HEADER_HEIGHT_DP * bar.height() / progress.heightDp;
        return body.height() > minSize && body.width() > minSize ? body : null;
    }

    private static boolean isPaused(Playback snapshot) {
        return snapshot != null && snapshot.session.isActive() && isPausedVideo(snapshot.state);
    }

    static boolean isPausedVideo(PlaybackState state) {
        return state != null && state.getState() == PlaybackState.STATE_PAUSED
                && state.getPosition() >= 0 && (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0;
    }

    boolean isCurrent(Press press) {
        return soleOutput == press.output && playback == press.playback && isPaused(press.playback)
                && press.output.ready() && press.view.isAttachedToWindow() && press.view.hasWindowFocus()
                && (press.output.activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0;
    }

    ParcelFileDescriptor open(Press press, BooleanSupplier held) throws IOException {
        if (Looper.myLooper() == main.getLooper() || !isCurrent(press) || !held.getAsBoolean()) return null;
        Output output = press.output;
        Bitmap bitmap = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888);
        CompletableFuture<Bitmap> copied = new CompletableFuture<>();
        try {
            PixelCopy.request(output.surface, bitmap, status -> {
                if (status == PixelCopy.SUCCESS) {
                    if (!copied.complete(bitmap)) bitmap.recycle();
                } else {
                    bitmap.recycle();
                    diagnostics.hit("pilibro_paused_copy_failed", "pixel_copy=" + status);
                    copied.complete(null);
                }
            }, main);
        } catch (RuntimeException failure) {
            bitmap.recycle();
            diagnostics.failure("PiliBro video surface unavailable", failure);
            return null;
        }
        try {
            Bitmap frame = copied.get(COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (frame == null || !isCurrent(press) || !held.getAsBoolean()) return null;
            File file = File.createTempFile("pilibro-frame-", ".png", press.view.getContext().getCacheDir());
            try {
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    if (!frame.compress(Bitmap.CompressFormat.PNG, 100, stream)) return null;
                }
                if (!isCurrent(press) || !held.getAsBoolean() || file.length() > PiliBroImageSource.MAX_BYTES) return null;
                diagnostics.hit("pilibro_paused_frame", "size=" + output.width + "x" + output.height);
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            } finally {
                file.delete();
            }
        } catch (TimeoutException | ExecutionException failure) {
            return null;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (!copied.cancel(false)) {
                Bitmap frame = copied.getNow(null);
                if (frame != null) frame.recycle();
            }
        }
    }

    record Press(Output output, Playback playback, View view, Rect bounds) {}

    private record ProgressMark(Rect bounds, float heightDp) {}

    private record Playback(MediaSession session, PlaybackState state) {}

    private record Output(Activity activity, Surface surface, int width, int height) {
        boolean ready() {
            return activity != null && surface != null && surface.isValid()
                    && width > 1 && height > 1 && width <= 4096 && height <= 4096
                    && (long) width * height <= MAX_PIXELS;
        }
    }
}
