package dev.ganvivo.dragall;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * X (12.42+) renders inside Jetpack Compose and lets R8 rename AndroidComposeView (e.g. to
 * androidx.compose.ui.platform.s). OriginOS's ViewGroup already calls
 * VivoDragManager.triggerHandleDragAndDrop for Compose children, but matches the literal string
 * "androidx.compose.ui.platform.AndroidComposeView", so obfuscated hosts are skipped.
 *
 * Rather than hardcoding the obfuscated class name, this hooks AbstractComposeView.addView (looked
 * up from the public, unobfuscated ComposeView superclass) to discover the actual Compose host
 * ViewGroup class once and attach a single ACTION_DOWN reporter mirroring the framework check.
 */
final class XComposeDragFeature {
    static final String COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView";
    private static final String COMPOSE_PLATFORM_PREFIX = "androidx.compose.ui.platform.";
    private static final String UNOBFUSCATED_HOST = "androidx.compose.ui.platform.AndroidComposeView";

    private XComposeDragFeature() {}

    static HookGroup install(XposedModule module, ClassLoader loader, FeatureConfig config,
            Diagnostics diagnostics) throws Throwable {
        Class<?> composeView = Reflection.load(loader, COMPOSE_VIEW);
        Class<?> abstractComposeView = composeView.getSuperclass();
        if (abstractComposeView == null || abstractComposeView == ViewGroup.class
                || !ViewGroup.class.isAssignableFrom(abstractComposeView)) {
            throw new NoSuchMethodException("Unexpected ComposeView superclass: " + abstractComposeView);
        }
        List<Method> addViews = new ArrayList<>();
        for (Method method : abstractComposeView.getDeclaredMethods()) {
            if (!"addView".equals(method.getName())) continue;
            int modifiers = method.getModifiers();
            if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length >= 1 && params[0] == View.class) {
                method.setAccessible(true);
                addViews.add(method);
            }
        }
        if (addViews.isEmpty()) {
            throw new NoSuchMethodException("No addView overloads on " + abstractComposeView.getName());
        }
        Method onAttach = null;
        try {
            onAttach = Reflection.method(abstractComposeView, "onAttachedToWindow", void.class);
        } catch (NoSuchMethodException ignored) {
        }
        Class<?> managerClass = Reflection.load(loader, "com.vivo.vdragdrop.manager.VivoDragManager");
        Method manager = Reflection.method(managerClass, "getInstance", managerClass);
        Method report = Reflection.method(managerClass, "triggerHandleDragAndDrop", void.class,
                View.class, MotionEvent.class);

        Set<Class<?>> hookedHosts = ConcurrentHashMap.newKeySet();
        HookGroup group = new HookGroup(module, diagnostics, "x_compose_drag");
        try {
            for (Method addView : addViews) {
                group.hook(addView, chain -> {
                    Object result = chain.proceed();
                    Object child = chain.getArg(0);
                    if (child instanceof ViewGroup) {
                        hookHostIfNeeded((ViewGroup) child, module, composeView, abstractComposeView,
                                manager, report, config, diagnostics, hookedHosts);
                    }
                    return result;
                });
            }
            if (onAttach != null) {
                group.hook(onAttach, chain -> {
                    Object result = chain.proceed();
                    Object owner = chain.getThisObject();
                    if (owner instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) owner;
                        for (int i = 0; i < vg.getChildCount(); i++) {
                            View child = vg.getChildAt(i);
                            if (child instanceof ViewGroup) {
                                hookHostIfNeeded((ViewGroup) child, module, composeView, abstractComposeView,
                                        manager, report, config, diagnostics, hookedHosts);
                            }
                        }
                    }
                    return result;
                });
            }
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private static void hookHostIfNeeded(ViewGroup child, XposedModule module,
            Class<?> composeView, Class<?> abstractComposeView, Method manager, Method report,
            FeatureConfig config, Diagnostics diagnostics, Set<Class<?>> hookedHosts) {
        Class<?> hostClass = child.getClass();
        String name = hostClass.getName();
        if (hostClass == composeView || hostClass == abstractComposeView
                || !name.startsWith(COMPOSE_PLATFORM_PREFIX)
                || name.contains(UNOBFUSCATED_HOST)) {
            return;
        }
        if (!hookedHosts.add(hostClass)) return;
        try {
            Method dispatch = Reflection.method(hostClass, "dispatchTouchEvent", boolean.class,
                    MotionEvent.class);
            XposedInterface.HookHandle handle = module.hook(dispatch)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object handled = chain.proceed();
                        MotionEvent event = (MotionEvent) chain.getArg(0);
                        if (!Boolean.TRUE.equals(handled) || event == null
                                || event.getActionMasked() != MotionEvent.ACTION_DOWN
                                || !config.enabled(Config.DRAG)) {
                            return handled;
                        }
                        Object target = chain.getThisObject();
                        if (!(target instanceof View)) return handled;
                        View host = (View) target;
                        try {
                            Reflection.invoke(report, Reflection.invoke(manager, null), host, event);
                            diagnostics.hit("x_compose_down", "host=" + name
                                    + " size=" + host.getWidth() + "x" + host.getHeight());
                        } catch (Throwable failure) {
                            diagnostics.failure("x compose host not reported", failure);
                        }
                        return handled;
                    });
            if (handle == null) {
                hookedHosts.remove(hostClass);
                diagnostics.failure("x compose host hook returned null: " + name, null);
            } else {
                diagnostics.info("x_compose_drag hooked obfuscated host " + dispatch);
            }
        } catch (Throwable failure) {
            hookedHosts.remove(hostClass);
            diagnostics.failure("x compose host hook failed: " + name, failure);
        }
    }
}
