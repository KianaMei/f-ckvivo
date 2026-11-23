package dev.xhook.filter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 X 的混淆模型对象里读 entryId / 正文。
 *
 * <p>不依赖类名：对每个类缓存一组“无参 String getter/字段”，第一次调用时用返回值形状验证。
 * 读不到就返回 null，过滤器会原样放行。</p>
 */
final class EntryAccess {
    private static final Map<Class<?>, Access> CACHE = new ConcurrentHashMap<>();

    private EntryAccess() {
    }

    static final class Access {
        final Method entryIdMethod;
        final Field entryIdField;
        final Method textMethod;
        final Field textField;

        Access(Method entryIdMethod, Field entryIdField, Method textMethod, Field textField) {
            this.entryIdMethod = entryIdMethod;
            this.entryIdField = entryIdField;
            this.textMethod = textMethod;
            this.textField = textField;
        }

        boolean hasEntryId() {
            return entryIdMethod != null || entryIdField != null;
        }

        boolean hasText() {
            return textMethod != null || textField != null;
        }
    }

    static String entryId(Object item) {
        if (item == null) {
            return null;
        }
        Access access = access(item);
        if (access == null) {
            return null;
        }
        try {
            if (access.entryIdMethod != null) {
                return asString(access.entryIdMethod.invoke(item));
            }
            if (access.entryIdField != null) {
                return asString(access.entryIdField.get(item));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String text(Object item) {
        if (item == null) {
            return null;
        }
        Access access = access(item);
        if (access == null) {
            return null;
        }
        try {
            if (access.textMethod != null) {
                return asString(access.textMethod.invoke(item));
            }
            if (access.textField != null) {
                return asString(access.textField.get(item));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Access access(Object item) {
        try {
            return CACHE.computeIfAbsent(item.getClass(), type -> build(type, item));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Access build(Class<?> type, Object sample) {
        List<Method> methods = stringMethods(type);

        Method entryIdMethod = null;
        for (Method method : methods) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("entry") && !name.equals("b") && !name.equals("a")
                    && !name.equals("c") && !name.equals("id")) {
                continue;
            }
            String value = invoke(method, sample);
            if (looksLikeEntryId(value)) {
                entryIdMethod = method;
                break;
            }
        }
        if (entryIdMethod == null) {
            for (Method method : methods) {
                String value = invoke(method, sample);
                if (looksLikeEntryId(value)) {
                    entryIdMethod = method;
                    break;
                }
            }
        }

        Field entryIdField = null;
        for (Field field : stringFields(type)) {
            try {
                String value = asString(field.get(sample));
                if (looksLikeEntryId(value)) {
                    entryIdField = field;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }

        String entryId = entryIdMethod != null ? invoke(entryIdMethod, sample)
                : entryIdField != null ? fieldValue(entryIdField, sample) : null;

        Method textMethod = null;
        List<Method> preferred = new ArrayList<>(methods);
        preferred.sort(Comparator.comparingInt(method -> textPriority(method.getName())));
        for (Method method : preferred) {
            if (method == entryIdMethod) {
                continue;
            }
            String value = invoke(method, sample);
            if (looksLikeText(value, entryId)) {
                textMethod = method;
                break;
            }
        }

        Field textField = null;
        for (Field field : stringFields(type)) {
            if (field == entryIdField) {
                continue;
            }
            String value = fieldValue(field, sample);
            if (looksLikeText(value, entryId)) {
                textField = field;
                break;
            }
        }

        return new Access(entryIdMethod, entryIdField, textMethod, textField);
    }

    private static List<Method> stringMethods(Class<?> type) {
        List<Method> result = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0) {
                    continue;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType != String.class
                        && !CharSequence.class.isAssignableFrom(returnType)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    result.add(method);
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    private static List<Field> stringFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getType() != String.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    result.add(field);
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    private static int textPriority(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("fulltext") || lower.contains("full_text")) {
            return 0;
        }
        if (lower.contains("text")) {
            return 1;
        }
        if (lower.contains("body") || lower.contains("content")
                || lower.contains("caption")) {
            return 2;
        }
        return 3;
    }

    private static String invoke(Method method, Object sample) {
        try {
            return asString(method.invoke(sample));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String fieldValue(Field field, Object sample) {
        try {
            return asString(field.get(sample));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String asString(Object value) {
        if (value instanceof CharSequence) {
            return value.toString();
        }
        return null;
    }

    private static boolean looksLikeEntryId(String value) {
        if (value == null || value.length() < 8 || value.length() > 300) {
            return false;
        }
        if (value.startsWith("promoted-tweet-")
                || value.startsWith("promotedTweet-")
                || value.startsWith("conversationthread-")
                || value.startsWith("search-conversation-")
                || value.startsWith("home-conversation-")
                || value.startsWith("tweet-")
                || value.startsWith("trend-")
                || value.startsWith("superhero-")
                || value.startsWith("rtb-image-ad-")
                || value.startsWith("suggest_who_to_follow")
                || value.startsWith("who_to_follow")) {
            return true;
        }
        return value.contains("-tweet-")
                || value.contains("conversationthread")
                || value.contains("promoted-tweet");
    }

    private static boolean looksLikeText(String value, String entryId) {
        if (value == null || value.length() < 2 || value.length() > 20_000) {
            return false;
        }
        if (entryId != null && entryId.equals(value)) {
            return false;
        }
        return value.indexOf(' ') >= 0 || value.length() >= 6;
    }

    static List<Method> none() {
        return Collections.emptyList();
    }
}
