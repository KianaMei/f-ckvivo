package dev.ganvivo.origintweaks;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedModule;

/**
 * Add "系统分享" as a super-drag menu option.
 *
 * The menu is List<IntentionService> in TransIslandIntentionBean. We append a
 * synthetic item cloned from the first visible item (for its icon), with
 * operation "skill=shareText". The existing shareText skill (ne.f) is hooked so
 * our service id opens the Android system chooser with the dragged ClipData.
 */
final class ShareHooks {

    private static final String MARKER = "om_system_share";
    private static ClassLoader sCl;

    private ShareHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        sCl = cl;
        try {
            Class<?> bean = Class.forName(
                    "com.vivo.intentservice.bean.TransIslandIntentionBean", false, cl);
            for (Method m : Hooks.methods(bean, "getMServiceInfo")) {
                Hooks.hook(module, m, chain -> {
                    Object result = chain.proceed();
                    try {
                        return inject(result);
                    } catch (Throwable t) {
                        Logx.w("share inject failed", t);
                        return result;
                    }
                });
            }
            Logx.i("super-drag share menu hook installed");
        } catch (Throwable t) {
            Logx.w("super-drag menu hook failed", t);
        }

        try {
            Class<?> skill = Class.forName("ne.f", false, cl);
            for (Method m : Hooks.methods(skill, "a")) {
                Hooks.hook(module, m, chain -> {
                    if (chain.getArgs().size() < 2 || chain.getArg(1) == null) {
                        return chain.proceed();
                    }
                    Object configs;
                    Object serviceId;
                    try {
                        configs = Hooks.call(chain.getArg(1), "getConfigs");
                        serviceId = configs == null ? null : Hooks.call(configs, "getServiceId");
                    } catch (Throwable t) {
                        return chain.proceed();
                    }
                    if (!(serviceId instanceof String) || !MARKER.equals(serviceId)) {
                        return chain.proceed();
                    }
                    // Always consume our synthetic item. If the chooser cannot
                    // start (no context/background denial), do nothing instead
                    // of running the original skill with a fake target package.
                    try {
                        boolean launched = launchChooser(chain.getArg(0), cl);
                        if (launched) {
                            Logx.i("system share chooser launched");
                        } else {
                            Logx.w("system share chooser not launched; skipped", null);
                        }
                    } catch (Throwable t) {
                        Logx.w("share skill hook", t);
                    }
                    return null;
                });
            }
            Logx.i("shareText skill hook installed");
        } catch (Throwable t) {
            Logx.w("shareText skill hook failed", t);
        }

        // The menu filters entries through ge.a.b(transIslandInfo, sourcePkg,
        // intentionService). Our synthetic target is a system chooser, not an
        // installed share app, so force that capability check to true.
        try {
            Class<?> menuFilter = Class.forName("ge.a", false, cl);
            for (Method m : Hooks.methods(menuFilter, "b")) {
                Hooks.hook(module, m, chain -> {
                    if (chain.getArgs().size() < 3 || chain.getArg(2) == null) {
                        return chain.proceed();
                    }
                    Object configs;
                    Object serviceId;
                    try {
                        configs = Hooks.call(chain.getArg(2), "getConfigs");
                        serviceId = configs == null ? null : Hooks.call(configs, "getServiceId");
                    } catch (Throwable t) {
                        return chain.proceed();
                    }
                    if (MARKER.equals(serviceId)) {
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });
            }
            Logx.i("share capability hook installed");
        } catch (Throwable t) {
            Logx.w("share capability hook failed", t);
        }

        // he.n.q(config) hides services whose package is not installed. The
        // system chooser is a framework surface, not an installable share app,
        // so keep our entry visible.
        try {
            Class<?> configClass = Class.forName(
                    "com.vivo.intentservice.bean.IntentionServiceBaseConfig", false, cl);
            Class<?> menu = Class.forName("he.n", false, cl);
            Hooks.hook(module, Hooks.method(menu, "q", configClass), chain -> {
                if (chain.getArgs().size() < 1 || chain.getArg(0) == null) {
                    return chain.proceed();
                }
                Object serviceId;
                try {
                    serviceId = Hooks.call(chain.getArg(0), "getServiceId");
                } catch (Throwable t) {
                    return chain.proceed();
                }
                if (MARKER.equals(serviceId)) {
                    return Boolean.TRUE;
                }
                return chain.proceed();
            });
            Logx.i("share visibility hook installed");
        } catch (Throwable t) {
            Logx.w("share visibility hook failed", t);
        }
    }

    private static Object inject(Object result) {
        if (!(result instanceof List)) {
            return result;
        }
        List list = (List) result;
        if (containsMarker(list)) {
            return result;
        }
        Object service = buildService(list);
        if (service == null) {
            return result;
        }
        // Return a fresh list instead of mutating the bean's list: the menu may
        // be read on another thread, and list.add could throw or race.
        ArrayList copy = new ArrayList(list);
        copy.add(0, service);
        return copy;
    }

    private static boolean containsMarker(List list) {
        for (Object o : list) {
            try {
                Object configs = Hooks.call(o, "getConfigs");
                Object serviceId = configs == null ? null : Hooks.call(configs, "getServiceId");
                if (MARKER.equals(serviceId)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Object buildService(List list) {
        try {
            Class<?> configClass = Class.forName(
                    "com.vivo.intentservice.bean.IntentionServiceBaseConfig", false, sCl);
            Constructor<?> configCtor = configClass.getConstructor(
                    String.class, String.class, String.class, String.class,
                    int.class, String.class, String.class, String.class,
                    int.class, boolean.class, List.class, List.class, Map.class);
            Object config = configCtor.newInstance(
                    MARKER, "系统分享", "com.android.intentresolver", "",
                    3, "",
                    "pkg=com.android.intentresolver:id=systemShare:skill=shareText",
                    "", 0, true,
                    new ArrayList(), new ArrayList(), new HashMap());

            Class<?> infoClass = Class.forName(
                    "com.vivo.intentservice.bean.IntentionServiceInfo", false, sCl);
            Class<?> appInfoClass = Class.forName(
                    "com.vivo.common.dragdrop.data.bean.AIEAppInfo", false, sCl);
            Object appInfo = appInfoClass.getDeclaredConstructor().newInstance();

            set(appInfo, "setAppName", "系统分享");
            set(appInfo, "setPkgName", "com.android.intentresolver");
            set(appInfo, "setServiceId", MARKER);
            set(appInfo, "setUniqueId", MARKER);
            set(appInfo, "setOpenMode", 3);
            set(appInfo, "setIntentId", "");

            Drawable icon = null;
            String iconLink = null;
            if (!list.isEmpty()) {
                try {
                    Object firstApp = Hooks.call(list.get(0), "getAieAppInfo");
                    Object value = Hooks.call(firstApp, "getIconRes");
                    if (value instanceof Drawable) {
                        icon = (Drawable) value;
                    }
                    Object link = Hooks.call(firstApp, "getIconLink");
                    if (link instanceof String) {
                        iconLink = (String) link;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (icon == null) {
                icon = android.content.res.Resources.getSystem()
                        .getDrawable(android.R.drawable.ic_menu_share);
            }
            if (iconLink == null) {
                iconLink = "android";
            }
            set(appInfo, "setIconRes", icon);
            set(appInfo, "setIconLink", iconLink);

            Object info = infoClass.getConstructor(
                    String.class, appInfoClass, boolean.class, List.class)
                    .newInstance("", appInfo, false, new ArrayList());

            Class<?> serviceClass = Class.forName(
                    "com.vivo.intentservice.bean.IntentionService", false, sCl);
            Object service = serviceClass.getDeclaredConstructor().newInstance();
            serviceClass.getMethod("setConfigs", configClass).invoke(service, config);
            serviceClass.getMethod("setIntentionServiceInfo", infoClass).invoke(service, info);
            try {
                serviceClass.getMethod("setCacheIconRes", Drawable.class).invoke(service, icon);
            } catch (Throwable ignored) {
            }
            try {
                serviceClass.getMethod("setCanSupportShare", int.class)
                        .invoke(service, Integer.valueOf(1));
            } catch (Throwable ignored) {
            }
            return service;
        } catch (Throwable t) {
            Logx.w("build share service failed", t);
            return null;
        }
    }

    private static void set(Object target, String method, Object value) {
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(method) && m.getParameterTypes().length == 1) {
                    m.invoke(target, value);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean launchChooser(Object transIslandInfo, ClassLoader cl) {
        try {
            Object value = Hooks.call(transIslandInfo, "getClipdata");
            if (!(value instanceof ClipData)) {
                return false;
            }
            ClipData clip = (ClipData) value;
            Context context = Ctx.get(cl);
            if (context == null) {
                Logx.w("share: no context", null);
                return false;
            }
            Intent send = buildSendIntent(clip);
            Intent chooser = Intent.createChooser(send, "分享");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            return true;
        } catch (Throwable t) {
            Logx.w("launch system chooser failed", t);
            return false;
        }
    }

    private static Intent buildSendIntent(ClipData clip) {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        String text = null;
        String html = null;
        String mime = "*/*";
        ClipDescription description = clip.getDescription();
        if (description != null && description.getMimeTypeCount() > 0) {
            String first = description.getMimeType(0);
            if (first != null) {
                mime = first;
            }
        }
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item == null) {
                continue;
            }
            if (item.getUri() != null) {
                uris.add(item.getUri());
            }
            if (text == null && item.getText() != null) {
                text = item.getText().toString();
            }
            if (html == null && item.getHtmlText() != null) {
                html = item.getHtmlText();
            }
        }

        Intent send;
        if (uris.size() > 1) {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        } else if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND);
            String body = text != null ? text : (html != null ? html : "");
            send.putExtra(Intent.EXTRA_TEXT, body);
            if ("*/*".equals(mime)) {
                mime = "text/plain";
            }
        }
        send.setType(mime);
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return send;
    }
}
