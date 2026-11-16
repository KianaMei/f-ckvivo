package com.douai.module.feature;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import com.douai.module.config.SettingsStore;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Scopes gradients to the inspected danmaku fill paint and restores paint after every callback. */
public final class UnifiedRainbowDanmakuHook implements AutoCloseable {
    public static final String ENABLED = "unified_rainbow_danmaku";
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<Object, Boolean> built = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, GradientEntry> gradients = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private final SettingsStore store;
    private UnifiedRainbowDanmakuHook(SettingsStore store) { this.store = store; }

    public static UnifiedRainbowDanmakuHook install(XposedInterface framework, ClassLoader loader, SettingsStore store) throws Exception {
        UnifiedRainbowDanmakuHook hook = new UnifiedRainbowDanmakuHook(store);
        try {
            hook.backend(framework, loader, "X.1OrF", false, "LJIILJJIL", "LJIIJJI", null, "LJIJ");
            hook.backend(framework, loader, "X.1OrG", false, "LJIILL", null, "LJIIJ", "LJIJJLI");
            hook.backend(framework, loader, "X.1OqQ", true, "LJIIJJI", "LJIIJJI", null, "LJIILIIL");
            hook.backend(framework, loader, "X.1Oqa", true, "LJIIIZ", null, "LJIIIIZZ", "LJIILL");
            return hook;
        } catch (Throwable failure) {
            hook.close(); if (failure instanceof Exception) throw (Exception) failure; throw failure;
        }
    }
    private void backend(XposedInterface framework, ClassLoader loader, String name, boolean skity,
                          String textName, String paintGetterName, String delegateName, String propertiesName) throws Exception {
        Class<?> cell = Class.forName(name, false, loader);
        Field text = cell.getDeclaredField(textName); text.setAccessible(true);
        if (!CharSequence.class.isAssignableFrom(text.getType())) throw new IllegalStateException("Danmaku text contract changed");
        Field width = cell.getSuperclass().getDeclaredField("LIZIZ"); width.setAccessible(true);
        Class<?> paintType = skity ? Class.forName("com.bytedance.skity.graphics.Paint", false, loader) : Paint.class;
        Class<?> canvasType = skity ? Class.forName("com.bytedance.skity.graphics.Canvas", false, loader) : Canvas.class;
        Class<?> shaderType = skity ? Class.forName("com.bytedance.skity.graphics.Shader", false, loader) : Shader.class;
        Method getShader = paintType.getMethod("getShader");
        Method setShader = paintType.getMethod("setShader", shaderType);
        Field properties = cell.getDeclaredField(propertiesName); properties.setAccessible(true);
        Method getter = paintGetterName == null ? null : cell.getDeclaredMethod(paintGetterName);
        Field delegate = delegateName == null ? null : cell.getDeclaredField(delegateName);
        Method delegatedGetter = null;
        if (getter != null) { getter.setAccessible(true); if (getter.getReturnType() != paintType) throw new IllegalStateException("Fill paint getter changed"); }
        if (delegate != null) {
            delegate.setAccessible(true);
            for (Method method : delegate.getType().getMethods()) {
                if (!method.isBridge() && method.getParameterCount() == 2 && method.getParameterTypes()[0].isAssignableFrom(cell)
                        && method.getParameterTypes()[1].getName().equals("kotlin.reflect.KProperty") && method.getReturnType() == paintType) {
                    if (delegatedGetter != null) throw new IllegalStateException("Ambiguous fill paint delegate");
                    delegatedGetter = method;
                }
            }
            if (delegatedGetter == null) throw new NoSuchMethodException("Fill paint delegate");
        }
        final Method delegated = delegatedGetter;
        Method build = skity ? null : cell.getDeclaredMethod("LIZ");
        if (build != null) build.setAccessible(true);
        Method draw = cell.getDeclaredMethod("LJIIIIZZ", canvasType); draw.setAccessible(true);
        Method gradient = null;
        Object tile = null;
        if (skity) {
            for (Method method : shaderType.getDeclaredMethods()) if (Modifier.isStatic(method.getModifiers())
                    && method.getName().equals("createLinearGradient") && method.getParameterCount() == 7
                    && method.getParameterTypes()[4] == int[].class && method.getParameterTypes()[5] == float[].class) gradient = method;
            if (gradient == null) throw new NoSuchMethodException("Skity gradient");
            for (Object value : gradient.getParameterTypes()[6].getEnumConstants()) if (((Enum<?>) value).name().equals("CLAMP")) tile = value;
            if (tile == null) throw new NoSuchFieldException("Skity CLAMP");
        }
        final Method factory = gradient;
        final Object clamp = tile;
        Method callback = skity ? draw : build;
        handles.add(framework.hook(callback).setId("DouAi.Unified.Rainbow." + name)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    Object owner = chain.getThisObject();
                    boolean enabled = store.getBoolean(ENABLED, false);
                    if (!skity) built.put(owner, enabled);
                    if (!enabled) return chain.proceed();
                    Object paint = getter != null ? getter.invoke(owner)
                            : delegated.invoke(delegate.get(owner), owner, ((Object[]) properties.get(null))[1]);
                    CharSequence caption = (CharSequence) text.get(owner);
                    int seed = caption == null ? 0 : caption.toString().hashCode();
                    float extent = Math.max(1, width.getInt(owner));
                    GradientEntry entry = gradients.get(owner);
                    if (entry == null || entry.seed != seed || entry.width != extent) {
                        int[] colors = colors(caption);
                        Object shader = skity ? factory.invoke(null, 0f, 0f, extent, 0f, colors, null, clamp)
                                : new LinearGradient(0f, 0f, extent, 0f, colors, null, Shader.TileMode.CLAMP);
                        entry = new GradientEntry(seed, extent, shader); gradients.put(owner, entry);
                    }
                    synchronized (paint) {
                        Object original = getShader.invoke(paint);
                        try { setShader.invoke(paint, entry.shader); return chain.proceed(); }
                        finally { setShader.invoke(paint, original); }
                    }
                }));
        if (!skity) handles.add(framework.hook(draw).setId("DouAi.Unified.Rainbow.Rebuild." + name)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    Object owner = chain.getThisObject();
                    boolean enabled = store.getBoolean(ENABLED, false);
                    Boolean previous = built.get(owner);
                    if (previous != null && previous != enabled || previous == null && enabled) build.invoke(owner);
                    return chain.proceed();
                }));
    }
    private static final class GradientEntry {
        final int seed; final float width; final Object shader;
        GradientEntry(int seed, float width, Object shader) { this.seed = seed; this.width = width; this.shader = shader; }
    }
    static int[] colors(CharSequence text) {
        int seed = text == null ? 0 : text.toString().hashCode();
        int[] colors = new int[7];
        for (int index = 0; index < colors.length; index++) colors[index] = android.graphics.Color.HSVToColor(
                new float[]{(Math.floorMod(seed, 360) + index * 60) % 360, 0.75f, 1f});
        return colors;
    }
    @Override public void close() { for (XposedInterface.HookHandle handle : handles) if (handle != null) handle.unhook(); handles.clear(); built.clear(); gradients.clear(); }
}
