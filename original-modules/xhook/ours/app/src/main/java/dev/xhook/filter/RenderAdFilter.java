package dev.xhook.filter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import dev.xhook.Config;
import dev.xhook.Runtime;
import io.github.libxposed.api.XposedInterface;

/**
 * 渲染层兜底去广告 + 帖子 Ask Grok 按钮隐藏（X 12.42–12.45.1+）。
 *
 * <p>作为 {@link TimelineItemFilter} 数据层拦截之后的双保险：挂接全 APK 帖子渲染边界、
 * LazyList Composer lambda、以及条目接口默认方法，并对边界直接调用者执行 {@code deoptimize}
 * 防止 AOT 内联绕过。</p>
 *
 * <p>同时在 {@code AvailablePost} 构造完成时，按需将 {@code showAskGrokButton} 布尔字段置为
 * {@code false}（使用 {@code Unsafe.allocateInstance} + {@code toString()} 动态探测字段，
 * 抗 R8 字段/append 重排，且不动底栏 Grok Tab 以兼容 LiquidTab）。</p>
 */
public final class RenderAdFilter {
    private static final int PROBE_LIMIT = 16;

    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong GROK_HIDDEN = new AtomicLong();
    private static final AtomicLong LAST_LOG = new AtomicLong();
    private static final AtomicInteger PROBES = new AtomicInteger();
    private static final Set<String> PROBE_SEEN = ConcurrentHashMap.newKeySet();

    private static final Map<Class<?>, Optional<Field>> DIRECT = new HashMap<>();
    private static final Map<Class<?>, Field[]> NESTED = new HashMap<>();

    private static volatile String promotedClassName;
    private static final AtomicBoolean GROK_FIELD_PROBED = new AtomicBoolean(false);
    private static volatile Field showAskGrokField;

    private RenderAdFilter() {
    }

    public static void install(ClassLoader classLoader, TargetCache.Bundle bundle) {
        if (bundle == null || bundle.promotedClass == null) {
            Runtime.log("RenderAdFilter: targets unavailable, inactive");
            return;
        }
        promotedClassName = bundle.promotedClass;

        int hooked = 0;
        hooked += hookAll(bundle.itemRenderMethods);
        hooked += hookAll(bundle.listLambdas);
        hooked += hookAll(bundle.renderBoundaries);

        int callersDeopt = 0;
        for (Method caller : bundle.boundaryCallers) {
            if (Runtime.deoptimize(caller)) {
                callersDeopt++;
            }
        }

        int grokCtors = installGrokButtonHook(classLoader, bundle.availablePostClass);

        Runtime.log("RenderAdFilter installed: promoted=" + promotedClassName
                + " items=" + bundle.itemClasses
                + " hooks=" + hooked
                + " callersDeopt=" + callersDeopt + "/" + bundle.boundaryCallers.size()
                + " grokCtors=" + grokCtors);
    }

    private static int installGrokButtonHook(ClassLoader classLoader, String availablePostClass) {
        if (availablePostClass == null) {
            return 0;
        }
        int hooked = 0;
        try {
            Class<?> model = Class.forName(availablePostClass, false, classLoader);
            for (Constructor<?> ctor : model.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    if (Runtime.hook(ctor, chain -> {
                        Object result = chain.proceed();
                        if (!Runtime.bool(Config.KEY_HIDE_GROK_PROMPTS, false)) {
                            return result;
                        }
                        try {
                            Object post = chain.getThisObject();
                            if (post != null) {
                                Field flag = resolveAskGrokField(model);
                                if (flag != null && flag.getBoolean(post)) {
                                    flag.setBoolean(post, false);
                                    if (GROK_HIDDEN.incrementAndGet() == 1) {
                                        Runtime.log("RenderAdFilter: hid showAskGrokButton on "
                                                + model.getName() + "#" + flag.getName());
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    }) != null) {
                        hooked++;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("RenderAdFilter: grok ctor hook failed", throwable);
        }
        return hooked;
    }

    /** 通过分配空白实例逐一置位 boolean 字段并比对 toString 精确找出 showAskGrokButton 字段。 */
    private static Field resolveAskGrokField(Class<?> owner) {
        if (GROK_FIELD_PROBED.get()) {
            return showAskGrokField;
        }
        synchronized (GROK_FIELD_PROBED) {
            if (GROK_FIELD_PROBED.get()) {
                return showAskGrokField;
            }
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                Object unsafe = theUnsafe.get(null);
                Object blank = unsafeClass.getMethod("allocateInstance", Class.class)
                        .invoke(unsafe, owner);
                Pattern matchTrue = Pattern.compile("(\\(|, )showAskGrokButton=true(,|\\))");
                if (blank != null && !matchTrue.matcher(String.valueOf(blank)).find()) {
                    Field matched = null;
                    int count = 0;
                    for (Field field : owner.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())
                                || field.getType() != boolean.class) {
                            continue;
                        }
                        field.setAccessible(true);
                        field.setBoolean(blank, true);
                        try {
                            if (matchTrue.matcher(String.valueOf(blank)).find()) {
                                matched = field;
                                count++;
                            }
                        } finally {
                            field.setBoolean(blank, false);
                        }
                    }
                    if (count == 1) {
                        showAskGrokField = matched;
                        Runtime.log("RenderAdFilter: resolved showAskGrokButton=" + matched.getName());
                    }
                }
            } catch (Throwable throwable) {
                Runtime.error("RenderAdFilter: probe showAskGrokButton failed", throwable);
            } finally {
                GROK_FIELD_PROBED.set(true);
            }
            return showAskGrokField;
        }
    }

    private static int hookAll(List<Method> methods) {
        int hooked = 0;
        for (Method method : methods) {
            try {
                if (Runtime.hook(method, RenderAdFilter::intercept) == null) {
                    continue;
                }
                hooked++;
                Runtime.deoptimize(method);
            } catch (Throwable throwable) {
                Runtime.error("RenderAdFilter hook failed: " + method, throwable);
            }
        }
        return hooked;
    }

    private static Object intercept(XposedInterface.Chain chain) throws Throwable {
        if (!Runtime.bool(Config.KEY_AD_FILTER, true)) {
            return chain.proceed();
        }
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
                if (total == 1 || (now - last > 5000L && LAST_LOG.compareAndSet(last, now))) {
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
