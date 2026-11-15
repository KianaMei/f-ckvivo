package dev.ganvivo.origintweaks;

import io.github.libxposed.api.XposedModule;

/**
 * OriginOS 7 gates 原子工作台 (Vivo MultiTask) to the inner screen:
 *
 *   VivoMultiTaskManagerService.isSupportVivoMultiTask():
 *     return (IS_FOLD && "local:secondary".equals(defaultDisplay.uniqueId)) ? false : true;
 *
 * On this device the inner display is "local:4627039422300187648" and the outer
 * display is "local:secondary". We only lift that specific false result, and
 * keep every other blocker (Xspace, super power save, children mode, simple
 * launcher, feature flag) intact.
 */
final class MultiTaskHooks {

    private MultiTaskHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        try {
            Class<?> service = Class.forName(
                    "com.android.server.wm.VivoMultiTaskManagerService", false, cl);
            Class<?> constants = Class.forName(
                    "com.vivo.multitask.VivoMultiTaskConstants", false, cl);

            Hooks.hook(module, Hooks.method(service, "isSupportVivoMultiTask"), chain -> {
                Object result = chain.proceed();
                // Only lift a concrete false; null/unknown means the
                // service state could not be read, so stay out of it.
                if (!Boolean.FALSE.equals(result)) {
                    return result;
                }
                if (!Hooks.staticBool(constants, "IS_FOLD")) {
                    return result;
                }
                if (!Hooks.staticBool(service, "SUPPORT_VIVO_MULTI_TASK")) {
                    return result;
                }
                Object self = chain.getThisObject();
                if (Hooks.bool(self, "mInXspaceStatus")
                        || Hooks.bool(self, "mInSuperPowerSaveMode")
                        || Hooks.bool(self, "mInChildrenMode")) {
                    return result;
                }
                // If simple-launcher detection fails, do not guess:
                // enabling multi-task there may hide the launcher UI.
                boolean simple;
                try {
                    simple = Boolean.TRUE.equals(Hooks.call(self, "isInSimpleStyleForLauncher"));
                } catch (Throwable t) {
                    Logx.w("multitask simple-style check failed; skip", t);
                    return result;
                }
                if (simple) {
                    return result;
                }
                Logx.i("multitask outer-screen unlock applied");
                return Boolean.TRUE;
            });
            Logx.i("multitask outer-screen hook installed");
        } catch (Throwable t) {
            Logx.w("multitask hook failed", t);
        }
    }
}
