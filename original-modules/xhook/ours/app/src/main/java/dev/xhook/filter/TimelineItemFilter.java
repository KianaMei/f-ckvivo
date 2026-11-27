package dev.xhook.filter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dev.xhook.Config;
import dev.xhook.Runtime;

/**
 * 数据层零空行去广告与垃圾回复过滤（X 12.42–12.45.1+ 主力路径）。
 *
 * <p>X 从本地数据库构建每一条时间线条目（{@code UrtTimelinePost}、推广账号、
 * {@code UrtTimelineRtbImageAd} 等）时，都会经过唯一静态构建函数 {@code itemBuilder}，
 * 该函数本身允许返回 {@code null} 表示跳过条目。</p>
 *
 * <p>在这里拦截并返回 {@code null}，比渲染层跳过更干净：不会残留空行或分隔线，
 * 也不会进入 Compose 渲染流程；同时直接打通了 X 12.45.1 上失联的关键词/正则/白名单过滤。</p>
 */
public final class TimelineItemFilter {
    private static final String TIMELINE_ITEMS_PREFIX = "com.x.models.timelines.items.";
    private static final String RTB_TO_STRING_PREFIX = "UrtTimelineRtb";

    private static final Map<Class<?>, Field[]> PROMOTED_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> RTB_CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, PostAccess> POST_ACCESS_CACHE = new ConcurrentHashMap<>();

    private static final AtomicLong AD_DROPPED = new AtomicLong();
    private static final AtomicLong SPAM_DROPPED = new AtomicLong();
    private static final AtomicLong LAST_LOG = new AtomicLong();

    private static volatile Class<?> promotedClass;
    private static volatile Class<?> urtPostClass;

    private TimelineItemFilter() {
    }

    public static void install(ClassLoader classLoader, TargetCache.Bundle bundle) {
        if (bundle == null || bundle.itemBuilder == null || bundle.promotedClass == null) {
            Runtime.log("TimelineItemFilter: itemBuilder unavailable, relying on RenderAdFilter");
            return;
        }
        try {
            promotedClass = Class.forName(bundle.promotedClass, false, classLoader);
        } catch (Throwable throwable) {
            Runtime.error("TimelineItemFilter: cannot load promoted class", throwable);
            return;
        }
        if (bundle.urtPostClass != null) {
            try {
                urtPostClass = Class.forName(bundle.urtPostClass, false, classLoader);
            } catch (Throwable ignored) {
            }
        }

        if (Runtime.hook(bundle.itemBuilder, chain -> {
            Object item = chain.proceed();
            if (item == null) {
                return null;
            }
            try {
                if (Runtime.bool(Config.KEY_AD_FILTER, true) && isAdItem(item)) {
                    logDrop("ad", AD_DROPPED.incrementAndGet(), item.getClass().getSimpleName());
                    return null;
                }
                if (Runtime.bool(Config.KEY_SPAM_FILTER, false)
                        && Rules.hasActiveRules()
                        && isSpamPost(item)) {
                    logDrop("spam", SPAM_DROPPED.incrementAndGet(), item.getClass().getSimpleName());
                    return null;
                }
            } catch (Throwable throwable) {
                Runtime.error("TimelineItemFilter: evaluation failed", throwable);
            }
            return item;
        }) == null) {
            return;
        }

        boolean selfDeopt = Runtime.deoptimize(bundle.itemBuilder);
        int callersDeopt = 0;
        for (Method caller : bundle.itemBuilderCallers) {
            if (Runtime.deoptimize(caller)) {
                callersDeopt++;
            }
        }
        Runtime.log("TimelineItemFilter installed on "
                + bundle.itemBuilder.getDeclaringClass().getName() + "#" + bundle.itemBuilder.getName()
                + " deopt=" + selfDeopt
                + " callersDeopt=" + callersDeopt + "/" + bundle.itemBuilderCallers.size());
    }

    private static boolean isAdItem(Object item) {
        Class<?> type = item.getClass();
        Field[] fields = PROMOTED_FIELDS.computeIfAbsent(type, TimelineItemFilter::findPromotedFields);
        for (Field field : fields) {
            try {
                if (field.get(item) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return isRtbAd(item, type);
    }

    private static Field[] findPromotedFields(Class<?> type) {
        Class<?> target = promotedClass;
        if (target == null) {
            return new Field[0];
        }
        List<Field> found = new ArrayList<>();
        for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Field field : cur.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getType() == target) {
                    try {
                        field.setAccessible(true);
                        found.add(field);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return found.toArray(new Field[0]);
    }

    private static boolean isRtbAd(Object item, Class<?> type) {
        Boolean cached = RTB_CLASS_CACHE.get(type);
        if (cached != null) {
            return cached;
        }
        boolean isRtb = false;
        if (type.getName().startsWith(TIMELINE_ITEMS_PREFIX)) {
            try {
                isRtb = String.valueOf(item).startsWith(RTB_TO_STRING_PREFIX);
            } catch (Throwable ignored) {
            }
        }
        RTB_CLASS_CACHE.put(type, isRtb);
        return isRtb;
    }

    private static boolean isSpamPost(Object item) {
        Class<?> postCls = urtPostClass;
        if (postCls != null && !postCls.isInstance(item)) {
            return false;
        }
        PostAccess access = POST_ACCESS_CACHE.computeIfAbsent(item.getClass(), PostAccess::build);
        if (!access.valid) {
            return false;
        }
        boolean spamHome = Runtime.bool(Config.KEY_SPAM_HOME, false);
        if (!spamHome && !access.isConversation(item)) {
            return false;
        }
        String text = access.readText(item);
        String author = access.readAuthor(item);
        return Rules.shouldBlock(text, author);
    }

    private static void logDrop(String kind, long total, String cls) {
        long now = System.currentTimeMillis();
        long last = LAST_LOG.get();
        if (total == 1 || (now - last > 5000L && LAST_LOG.compareAndSet(last, now))) {
            Runtime.log("TimelineItemFilter dropped " + kind + " total=" + total + " type=" + cls);
        }
    }

    /** 缓存 UrtTimelinePost 的正文、URL 作者与对话归属访问器。 */
    private static final class PostAccess {
        final boolean valid;
        final Method getText;
        final Method getUrl;
        final List<Field> stringFields;
        final List<Field> hostingFields;

        PostAccess(Method getText,
                   Method getUrl,
                   List<Field> stringFields,
                   List<Field> hostingFields) {
            this.getText = getText;
            this.getUrl = getUrl;
            this.stringFields = stringFields;
            this.hostingFields = hostingFields;
            this.valid = getText != null || getUrl != null;
        }

        static PostAccess build(Class<?> type) {
            Method getText = findNoArgString(type, "getText");
            Method getUrl = findNoArgString(type, "getUrl");
            List<Field> strings = new ArrayList<>();
            List<Field> hosting = new ArrayList<>();
            for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (f.getType() == String.class) {
                        strings.add(f);
                    } else if (f.getType().getName().startsWith("com.x.models.")) {
                        // HostingModuleMetadata 持有 entryId: String 与 moduleDisplayType
                        for (Field sub : f.getType().getDeclaredFields()) {
                            if (!Modifier.isStatic(sub.getModifiers()) && sub.getType() == String.class) {
                                hosting.add(f);
                                break;
                            }
                        }
                    }
                }
            }
            return new PostAccess(getText, getUrl, strings, hosting);
        }

        private static Method findNoArgString(Class<?> type, String name) {
            try {
                Method m = type.getMethod(name);
                if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        boolean isConversation(Object item) {
            for (Field f : stringFields) {
                try {
                    Object v = f.get(item);
                    if (v instanceof String && Rules.isConversation((String) v)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            for (Field f : hostingFields) {
                try {
                    Object sub = f.get(item);
                    if (sub == null) {
                        continue;
                    }
                    for (Field sf : sub.getClass().getDeclaredFields()) {
                        if (Modifier.isStatic(sf.getModifiers()) || sf.getType() != String.class) {
                            continue;
                        }
                        sf.setAccessible(true);
                        Object v = sf.get(sub);
                        if (v instanceof String && Rules.isConversation((String) v)) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return false;
        }

        String readText(Object item) {
            if (getText != null) {
                try {
                    Object v = getText.invoke(item);
                    if (v instanceof String) {
                        return (String) v;
                    }
                } catch (Throwable ignored) {
                }
            }
            return EntryAccess.text(item);
        }

        String readAuthor(Object item) {
            if (getUrl == null) {
                return null;
            }
            try {
                Object v = getUrl.invoke(item);
                if (!(v instanceof String)) {
                    return null;
                }
                return extractHandleFromUrl((String) v);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** 从 https://x.com/<handle>/status/<id> 提取作者 handle。 */
    static String extractHandleFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        int scheme = url.indexOf("://");
        int start = scheme >= 0 ? url.indexOf('/', scheme + 3) : url.indexOf('/');
        if (start < 0 || start + 1 >= url.length()) {
            return null;
        }
        int end = url.indexOf('/', start + 1);
        if (end <= start + 1) {
            return null;
        }
        String seg = url.substring(start + 1, end);
        if ("i".equalsIgnoreCase(seg) || "intent".equalsIgnoreCase(seg)
                || "hashtag".equalsIgnoreCase(seg) || "search".equalsIgnoreCase(seg)) {
            return null;
        }
        return seg;
    }
}
