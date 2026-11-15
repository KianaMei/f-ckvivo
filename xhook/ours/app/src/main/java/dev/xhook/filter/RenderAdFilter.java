package dev.xhook.filter;

import org.luckypray.dexkit.DexKitBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import dev.xhook.Config;
import dev.xhook.Runtime;
import io.github.libxposed.api.XposedInterface;

/**
 * 去广告（X 12.42+）。
 *
 * <p>12.42 不再把 {@code promoted-tweet-} 拼进 entryId，广告由时间线模型上的
 * {@code TimelinePromotedMetadata} 对象标记。这里在“帖子渲染 composable”
 * （{@code com.x.urt.items.post} 包内的 static void(…, Composer, int)）上判断该字段：
 * 非空就不执行原方法，该条目什么也不画。</p>
 *
 * <p>只跳过推广条目，普通帖子原样渲染；所有异常都吞掉（fail-open）。</p>
 */
public final class RenderAdFilter {
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong LAST_LOG = new AtomicLong();

    /** 条目类 → promoted 字段；null 表示该类没有这个字段（缓存，避免每帧反射扫描）。 */
    private static final Map<Class<?>, Optional<Field>> CACHE = new HashMap<>();

    private static volatile String promotedClassName;

    private RenderAdFilter() {
    }

    public static void install(ClassLoader classLoader, DexKitBridge bridge) {
        if (!Runtime.bool(Config.KEY_AD_FILTER, true)) {
            Runtime.log("RenderAdFilter disabled by config");
            return;
        }
        if (bridge == null) {
            Runtime.log("RenderAdFilter: no DexKit bridge");
            return;
        }
        long startedAt = System.currentTimeMillis();
        String promoted = Discovery.findPromotedMetadataClass(bridge);
        if (promoted == null) {
            Runtime.log("RenderAdFilter: promoted metadata class not found, ad filter inactive");
            return;
        }
        promotedClassName = promoted;
        List<String> itemClasses = Discovery.findItemClasses(bridge, promoted);
        if (itemClasses.isEmpty()) {
            Runtime.log("RenderAdFilter: no item class carries " + promoted + ", ad filter inactive");
            return;
        }
        List<Method> boundaries = Discovery.findRenderBoundaries(bridge, classLoader, itemClasses);

        int hooked = 0;
        for (Method method : boundaries) {
            try {
                if (Runtime.hook(method, chain -> intercept(chain)) != null) {
                    hooked++;
                    Runtime.log("RenderAdFilter hook: " + method.getDeclaringClass().getName()
                            + "#" + method.getName()
                            + " deopt=" + Runtime.deoptimize(method));
                }
            } catch (Throwable throwable) {
                Runtime.error("RenderAdFilter hook failed: " + method, throwable);
            }
        }
        Runtime.log("RenderAdFilter installed: promoted=" + promoted
                + " items=" + itemClasses.size()
                + " boundaries=" + boundaries.size()
                + " hooks=" + hooked
                + " in " + (System.currentTimeMillis() - startedAt) + "ms");
    }

    private static Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object item = null;
        try {
            item = chain.getArg(0);
        } catch (Throwable ignored) {
        }
        try {
            if (item != null && isPromoted(item)) {
                long total = SKIPPED.incrementAndGet();
                long now = System.currentTimeMillis();
                long last = LAST_LOG.get();
                if (now - last > 5000L && LAST_LOG.compareAndSet(last, now)) {
                    Runtime.log("RenderAdFilter: skipped promoted item "
                            + item.getClass().getName() + " total=" + total);
                }
                // 不调用 chain.proceed()：该 composable 什么也不发射。
                return null;
            }
        } catch (Throwable throwable) {
            Runtime.error("RenderAdFilter: check failed", throwable);
        }
        return chain.proceed();
    }

    private static boolean isPromoted(Object item) {
        Field field = promotedField(item.getClass());
        if (field == null) {
            return false;
        }
        try {
            return field.get(item) != null;
        } catch (Throwable throwable) {
            Runtime.error("RenderAdFilter: read promoted field failed", throwable);
            return false;
        }
    }

    private static Field promotedField(Class<?> type) {
        synchronized (CACHE) {
            Optional<Field> cached = CACHE.get(type);
            if (cached == null) {
                cached = Optional.ofNullable(search(type));
                CACHE.put(type, cached);
            }
            return cached.orElse(null);
        }
    }

    private static Field search(Class<?> type) {
        String promoted = promotedClassName;
        if (promoted == null) {
            return null;
        }
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!promoted.equals(field.getType().getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return field;
            }
        }
        return null;
    }
}
