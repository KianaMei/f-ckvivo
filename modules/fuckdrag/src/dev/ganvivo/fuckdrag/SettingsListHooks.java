package dev.ganvivo.fuckdrag;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * "超级拖拽" settings page only lists packages present in the vendor JSON
 * (EnhancedDragWhiteListV2 / EnhancedDragFeatureListV2). The page loader is
 * jh.z: it calls bb.a.c(), filters with kb.i1.b(), then builds the item list.
 *
 * Expand bb.a.c() *only for that loader* so the page shows every installed
 * launcher app in addition to the vendor list. The cached list is never
 * mutated, and other callers (runtime drag gate, availability probe) keep the
 * original data.
 */
final class SettingsListHooks {

    private static final AtomicInteger EXPAND_LOG = new AtomicInteger();

    private SettingsListHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        try {
            Class<?> config = Class.forName("bb.a", false, cl);
            for (Method m : Hooks.methods(config, "c")) {
                Hooks.hook(module, m, chain -> {
                    Object result = chain.proceed();
                    try {
                        return expand(result, cl);
                    } catch (Throwable t) {
                        Logx.w("settings list expand failed", t);
                        return result;
                    }
                });
            }
            Logx.i("settings app list hook installed (bb.a.c)");
        } catch (Throwable t) {
            Logx.w("settings app list hook failed", t);
        }

        // The loader also drops a couple of vendor packages; for this page only,
        // do not filter them out.
        try {
            Class<?> availability = Class.forName("kb.i1", false, cl);
            Hooks.hook(module, Hooks.method(availability, "b", Context.class, String.class),
                    chain -> {
                        if (fromSettingsLoader()) {
                            return Boolean.TRUE;
                        }
                        return chain.proceed();
                    });
            Logx.i("settings app filter hook installed (kb.i1.b)");
        } catch (Throwable t) {
            Logx.w("settings app filter hook failed", t);
        }
    }

    private static Object expand(Object value, ClassLoader cl) {
        if (!fromSettingsLoader()) {
            return value;
        }
        if (!(value instanceof List)) {
            return value;
        }
        List<?> original = (List<?>) value;
        Context context = Ctx.get(cl);
        if (context == null) {
            return value;
        }

        ArrayList<String> merged = new ArrayList<String>();
        HashSet<String> seen = new HashSet<String>();
        for (Object item : original) {
            if (item instanceof String) {
                String pkg = (String) item;
                if (pkg.length() > 0 && seen.add(pkg)) {
                    merged.add(pkg);
                }
            }
        }
        int vendorCount = merged.size();

        boolean queried = false;
        try {
            PackageManager pm = context.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> activities = pm.queryIntentActivities(launcher, 0);
            if (activities != null) {
                queried = true;
                for (ResolveInfo info : activities) {
                    if (info == null || info.activityInfo == null) {
                        continue;
                    }
                    String pkg = info.activityInfo.packageName;
                    if (pkg != null && pkg.length() > 0 && seen.add(pkg)) {
                        merged.add(pkg);
                    }
                }
            }
        } catch (Throwable t) {
            Logx.w("query launcher apps failed", t);
        }

        // Package-visibility or OEM launcher oddities: fall back to the visible
        // installed application list rather than showing nothing.
        if (!queried) {
            try {
                PackageManager pm = context.getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                if (apps != null) {
                    for (ApplicationInfo app : apps) {
                        if (app == null || app.packageName == null
                                || app.packageName.length() == 0) {
                            continue;
                        }
                        if (seen.add(app.packageName)) {
                            merged.add(app.packageName);
                        }
                    }
                }
            } catch (Throwable t) {
                Logx.w("query installed apps failed", t);
            }
        }

        if (merged.size() == vendorCount) {
            return value;
        }
        if (EXPAND_LOG.getAndIncrement() < 10) {
            Logx.i("settings app list expanded " + vendorCount + " -> " + merged.size());
        }
        return merged;
    }

    private static boolean fromSettingsLoader() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("jh.z".equals(frame.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
