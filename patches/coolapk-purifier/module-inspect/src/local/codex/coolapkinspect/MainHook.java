package local.codex.coolapkinspect;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 酷安运行时类结构导出器（只跑在 com.coolapk.market）。
 *
 * 目的：酷安 16.7.3 被易盾（com.netease.nis.wrapper）加固，APK 里的 classes.dex 只有 30 个壳类，
 * 真实类在运行时由壳解密后定义，静态拿不到完整结构。这个模块在进程内把运行时真正存在的
 * 类/父类/接口/方法签名/字段全部导出成 JSON，供离线给「酷安净化」生成 16.7.3 的 profile。
 *
 * 采集手段（多路兜底，全部只读、只写自己 files 目录，不动酷安任何数据）：
 *   1. hook ClassLoader.defineClass(...) —— 壳若走 Java 层定义类就能直接拿到名字
 *   2. hook DexFile / InMemoryDexClassLoader 构造 —— 记录被加载的 dex
 *   3. hook ClassLoader.loadClass(String) —— 记录运行中加载过的类名
 *   4. 直接遍历 BaseDexClassLoader 的 dexElements，用 DexFile.entries() 枚举
 *
 * 输出：/data/data/com.coolapk.market/files/fqi/classes.json（root 侧 pull 即可）
 */
public final class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "CoolapkInspect";
    private static final String TARGET_PACKAGE = "com.coolapk.market";
    private static final String OUT_DIR = "fqi";
    private static final String OUT_FILE = "classes.json";

    private static final Set<String> NAMES = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final AtomicBoolean DUMPED = new AtomicBoolean(false);
    private static final AtomicBoolean WORKER_STARTED = new AtomicBoolean(false);

    private static volatile Context APP;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET_PACKAGE.equals(lp.packageName)) {
            return;
        }
        log("=== attached to " + lp.processName + " (sdk=" + Build.VERSION.SDK_INT + ") ===");
        // 主进程的 ClassLoader 在 Application 起来后才有宿主类，Activity 创建时再补一刀。
        try {
            Class<?> inst = Class.forName("android.app.Instrumentation", false, lp.classLoader);
            Class<?> activity = Class.forName("android.app.Activity", false, lp.classLoader);
            Class<?> bundle = Class.forName("android.os.Bundle", false, lp.classLoader);
            XposedHelpers.findAndHookMethod(
                inst, "callActivityOnCreate", activity, bundle,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object act = param.args[0];
                        if (act == null || FILTER_HOOKED.get()) {
                            return;
                        }
                        try {
                            ClassLoader cl = (ClassLoader) XposedHelpers.callMethod(act, "getClassLoader");
                            filterSponsorCards(cl);
                        } catch (Throwable t) {
                            log("activity filter failed: " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            log("activity hook failed: " + t);
        }
        // 只做回复赞助过滤。重型探针（dump / binder）默认不跑，避免每次开酷安扫几千个类。
        Thread probe = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 12; i++) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    Context ctx = context();
                    if (ctx == null) {
                        continue;
                    }
                    filterSponsorCards(ctx.getClassLoader());
                    if (FILTER_HOOKED.get()) {
                        break;
                    }
                }
            }
        }, "coolapk-reply-filter");
        probe.setDaemon(true);
        probe.start();
    }

    // --------------------------------------------------------------- 采集钩子

    private static void hookDefineClass(ClassLoader cl) {
        Class<?> loader = ClassLoader.class;
        // defineClass(String, byte[], int, int) 与带 ProtectionDomain 的重载
        hookAll(loader, "defineClass", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof String) {
                    record((String) param.args[0]);
                }
            }
        });
        log("H1 defineClass hooks ready");
    }

    private static void hookDexLoading(ClassLoader cl) {
        for (String name : new String[]{
            "dalvik.system.DexFile",
            "dalvik.system.InMemoryDexClassLoader",
            "dalvik.system.DexClassLoader",
        }) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    try {
                        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                log("dex object created: " + param.thisObject.getClass().getName());
                            }
                        });
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                log("hook " + name + " failed: " + t);
            }
        }
        log("H2 dex loading hooks ready");
    }

    /**
     * 关键一步：壳把解密后的 dex 通过 InMemoryDexClassLoader(ByteBuffer) 交给 ART，
     * 手里就是一个完整的 dex 字节缓冲。把它整段抄出来 = 拿到未加固的 dex。
     */
    private static void hookInMemoryDex(ClassLoader cl) {
        try {
            Class<?> imdc = Class.forName("dalvik.system.InMemoryDexClassLoader", false, cl);
            XposedBridge.hookAllConstructors(imdc, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        for (Object arg : param.args) {
                            if (arg instanceof java.nio.ByteBuffer) {
                                saveDexBuffer((java.nio.ByteBuffer) arg, "imdc");
                            } else if (arg instanceof java.nio.ByteBuffer[]) {
                                java.nio.ByteBuffer[] arr = (java.nio.ByteBuffer[]) arg;
                                for (int i = 0; i < arr.length; i++) {
                                    saveDexBuffer(arr[i], "imdc-" + i);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log("save buffers failed: " + t);
                    }
                }
            });
            log("H5 InMemoryDexClassLoader captured");
        } catch (Throwable t) {
            log("H5 hook failed: " + t);
        }

        // DexFile(ByteBuffer) 也兜一下
        try {
            Class<?> dexFile = Class.forName("dalvik.system.DexFile", false, cl);
            XposedBridge.hookAllConstructors(dexFile, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        for (Object arg : param.args) {
                            if (arg instanceof java.nio.ByteBuffer) {
                                saveDexBuffer((java.nio.ByteBuffer) arg, "dexfile");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("H5b DexFile(ByteBuffer) captured");
        } catch (Throwable t) {
            log("H5b hook failed: " + t);
        }
    }

    private static final AtomicBoolean SIZE_LOGGED = new AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicInteger PROBE_HOOKED =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final AtomicBoolean FILTER_HOOKED = new AtomicBoolean(false);

    /** 把 ByteBuffer 里的 dex 原样落盘（不改动原 buffer 位置/limit）。 */
    private static void saveDexBuffer(java.nio.ByteBuffer src, String tag) {
        if (src == null) {
            return;
        }
        java.nio.ByteBuffer buf;
        try {
            buf = src.duplicate();
        } catch (Throwable t) {
            return;
        }
        int len = buf.remaining();
        if (len < 112) {
            return;
        }
        try {
            int p = buf.position();
            byte[] magic = new byte[8];
            buf.duplicate().get(magic);
            boolean isDex = magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x' && magic[3] == 10;
            boolean isCdex = magic[0] == 'c' && magic[1] == 'd' && magic[2] == 'e' && magic[3] == 'x';
            if (!isDex && !isCdex) {
                return;
            }
            // 注意：壳会在 Application.onCreate 之前就加载 dex，这时 APP 还是 null。
            // 本进程 uid 就是酷安的 uid，可以直接写它自己的 files 目录。
            File dir = new File("/data/user/0/" + TARGET_PACKAGE + "/files/" + OUT_DIR + "/dex");
            if (!dir.exists() && !dir.mkdirs()) {
                Context ctx = APP;
                if (ctx == null) {
                    return;
                }
                dir = new File(ctx.getFilesDir(), OUT_DIR + "/dex");
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
            }
            String name = tag + "-" + (isDex ? "dex" : "cdex") + "-" + len + "-"
                + Integer.toHexString(java.util.Arrays.hashCode(magic)) + "-" + System.nanoTime() + ".bin";
            File out = new File(dir, name);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                byte[] chunk = new byte[1 << 20];
                buf.position(p);
                int remain = len;
                while (remain > 0) {
                    int n = Math.min(chunk.length, remain);
                    buf.get(chunk, 0, n);
                    fos.write(chunk, 0, n);
                    remain -= n;
                }
            } finally {
                fos.close();
            }
            log("saved dex buffer " + name + " (" + len + " bytes)");
        } catch (Throwable t) {
            log("saveDexBuffer failed: " + t);
        }
    }

    private static void hookLoadClass(ClassLoader cl) {
        // 易盾的类加载器多半 override 了 loadClass，但通常仍会回调
        // ClassLoader#loadClass(String, boolean) 或 BaseDexClassLoader#findClass。
        // 这几个都挂上，才能看到真正被定义的 app 类。
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        record((String) param.args[0]);
                    }
                }
            });
        } catch (Throwable t) {
            log("hook ClassLoader#loadClass failed: " + t);
        }
        try {
            Class<?> bdcl = Class.forName("dalvik.system.BaseDexClassLoader", false, cl);
            XposedBridge.hookAllMethods(bdcl, "findClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        record((String) param.args[0]);
                    }
                }
            });
            log("H6 BaseDexClassLoader#findClass hooked");
        } catch (Throwable t) {
            log("H6 failed: " + t);
        }
        try {
            Class<?> dpl = Class.forName("dalvik.system.DexPathList", false, cl);
            XposedBridge.hookAllMethods(dpl, "findClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        record((String) param.args[0]);
                    }
                }
            });
            log("H7 DexPathList#findClass hooked");
        } catch (Throwable t) {
            log("H7 failed: " + t);
        }
        hookAll(ClassLoader.class, "loadClass", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof String) {
                    record((String) param.args[0]);
                }
            }
        });
        log("H3 loadClass hooks ready");
    }

    private static void hookAppCreate(ClassLoader cl) {
        try {
            Class<?> instrumentation = Class.forName("android.app.Instrumentation", false, cl);
            hookAll(instrumentation, "callApplicationOnCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length > 0 && param.args[0] instanceof Application) {
                            APP = (Application) param.args[0];
                        }
                    } catch (Throwable ignored) {
                    }
                    startWorker();
                }
            });
            // 双保险：Application#onCreate 也挂上，避免壳改 Instrumentation 导致 H4 不触发
            XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof Application) {
                            APP = (Application) param.thisObject;
                        }
                    } catch (Throwable ignored) {
                    }
                    startWorker();
                }
            });
            log("H4 application hooks ready (Instrumentation + Application#onCreate)");
        } catch (Throwable t) {
            log("H4 failed: " + t);
        }
    }

    private static void hookAll(Class<?> owner, String method, XC_MethodHook cb) {
        try {
            for (Method m : owner.getDeclaredMethods()) {
                if (!method.equals(m.getName())) {
                    continue;
                }
                try {
                    XposedBridge.hookAllMethods(owner, method, cb);
                } catch (Throwable t) {
                    log("hookAll " + owner.getName() + "#" + method + " failed: " + t);
                }
                return;
            }
        } catch (Throwable t) {
            log("hookAll(" + owner.getName() + "#" + method + ") failed: " + t);
        }
    }

    /** 兜底取 Context：H4 若在 Application 创建之后才装上，就用 ActivityThread 现取。 */
    private static Context context() {
        Context c = APP;
        if (c != null) {
            return c;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            if (app instanceof Context) {
                APP = (Context) app;
                return APP;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void record(String name) {
        if (name == null || name.length() == 0 || name.startsWith("[")) {
            return;
        }
        if (NAMES.size() > 400000) {
            return;
        }
        NAMES.add(name);
    }

    // --------------------------------------------------------------- 导出流程

    private static void startWorker() {
        if (!WORKER_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(8000);                 // 等壳把 dex 装完
                    Set<String> fromDex = enumerateDexFiles();
                    log("dex enumeration: " + fromDex.size() + " names; hooked names: " + NAMES.size());
                    Set<String> all = new LinkedHashSet<String>();
                    all.addAll(fromDex);
                    all.addAll(NAMES);
                    dump(all);
                } catch (Throwable t) {
                    log("worker failed: " + t);
                }
                watchTrigger();                          // 之后按需重跑
            }
        }, "coolapk-inspect");
        t.setDaemon(true);
        t.start();
    }

    /** 轮询 files/fqi/trigger：出现就再导一次（用于「UI 逛一圈后重新采样」）。 */
    private static void watchTrigger() {
        File dir = new File("/data/user/0/" + TARGET_PACKAGE + "/files/" + OUT_DIR);
        File trigger = new File(dir, "trigger");
        File brute = new File(dir, "brute");
        while (true) {
            try {
                Thread.sleep(2000);
                if (!trigger.exists()) {
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                trigger.delete();
                log("trigger detected, re-dumping (brute=" + brute.exists() + ")");
                Set<String> all = new LinkedHashSet<String>();
                all.addAll(enumerateDexFiles());
                all.addAll(NAMES);
                if (brute.exists()) {
                    bruteShortNames();
                }
                if (new File(dir, "binders.txt").exists()) {
                    probeBinders();
                }
                all.addAll(NAMES);
                DUMPED.set(false);
                dump(all);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                log("trigger loop failed: " + t);
            }
        }
    }

    /**
     * 绑定器探针：16.7.3 的卡片都走 `z26`/`jd` 子类的 ގ(Entity) 方法，
     * 在这里把「实体模板 -> 绑定器类」的对应关系打出来，用于给净化模块补 target。
     */
    private static void probeBinders() {
        Context ctx = context();
        if (ctx == null) {
            return;
        }
        final ClassLoader cl = ctx.getClassLoader();
        File list = new File("/data/user/0/" + TARGET_PACKAGE + "/files/" + OUT_DIR + "/binders.txt");
        java.io.BufferedReader r = null;
        int hooked = 0;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(list), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                final String name = line.trim();
                if (name.length() < 1) {
                    continue;
                }
                try {
                    Class<?> c = Class.forName(name, false, cl);
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (!"ގ".equals(m.getName()) || m.getParameterTypes().length != 1
                            || !"com.coolapk.market.model.Entity".equals(m.getParameterTypes()[0].getName())
                            || m.getReturnType() != void.class) {
                            continue;
                        }
                        XposedBridge.hookAllMethods(c, "ގ", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object e = param.args[0];
                                    Object tpl = XposedHelpers.callMethod(e, "getEntityTemplate");
                                    log("BINDER " + name + " <- template=" + tpl);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                        hooked++;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            PROBE_HOOKED.set(hooked);
            // 兜底：直接 hook Entity.getEntityTemplate()。
            // 这个方法在 Entity 本身上就有实现（子类只覆盖字段），
            // 所以 hook 基类就能覆盖所有实体，不再依赖某几个具体子类。
            int tplHooks = hookEntityTemplates(cl);
            log("probeBinders: entity template hooks=" + tplHooks);
            hookCardCtor(cl);
            hookZ26(cl);
            hookOnBind(cl);
            log("probeBinders hooked=" + hooked);
        } catch (Throwable t) {
            log("probeBinders failed: " + t);
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 回复赞助卡在 EntityListTypeAdapter.Ϳ 反序列化时就进了 List，
     * 没有任何 ViewHolder 再去绑定它。这里直接从列表里删掉。
     */
    private static void filterSponsorCards(ClassLoader cl) {
        try {
            Class<?> adapter = Class.forName(
                "com.coolapk.market.remote.EntityListTypeAdapter", true, cl);
            XposedBridge.hookAllMethods(adapter, "\u037f", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof java.util.List)) {
                        return;
                    }
                    java.util.List list = (java.util.List) result;
                    int removed = 0;
                    for (int i = list.size() - 1; i >= 0; i--) {
                        Object e = list.get(i);
                        if (e == null) {
                            continue;
                        }
                        String tpl;
                        try {
                            Object t = XposedHelpers.callMethod(e, "getEntityTemplate");
                            tpl = t == null ? "" : String.valueOf(t);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (tpl.toLowerCase().contains("sponsor")) {
                            list.remove(i);
                            removed++;
                            log("FILTER removed " + tpl);
                        }
                    }
                    if (removed > 0) {
                        log("FILTER total=" + removed);
                    }
                }
            });
            FILTER_HOOKED.set(true);
            log("filterSponsorCards hooked");
        } catch (Throwable t) {
            log("filterSponsorCards failed: " + t);
        }
    }

    /**
     * Adapter.onBindViewHolder 是 abstract。16.7.3 里只有这几个具体 Adapter，
     * 逐个挂上，绑定时打出 ViewHolder 类名。赞助卡会单独标 BIND-HIT。
     */
    private static final Set<String> SEEN_HOLDERS = Collections.newSetFromMap(
        new ConcurrentHashMap<String, Boolean>());

    private static void hookOnBind(ClassLoader cl) {
        String[] adapters = new String[]{
            "j3", "c2j", "gid", "it6", "lkj", "qs2", "thd", "xef", "z00",
            "com.coolapk.market.view.feedv8.photo.PickPhotoAdapterV8",
            "com.coolapk.market.view.product.subdirectory.ExpandableRecyclerViewAdapter"
        };
        int n = 0;
        for (String name : adapters) {
            try {
                Class<?> c = Class.forName(name, true, cl);
                XposedBridge.hookAllMethods(c, "onBindViewHolder", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                            || param.args[0] == null) {
                            return;
                        }
                        Object holder = param.args[0];
                        String who = holder.getClass().getName();
                        String hit = findSponsor(holder);
                        if (hit != null) {
                            log("BIND-HIT holder=" + who + " " + hit);
                            return;
                        }
                        if (SEEN_HOLDERS.add(who)) {
                            log("BIND holder=" + who);
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        log("hooked concrete onBindViewHolder=" + n);
    }

    private static String findSponsor(Object holder) {
        Class<?> c = holder.getClass();
        for (int depth = 0; c != null && depth < 4; depth++, c = c.getSuperclass()) {
            java.lang.reflect.Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (java.lang.reflect.Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(holder);
                } catch (Throwable t) {
                    continue;
                }
                String s = sponsorOf(v);
                if (s != null) {
                    return s + " field=" + f.getName();
                }
            }
        }
        return null;
    }

    private static String sponsorOf(Object v) {
        if (v == null) {
            return null;
        }
        try {
            Object t = XposedHelpers.callMethod(v, "getEntityTemplate");
            if (t == null) {
                return null;
            }
            String tpl = String.valueOf(t);
            String low = tpl.toLowerCase();
            if (low.contains("sponsor") || low.contains("goods")) {
                return "tpl=" + tpl + " entity=" + v.getClass().getName();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * z26.ޅ(Object) 是 abstract，Xposed 挂不上。
     * 名单是离线 dump 里所有具体实现了 ޅ(Object) 且祖先是 ViewHolder 的类。
     */
    private static void hookZ26(ClassLoader cl) {
        java.io.BufferedReader br = null;
        int n = 0;
        try {
            java.io.InputStream in = null;
            Context ctx = context();
            if (ctx != null) {
                in = ctx.createPackageContext("local.codex.coolapkinspect", 0)
                    .getAssets().open("z26-classes.txt");
            }
            if (in == null) {
                log("z26-classes.txt missing");
                return;
            }
            br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            String name;
            while ((name = br.readLine()) != null) {
                name = name.trim();
                if (name.length() == 0) {
                    continue;
                }
                try {
                    final Class<?> c = Class.forName(name, true, cl);
                    XposedBridge.hookAllMethods(c, "\u0785", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0
                                || param.args[0] == null) {
                                return;
                            }
                            Object e = param.args[0];
                            String tpl = "";
                            try {
                                Object t = XposedHelpers.callMethod(e, "getEntityTemplate");
                                tpl = t == null ? "" : String.valueOf(t);
                            } catch (Throwable ignored) {
                            }
                            String low = tpl.toLowerCase();
                            if (!(low.contains("sponsor") || low.contains("ad")
                                || low.contains("goods"))) {
                                return;
                            }
                            String who = param.thisObject == null
                                ? c.getName() : param.thisObject.getClass().getName();
                            log("Z26-HIT " + who + " <- " + tpl);
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            log("hooked z26 subclasses=" + n);
        } catch (Throwable t) {
            log("hookZ26 failed: " + t);
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 赞助卡构造时打调用栈，定位真正的 binder。 */
    private static void hookCardCtor(ClassLoader cl) {
        try {
            Class<?> card = Class.forName(
                "com.coolapk.market.model.AutoValue_EntityCard", true, cl);
            XposedBridge.hookAllConstructors(card, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object tpl = XposedHelpers.callMethod(param.thisObject, "getEntityTemplate");
                        if (tpl == null || !String.valueOf(tpl).toLowerCase().contains("sponsor")) {
                            return;
                        }
                        log("CARD-CTOR " + tpl);
                        StackTraceElement[] st = new Throwable().getStackTrace();
                        int limit = Math.min(st.length, 30);
                        for (int i = 0; i < limit; i++) {
                            String cn = st[i].getClassName();
                            if (cn.startsWith("local.codex") || cn.contains("Xposed")
                                || cn.startsWith("WqCN") || cn.startsWith("org.lsposed")
                                || cn.startsWith("de.robv") || cn.startsWith("LSPHooker")
                                || cn.equals("g") || cn.equals("e2") || cn.equals("c3")) {
                                continue;
                            }
                            log("CARD-ST " + cn + "#" + st[i].getMethodName());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("hooked AutoValue_EntityCard ctors");
        } catch (Throwable t) {
            log("hookCardCtor failed: " + t);
        }
    }

    /**
     * Entity.getEntityTemplate() 是 abstract，Xposed 挂不上。
     * 名单来自 assets/tpl-classes.txt（离线 dump 里所有具体实现，78 个）。
     * 每个模板只记一次；名字里带 sponsor/ad/goods 的打 TPL-HIT。
     */
    private static final Set<String> SEEN_TPL = Collections.newSetFromMap(
        new ConcurrentHashMap<String, Boolean>());

    private static int hookEntityTemplates(ClassLoader cl) {
        int n = 0;
        java.io.BufferedReader br = null;
        try {
            java.io.InputStream in = MainHook.class.getClassLoader()
                .getResourceAsStream("assets/tpl-classes.txt");
            if (in == null) {
                // 模块 apk 的 assets 不在本 ClassLoader，走 context
                Context ctx = context();
                if (ctx != null) {
                    try {
                        in = ctx.createPackageContext(
                            "local.codex.coolapkinspect", 0).getAssets()
                            .open("tpl-classes.txt");
                    } catch (Throwable t) {
                        log("tpl list via context failed: " + t);
                    }
                }
            }
            if (in == null) {
                log("tpl-classes.txt missing");
                return 0;
            }
            br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            String name;
            while ((name = br.readLine()) != null) {
                name = name.trim();
                if (name.length() == 0 || name.charAt(0) == '#') {
                    continue;
                }
                try {
                    Class<?> c = Class.forName(name, true, cl);
                    XposedBridge.hookAllMethods(c, "getEntityTemplate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object r = param.getResult();
                            if (r == null) {
                                return;
                            }
                            String tpl = String.valueOf(r);
                            String cls = param.thisObject == null
                                ? "?" : param.thisObject.getClass().getName();
                            if (!SEEN_TPL.add(tpl + "|" + cls)) {
                                return;
                            }
                            String low = tpl.toLowerCase();
                            if (low.contains("sponsor") || low.contains("ad")
                                || low.contains("goods")) {
                                log("TPL-HIT " + tpl + " class=" + cls);
                                StackTraceElement[] st = new Throwable().getStackTrace();
                                int limit = Math.min(st.length, 18);
                                for (int i = 0; i < limit; i++) {
                                    log("TPL-ST " + tpl + " " + st[i].getClassName()
                                        + "#" + st[i].getMethodName());
                                }
                            } else {
                                log("TPL " + tpl + " class=" + cls);
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            log("hookEntityTemplates concrete=" + n);
        } catch (Throwable t) {
            log("hookEntityTemplates failed: " + t);
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /**
     * 壳里还有大量「默认包名 + 1~4 字符」的混淆类（b25、gg4、jw、x2f…），
     * 不在 Lcom/coolapk/... 的文本扫描范围里。用 Class.forName 暴力枚举补全。
     */
    private static void bruteShortNames() {
        Context ctx = context();
        if (ctx == null) {
            return;
        }
        ClassLoader cl = ctx.getClassLoader();
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        int found = 0, tried = 0;
        char[] buf = new char[4];
        // 长度 1..4
        for (int len = 1; len <= 4; len++) {
            int total = (int) Math.pow(alphabet.length(), len);
            for (int i = 0; i < total; i++) {
                int v = i;
                for (int k = len - 1; k >= 0; k--) {
                    buf[k] = alphabet.charAt(v % alphabet.length());
                    v /= alphabet.length();
                }
                String name = new String(buf, 0, len);
                tried++;
                try {
                    Class.forName(name, false, cl);
                } catch (Throwable ignored) {
                    continue;
                }
                record(name);
                found++;
            }
            log("brute len=" + len + " tried=" + tried + " found=" + found);
        }
        log("brute done: tried=" + tried + " found=" + found);
    }

    /** 遍历 ClassLoader 的 dexElements，用 DexFile.entries() 枚举（对壳解出来的 dex 也常可用）。 */
    private static Set<String> enumerateDexFiles() {
        Set<String> out = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        try {
            ClassLoader cl = TARGET_PACKAGE.equals(APP != null ? APP.getPackageName() : "")
                ? APP.getClassLoader() : null;
            if (cl == null) {
                return out;
            }
            Field pathListField = findField(cl.getClass(), "pathList");
            Object pathList = pathListField.get(cl);
            Field elementsField = findField(pathList.getClass(), "dexElements");
            Object[] elements = (Object[]) elementsField.get(pathList);
            if (elements == null) {
                return out;
            }
            log("dexElements: " + elements.length);
            for (Object element : elements) {
                try {
                    Field f = findField(element.getClass(), "dexFile");
                    Object dexFile = f.get(element);
                    if (!(dexFile instanceof DexFile)) {
                        log("element dexFile null (in-memory dex): " + element.getClass().getName());
                        continue;
                    }
                    java.util.Enumeration<String> en = ((DexFile) dexFile).entries();
                    while (en.hasMoreElements()) {
                        out.add(en.nextElement());
                    }
                } catch (Throwable t) {
                    log("element failed: " + t);
                }
            }
        } catch (Throwable t) {
            log("enumerateDexFiles failed: " + t);
        }
        return out;
    }

    private static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
        Class<?> c = owner;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(owner + "#" + name);
    }

    private static void dump(Set<String> namesIn) {
        Set<String> names = namesIn;
        if (!DUMPED.compareAndSet(false, true)) {
            return;
        }
        Context ctx = context();
        if (ctx == null) {
            log("no context, abort dump");
            return;
        }
        File dir = new File("/data/user/0/" + TARGET_PACKAGE + "/files/" + OUT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            dir = new File(ctx.getFilesDir(), OUT_DIR);
        }
        if (!dir.exists() && !dir.mkdirs()) {
            log("mkdir " + dir + " failed");
            return;
        }
        File out = new File(dir, "classes-" + System.currentTimeMillis() + ".json");
        ClassLoader cl = ctx.getClassLoader();
        // 静态扫描出来的类名清单（壳把完整类名以明文留在 dex 里），逐个 Class.forName
        Set<String> forced = readNameList(dir);
        if (!forced.isEmpty()) {
            names = new LinkedHashSet<String>(names);
            names.addAll(forced);
            log("force-load from names.txt: " + forced.size() + " (merged=" + names.size() + ")");
        }
        int classes = 0, methods = 0, fields = 0;
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
            w.write("{\"package\":\"com.coolapk.market\",\"classes\":[\n");
            boolean first = true;
            for (String name : names) {
                Class<?> c;
                try {
                    c = Class.forName(name, false, cl);
                } catch (Throwable t) {
                    continue;
                }
                if (c == null) {
                    continue;
                }
                StringBuilder sb = new StringBuilder(512);
                sb.append(first ? "" : ",\n");
                first = false;
                sb.append("{\"name\":\"").append(esc(c.getName())).append("\",");
                sb.append("\"modifiers\":").append(c.getModifiers()).append(',');
                Class<?> sup = c.getSuperclass();
                sb.append("\"super\":");
                if (sup == null) {
                    sb.append("null");
                } else {
                    sb.append('"').append(esc(sup.getName())).append('"');
                }
                sb.append(",\"interfaces\":[");
                Class<?>[] ifaces = c.getInterfaces();
                for (int i = 0; i < ifaces.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(esc(ifaces[i].getName())).append('"');
                }
                sb.append("],\"methods\":[");
                Method[] ms;
                try {
                    ms = c.getDeclaredMethods();
                } catch (Throwable t) {
                    ms = new Method[0];
                }
                for (int i = 0; i < ms.length; i++) {
                    Method m = ms[i];
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"n\":\"").append(esc(m.getName())).append("\",\"mod\":")
                      .append(m.getModifiers()).append(",\"ret\":\"")
                      .append(esc(m.getReturnType().getName())).append("\",\"p\":[");
                    Class<?>[] ps = m.getParameterTypes();
                    for (int k = 0; k < ps.length; k++) {
                        if (k > 0) {
                            sb.append(',');
                        }
                        sb.append('"').append(esc(ps[k].getName())).append('"');
                    }
                    sb.append("]}");
                    methods++;
                }
                sb.append("],\"fields\":[");
                Field[] fs;
                try {
                    fs = c.getDeclaredFields();
                } catch (Throwable t) {
                    fs = new Field[0];
                }
                for (int i = 0; i < fs.length; i++) {
                    Field f = fs[i];
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"n\":\"").append(esc(f.getName())).append("\",\"mod\":")
                      .append(f.getModifiers()).append(",\"t\":\"")
                      .append(esc(f.getType().getName())).append("\"}");
                    fields++;
                }
                sb.append("]}");
                w.write(sb.toString());
                classes++;
            }
            w.write("\n]}\n");
            w.flush();
            log("dumped classes=" + classes + " methods=" + methods + " fields=" + fields
                + " -> " + out + " (" + out.length() + " bytes)");
        } catch (Throwable t) {
            log("dump failed: " + t);
        } finally {
            try {
                if (w != null) {
                    w.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 读 names.txt（一行一个类名），用于强制加载壳里的类。 */
    private static Set<String> readNameList(File dir) {
        Set<String> out = new LinkedHashSet<String>();
        File f = new File(dir, "names.txt");
        if (!f.exists()) {
            log("names.txt not found at " + f);
            return out;
        }
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() > 3) {
                    out.add(line);
                }
            }
            log("names.txt loaded: " + out.size());
        } catch (Throwable t) {
            log("readNameList failed: " + t);
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
}
