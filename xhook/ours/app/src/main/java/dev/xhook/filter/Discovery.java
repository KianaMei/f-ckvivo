package dev.xhook.filter;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
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
 * DexKit 结构发现：找“处理 timeline 列表”的方法，而不是硬编码 X 的混淆类名。
 *
 * <p>思路来自 TwitterHideAds / TwiFucker 的结构适配：X 每次构建都会改类名，但
 * promoted/RTB 的 entryId 字符串和“列表进出”的形状相对稳定。找不到就返回空，
 * 过滤器完全不生效（fail-open）。</p>
 */
final class Discovery {
    private static final String[] MARKERS = {
            "promoted-tweet-",
            "promotedTweet-",
            "rtb-image-ad-",
            "conversationthread-",
            "superhero-",
    };

    private Discovery() {
    }

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
            Runtime.log("discovery: " + result.size() + " candidate method(s)");
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
            if (!data.isMethod()) {
                continue;
            }
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
