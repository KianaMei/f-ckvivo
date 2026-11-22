package dev.ganvivo.dragall;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.github.libxposed.api.XposedModule;

final class ShareFeature {
    private static final String MARKER = "drag_all_system_share";
    private final Context context;
    private final Diagnostics diagnostics;
    private final Class<?> serviceClass;
    private final Constructor<?> configConstructor;
    private final Constructor<?> appConstructor;
    private final Constructor<?> infoConstructor;
    private final Constructor<?> serviceConstructor;
    private final Method getConfigs;
    private final Method getServiceId;
    private final Method setConfigs;
    private final Method setInfo;
    private final Method setCacheIcon;
    private final Method setShareSupport;
    private final Map<String, Method> appSetters = new HashMap<>();

    private ShareFeature(ClassLoader loader, Context context, Diagnostics diagnostics) throws Throwable {
        this.context = context;
        this.diagnostics = diagnostics;
        Class<?> configClass = Reflection.load(loader, "com.vivo.intentservice.bean.IntentionServiceBaseConfig");
        Class<?> infoClass = Reflection.load(loader, "com.vivo.intentservice.bean.IntentionServiceInfo");
        Class<?> appClass = Reflection.load(loader, "com.vivo.common.dragdrop.data.bean.AIEAppInfo");
        serviceClass = Reflection.load(loader, "com.vivo.intentservice.bean.IntentionService");
        configConstructor = configClass.getConstructor(String.class, String.class, String.class,
                String.class, int.class, String.class, String.class, String.class, int.class,
                boolean.class, List.class, List.class, Map.class);
        appConstructor = appClass.getDeclaredConstructor();
        serviceConstructor = serviceClass.getDeclaredConstructor();
        infoConstructor = infoClass.getConstructor(String.class, appClass, boolean.class, List.class);
        getConfigs = serviceClass.getMethod("getConfigs");
        getServiceId = configClass.getMethod("getServiceId");
        setConfigs = serviceClass.getMethod("setConfigs", configClass);
        setInfo = serviceClass.getMethod("setIntentionServiceInfo", infoClass);
        setCacheIcon = serviceClass.getMethod("setCacheIconRes", Drawable.class);
        setShareSupport = serviceClass.getMethod("setCanSupportShare", int.class);
        for (String name : new String[]{"setAppName", "setPkgName", "setServiceId", "setUniqueId",
                "setIntentId", "setIconLink"}) {
            appSetters.put(name, appClass.getMethod(name, String.class));
        }
        appSetters.put("setOpenMode", appClass.getMethod("setOpenMode", int.class));
        appSetters.put("setIconRes", appClass.getMethod("setIconRes", Drawable.class));
    }

    static HookGroup install(XposedModule module, ClassLoader loader, Context context,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        ShareFeature feature = new ShareFeature(loader, context, diagnostics);
        Class<?> inputClass = Reflection.load(loader, "com.vivo.common.dragdrop.data.bean.TransIslandInfo");
        Class<?> callbackClass = Reflection.load(loader, "ee.a");
        Method skill = Reflection.method(Reflection.load(loader, "ne.f"), "a", void.class,
                inputClass, feature.serviceClass, callbackClass);
        Method getClip = Reflection.method(inputClass, "getClipdata", ClipData.class);
        Method capability = Reflection.method(Reflection.load(loader, "ge.a"), "b", Boolean.class,
                inputClass, String.class, feature.serviceClass);
        Class<?> configClass = feature.configConstructor.getDeclaringClass();
        Method visibility = Reflection.method(Reflection.load(loader, "he.n"), "q", boolean.class, configClass);
        Method items = Reflection.method(Reflection.load(loader,
                "com.vivo.intentservice.bean.TransIslandIntentionBean"), "getMServiceInfo", List.class);
        HookGroup group = new HookGroup(module, diagnostics, Config.SHARE);
        try {
            group.hook(skill, chain -> {
                if (!feature.isMarker(chain.getArg(1))) return chain.proceed();
                if (!config.enabled(Config.SHARE)) return null;
                try {
                    Object value = Reflection.invoke(getClip, chain.getArg(0));
                    Intent chooser = value instanceof ClipData
                            ? ShareIntentBuilder.build(context, (ClipData) value) : null;
                    if (chooser == null) {
                        feature.unavailable();
                    } else {
                        context.startActivity(chooser);
                        diagnostics.hit(Config.SHARE, "chooser launch requested");
                    }
                } catch (Throwable failure) {
                    diagnostics.failure("system share launch failed", failure);
                    feature.unavailable();
                }
                return null;
            });
            group.hook(capability, chain -> feature.isMarker(chain.getArg(2))
                    ? config.enabled(Config.SHARE) : chain.proceed());
            group.hook(visibility, chain -> feature.isMarkerConfig(chain.getArg(0))
                    ? config.enabled(Config.SHARE) : chain.proceed());
            group.hook(items, chain -> {
                Object original = chain.proceed();
                if (!(original instanceof List<?>)) return original;
                try {
                    return feature.items((List<?>) original, config.enabled(Config.SHARE));
                } catch (Throwable failure) {
                    diagnostics.failure("system share menu unchanged", failure);
                    return original;
                }
            });
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private Object items(List<?> original, boolean enabled) throws Throwable {
        List<Object> result = new ArrayList<>(original.size() + 1);
        boolean found = false;
        for (Object item : original) {
            if (isMarker(item)) found = true;
            else result.add(item);
        }
        if (!enabled) return found ? result : original;
        result.add(0, buildService());
        diagnostics.hit("share_menu", "item added");
        return result;
    }

    private Object buildService() throws Throwable {
        Object config = configConstructor.newInstance(MARKER, "系统分享", "com.android.intentresolver", "",
                3, "", "pkg=com.android.intentresolver:id=systemShare:skill=shareText", "", 0, true,
                new ArrayList<>(), new ArrayList<>(), new HashMap<>());
        Object app = appConstructor.newInstance();
        set(app, "setAppName", "系统分享");
        set(app, "setPkgName", "com.android.intentresolver");
        set(app, "setServiceId", MARKER);
        set(app, "setUniqueId", MARKER);
        set(app, "setOpenMode", 3);
        set(app, "setIntentId", "");
        set(app, "setIconLink", "");
        Drawable icon = new SystemShareIcon(context.getResources().getDisplayMetrics().density);
        set(app, "setIconRes", icon);
        Object info = infoConstructor.newInstance("", app, false, new ArrayList<>());
        Object service = serviceConstructor.newInstance();
        Reflection.invoke(setConfigs, service, config);
        Reflection.invoke(setInfo, service, info);
        Reflection.invoke(setCacheIcon, service, icon);
        Reflection.invoke(setShareSupport, service, 1);
        if (!isMarker(service)) throw new IllegalStateException("Synthetic share service has no marker");
        return service;
    }

    private void set(Object app, String name, Object value) throws Throwable {
        Reflection.invoke(appSetters.get(name), app, value);
    }

    private boolean isMarker(Object service) {
        if (!serviceClass.isInstance(service)) return false;
        try {
            return isMarkerConfig(Reflection.invoke(getConfigs, service));
        } catch (Throwable failure) {
            diagnostics.failure("share marker unreadable", failure);
            return false;
        }
    }

    private boolean isMarkerConfig(Object config) {
        if (config == null || !configConstructor.getDeclaringClass().isInstance(config)) return false;
        try {
            return MARKER.equals(Reflection.invoke(getServiceId, config));
        } catch (Throwable failure) {
            diagnostics.failure("share config unreadable", failure);
            return false;
        }
    }

    private void unavailable() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(context, "暂时无法打开系统分享", Toast.LENGTH_SHORT).show();
            } catch (RuntimeException failure) {
                diagnostics.failure("share feedback unavailable", failure);
            }
        });
    }
}
