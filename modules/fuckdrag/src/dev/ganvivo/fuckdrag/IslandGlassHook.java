package dev.ganvivo.fuckdrag;

import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import io.github.libxposed.api.XposedModule;

/**
 * The super-drag island paints its card background with a black gradient in
 * SpringTransIslandView.g():
 *
 *   new LinearGradient(0, 0, 0, bottom,
 *       {0xff000000, 0xb3000000}, {0.2f, 1.0f}, CLAMP)
 *
 * The view already calls VBlurUtils.setBlurRegion() when blur is supported, so
 * the blur is behind the black paint. Replace that shader with a translucent
 * white glass gradient after g() returns; the blur then shows through and the
 * top drop area becomes liquid glass instead of a black slab.
 */
final class IslandGlassHook {

    private IslandGlassHook() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        try {
            Class<?> island = Class.forName(
                    "com.vivo.transisland.ui.SpringTransIslandView", false, cl);
            Hooks.hook(module, Hooks.method(island, "g"), chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (!(self instanceof View)) {
                        return result;
                    }
                    Object value = Hooks.field(self, "h");
                    if (!(value instanceof Paint)) {
                        return result;
                    }
                    View view = (View) self;
                    int height = view.getHeight();
                    if (height <= 0) {
                        height = 1000;
                    }
                    Paint paint = (Paint) value;
                    paint.setShader(new LinearGradient(
                            0.0f, 0.0f, 0.0f, height,
                            0x40FFFFFF, 0x66FFFFFF,
                            Shader.TileMode.CLAMP));
                    Logx.i("island glass applied");
                } catch (Throwable t) {
                    Logx.w("island glass apply failed", t);
                }
                return result;
            });
            Logx.i("island glass hook installed");
        } catch (Throwable t) {
            Logx.w("island glass hook failed", t);
        }
    }
}
