package dev.ganvivo.origintweaks;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import io.github.libxposed.api.XposedModule;

/**
 * Apply a window blur + translucent background to the "超级拖拽" settings page.
 * This is the same page the user calls 超级拖拽页面; it is a normal settings list
 * with an opaque window background, so blur-behind plus a light scrim gives the
 * liquid-glass look without touching any resource or partition.
 */
final class GlassHooks {

    private GlassHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        try {
            Hooks.hook(module,
                    Hooks.method(Instrumentation.class, "callActivityOnResume", Activity.class),
                    chain -> {
                        Object result = chain.proceed();
                        Object value = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                        if (value instanceof Activity) {
                            Activity activity = (Activity) value;
                            String name = activity.getClass().getName();
                            if (name.contains("SuperDraggingSettingActivity")) {
                                apply(activity);
                            }
                        }
                        return result;
                    });
            Logx.i("super-drag glass hook installed");
        } catch (Throwable t) {
            Logx.w("super-drag glass hook failed", t);
        }
    }

    private static void apply(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 31) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                window.setBackgroundBlurRadius(80);
            }
            window.setBackgroundDrawable(new ColorDrawable(0x40FFFFFF));
            View decor = window.peekDecorView();
            if (decor != null) {
                decor.setBackgroundColor(0x00000000);
            }
            Logx.i("glass applied: " + activity.getClass().getName());
        } catch (Throwable t) {
            Logx.w("apply glass failed", t);
        }
    }
}
