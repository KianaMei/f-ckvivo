package dev.ganvivo.dragall;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

final class IslandGlassFeature {
    private final Field paintField;
    private final Method currentRect;
    private final Method getGeometry;
    private final Method setRadius;
    private final FeatureConfig config;
    private final Diagnostics diagnostics;
    private final Map<View, SavedPaint> views = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private IslandGlassFeature(Field paintField, Method currentRect, Method getGeometry,
            Method setRadius, FeatureConfig config, Diagnostics diagnostics) {
        this.paintField = paintField;
        this.currentRect = currentRect;
        this.getGeometry = getGeometry;
        this.setRadius = setRadius;
        this.config = config;
        this.diagnostics = diagnostics;
    }

    static HookGroup install(XposedModule module, ClassLoader loader,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        Class<?> island = Reflection.load(loader, "com.vivo.transisland.ui.SpringTransIslandView");
        if (!View.class.isAssignableFrom(island)) throw new ClassCastException("Island is not a View");
        Method update = Reflection.method(island, "g", void.class);
        Method draw = Reflection.method(island, "onDraw", void.class, Canvas.class);
        Field paint = Reflection.field(island, "h", Paint.class);
        Method rectangle = Reflection.method(island, "getMCurrentRect", RectF.class);
        Method geometry = View.class.getDeclaredMethod("getBlurGeometry", int.class);
        geometry.setAccessible(true);
        Method radius = Reflection.method(View.class, "setMaterialBlurRadius", void.class, float.class);
        IslandGlassFeature feature = new IslandGlassFeature(paint, rectangle, geometry, radius,
                config, diagnostics);
        HookGroup group = new HookGroup(module, diagnostics, Config.GLASS);
        try {
            group.hook(update, chain -> {
                Object result = chain.proceed();
                View view = (View) chain.getThisObject();
                try {
                    feature.apply(view);
                } catch (Throwable failure) {
                    diagnostics.failure("island appearance unchanged", failure);
                }
                return result;
            });
            group.hook(draw, chain -> {
                Object result = chain.proceed();
                View view = (View) chain.getThisObject();
                try {
                    if (feature.apply(view)) view.invalidate();
                } catch (Throwable failure) {
                    diagnostics.failure("island material unchanged", failure);
                }
                return result;
            });
            group.commit();
            config.addListener(() -> feature.main.post(feature::refreshViews));
            return group;
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private synchronized boolean apply(View view) throws Throwable {
        Paint paint = (Paint) paintField.get(view);
        if (paint == null) return false;
        SavedPaint saved = views.computeIfAbsent(view, ignored -> new SavedPaint());
        if (!saved.watchingLayout) {
            view.addOnLayoutChangeListener((changed, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                if (!config.enabled(Config.GLASS) || bottom - top == oldBottom - oldTop) return;
                try {
                    apply(changed);
                    changed.invalidate();
                } catch (Throwable failure) {
                    diagnostics.failure("island layout refresh failed", failure);
                }
            });
            saved.watchingLayout = true;
        }
        Shader current = paint.getShader();
        if (current != saved.applied) saved.original = current;
        Object geometry = Reflection.invoke(getGeometry, view, 0);
        Field radiusField = null;
        float actualRadius = Float.NaN;
        if (geometry != null) {
            radiusField = geometry.getClass().getField("mBlurRadius");
            actualRadius = radiusField.getFloat(geometry);
            if (saved.geometry.get() != geometry) {
                saved.geometry = new WeakReference<>(geometry);
                saved.originalRadius = actualRadius;
                saved.appliedRadius = Float.NaN;
                saved.strength = -1;
            } else if (Float.compare(actualRadius, saved.appliedRadius) != 0) {
                saved.originalRadius = actualRadius;
            }
        }
        boolean changed = false;
        if (!config.enabled(Config.GLASS)) {
            if (saved.applied != null && current == saved.applied) {
                paint.setShader(saved.original);
                changed = true;
            }
            if (geometry != null && Float.isFinite(saved.appliedRadius)
                    && Float.compare(actualRadius, saved.appliedRadius) == 0
                    && Float.isFinite(saved.originalRadius)) {
                writeRadius(view, geometry, saved.originalRadius);
                changed |= Float.compare(actualRadius, radiusField.getFloat(geometry)) != 0;
            }
            saved.applied = null;
            saved.appliedRadius = Float.NaN;
            saved.strength = -1;
            return changed;
        }
        RectF rectangle = (RectF) Reflection.invoke(currentRect, view);
        int height = rectangle == null ? 0 : Math.round(rectangle.bottom);
        if (height <= 0) height = Math.max(view.getHeight(), view.getMeasuredHeight());
        int tint = config.tintOpacity();
        if (height > 0 && (current != saved.applied || saved.height != height || saved.tint != tint)) {
            int alpha = Math.round(tint * 2.55f);
            saved.applied = new LinearGradient(0, 0, 0, height,
                    Color.argb(Math.round(alpha * 0.8f), 12, 16, 22),
                    Color.argb(alpha, 12, 16, 22), Shader.TileMode.CLAMP);
            paint.setShader(saved.applied);
            saved.height = height;
            saved.tint = tint;
            changed = true;
        }
        int strength = config.blurStrength();
        float requestedRadius = Math.max(0, saved.originalRadius) * strength / Config.DEFAULT_BLUR;
        if (geometry != null && Float.isFinite(requestedRadius)
                && Float.compare(actualRadius, requestedRadius) != 0
                && (saved.strength != strength || Float.compare(actualRadius, saved.appliedRadius) != 0)) {
            writeRadius(view, geometry, requestedRadius);
            saved.appliedRadius = radiusField.getFloat(geometry);
            saved.strength = strength;
            changed |= Float.compare(actualRadius, saved.appliedRadius) != 0;
        }
        if (changed) diagnostics.hit(Config.GLASS, "blur=" + strength + " tint=" + tint
                + " native=" + (geometry != null) + " appliedRadius="
                + (geometry == null ? Float.NaN : radiusField.getFloat(geometry)));
        return changed;
    }

    private void writeRadius(View view, Object geometry, float radius) throws Throwable {
        Field style = geometry.getClass().getField("mStyle");
        int originalStyle = style.getInt(geometry);
        try {
            style.setInt(geometry, -1);
            Reflection.invoke(setRadius, view, radius);
        } finally {
            style.setInt(geometry, originalStyle);
        }
    }

    private synchronized void refreshViews() {
        for (View view : views.keySet().toArray(new View[0])) {
            if (view == null) continue;
            try {
                apply(view);
                view.invalidate();
            } catch (Throwable failure) {
                diagnostics.failure("island appearance refresh failed", failure);
            }
        }
    }

    private static final class SavedPaint {
        Shader original;
        Shader applied;
        boolean watchingLayout;
        WeakReference<Object> geometry = new WeakReference<>(null);
        float originalRadius = Float.NaN;
        float appliedRadius = Float.NaN;
        int height;
        int tint = -1;
        int strength = -1;
    }
}
