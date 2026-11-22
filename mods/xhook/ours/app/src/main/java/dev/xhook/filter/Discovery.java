package dev.xhook.filter;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.xhook.Runtime;

/**
 * DexKit 结构发现。两类锚点：
 *
 * <ol>
 *   <li><b>12.42+ 渲染层</b>：推广帖由时间线模型上的 TimelinePromotedMetadata
 *       对象标记（不再拼进 entryId）。找“帖子渲染 composable”：声明在
 *       {@code com.x.urt.items.post} 包内、static、参数含 Composer 与 int、
 *       第一参数类型带 promoted-metadata 字段。</li>
 *   <li><b>旧版 entryId</b>：老 X 把 promoted 前缀拼进 entryId，按字符串找列表方法。
 *       12.42 已删掉这些字符串，只作为老版本回退。</li>
 * </ol>
 *
 * <p>全部查询都容错：找不到就返回空，调用方 fail-open。</p>
 */
final class Discovery {
    /** 12.42 的推广元数据类 toString 前缀（类名被混淆，这是唯一稳定字面量）。 */
    private static final String PROMOTED_TO_STRING = "TimelinePromotedMetadata(";

    /** 与 TwitterHideAds 一致的帖子渲染包（12.42 仍存在，502 个类）。 */
    private static final String POST_PACKAGE = "com.x.urt.items.post";

    private static final String COMPOSER = "androidx.compose.runtime.Composer";
    private static final String COMPOSER_DESC =
            "Landroidx/compose/runtime/Composer;";

    private static final String[] MARKERS = {
            "promoted-tweet-",
            "promotedTweet-",
            "rtb-image-ad-",
            "conversationthread-",
            "superhero-",
    };

    private Discovery() {
    }

    /** 找推广元数据模型类（12.42: {@code com.x.models.ff}），失败返回 null。 */
    static String findPromotedMetadataClass(DexKitBridge bridge) {
        if (bridge == null) {
            return null;
        }
        try {
            String found = findByString(bridge, PROMOTED_TO_STRING);
            if (found != null) {
                return found;
            }
        } catch (Throwable throwable) {
            Runtime.error("ad discovery: promoted class query failed", throwable);
        }
        // 回退：直接找用该字符串的 toString 方法。
        try {
            FindMethod query = new FindMethod();
            query.matcher(MethodMatcher.create()
                    .usingStrings(Collections.singletonList(PROMOTED_TO_STRING),
                            StringMatchType.Contains, false));
            MethodDataList list = bridge.findMethod(query);
            for (MethodData data : list) {
                String owner = data.getDeclaredClassName();
                if (owner != null && owner.indexOf('$') < 0 && owner.indexOf('.') >= 0) {
                    return owner;
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("ad discovery: promoted method query failed", throwable);
        }
        return null;
    }

    private static String findByString(DexKitBridge bridge, String marker) {
        FindClass query = new FindClass();
        query.matcher(ClassMatcher.create().usingStrings(
                Collections.singletonList(marker), StringMatchType.Contains, false));
        ClassDataList list = bridge.findClass(query);
        for (ClassData data : list) {
            String name = data.getName();
            if (name == null || name.indexOf('$') >= 0 || name.indexOf('.') < 0) {
                continue;
            }
            return name;
        }
        return null;
    }

    /** 找直接持有 promoted-metadata 字段的条目模型（12.42: {@code g6}）。 */
    static List<String> findItemClasses(DexKitBridge bridge, String promotedClassName) {
        List<String> items = new ArrayList<>();
        if (bridge == null || promotedClassName == null) {
            return items;
        }
        String descriptor = "L" + promotedClassName.replace('.', '/') + ";";
        // 包内全量枚举（不依赖 addFieldForType 对点号/描述符的约定）。
        try {
            FindClass query = new FindClass();
            query.matcher(ClassMatcher.create().className(
                    POST_PACKAGE + ".", StringMatchType.StartsWith, false));
            query.searchPackages(POST_PACKAGE);
            ClassDataList list = bridge.findClass(query);
            for (ClassData data : list) {
                String name = data.getName();
                if (!isDirectlyInPackage(name, POST_PACKAGE)) {
                    continue;
                }
                for (FieldData field : data.getFields()) {
                    String type = field.getTypeName();
                    if (promotedClassName.equals(type) || descriptor.equals(type)) {
                        items.add(name);
                        break;
                    }
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("ad discovery: item class query failed", throwable);
        }
        return items;
    }

    /**
     * 找帖子渲染 composable：static、声明类直接位于 post 包、第一参数是条目模型、
     * 参数里带 {@code Composer} 和 {@code int}。
     */
    static List<Method> findRenderBoundaries(DexKitBridge bridge,
                                             ClassLoader classLoader,
                                             List<String> itemClasses) {
        List<Method> result = new ArrayList<>();
        if (bridge == null || itemClasses.isEmpty()) {
            return result;
        }
        Set<String> items = new HashSet<>(itemClasses);
        Set<String> seen = new HashSet<>();
        try {
            FindMethod query = new FindMethod();
            query.searchPackages(POST_PACKAGE);
            query.matcher(MethodMatcher.create().declaredClass(
                    POST_PACKAGE + ".", StringMatchType.StartsWith, false));
            MethodDataList list = bridge.findMethod(query);
            for (MethodData data : list) {
                String owner = data.getDeclaredClassName();
                if (!isDirectlyInPackage(owner, POST_PACKAGE)) {
                    continue;
                }
                List<String> params = data.getParamTypeNames();
                if (params == null || params.isEmpty()) {
                    continue;
                }
                if (!items.contains(params.get(0))) {
                    continue;
                }
                if (!hasComposer(params) || !hasInt(params)) {
                    continue;
                }
                String key = data.getMethodSign();
                if (key == null) {
                    key = owner + "#" + data.getMethodName() + params;
                }
                if (!seen.add(key)) {
                    continue;
                }
                try {
                    Method method = data.getMethodInstance(classLoader);
                    if (method == null || method.getReturnType() != void.class) {
                        continue;
                    }
                    if (!Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    if (Modifier.isAbstract(method.getModifiers())
                            || Modifier.isNative(method.getModifiers())) {
                        continue;
                    }
                    result.add(method);
                    Runtime.log("ad discovery: boundary " + owner + "#"
                            + method.getName() + "(" + params.size() + " params)");
                } catch (Throwable throwable) {
                    Runtime.error("ad discovery: resolve failed: " + key, throwable);
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("ad discovery: render boundary query failed", throwable);
        }
        return result;
    }

    /**
     * 找条目自己的渲染方法：12.42 的时间线列表对每个 item（l6 实现类，帖子是 g6）
     * 调 {@code l6#a(LazyListScope, Z, e3, Modifier, Composer, I)} —— 接口默认方法。
     * 从条目类的接口里按形状取。
     */
    static List<Method> findItemRenderMethods(DexKitBridge bridge,
                                              ClassLoader classLoader,
                                              List<String> itemClasses) {
        List<Method> result = new ArrayList<>();
        if (bridge == null || itemClasses.isEmpty()) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (String itemName : itemClasses) {
            try {
                FindClass query = new FindClass();
                query.matcher(ClassMatcher.create()
                        .className(itemName, StringMatchType.Equals, false));
                ClassDataList list = bridge.findClass(query);
                for (ClassData data : list) {
                    collectRenderers(data, classLoader, result, seen);
                    for (ClassData iface : data.getInterfaces()) {
                        collectRenderers(iface, classLoader, result, seen);
                    }
                }
            } catch (Throwable throwable) {
                Runtime.error("ad discovery: item renderer query failed: " + itemName, throwable);
            }
        }
        return result;
    }

    private static void collectRenderers(ClassData data,
                                         ClassLoader classLoader,
                                         List<Method> out,
                                         Set<String> seen) {
        for (MethodData methodData : data.getMethods()) {
            List<String> params = methodData.getParamTypeNames();
            if (params == null || !hasComposer(params) || !hasInt(params)) {
                continue;
            }
            try {
                Method method = methodData.getMethodInstance(classLoader);
                if (method == null || method.getReturnType() != void.class) {
                    continue;
                }
                if (Modifier.isAbstract(method.getModifiers())
                        || Modifier.isNative(method.getModifiers())) {
                    continue;
                }
                if (!seen.add(method.getDeclaringClass().getName() + "#" + method.getName())) {
                    continue;
                }
                out.add(method);
                Runtime.log("ad discovery: item renderer "
                        + method.getDeclaringClass().getName() + "#" + method.getName()
                        + "(" + params.size() + " params)");
            } catch (Throwable throwable) {
                Runtime.error("ad discovery: item renderer resolve failed", throwable);
            }
        }
    }

    /**
     * 兜底：找把条目写进 Lazy 列表的 Composer lambda（12.42 是
     * {@code com.x.android.main.hl1#invoke}）。它的字段是复用的 Object，无法按字段类型找，
     * 但构造器参数里有 {@code com.x.urt.v1}（条目基接口）。
     */
    static List<Method> findListLambdas(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> result = new ArrayList<>();
        if (bridge == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        try {
            FindClass query = new FindClass();
            query.searchPackages("com.x.android.main");
            query.matcher(ClassMatcher.create().addInterface(
                    "kotlin.jvm.functions.Function2", StringMatchType.Equals, false));
            for (ClassData data : bridge.findClass(query)) {
                if (data.getName() == null || data.getName().indexOf('$') >= 0) {
                    continue;
                }
                boolean holdsItem = false;
                for (MethodData methodData : data.getMethods()) {
                    if (!"<init>".equals(methodData.getMethodName())) {
                        continue;
                    }
                    List<String> params = methodData.getParamTypeNames();
                    if (params != null && params.contains("com.x.urt.v1")) {
                        holdsItem = true;
                        break;
                    }
                }
                if (!holdsItem) {
                    continue;
                }
                for (MethodData methodData : data.getMethods()) {
                    if (!"invoke".equals(methodData.getMethodName())) {
                        continue;
                    }
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (method == null || Modifier.isStatic(method.getModifiers())) {
                            continue;
                        }
                        if (!seen.add(method.getDeclaringClass().getName() + "#invoke")) {
                            continue;
                        }
                        result.add(method);
                        Runtime.log("ad discovery: list lambda "
                                + method.getDeclaringClass().getName() + "#invoke");
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("ad discovery: list lambda query failed", throwable);
        }
        return result;
    }

    private static boolean hasComposer(List<String> params) {
        for (String param : params) {
            if (COMPOSER.equals(param) || COMPOSER_DESC.equals(param)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInt(List<String> params) {
        for (String param : params) {
            if ("int".equals(param) || "I".equals(param)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDirectlyInPackage(String className, String packageName) {
        if (className == null || !className.startsWith(packageName + ".")) {
            return false;
        }
        return className.indexOf('.', packageName.length() + 1) < 0;
    }

    // ---------------------------------------------------------------- legacy
    // 12.42 已删除以下 entryId 前缀字符串，此路径只对老版本 X 有效。
    // 保留是为了模块在 12.30 之前的目标上仍能工作。

    static List<Method> findTimelineMethods(ClassLoader classLoader, DexKitBridge bridge) {
        if (bridge == null) {
            Runtime.log("discovery: no DexKit bridge");
            return Collections.emptyList();
        }
        try {
            List<Candidate> candidates = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String marker : MARKERS) {
                try {
                    collect(bridge, marker, classLoader, candidates, seen);
                } catch (Throwable throwable) {
                    Runtime.error("discovery marker failed: " + marker, throwable);
                }
            }
            candidates.sort(Comparator.comparingInt(candidate -> candidate.score));
            List<Method> result = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (result.size() >= 12) {
                    break;
                }
                result.add(candidate.method);
            }
            Runtime.log("discovery(entryId): " + result.size() + " candidate method(s)");
            for (Method method : result) {
                Runtime.log("discovery target: " + method.getDeclaringClass().getName()
                        + "#" + method.getName()
                        + java.util.Arrays.toString(method.getParameterTypes())
                        + " -> " + method.getReturnType().getName());
            }
            return result;
        } catch (Throwable throwable) {
            Runtime.error("discovery failed", throwable);
            return Collections.emptyList();
        }
    }

    private static void collect(DexKitBridge bridge,
                                String marker,
                                ClassLoader classLoader,
                                List<Candidate> out,
                                Set<String> seen) {
        MethodMatcher matcher = new MethodMatcher();
        matcher.usingStrings(Collections.singletonList(marker), StringMatchType.Contains, false);
        matcher.declaredClass("com.x", StringMatchType.StartsWith, true);

        FindMethod query = new FindMethod();
        query.matcher(matcher);
        query.searchPackages("com.x");

        MethodDataList list = bridge.findMethod(query);
        for (MethodData data : list) {
            String returnType = data.getReturnTypeName();
            List<String> params = data.getParamTypeNames();
            boolean returnList = isListType(returnType);
            boolean paramList = false;
            for (String param : params) {
                if (isListType(param)) {
                    paramList = true;
                    break;
                }
            }
            if (!returnList && !paramList) {
                continue;
            }
            if (params.size() > 4) {
                continue;
            }
            String key = data.getMethodSign();
            if (key == null) {
                key = data.getDeclaredClassName() + "#" + data.getMethodName()
                        + java.util.Arrays.toString(params.toArray());
            }
            if (!seen.add(key)) {
                continue;
            }
            try {
                Method method = data.getMethodInstance(classLoader);
                if (method == null || Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                int score = returnList ? (returnType.contains("ImmutableList") ? 0 : 1) : 2;
                out.add(new Candidate(method, score));
            } catch (Throwable throwable) {
                Runtime.error("discovery resolve failed: " + key, throwable);
            }
        }
    }

    private static boolean isListType(String type) {
        if (type == null) {
            return false;
        }
        return type.endsWith("List") || type.endsWith("List<")
                || type.equals("java.util.Collection")
                || type.contains("kotlinx.collections.immutable");
    }

    private static final class Candidate {
        final Method method;
        final int score;

        Candidate(Method method, int score) {
            this.method = method;
            this.score = score;
        }
    }
}
