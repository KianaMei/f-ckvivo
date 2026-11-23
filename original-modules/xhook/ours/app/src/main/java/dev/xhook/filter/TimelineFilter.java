package dev.xhook.filter;

import android.content.Context;

import org.luckypray.dexkit.DexKitBridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import dev.xhook.Config;
import dev.xhook.Runtime;
import io.github.libxposed.api.XposedInterface;

/**
 * 在 X 的 timeline 列表进入 UI 前过滤 promoted / 关键词命中的条目。
 *
 * <p>只对“列表进出”的方法装 hook；列表元素读不到 entryId 时原样放行。找不到目标方法时
 * 什么都不装。所有异常都吞掉，绝不把异常抛回 X。</p>
 */
public final class TimelineFilter {
    private static final AtomicLong REMOVED = new AtomicLong();
    private static final AtomicLong LAST_LOG = new AtomicLong();

    private TimelineFilter() {
    }

    public static void install(Context context,
                               ClassLoader classLoader,
                               DexKitBridge bridge) {
        boolean ad = Runtime.bool(Config.KEY_AD_FILTER, true);
        boolean spam = Runtime.bool(Config.KEY_SPAM_FILTER, false);
        if (!ad && !spam) {
            Runtime.log("TimelineFilter disabled by config");
            return;
        }

        List<Method> methods = Discovery.findTimelineMethods(classLoader, bridge);
        int hooked = 0;
        for (Method method : methods) {
            try {
                if (Runtime.hook(method, chain -> intercept(chain)) != null) {
                    hooked++;
                }
            } catch (Throwable throwable) {
                Runtime.error("TimelineFilter hook failed: " + method, throwable);
            }
        }
        Runtime.log("TimelineFilter installed hooks=" + hooked
                + " ad=" + ad + " spam=" + spam
                + " home=" + Runtime.bool(Config.KEY_SPAM_HOME, false)
                + " rules=" + Rules.summary());
    }

    private static Object intercept(XposedInterface.Chain chain) throws Throwable {
        List<Object> args = chain.getArgs();
        for (int index = 0; index < args.size(); index++) {
            Object argument = args.get(index);
            if (!(argument instanceof List)) {
                continue;
            }
            List<?> filtered = filter((List<?>) argument,
                    chain.getExecutable().getDeclaringClass().getClassLoader());
            if (filtered != null) {
                Object[] replaced = args.toArray();
                replaced[index] = filtered;
                return chain.proceed(replaced);
            }
            break;
        }

        Object result = chain.proceed();
        if (result instanceof List) {
            List<?> filtered = filter((List<?>) result,
                    chain.getExecutable().getDeclaringClass().getClassLoader());
            if (filtered != null) {
                return filtered;
            }
        }
        return result;
    }

    private static List<?> filter(List<?> source, ClassLoader classLoader) {
        try {
            if (source.isEmpty()) {
                return null;
            }
            boolean ad = Runtime.bool(Config.KEY_AD_FILTER, true);
            boolean spam = Runtime.bool(Config.KEY_SPAM_FILTER, false);
            boolean spamHome = Runtime.bool(Config.KEY_SPAM_HOME, false);
            if (!ad && !spam) {
                return null;
            }

            ArrayList<Object> kept = new ArrayList<>(source.size());
            int removed = 0;
            for (Object item : source) {
                if (item == null) {
                    kept.add(null);
                    continue;
                }
                String entryId = EntryAccess.entryId(item);
                if (ad && Rules.isPromoted(entryId)) {
                    removed++;
                    continue;
                }
                if (spam && entryId != null
                        && (Rules.isConversation(entryId) || spamHome)) {
                    String text = EntryAccess.text(item);
                    if (Rules.matches(text)) {
                        removed++;
                        continue;
                    }
                }
                kept.add(item);
            }

            if (removed == 0) {
                return null;
            }
            List<?> rebuilt = rebuild(source, kept, classLoader);
            if (rebuilt == null) {
                Runtime.log("TimelineFilter: rebuild failed, keeping original list");
                return null;
            }
            REMOVED.addAndGet(removed);
            long now = System.currentTimeMillis();
            long last = LAST_LOG.get();
            if (now - last > 5000L && LAST_LOG.compareAndSet(last, now)) {
                Runtime.log("TimelineFilter removed=" + removed
                        + " total=" + REMOVED.get()
                        + " list=" + source.getClass().getSimpleName());
            }
            return rebuilt;
        } catch (Throwable throwable) {
            Runtime.error("TimelineFilter filter failed", throwable);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<?> rebuild(List<?> original, List<Object> kept, ClassLoader classLoader) {
        try {
            Class<?> immutable = Class.forName(
                    "kotlinx.collections.immutable.ImmutableList", false, classLoader);
            if (immutable.isInstance(original)) {
                Method builderMethod = null;
                try {
                    builderMethod = original.getClass().getMethod("builder");
                } catch (Throwable ignored) {
                    try {
                        builderMethod = immutable.getMethod("builder");
                    } catch (Throwable ignoredAgain) {
                    }
                }
                if (builderMethod != null) {
                    Object builder = builderMethod.invoke(original);
                    if (builder instanceof List) {
                        List<Object> mutable = (List<Object>) builder;
                        mutable.clear();
                        mutable.addAll(kept);
                        Object built = builder.getClass().getMethod("build").invoke(builder);
                        if (built instanceof List) {
                            return (List<?>) built;
                        }
                    }
                }
                return null;
            }
        } catch (Throwable ignored) {
        }
        try {
            return new ArrayList<>(kept);
        } catch (Throwable throwable) {
            return null;
        }
    }
}
