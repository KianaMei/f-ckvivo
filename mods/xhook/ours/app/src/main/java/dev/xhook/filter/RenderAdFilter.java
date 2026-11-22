package dev.xhook.filter;

import org.luckypray.dexkit.DexKitBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.xhook.Config;
import dev.xhook.Runtime;
import io.github.libxposed.api.XposedInterface;

/**
 * 去广告（X 12.42+）。
 *
 * <p>12.42 不再把 {@code promoted-tweet-} 拼进 entryId，广告由时间线模型上的
 * {@code TimelinePromotedMetadata} 对象标记。这里在时间线条目的渲染入口上判断该字段：
 * 非空就不执行原方法，该条目什么也不画。</p>
 *
 * <p>12.42 的实际渲染链（离线 dex 取证）：列表对每个 item 调用
 * {@code com.x.urt.items.post.l6#a(LazyListScope, …, Composer, int)}（接口默认方法），
 * g6（帖子）由 {@code com.x.android.main.hl1#invoke} 这个 Composer lambda 落盘，
 * 帖子内部再走 {@code h#a / a2#a / a2#c}。三条路径都挂，任一条命中即可。</p>
 *
 * <p>普通帖子原样渲染；找不到/类型不符/异常一律放行（fail-open）。</p>
 */
public final class RenderAdFilter {
    /** 前 N 次调用打探针日志，便于离线校准（每次进程启动重新计数）。 */
    private static final int PROBE_LIMIT = 24;

    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong LAST_LOG = new AtomicLong();
    private static final AtomicInteger PROBES = new AtomicInteger();
    private static final Set<String> PROBE_SEEN = ConcurrentHashMap.newKeySet();

    /** 类 → 直接持有 promoted 元数据的字段。null 表示没有。 */
    private static final Map<Class<?>, Optional<Field>> DIRECT = new HashMap<>();

    /** 类 → 可能装着“含 promoted 字段的对象”的字段（接口/Object 字段也算，运行时再看真实类型）。 */
    private static final Map<Class<?>, Field[]> NESTED = new HashMap<>();

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

        int hooked = 0;
        hooked += hookAll(Discovery.findItemRenderMethods(bridge, classLoader, itemClasses));
        hooked += hookAll(Discovery.findListLambdas(bridge, classLoader));
        hooked += hookAll(Discovery.findRenderBoundaries(bridge, classLoader, itemClasses));

        Runtime.log("RenderAdFilter installed: promoted=" + promoted
                + " items=" + itemClasses
                + " hooks=" + hooked
                + " in " + (System.currentTimeMillis() - startedAt) + "ms");
    }

    private static int hookAll(List<Method> methods) {
        int hooked = 0;
        for (Method method : methods) {
            try {
                if (Runtime.hook(method, chain -> intercept(chain)) == null) {
                    continue;
                }
                hooked++;
                Runtime.log("RenderAdFilter hook: " + method.getDeclaringClass().getName()
                        + "#" + method.getName()
                        + " deopt=" + Runtime.deoptimize(method));
            } catch (Throwable throwable) {
                Runtime.error("RenderAdFilter hook failed: " + method, throwable);
            }
        }
        return hooked;
    }

    private static Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object self = null;
        Object arg0 = null;
        try {
            self = chain.getThisObject();
        } catch (Throwable ignored) {
        }
        try {
            arg0 = chain.getArg(0);
        } catch (Throwable ignored) {
        }

        try {
            boolean promoted = isPromoted(self) || isPromoted(arg0);
            probe(chain, self, arg0, promoted);
            if (promoted) {
                long total = SKIPPED.incrementAndGet();
                long now = System.currentTimeMillis();
                long last = LAST_LOG.get();
                if (now - last > 5000L && LAST_LOG.compareAndSet(last, now)) {
                    Runtime.log("RenderAdFilter: skipped promoted item total=" + total
                            + " self=" + className(self) + " arg0=" + className(arg0));
                }
                return null;
            }
        } catch (Throwable throwable) {
            Runtime.error("RenderAdFilter: check failed", throwable);
        }
        return chain.proceed();
    }

    private static void probe(XposedInterface.Chain chain, Object self, Object arg0, boolean promoted) {
        if (PROBES.get() >= PROBE_LIMIT) {
            return;
        }
        String key = chain.getExecutable() + "|" + className(self) + "|" + className(arg0);
        if (!PROBE_SEEN.add(key)) {
            return;
        }
        PROBES.incrementAndGet();
        Runtime.log("probe " + chain.getExecutable().getDeclaringClass().getName()
                + "#" + chain.getExecutable().getName()
                + " self=" + className(self) + " arg0=" + className(arg0)
                + " promoted=" + promoted);
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static boolean isPromoted(Object value) {
        if (value == null) {
            return false;
        }
        Field direct = directField(value.getClass());
        if (direct != null) {
            return read(direct, value) != null;
        }
        for (Field field : nestedFields(value.getClass())) {
            Object child = read(field, value);
            if (child == null) {
                continue;
            }
            Field childDirect = directField(child.getClass());
            if (childDirect != null && read(childDirect, child) != null) {
                return true;
            }
        }
        return false;
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Field directField(Class<?> type) {
        synchronized (DIRECT) {
            Optional<Field> cached = DIRECT.get(type);
            if (cached == null) {
                cached = Optional.ofNullable(searchDirect(type));
                DIRECT.put(type, cached);
            }
            return cached.orElse(null);
        }
    }

    private static Field searchDirect(Class<?> type) {
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

    private static Field[] nestedFields(Class<?> type) {
        synchronized (NESTED) {
            Field[] cached = NESTED.get(type);
            if (cached != null) {
                return cached;
            }
            List<Field> fields = new ArrayList<>();
            for (Class<?> current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }
                    Class<?> fieldType = field.getType();
                    // 接口/Object 声明的字段运行时可能是 g6 之类的条目，必须纳入。
                    if (fieldType == Object.class || fieldType.isInterface()
                            || directField(fieldType) != null) {
                        try {
                            field.setAccessible(true);
                        } catch (Throwable ignored) {
                        }
                        fields.add(field);
                    }
                }
            }
            Field[] result = fields.toArray(new Field[0]);
            NESTED.put(type, result);
            return result;
        }
    }
}
