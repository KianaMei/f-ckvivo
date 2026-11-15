package local.codex.coolapk;

import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 酷安回复赞助过滤（合并进「酷安净化」模块的第二入口）。
 *
 * 背景见 coolapk/NOTES.md 附六：16.7.3 的 feedDetailReplySponsorCard 不走 ViewHolder，
 * 是在 com.coolapk.market.remote.EntityListTypeAdapter 的 Ϳ(...) 反序列化时直接塞进列表的，
 * 所以净化模块自带的 replySponsor profile 永远 TARGET_MISSING。
 * 这里挂住这个类里所有名为 Ϳ 的方法，把返回 List 里模板名含 sponsor 的项删掉。
 *
 * 只读列表、只删赞助项，不改酷安配置和数据库。
 * 使用 Vector 的新 Xposed API（io.github.libxposed.api，102），
 * 与净化模块共用同一个 APK 与同一个 java_init.list 入口。
 */
public final class MergedHook extends XposedModule {

    private static final String TAG = "CoolapkReply";
    private static final String TARGET = "com.coolapk.market";
    private static final String ADAPTER = "com.coolapk.market.remote.EntityListTypeAdapter";
    /** Ϳ = U+037F，16.7.3 里 EntityListTypeAdapter 的解析方法名。 */
    private static final String HOOK_NAME = "\u037f";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    /** 只在前几次解析时打点，确认 hook 真的走到了（正常浏览不刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEEN =
        new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        if (!TARGET.equals(pkg)) {
            return;
        }
        final ClassLoader cl = param.getClassLoader();
        Log.i(TAG, "attached " + pkg);
        if (install(cl)) {
            return;
        }
        // 易盾壳可能还没把宿主类装进 ClassLoader，后台重试一会儿
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 20 && !INSTALLED.get(); i++) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (install(cl)) {
                        return;
                    }
                }
                Log.i(TAG, "gave up, " + ADAPTER + " not found");
            }
        }, "coolapk-reply-filter");
        t.setDaemon(true);
        t.start();
    }

    private boolean install(ClassLoader cl) {
        if (INSTALLED.get()) {
            return true;
        }
        Class<?> adapter;
        try {
            adapter = Class.forName(ADAPTER, false, cl);
        } catch (Throwable notYet) {
            return false;
        }
        int hooked = 0;
        for (Method m : adapter.getDeclaredMethods()) {
            if (!HOOK_NAME.equals(m.getName())) {
                continue;
            }
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
                // 框架会在 hook 时处理访问性
            }
            try {
                hook(m).intercept(new SponsorFilter());
                hooked++;
            } catch (Throwable t) {
                Log.i(TAG, "hook failed " + m + ": " + t);
            }
        }
        if (hooked == 0) {
            return false;
        }
        INSTALLED.set(true);
        Log.i(TAG, "hooked " + ADAPTER + " methods=" + hooked);
        return true;
    }

    private static final class SponsorFilter implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) {
            Object result = chain.proceed();
            filter(result);
            return result;
        }
    }

    private static void filter(Object result) {
        if (!(result instanceof List)) {
            return;
        }
        List list = (List) result;
        if (SEEN.getAndIncrement() < 3) {
            Log.i(TAG, "intercept list size=" + list.size());
        }
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            Object item = list.get(i);
            if (item == null) {
                continue;
            }
            String template = templateOf(item);
            if (template != null && template.toLowerCase(Locale.ROOT).contains("sponsor")) {
                try {
                    list.remove(i);
                    removed++;
                } catch (Throwable ignored) {
                    // 不可变列表就跳过，别把宿主搞崩
                }
            }
        }
        if (removed > 0) {
            Log.i(TAG, "removed sponsor cards=" + removed);
        }
    }

    private static String templateOf(Object item) {
        Class<?> c = item.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod("getEntityTemplate");
                m.setAccessible(true);
                Object v = m.invoke(item);
                return v == null ? null : String.valueOf(v);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 调试用：确认 hook 实例拿到的是哪个 Executable。 */
    static String describe(Executable e) {
        return e == null ? "null" : e.toString();
    }
}
