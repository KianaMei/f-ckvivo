package dev.xhook.filter;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dev.xhook.Runtime;

/**
 * DexKit 结构解析结果的持久化指纹缓存。
 *
 * <p>以宿主 APK（路径/大小/mtime/所有 DEX 条目 CRC）与模块自身 APK（大小/mtime）的双指纹作为键，
 * 存于宿主 {@code cacheDir/xhook-targets-v2.properties}。命中时直接反射还原方法，完全跳过
 * {@code libdexkit.so} 加载与全量 DEX 扫描，冷启动耗时从 ~650ms 降至数毫秒。</p>
 */
public final class TargetCache {
    private static final String SCHEMA = "xhook-targets-v2";
    private static final String FILE_NAME = "xhook-targets-v2.properties";

    private TargetCache() {
    }

    public static final class Bundle {
        public String promotedClass;
        public String urtPostClass;
        public String availablePostClass;
        public final List<String> itemClasses = new ArrayList<>();
        public Method itemBuilder;
        public final List<Method> itemBuilderCallers = new ArrayList<>();
        public final List<Method> itemRenderMethods = new ArrayList<>();
        public final List<Method> listLambdas = new ArrayList<>();
        public final List<Method> renderBoundaries = new ArrayList<>();
        public final List<Method> boundaryCallers = new ArrayList<>();
        public final List<Method> homeReselectMethods = new ArrayList<>();
        public final List<Method> refreshMethods = new ArrayList<>();
        public boolean fromCache;

        public boolean hasUsefulTargets() {
            return promotedClass != null && (itemBuilder != null
                    || !renderBoundaries.isEmpty()
                    || !listLambdas.isEmpty());
        }
    }

    public static String fingerprint(Context app) {
        if (app == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((SCHEMA + "|").getBytes(StandardCharsets.UTF_8));

            ApplicationInfo host = app.getApplicationInfo();
            if (host != null && host.sourceDir != null) {
                File apk = new File(host.sourceDir);
                digest.update(("host:" + apk.getPath() + ":" + apk.length() + ":"
                        + apk.lastModified() + "|").getBytes(StandardCharsets.UTF_8));
                try (ZipFile zip = new ZipFile(apk)) {
                    List<String> dexEntries = new ArrayList<>();
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith("classes") && name.endsWith(".dex")) {
                            dexEntries.add(name + ":" + entry.getCrc() + ":" + entry.getSize());
                        }
                    }
                    Collections.sort(dexEntries);
                    for (String item : dexEntries) {
                        digest.update((item + ";").getBytes(StandardCharsets.UTF_8));
                    }
                }
            }

            ApplicationInfo mod = Runtime.api() == null ? null : Runtime.api().getModuleApplicationInfo();
            if (mod != null && mod.sourceDir != null) {
                File modApk = new File(mod.sourceDir);
                digest.update(("|mod:" + modApk.length() + ":" + modApk.lastModified())
                        .getBytes(StandardCharsets.UTF_8));
            }

            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable throwable) {
            Runtime.error("TargetCache fingerprint failed", throwable);
            return null;
        }
    }

    public static Bundle load(Context app, ClassLoader classLoader, String expectedFp) {
        if (app == null || classLoader == null || expectedFp == null) {
            return null;
        }
        File file = new File(app.getCacheDir(), FILE_NAME);
        if (!file.isFile()) {
            return null;
        }
        try {
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            }
            if (!SCHEMA.equals(props.getProperty("schema"))
                    || !expectedFp.equals(props.getProperty("fingerprint"))) {
                return null;
            }

            Bundle bundle = new Bundle();
            bundle.fromCache = true;
            bundle.promotedClass = nonEmpty(props.getProperty("promotedClass"));
            bundle.urtPostClass = nonEmpty(props.getProperty("urtPostClass"));
            bundle.availablePostClass = nonEmpty(props.getProperty("availablePostClass"));
            bundle.itemClasses.addAll(splitLines(props.getProperty("itemClasses")));

            String itemBuilderSig = nonEmpty(props.getProperty("itemBuilder"));
            if (itemBuilderSig != null) {
                bundle.itemBuilder = parseMethod(itemBuilderSig, classLoader);
                if (bundle.itemBuilder == null) {
                    return null;
                }
            }

            if (!loadMethods(props.getProperty("itemBuilderCallers"), classLoader, bundle.itemBuilderCallers)
                    || !loadMethods(props.getProperty("itemRenderMethods"), classLoader, bundle.itemRenderMethods)
                    || !loadMethods(props.getProperty("listLambdas"), classLoader, bundle.listLambdas)
                    || !loadMethods(props.getProperty("renderBoundaries"), classLoader, bundle.renderBoundaries)
                    || !loadMethods(props.getProperty("boundaryCallers"), classLoader, bundle.boundaryCallers)
                    || !loadMethods(props.getProperty("homeReselectMethods"), classLoader, bundle.homeReselectMethods)
                    || !loadMethods(props.getProperty("refreshMethods"), classLoader, bundle.refreshMethods)) {
                return null;
            }

            return bundle.hasUsefulTargets() ? bundle : null;
        } catch (Throwable throwable) {
            Runtime.error("TargetCache load failed, rescanning", throwable);
            return null;
        }
    }

    public static void save(Context app, String fp, Bundle bundle) {
        if (app == null || fp == null || bundle == null || !bundle.hasUsefulTargets()) {
            return;
        }
        try {
            Properties props = new Properties();
            props.setProperty("schema", SCHEMA);
            props.setProperty("fingerprint", fp);
            if (bundle.promotedClass != null) {
                props.setProperty("promotedClass", bundle.promotedClass);
            }
            if (bundle.urtPostClass != null) {
                props.setProperty("urtPostClass", bundle.urtPostClass);
            }
            if (bundle.availablePostClass != null) {
                props.setProperty("availablePostClass", bundle.availablePostClass);
            }
            props.setProperty("itemClasses", joinStrings(bundle.itemClasses));
            if (bundle.itemBuilder != null) {
                props.setProperty("itemBuilder", formatMethod(bundle.itemBuilder));
            }
            props.setProperty("itemBuilderCallers", formatMethods(bundle.itemBuilderCallers));
            props.setProperty("itemRenderMethods", formatMethods(bundle.itemRenderMethods));
            props.setProperty("listLambdas", formatMethods(bundle.listLambdas));
            props.setProperty("renderBoundaries", formatMethods(bundle.renderBoundaries));
            props.setProperty("boundaryCallers", formatMethods(bundle.boundaryCallers));
            props.setProperty("homeReselectMethods", formatMethods(bundle.homeReselectMethods));
            props.setProperty("refreshMethods", formatMethods(bundle.refreshMethods));

            File target = new File(app.getCacheDir(), FILE_NAME);
            File temp = new File(app.getCacheDir(), FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                props.store(out, SCHEMA);
            }
            if (!temp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                if (!temp.renameTo(target)) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("TargetCache save failed", throwable);
        }
    }

    private static boolean loadMethods(String raw, ClassLoader classLoader, List<Method> out) {
        for (String sig : splitLines(raw)) {
            Method method = parseMethod(sig, classLoader);
            if (method == null) {
                return false;
            }
            out.add(method);
        }
        return true;
    }

    private static String formatMethods(List<Method> methods) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < methods.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(formatMethod(methods.get(i)));
        }
        return sb.toString();
    }

    private static String formatMethod(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getName());
        }
        sb.append(')');
        return sb.toString();
    }

    private static Method parseMethod(String sig, ClassLoader classLoader) {
        try {
            int hash = sig.indexOf('#');
            int open = sig.indexOf('(', hash + 1);
            int close = sig.lastIndexOf(')');
            if (hash <= 0 || open <= hash || close < open) {
                return null;
            }
            String ownerName = sig.substring(0, hash);
            String methodName = sig.substring(hash + 1, open);
            String paramsRaw = sig.substring(open + 1, close);

            Class<?> owner = Class.forName(ownerName, false, classLoader);
            Class<?>[] params;
            if (paramsRaw.isEmpty()) {
                params = new Class<?>[0];
            } else {
                String[] parts = paramsRaw.split(",");
                params = new Class<?>[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    params[i] = loadType(parts[i], classLoader);
                }
            }
            Method method = owner.getDeclaredMethod(methodName, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> loadType(String name, ClassLoader classLoader) throws ClassNotFoundException {
        switch (name) {
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "char": return char.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "void": return void.class;
            default: return Class.forName(name, false, classLoader);
        }
    }

    private static String joinStrings(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static List<String> splitLines(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String nonEmpty(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
