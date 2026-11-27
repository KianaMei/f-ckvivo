package dev.xhook.filter;

import android.content.Context;
import android.content.pm.ApplicationInfo;

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

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.xhook.Runtime;

/**
 * 统一的 DexKit 结构发现（带持久化缓存，全面适配 X 12.42–12.45.1+ R8 跨包合并）。
 *
 * <ol>
 *   <li><b>数据层咽喉（itemBuilder）</b>：定位唯一接收 {@code (?, LinkedHashMap, LinkedHashMap, ?)}
 *       且调用 {@code UrtTimelinePost.<init>} 的静态方法（不限包名，兼容 R8 跨包合并到
 *       {@code org.chromium.*} 等类），并收集直接 callers 供 deoptimize。</li>
 *   <li><b>渲染层边界（AvailablePost / list lambda）</b>：不限包名扫描第一参数为帖子 UI 模型
 *       且含 {@code Composer}/{@code int} 的 static void 方法（兼容 R8 合并到 {@code mlkit_*}、
 *       {@code com.x.video.tab.*} 等类），动态推导 URT 条目基接口查找 {@code Function2} 列表 lambda
 *       （兼容 12.45.1 的 {@code com.x.urt.r2} 与 {@code androidx.compose.material.r0}）。</li>
 *   <li><b>刷新守卫</b>：同步发现 URT 仓库自动刷新方法与旧版 Home 重选方法。</li>
 * </ol>
 */
public final class Discovery {
    private static final String PROMOTED_TO_STRING = "TimelinePromotedMetadata(";
    private static final String URT_POST_TO_STRING = "UrtTimelinePost(";
    private static final String AVAILABLE_POST_TO_STRING = "AvailablePost(";

    private static final String POST_PACKAGE = "com.x.urt.items.post";
    private static final String URT_ROOT_PREFIX = "com.x.urt.";
    private static final String LAZY_PACKAGE_PREFIX = "androidx.compose.foundation.lazy.";
    private static final String COMPOSER = "androidx.compose.runtime.Composer";
    private static final String COMPOSER_DESC = "Landroidx/compose/runtime/Composer;";
    private static final String LINKED_HASH_MAP = "java.util.LinkedHashMap";

    private static final String P_HOME = "com.x.home.tabbed.";
    private static final String P_REFRESH = "com.x.repositories.urt.";
    private static final String TYPE_AUTO = "AUTO_REFRESH";
    private static final String TYPE_VIEWPORT = "VIEWPORT_AWARE_AUTO_REFRESH";

    private static final String[] LEGACY_HOME_CLASSES = {
            "com.x.home.tabbed.n",
            "com.x.home.tabbed.o",
            "com.x.home.tabbed.k",
            "com.x.home.tabbed.j",
            "com.x.home.tabbed.i",
    };

    private static final String[] MARKERS = {
            "promoted-tweet-",
            "promotedTweet-",
            "rtb-image-ad-",
            "conversationthread-",
            "superhero-",
    };

    private Discovery() {
    }

    /** 先查缓存，未命中时懒加载 DexKit 单轮全量发现并回写缓存。 */
    public static TargetCache.Bundle resolveAll(Context app, ClassLoader classLoader) {
        long startedAt = System.currentTimeMillis();
        String fp = TargetCache.fingerprint(app);
        TargetCache.Bundle cached = TargetCache.load(app, classLoader, fp);
        if (cached != null) {
            Runtime.log("Discovery cache hit in " + (System.currentTimeMillis() - startedAt) + "ms"
                    + " builder=" + (cached.itemBuilder != null)
                    + " lambdas=" + cached.listLambdas.size()
                    + " boundaries=" + cached.renderBoundaries.size()
                    + " refresh=" + cached.refreshMethods.size());
            return cached;
        }

        DexKitBridge bridge = openDexKit(app);
        if (bridge == null) {
            return new TargetCache.Bundle();
        }
        try {
            TargetCache.Bundle bundle = new TargetCache.Bundle();
            bundle.promotedClass = findToStringClass(bridge, PROMOTED_TO_STRING);
            bundle.urtPostClass = findToStringClass(bridge, URT_POST_TO_STRING);
            bundle.availablePostClass = findToStringClass(bridge, AVAILABLE_POST_TO_STRING);

            LinkedHashSet<String> items = new LinkedHashSet<>();
            if (bundle.availablePostClass != null) {
                items.add(bundle.availablePostClass);
            }
            if (bundle.promotedClass != null) {
                items.addAll(findItemClasses(bridge, bundle.promotedClass));
            }
            bundle.itemClasses.addAll(items);

            if (bundle.urtPostClass != null) {
                findItemBuilder(bridge, classLoader, bundle.urtPostClass, bundle);
            }
            if (!bundle.itemClasses.isEmpty()) {
                bundle.itemRenderMethods.addAll(
                        findItemRenderMethods(bridge, classLoader, bundle.itemClasses));
                Set<String> baseInterfaces = collectUrtBaseInterfaces(classLoader, bundle.itemClasses);
                bundle.listLambdas.addAll(
                        findListLambdas(bridge, classLoader, baseInterfaces));
                findRenderBoundaries(bridge, classLoader, bundle.itemClasses, bundle);
            }
            bundle.homeReselectMethods.addAll(findHomeReselect(bridge, classLoader));
            bundle.refreshMethods.addAll(findRefreshMethods(bridge, classLoader));

            TargetCache.save(app, fp, bundle);
            Runtime.log("Discovery full scan in " + (System.currentTimeMillis() - startedAt) + "ms"
                    + " promoted=" + bundle.promotedClass
                    + " urtPost=" + bundle.urtPostClass
                    + " availPost=" + bundle.availablePostClass
                    + " builder=" + (bundle.itemBuilder == null ? "none"
                    : bundle.itemBuilder.getDeclaringClass().getName() + "#" + bundle.itemBuilder.getName())
                    + " builderCallers=" + bundle.itemBuilderCallers.size()
                    + " lambdas=" + bundle.listLambdas.size()
                    + " boundaries=" + bundle.renderBoundaries.size()
                    + " boundaryCallers=" + bundle.boundaryCallers.size()
                    + " home=" + bundle.homeReselectMethods.size()
                    + " refresh=" + bundle.refreshMethods.size());
            return bundle;
        } catch (Throwable throwable) {
            Runtime.error("Discovery full scan failed", throwable);
            return new TargetCache.Bundle();
        } finally {
            try {
                bridge.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static DexKitBridge openDexKit(Context app) {
        try {
            long t0 = System.currentTimeMillis();
            try {
                System.loadLibrary("dexkit");
            } catch (Throwable first) {
                ApplicationInfo moduleInfo =
                        Runtime.api() == null ? null : Runtime.api().getModuleApplicationInfo();
                if (moduleInfo == null || moduleInfo.nativeLibraryDir == null) {
                    throw first;
                }
                System.load(new File(moduleInfo.nativeLibraryDir, "libdexkit.so")
                        .getAbsolutePath());
            }
            DexKitBridge bridge = DexKitBridge.create(app.getApplicationInfo().sourceDir);
            Runtime.log("DexKit ready in " + (System.currentTimeMillis() - t0) + "ms");
            return bridge;
        } catch (Throwable throwable) {
            Runtime.error("DexKit init failed", throwable);
            return null;
        }
    }

    /** 找 toString 含特定 data-class 前缀的顶层模型类（如 TimelinePromotedMetadata( / UrtTimelinePost(）。 */
    static String findToStringClass(DexKitBridge bridge, String prefix) {
        if (bridge == null) {
            return null;
        }
        try {
            FindMethod query = new FindMethod();
            query.matcher(MethodMatcher.create()
                    .name("toString")
                    .paramCount(0)
                    .usingStrings(Collections.singletonList(prefix),
                            StringMatchType.StartsWith, false));
            MethodDataList list = bridge.findMethod(query);
            for (MethodData data : list) {
                String owner = data.getDeclaredClassName();
                if (owner != null && owner.indexOf('$') < 0 && owner.indexOf('.') >= 0) {
                    return owner;
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("discovery toString(" + prefix + ") failed", throwable);
        }
        try {
            FindClass query = new FindClass();
            query.matcher(ClassMatcher.create().usingStrings(
                    Collections.singletonList(prefix), StringMatchType.Contains, false));
            for (ClassData data : bridge.findClass(query)) {
                String name = data.getName();
                if (name != null && name.indexOf('$') < 0 && name.indexOf('.') >= 0) {
                    return name;
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("discovery class(" + prefix + ") failed", throwable);
        }
        return null;
    }

    /** 找直接持有 promoted-metadata 字段的条目模型（如 12.42 g6 / 12.45.1 k6）。 */
    static List<String> findItemClasses(DexKitBridge bridge, String promotedClassName) {
        List<String> items = new ArrayList<>();
        if (bridge == null || promotedClassName == null) {
            return items;
        }
        String descriptor = "L" + promotedClassName.replace('.', '/') + ";";
        try {
            FindClass query = new FindClass();
            query.matcher(ClassMatcher.create().className(
                    POST_PACKAGE + ".", StringMatchType.StartsWith, false));
            query.searchPackages(POST_PACKAGE);
            for (ClassData data : bridge.findClass(query)) {
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
     * 找数据层总咽喉 itemBuilder（不限包名，兼容 R8 合并到非 com.x 类）：
     * 静态方法、4 参且第 1/2 参为 LinkedHashMap、且内部调用 {@code UrtTimelinePost.<init>}。
     */
    private static void findItemBuilder(DexKitBridge bridge,
                                        ClassLoader classLoader,
                                        String urtPostClass,
                                        TargetCache.Bundle out) {
        try {
            FindMethod query = new FindMethod();
            query.matcher(MethodMatcher.create()
                    .modifiers(Modifier.STATIC)
                    .paramCount(4)
                    .addInvoke(MethodMatcher.create()
                            .declaredClass(urtPostClass, StringMatchType.Equals, false)
                            .name("<init>")));
            MethodDataList list = bridge.findMethod(query);
            List<MethodData> matched = new ArrayList<>();
            for (MethodData data : list) {
                List<String> params = data.getParamTypeNames();
                if (params == null || params.size() != 4) {
                    continue;
                }
                if (LINKED_HASH_MAP.equals(params.get(1))
                        && LINKED_HASH_MAP.equals(params.get(2))
                        && !"void".equals(data.getReturnTypeName())
                        && !"boolean".equals(data.getReturnTypeName())) {
                    matched.add(data);
                }
            }
            if (matched.size() != 1) {
                Runtime.log("itemBuilder matched " + matched.size() + " times (skipped)");
                return;
            }
            MethodData winner = matched.get(0);
            Method method = winner.getMethodInstance(classLoader);
            if (method == null || !Modifier.isStatic(method.getModifiers())) {
                return;
            }
            method.setAccessible(true);
            out.itemBuilder = method;
            collectCallers(winner, classLoader, out.itemBuilderCallers, 24);
        } catch (Throwable throwable) {
            Runtime.error("itemBuilder discovery failed", throwable);
        }
    }

    /**
     * 全 APK 找帖子渲染 composable 边界（不限包名，兼容 R8 跨包合并进 mlkit / video.tab）：
     * static void、第 0 参是条目模型、参数含 Composer 与 int，并收集直接 callers 供 deoptimize。
     */
    private static void findRenderBoundaries(DexKitBridge bridge,
                                             ClassLoader classLoader,
                                             List<String> itemClasses,
                                             TargetCache.Bundle out) {
        Set<String> items = new HashSet<>(itemClasses);
        Set<String> seen = new HashSet<>();
        Set<String> seenCallers = new HashSet<>();
        try {
            FindMethod query = new FindMethod();
            query.matcher(MethodMatcher.create()
                    .modifiers(Modifier.STATIC)
                    .returnType("void"));
            for (MethodData data : bridge.findMethod(query)) {
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
                String key = data.getDescriptor();
                if (!seen.add(key)) {
                    continue;
                }
                try {
                    Method method = data.getMethodInstance(classLoader);
                    if (method == null || method.getReturnType() != void.class
                            || !Modifier.isStatic(method.getModifiers())
                            || Modifier.isAbstract(method.getModifiers())
                            || Modifier.isNative(method.getModifiers())) {
                        continue;
                    }
                    out.renderBoundaries.add(method);
                    collectCallersDedup(data, classLoader, out.boundaryCallers, seenCallers, 48);
                } catch (Throwable throwable) {
                    Runtime.error("ad discovery: boundary resolve failed: " + key, throwable);
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("ad discovery: render boundary query failed", throwable);
        }
    }

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
                for (ClassData data : bridge.findClass(query)) {
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
                if (method == null || method.getReturnType() != void.class
                        || Modifier.isAbstract(method.getModifiers())
                        || Modifier.isNative(method.getModifiers())) {
                    continue;
                }
                if (!seen.add(method.getDeclaringClass().getName() + "#" + method.getName())) {
                    continue;
                }
                out.add(method);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 从帖子条目模型的接口继承树动态推导 com.x.urt 根包基接口（如 12.42 v1 / 12.45.1 r2）。 */
    private static Set<String> collectUrtBaseInterfaces(ClassLoader classLoader,
                                                        List<String> itemClasses) {
        Set<String> result = new LinkedHashSet<>();
        Set<Class<?>> visited = new HashSet<>();
        for (String name : itemClasses) {
            try {
                collectUrtInterfacesWalk(Class.forName(name, false, classLoader), visited, result);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static void collectUrtInterfacesWalk(Class<?> type,
                                                 Set<Class<?>> visited,
                                                 Set<String> out) {
        if (type == null || !visited.add(type)) {
            return;
        }
        String name = type.getName();
        if (type.isInterface() && isDirectlyInPackage(name, "com.x.urt")) {
            out.add(name);
        }
        for (Class<?> iface : type.getInterfaces()) {
            collectUrtInterfacesWalk(iface, visited, out);
        }
        collectUrtInterfacesWalk(type.getSuperclass(), visited, out);
    }

    /**
     * 全 APK 找把时间线条目渲染进 Lazy 列表的 Function2 Composer lambda：
     * 不限包名（兼容 R8 跨包合并进 {@code androidx.compose.material.r0} 等类），
     * 构造器同时接收条目基接口与 {@code androidx.compose.foundation.lazy.*}。
     */
    private static List<Method> findListLambdas(DexKitBridge bridge,
                                                ClassLoader classLoader,
                                                Set<String> baseInterfaces) {
        List<Method> result = new ArrayList<>();
        if (bridge == null || baseInterfaces.isEmpty()) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        try {
            FindClass query = new FindClass();
            query.matcher(ClassMatcher.create().addInterface(
                    "kotlin.jvm.functions.Function2", StringMatchType.Equals, false));
            for (ClassData data : bridge.findClass(query)) {
                if (data.getName() == null || data.getName().indexOf('$') >= 0) {
                    continue;
                }
                boolean matchesCtor = false;
                for (MethodData methodData : data.getMethods()) {
                    if (!"<init>".equals(methodData.getMethodName())) {
                        continue;
                    }
                    List<String> params = methodData.getParamTypeNames();
                    if (params == null) {
                        continue;
                    }
                    boolean hasBase = false;
                    boolean hasLazy = false;
                    for (String p : params) {
                        if (baseInterfaces.contains(p)) {
                            hasBase = true;
                        }
                        if (p.startsWith(LAZY_PACKAGE_PREFIX)) {
                            hasLazy = true;
                        }
                    }
                    if (hasBase && hasLazy) {
                        matchesCtor = true;
                        break;
                    }
                }
                if (!matchesCtor) {
                    continue;
                }
                for (MethodData methodData : data.getMethods()) {
                    if (!"invoke".equals(methodData.getMethodName())) {
                        continue;
                    }
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (method == null || Modifier.isStatic(method.getModifiers())
                                || method.getParameterCount() != 2) {
                            continue;
                        }
                        if (!seen.add(method.getDeclaringClass().getName() + "#invoke")) {
                            continue;
                        }
                        result.add(method);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("ad discovery: list lambda query failed", throwable);
        }
        return result;
    }

    private static List<Method> findHomeReselect(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> result = new ArrayList<>();
        try {
            FindClass query = new FindClass();
            query.searchPackages("com.x.home.tabbed");
            for (ClassData data : bridge.findClass(query)) {
                String name = data.getName();
                if (name == null || !name.startsWith(P_HOME)) {
                    continue;
                }
                try {
                    Class<?> type = Class.forName(name, false, classLoader);
                    if (!matchesHomeReselectClass(type)) {
                        continue;
                    }
                    for (Method method : type.getDeclaredMethods()) {
                        if (isHomeReselectMethod(method)) {
                            result.add(method);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("Home reselect scan failed", throwable);
        }
        if (!result.isEmpty()) {
            return result;
        }
        for (String name : LEGACY_HOME_CLASSES) {
            try {
                Class<?> type = Class.forName(name, false, classLoader);
                for (Method method : type.getDeclaredMethods()) {
                    if (isHomeReselectMethod(method)) {
                        result.add(method);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static boolean isHomeReselectMethod(Method method) {
        return !Modifier.isStatic(method.getModifiers())
                && !Modifier.isAbstract(method.getModifiers())
                && method.getReturnType() == boolean.class
                && method.getParameterCount() == 2
                && method.getParameterTypes()[0] == boolean.class
                && method.getParameterTypes()[1] == boolean.class;
    }

    private static boolean matchesHomeReselectClass(Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())
                || !Modifier.isFinal(type.getModifiers())) {
            return false;
        }
        int matches = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (isHomeReselectMethod(method)) {
                matches++;
            }
        }
        if (matches != 1) {
            return false;
        }
        boolean hasInstant = false;
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    && "kotlin.time.Instant".equals(field.getType().getName())) {
                hasInstant = true;
                break;
            }
        }
        if (!hasInstant) {
            return false;
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            for (Method method : field.getType().getMethods()) {
                if (method.getReturnType() == int.class
                        && Arrays.equals(method.getParameterTypes(),
                        new Class[]{String.class, int.class})) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Method> findRefreshMethods(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> result = new ArrayList<>();
        try {
            MethodMatcher matcher = new MethodMatcher();
            matcher.declaredClass(P_REFRESH, StringMatchType.StartsWith, true);
            matcher.returnType("void");
            matcher.paramCount(2);
            FindMethod query = new FindMethod();
            query.matcher(matcher);
            query.searchPackages("com.x.repositories.urt");
            for (MethodData data : bridge.findMethod(query)) {
                if (!data.isMethod() || data.getParamCount() != 2) {
                    continue;
                }
                List<String> params = data.getParamTypeNames();
                if (params.size() != 2 || !isAutoRefreshEnum(params.get(0), classLoader)) {
                    continue;
                }
                try {
                    Method method = data.getMethodInstance(classLoader);
                    if (method == null || Modifier.isStatic(method.getModifiers())
                            || Modifier.isAbstract(method.getModifiers())) {
                        continue;
                    }
                    if (method.getParameterTypes()[0].isEnum()) {
                        result.add(method);
                        if (result.size() >= 6) {
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("RefreshGuard refresh query failed", throwable);
        }
        return result;
    }

    private static boolean isAutoRefreshEnum(String typeName, ClassLoader classLoader) {
        if (typeName == null) {
            return false;
        }
        try {
            Class<?> type = Class.forName(typeName, false, classLoader);
            if (!type.isEnum()) {
                return false;
            }
            boolean auto = false;
            boolean viewport = false;
            Object[] constants = type.getEnumConstants();
            if (constants == null) {
                return false;
            }
            for (Object constant : constants) {
                String name = ((Enum<?>) constant).name();
                if (TYPE_AUTO.equals(name)) {
                    auto = true;
                } else if (TYPE_VIEWPORT.equals(name)) {
                    viewport = true;
                }
            }
            return auto && viewport;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void collectCallers(MethodData target,
                                       ClassLoader classLoader,
                                       List<Method> out,
                                       int limit) {
        collectCallersDedup(target, classLoader, out, new HashSet<>(), limit);
    }

    private static void collectCallersDedup(MethodData target,
                                            ClassLoader classLoader,
                                            List<Method> out,
                                            Set<String> seen,
                                            int limit) {
        try {
            for (MethodData caller : target.getCallers()) {
                if (out.size() >= limit) {
                    break;
                }
                if (!caller.isMethod()) {
                    continue;
                }
                if (!seen.add(caller.getDescriptor())) {
                    continue;
                }
                try {
                    Method m = caller.getMethodInstance(classLoader);
                    if (m != null && !Modifier.isAbstract(m.getModifiers())
                            && !Modifier.isNative(m.getModifiers())) {
                        out.add(m);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
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
    static List<Method> findTimelineMethods(ClassLoader classLoader, DexKitBridge bridge) {
        if (bridge == null) {
            return Collections.emptyList();
        }
        try {
            List<Candidate> candidates = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String marker : MARKERS) {
                try {
                    collect(bridge, marker, classLoader, candidates, seen);
                } catch (Throwable ignored) {
                }
            }
            candidates.sort(Comparator.comparingInt(c -> c.score));
            List<Method> result = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (result.size() >= 12) {
                    break;
                }
                result.add(candidate.method);
            }
            return result;
        } catch (Throwable throwable) {
            Runtime.error("legacy discovery failed", throwable);
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

        for (MethodData data : bridge.findMethod(query)) {
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
            if ((!returnList && !paramList) || params.size() > 4) {
                continue;
            }
            String key = data.getDescriptor();
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
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isListType(String type) {
        if (type == null) {
            return false;
        }
        return type.endsWith("List") || type.endsWith("List<")
                || "java.util.Collection".equals(type)
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
