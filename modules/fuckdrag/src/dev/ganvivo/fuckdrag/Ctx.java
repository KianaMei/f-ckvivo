package dev.ganvivo.fuckdrag;

import android.content.Context;

/** Best-effort Application context lookup inside a hooked app process. */
final class Ctx {

    private Ctx() {
    }

    static Context get(ClassLoader cl) {
        try {
            Class<?> helper = Class.forName("android.app.AndroidAppHelper");
            Object app = helper.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> ya = Class.forName("ya.b", false, cl);
            Object app = ya.getMethod("b").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
            if (app != null) {
                Object context = app.getClass().getMethod("getApplicationContext").invoke(app);
                if (context instanceof Context) {
                    return (Context) context;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
