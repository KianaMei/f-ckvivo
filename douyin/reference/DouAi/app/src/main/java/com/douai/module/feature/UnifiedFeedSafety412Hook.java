package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Only the original-DEX-proven safety-task branches and feed insertion entry points. */
public final class UnifiedFeedSafety412Hook implements AutoCloseable {
    private final BehaviorHookGroup safety, insertion;
    private volatile LocalOptions options;
    private volatile boolean closed;
    private volatile boolean safetyReady, insertionReady;
    private UnifiedFeedSafety412Hook(LocalOptions options, Consumer<String> log) {
        this.options = options; safety = new BehaviorHookGroup("unified412_safety", log);
        insertion = new BehaviorHookGroup("unified412_insertion", log);
    }
    public static UnifiedFeedSafety412Hook install(XposedInterface framework, HostScanReport report,
                                                   ClassLoader loader, LocalOptions options, Consumer<String> log) {
        if (HostAdaptation.currentProfile() != HostAdaptation.PROFILE_412) throw new IllegalStateException("Reviewed 41.2 profile required");
        UnifiedFeedSafety412Hook hook = new UnifiedFeedSafety412Hook(options, log);
        try {
            Class<?> unit = Class.forName("kotlin.Unit", false, loader);
            Object returned = unit.getField("INSTANCE").get(null);
            for (String id : new String[]{"adv_ui_hidden_recall_task", "verified412_safety_secondary", "adv_ui_hidden_background_task"}) {
                Method target = HostTargets.method(report, id, loader);
                if (target.getReturnType() != Object.class || target.getParameterCount() != 0) throw new IllegalStateException("Safety task shape changed");
                boolean primary = id.equals("adv_ui_hidden_recall_task"), secondary = id.equals("verified412_safety_secondary");
                Field branch = primary || secondary ? target.getDeclaringClass().getDeclaredField("$t") : null;
                if (branch != null) { if (branch.getType() != int.class) throw new IllegalStateException("Safety branch field changed"); branch.setAccessible(true); }
                hook.safety.add(framework, target, chain -> {
                    boolean selected = branch == null || primary && (branch.getInt(chain.getThisObject()) == 0 || branch.getInt(chain.getThisObject()) == 1)
                            || secondary && branch.getInt(chain.getThisObject()) == 1;
                    return !hook.closed && hook.safetyReady && hook.safety.isActive() && hook.options.enabled("prevent_skip_hidden") && selected
                            ? returned : chain.proceed();
                });
            }
            hook.safetyReady = true;
        } catch (Exception | LinkageError failure) { hook.safety.fail(failure); }
        try {
            for (String id : new String[]{"adv_ui_insert_single", "adv_ui_insert_single_fallback", "adv_ui_insert_list", "adv_ui_insert_list_fallback"}) {
                Method target = HostTargets.method(report, id, loader);
                if (target.getReturnType() != void.class || target.getParameterCount() != 2 || target.getParameterTypes()[0] != int.class) throw new IllegalStateException("Feed insertion shape changed");
                hook.insertion.add(framework, target, chain -> !hook.closed && hook.insertionReady && hook.insertion.isActive()
                        && hook.options.enabled("prevent_insert_video") ? null : chain.proceed());
            }
            hook.insertionReady = true;
        } catch (Exception | LinkageError failure) { hook.insertion.fail(failure); }
        return hook;
    }
    public boolean supports(String feature) { return !closed && ("prevent_skip_hidden".equals(feature) ? safetyReady && safety.isActive() : "prevent_insert_video".equals(feature) && insertionReady && insertion.isActive()); }
    public void setOptions(LocalOptions value) { options = value; }
    @Override public void close() { closed = true; safety.close(); insertion.close(); }
}
