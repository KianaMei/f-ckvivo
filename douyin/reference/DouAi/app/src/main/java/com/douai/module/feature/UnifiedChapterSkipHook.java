package com.douai.module.feature;

import com.douai.module.config.SettingsStore;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Marks only host-labelled ad chapters for the native player's own skip mechanism. */
public final class UnifiedChapterSkipHook implements AutoCloseable {
    public static final String ENABLED = "unified_skip_chapter_ads";
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<Object, Object> originals = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private Field autoSkip;
    private UnifiedChapterSkipHook() { }
    public static UnifiedChapterSkipHook install(XposedInterface framework, ClassLoader loader, SettingsStore store) throws Exception {
        Class<?> aweme = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader);
        Class<?> chapter = Class.forName("com.ss.ugc.aweme.ChapterData", false, loader);
        Field data = aweme.getField("chapterData"), chapters = aweme.getField("chapterList");
        Field ads = chapter.getField("adChapterIndexList"), skips = chapter.getField("adChapterAutoSkipIndexList");
        if (data.getType() != chapter || chapters.getType() != List.class || ads.getType() != List.class || skips.getType() != List.class)
            throw new IllegalStateException("Native chapter data contract changed");
        UnifiedChapterSkipHook hook = new UnifiedChapterSkipHook(); hook.autoSkip = skips;
        try {
            for (String name : new String[]{"com.ss.android.ugc.aweme.feed.adapter.VideoViewHolder", "com.ss.android.ugc.aweme.feed.adapter.FeedImageViewHolder"}) {
                Class<?> holder = Class.forName(name, false, loader);
                for (Method method : holder.getDeclaredMethods()) {
                    if (!method.getName().equals("bind") || method.getParameterCount() < 1 || method.getParameterTypes()[0] != aweme) continue;
                    XposedInterface.HookHandle handle = framework.hook(method).setId("DouAi.Unified.ChapterSkip." + name + method.getParameterCount())
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                                Object work = chain.getArg(0);
                                if (work != null) {
                                    Object metadata = data.get(work);
                                    if (metadata != null) {
                                        if (!store.getBoolean(ENABLED, false)) {
                                            synchronized (hook.originals) {
                                                if (hook.originals.containsKey(metadata)) skips.set(metadata, hook.originals.remove(metadata));
                                            }
                                        } else {
                                            Object count = chapters.get(work), indices = ads.get(metadata);
                                            if (count instanceof List && indices instanceof List && !((List<?>) count).isEmpty()) {
                                                List<Integer> verified = validIndices((List<?>) indices, ((List<?>) count).size());
                                                if (!verified.isEmpty()) {
                                                    synchronized (hook.originals) {
                                                        if (!hook.originals.containsKey(metadata)) hook.originals.put(metadata, skips.get(metadata));
                                                        skips.set(metadata, verified);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                return chain.proceed();
                            });
                    if (handle == null) throw new IllegalStateException("Chapter callback unavailable");
                    hook.handles.add(handle);
                }
            }
            if (hook.handles.isEmpty()) throw new NoSuchMethodException("Native chapter binding");
            return hook;
        } catch (Throwable failure) { hook.close(); if (failure instanceof Exception) throw (Exception) failure; throw failure; }
    }
    static List<Integer> validIndices(List<?> indices, int chapterCount) {
        java.util.Set<Integer> result = new java.util.LinkedHashSet<>();
        for (Object value : indices) if (value instanceof Number) {
            double number = ((Number) value).doubleValue(); int index = ((Number) value).intValue();
            if (number == index && index >= 0 && index < chapterCount) result.add(index);
        }
        return new ArrayList<>(result);
    }
    @Override public void close() {
        for (XposedInterface.HookHandle handle : handles) if (handle != null) handle.unhook(); handles.clear();
        synchronized (originals) { if (autoSkip != null) for (Map.Entry<Object, Object> entry : originals.entrySet()) try { autoSkip.set(entry.getKey(), entry.getValue()); } catch (Exception ignored) { } originals.clear(); }
    }
}
