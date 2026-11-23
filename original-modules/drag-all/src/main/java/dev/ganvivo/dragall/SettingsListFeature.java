package dev.ganvivo.dragall;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import io.github.libxposed.api.XposedModule;

final class SettingsListFeature {
    private final Context context;
    private List<String> cachedPackages;
    private long cachedAt;

    private SettingsListFeature(Context context) {
        this.context = context;
    }

    static HookGroup install(XposedModule module, ClassLoader loader, Context context,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        Method list = Reflection.method(Reflection.load(loader, "bb.a"), "c", List.class);
        Method filter = Reflection.method(Reflection.load(loader, "kb.i1"), "b", boolean.class,
                Context.class, String.class);
        List<Method> callers = Reflection.callers(Reflection.load(loader, "jh.z"));
        SettingsListFeature feature = new SettingsListFeature(context);
        CallerScope scope = new CallerScope();
        HookGroup group = new HookGroup(module, diagnostics, Config.APPS);
        try {
            group.hook(list, chain -> {
                Object original = chain.proceed();
                if (!scope.active() || !(original instanceof List<?>)) return original;
                try {
                    List<String> merged = feature.merge((List<?>) original);
                    if (merged == null) return original;
                    diagnostics.hit(Config.APPS, "count=" + ((List<?>) original).size() + "->" + merged.size());
                    return merged;
                } catch (Throwable failure) {
                    diagnostics.failure("launcher app query failed", failure);
                    return original;
                }
            });
            group.hook(filter, chain -> {
                Object packageName = chain.getArg(1);
                if (scope.active() && packageName instanceof String
                        && !((String) packageName).isEmpty()) return true;
                return chain.proceed();
            });
            for (Method caller : callers) group.scope(caller, scope, config, Config.APPS);
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private List<String> merge(List<?> original) {
        Set<String> merged = new LinkedHashSet<>();
        for (Object item : original) {
            if (!(item instanceof String)) return null;
            merged.add((String) item);
        }
        if (!merged.addAll(launcherPackages())) return null;
        return new ArrayList<>(merged);
    }

    private synchronized List<String> launcherPackages() {
        long now = SystemClock.elapsedRealtime();
        if (cachedPackages != null && now - cachedAt < 30_000) return cachedPackages;
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = context.getPackageManager().queryIntentActivities(launcher, 0);
        Set<String> packageNames = new TreeSet<>();
        if (activities != null) {
            for (ResolveInfo activity : activities) {
                if (activity.activityInfo != null && activity.activityInfo.packageName != null) {
                    packageNames.add(activity.activityInfo.packageName);
                }
            }
        }
        cachedPackages = new ArrayList<>(packageNames);
        cachedAt = now;
        return cachedPackages;
    }
}
