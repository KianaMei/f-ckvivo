package dev.ganvivo.dragall;

import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedModule;

final class DragFeature {
    private static final int CATCH_ITEM = 20;
    private static final int CATCH_SUCCESS = 21;
    private static final int CATCH_FAILED = 22;
    private static final int CATCH_DOWNLOAD = 23;
    private static final int ACTIVITY_NOT_SUPPORTED = 27;
    private static final String REQUEST_ID = "widget_req_id_key";
    private static final String SOURCE = "widget_package_key";
    private static final String COMPONENT = "widget_component_name_key";
    private static final String ACTIVITY = "widget_activity_name_key";
    private static final String WIDGET_TYPE = "widget_type_key";
    private static final String CLIP = "drag_clip_data_key";
    private static final String PREVIEW = "drag_clip_data_image";

    private DragFeature() {}

    static HookGroup install(XposedModule module, ClassLoader loader,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        Method gate = Reflection.method(Reflection.load(loader, "vb.p"), "h", boolean.class,
                String.class, String.class);
        Method lookup = Reflection.method(Reflection.load(loader, "bb.a"), "a", Object.class,
                String.class, String.class);
        Class<?> captureClass = Reflection.load(loader, "vb.o");
        Method capture = Reflection.method(captureClass, "p", Object.class, Object.class);
        Field request = Reflection.field(captureClass, "k", Message.class);
        Method send = Reflection.method(Messenger.class, "send", void.class, Message.class);
        DragProfile profiles = new DragProfile(loader);
        List<Method> callers = new ArrayList<>();
        for (Method caller : Reflection.callers(Reflection.load(loader, "yb.f"))) {
            Class<?>[] parameters = caller.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == Message.class
                    && (caller.getReturnType() == void.class || caller.getReturnType() == boolean.class)) {
                callers.add(caller);
            }
        }
        if (callers.isEmpty()) throw new NoSuchMethodException("yb.f message handler missing");
        CallerScope scope = new CallerScope();
        // The catch request this thread is handling, so the gate can see what was pressed.
        ThreadLocal<Message> handling = new ThreadLocal<>();
        // Set when the gate refused a player surface; the reply for it is sent on the same thread.
        ThreadLocal<Boolean> playerRefused = new ThreadLocal<>();
        // Bilibili Compose requests still waiting for the catcher's answer.
        Set<String> composeRequests = ConcurrentHashMap.newKeySet();
        HookGroup group = new HookGroup(module, diagnostics, Config.DRAG);
        try {
            group.hook(gate, chain -> {
                Object original;
                if (!scope.active() || !valid(chain.getArg(0)) || !valid(chain.getArg(1))) {
                    original = chain.proceed();
                    if (scope.active()) diagnostics.hit("drag_rejected:" + chain.getArg(0),
                            "invalid activity=" + chain.getArg(1));
                    return original;
                }
                original = chain.proceed();
                String gatePackage = (String) chain.getArg(0);
                String gateActivity = String.valueOf(chain.getArg(1));
                boolean pageContent = false;
                if (isPlayerSurface(gatePackage, gateActivity)) {
                    // The player and the comment list share one Activity. Only a press that a Compose
                    // host reported is page content; everything else is the player and stays blocked.
                    Message catching = handling.get();
                    if (!BiliViewerDragFeature.COMPOSE_HOST.equals(text(catching, COMPONENT))) {
                        GateState.record(gatePackage, false);
                        if (catching != null) playerRefused.set(Boolean.TRUE);
                        diagnostics.hit("drag_gate:" + gatePackage,
                                "activity=" + gateActivity + " player_blocked original=" + original);
                        return false;
                    }
                    pageContent = true;
                }
                GateState.record(gatePackage, Boolean.TRUE.equals(original));
                diagnostics.hit((pageContent ? "drag_gate_compose:" : "drag_gate:") + gatePackage,
                        "activity=" + gateActivity + " gate passed original=" + original);
                return true;
            });
            group.hook(lookup, chain -> {
                Object original = chain.proceed();
                if (!scope.active() || !valid(chain.getArg(0))) return original;
                Object list = chain.getArg(1);
                if (!"EnhancedDragWhiteListV2".equals(list)) return original;
                Object profile = profiles.allowPackage(original, (String) chain.getArg(0));
                diagnostics.hit("drag_profile:" + chain.getArg(0), "package policy"
                        + " generic=" + (original == null));
                return profile;
            });
            for (Method caller : callers) {
                group.hook(caller, chain -> {
                    if (!config.enabled(Config.DRAG)) return chain.proceed();
                    Object argument = chain.getArg(0);
                    Message catching = argument instanceof Message
                            && ((Message) argument).what == CATCH_ITEM ? (Message) argument : null;
                    Message outer = handling.get();
                    if (catching != null) {
                        handling.set(catching);
                        if (outer != catching) remember(catching, composeRequests, diagnostics);
                    }
                    int previous = scope.enter();
                    try {
                        return chain.proceed();
                    } finally {
                        scope.exit(previous);
                        if (catching != null) {
                            if (outer == null) {
                                handling.remove();
                                playerRefused.remove();
                            } else {
                                handling.set(outer);
                            }
                        }
                    }
                });
                diagnostics.info(Config.DRAG + " caller deopt=" + module.deoptimize(caller) + " " + caller);
            }
            group.hook(send, chain -> {
                Object argument = chain.getArg(0);
                if (argument instanceof Message && config.enabled(Config.DRAG)) {
                    Message reply = (Message) argument;
                    if (reply.what == ACTIVITY_NOT_SUPPORTED && handling.get() != null
                            && Boolean.TRUE.equals(playerRefused.get())) {
                        // 27 makes the source app blacklist the whole Activity for up to a day, which
                        // would take the comment list down with the player. 22 only ends this press.
                        reply.what = CATCH_FAILED;
                        diagnostics.hit("drag_player_cancel", "replied 22 instead of 27");
                    } else if (reply.what == CATCH_SUCCESS || reply.what == CATCH_DOWNLOAD
                            || reply.what == CATCH_FAILED) {
                        Bundle data = reply.peekData();
                        String id = text(data, REQUEST_ID);
                        if (!id.isEmpty() && composeRequests.remove(id)) {
                            boolean failed = reply.what == CATCH_FAILED;
                            boolean image = !failed && carriesImage(data);
                            diagnostics.hit("bili_compose_reply:" + (failed ? "failed" : image ? "image" : "other"),
                                    describe(reply.what, data));
                            // Text on Bilibili belongs to the free-copy module; only images may leave.
                            if (!failed && !image) reply.what = CATCH_FAILED;
                        }
                    }
                }
                return chain.proceed();
            });
            group.hook(capture, chain -> {
                if (!config.enabled(Config.DRAG)) return chain.proceed();
                Message message = (Message) request.get(chain.getThisObject());
                if (message == null) return chain.proceed();
                CharSequence packageValue = message.getData().getCharSequence("widget_package_key");
                String packageName = packageValue == null ? null : packageValue.toString();
                if (!valid(packageName)) return chain.proceed();
                diagnostics.hit("drag_capture:" + packageName, "async request");
                int previous = scope.enter();
                try {
                    return chain.proceed();
                } finally {
                    scope.exit(previous);
                }
            });
            diagnostics.info("drag_capture caller deopt=" + module.deoptimize(capture));
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private static final String[][] PLAYER_SURFACES = {
            {"tv.danmaku.bili", "UnitedBizDetailsActivity"},
            {"tv.danmaku.bili", "VideoDetailActivity"},
            {"tv.danmaku.bili", "StoryVideoActivity"},
            {"tv.danmaku.bili", "BangumiDetailActivity"},
            {"tv.danmaku.bili", "LiveRoomActivity"},
            {"tv.danmaku.bili", "LivePlayer"},
            {"tv.danmaku.bili", "PlayerWebActivity"},
    };

    /** Video player pages must keep their own long-press (2x speed) and scrub gestures. */
    static boolean isPlayerSurface(String packageName, String activity) {
        for (String[] rule : PLAYER_SURFACES) {
            if (rule[0].equals(packageName) && activity.contains(rule[1])) return true;
        }
        return false;
    }

    private static void remember(Message catching, Set<String> composeRequests, Diagnostics diagnostics) {
        try {
            Bundle data = catching.peekData();
            if (!Config.BILI.equals(text(data, SOURCE))) return;
            String component = text(data, COMPONENT);
            boolean compose = BiliViewerDragFeature.COMPOSE_HOST.equals(component);
            diagnostics.hit("bili_request:" + (compose ? "compose" : "view"), "component=" + component
                    + " type=" + text(data, WIDGET_TYPE) + " activity=" + text(data, ACTIVITY));
            String id = text(data, REQUEST_ID);
            if (!compose || id.isEmpty()) return;
            if (composeRequests.size() >= 16) composeRequests.clear();
            composeRequests.add(id);
        } catch (Throwable failure) {
            // Bookkeeping only: the request itself must still reach the vendor handler.
            diagnostics.failure("bilibili request not tracked", failure);
        }
    }

    private static boolean carriesImage(Bundle data) {
        Object clip = data == null ? null : data.get(CLIP);
        if (!(clip instanceof ClipData)) return false;
        ClipData content = (ClipData) clip;
        if (content.getItemCount() > 0 && content.getItemAt(0).getUri() != null) return true;
        ClipDescription description = content.getDescription();
        boolean plainText = description != null && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
        return !plainText && data.get(PREVIEW) instanceof Bitmap;
    }

    /** Shape of a reply for the log: types, sizes and key names, never the dragged content. */
    private static String describe(int what, Bundle data) {
        StringBuilder shape = new StringBuilder("what=").append(what);
        if (data == null) return shape.toString();
        Object clip = data.get(CLIP);
        if (clip instanceof ClipData) {
            ClipData content = (ClipData) clip;
            ClipDescription description = content.getDescription();
            shape.append(" items=").append(content.getItemCount());
            for (int index = 0; description != null && index < description.getMimeTypeCount(); index++) {
                shape.append(index == 0 ? " mime=" : ",").append(description.getMimeType(index));
            }
            if (content.getItemCount() > 0) {
                shape.append(" uri=").append(content.getItemAt(0).getUri() != null);
            }
        }
        Object preview = data.get(PREVIEW);
        if (preview instanceof Bitmap) {
            shape.append(" preview=").append(((Bitmap) preview).getWidth())
                    .append('x').append(((Bitmap) preview).getHeight());
        }
        return shape.append(" type=").append(text(data, WIDGET_TYPE))
                .append(" keys=").append(data.keySet()).toString();
    }

    private static String text(Message message, String key) {
        return message == null ? "" : text(message.peekData(), key);
    }

    private static String text(Bundle data, String key) {
        CharSequence value = data == null ? null : data.getCharSequence(key);
        return value == null ? "" : value.toString();
    }

    private static boolean valid(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty() && !"NA".equals(value);
    }
}
