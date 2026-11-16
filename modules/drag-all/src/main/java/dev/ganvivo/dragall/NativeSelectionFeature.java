package dev.ganvivo.dragall;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.accessibility.AccessibilityManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.libxposed.api.XposedModule;

final class NativeSelectionFeature {
    private NativeSelectionFeature() {}

    static HookGroup install(XposedModule module, ClassLoader loader, Context context,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        Class<?> capture = Reflection.load(loader, "vb.p");
        Class<?> drag = Reflection.load(loader, "com.vivo.common.dragdrop.data.bean.DragInfo");
        Class<?> widget = Reflection.load(loader, "com.vivo.common.dragdrop.data.bean.WidgetItemBean");
        Class<?> callback = Reflection.load(loader,
                "vivo.accessibilityenhance.server.AccessibilityEnhanceClient$AccessibilityEnhanceCallback");
        Class<?> info = Reflection.load(loader, "vivo.accessibilityenhance.server.AccessibilityEnhanceInfo");
        if (!callback.isInterface()) throw new IllegalStateException("Unexpected capture callback");
        Method start = Reflection.method(capture, "g", void.class, Context.class, boolean.class);
        Method current = Reflection.method(capture, "r", drag);
        Method getWidget = Reflection.method(drag, "getCurrentWidgetItem", widget);
        Method getPackage = Reflection.method(widget, "getPackageName", String.class);
        Method getUri = Reflection.method(drag, "getUri", Uri.class);
        Method setUri = Reflection.method(drag, "setUri", void.class, Uri.class);
        Method setWidgetType = Reflection.method(drag, "setWidgetType", void.class, String.class);
        Method setType = Reflection.method(drag, "setType", void.class, String.class);
        Method setBitmap = Reflection.method(drag, "setLocalBitmap", void.class, Bitmap.class);
        Field serviceReference = Reflection.field(capture, "n", WeakReference.class);
        Class<?> service = Reflection.load(loader, "com.vivo.dragdrop.service.DragService");
        Method notifyResult = Reflection.method(service, "j", void.class, int.class);
        Method request = Reflection.method(AccessibilityManager.class, "requestAccessibilityEnhanceExt",
                void.class, Bundle.class, callback);
        Method resultCallback = callback.getMethod("onRequestResult", info);
        Method getRequest = Reflection.method(info, "getRequestId", String.class);
        Method getComponent = Reflection.method(info, "getComponentName", ComponentName.class);
        Method getCode = Reflection.method(info, "getResultCode", int.class);
        Method setCode = Reflection.method(info, "setResultCode", void.class, int.class);
        Method getData = Reflection.method(info, "getData", Bundle.class);
        Method setData = Reflection.method(info, "setData", void.class, Bundle.class);
        String template;
        Context resources = context.createPackageContext(BuildConfig.APPLICATION_ID, 0);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resources.getAssets().open("telegram-native.json"), StandardCharsets.UTF_8))) {
            StringBuilder rules = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) rules.append(line).append('\n');
            template = rules.toString();
        }
        new JSONArray(template);
        ThreadLocal<String> sourceScope = new ThreadLocal<>();
        HookGroup group = new HookGroup(module, diagnostics, "native_selection");
        try {
            group.hook(start, chain -> {
                if (!config.enabled(Config.DRAG)) return chain.proceed();
                Object state = Reflection.invoke(current, null);
                Object item = state == null ? null : Reflection.invoke(getWidget, state);
                String packageName = item == null ? null : (String) Reflection.invoke(getPackage, item);
                if (!GateState.allowed(packageName)) return chain.proceed();
                if (!ClientCompat.canExtract(context, packageName)) return chain.proceed();
                String previous = sourceScope.get();
                sourceScope.set(packageName);
                try {
                    return chain.proceed();
                } finally {
                    if (previous == null) sourceScope.remove();
                    else sourceScope.set(previous);
                }
            });
            group.hook(request, chain -> {
                Bundle original = (Bundle) chain.getArg(0);
                String sourcePackage = sourceScope.get();
                if (sourcePackage == null || !config.enabled(Config.DRAG) || original == null
                        || !"supportDragDropBusiness".equals(original.getString("actionType"))
                        || !"dragdrop".equals(original.getString("businessName"))) return chain.proceed();
                String requestId = original.getString("requestId", "");
                Object state = Reflection.invoke(current, null);
                Uri mediaUri = state == null ? null : (Uri) Reflection.invoke(getUri, state);
                int pointX = original.getInt("pointX", -1);
                int pointY = original.getInt("pointY", -1);
                if (requestId.isEmpty() || pointX < 0 || pointY < 0) return chain.proceed();
                if (mediaUri == null || !"content".equals(mediaUri.getScheme())
                        || !"media".equals(mediaUri.getAuthority())) {
                    return chain.proceed();
                }
                String output = mediaUri.toString();
                Uri outputUri = mediaUri;
                String rule = template.replace("\"{{POINT_X}}\"", Integer.toString(pointX))
                        .replace("\"{{POINT_Y}}\"", Integer.toString(pointY))
                        .replace("\"{{OUTPUT_URI}}\"", JSONObject.quote(output));
                new JSONArray(rule);
                Bundle adapted = new Bundle(original);
                adapted.putString("actionType", "generalizeGrab");
                adapted.putString("businessPackageName", Config.TARGET);
                adapted.putInt("generalizedType", 0);
                adapted.putString("expressionChain", rule);
                context.grantUriPermission(sourcePackage, outputUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Object originalCallback = chain.getArg(1);
                Object wrapped = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback},
                        (proxy, method, arguments) -> {
                            if (!method.equals(resultCallback)) {
                                return Reflection.invoke(method, originalCallback,
                                        arguments == null ? new Object[0] : arguments);
                            }
                            Object response = arguments[0];
                            if (response != null) {
                                int code = (Integer) Reflection.invoke(getCode, response);
                                ComponentName source = (ComponentName) Reflection.invoke(getComponent, response);
                                boolean matches = requestId.equals(Reflection.invoke(getRequest, response))
                                        && source != null && sourcePackage.equals(source.getPackageName())
                                        && Reflection.invoke(current, null) == state;
                                if (code == 200 && matches) {
                                    try {
                                        Bundle data = new Bundle((Bundle) Reflection.invoke(getData, response));
                                        String kind = data.getString("drag_all_kind", "");
                                        String text = data.getString("content", "");
                                        if ("text".equals(kind) && !text.isEmpty() && text.length() <= 20000) {
                                            data.putString("selectionContent", text);
                                            data.putString("viewClassPath", "org.telegram.ui.Cells.ChatMessageCell");
                                            diagnostics.hit("native_selected_text", "chars=" + text.length());
                                        } else if ("image".equals(kind) && output.equals(data.getString("imageUrl"))) {
                                            Bitmap thumbnail = readPreview(context, mediaUri);
                                            WeakReference<?> reference = (WeakReference<?>) serviceReference.get(null);
                                            Object receiver = reference == null ? null : reference.get();
                                            if (!service.isInstance(receiver)) throw new IllegalStateException("Drag service unavailable");
                                            Reflection.invoke(setUri, state, outputUri);
                                            Reflection.invoke(setWidgetType, state, "10");
                                            Reflection.invoke(setType, state, "image");
                                            Reflection.invoke(setBitmap, state, thumbnail);
                                            context.grantUriPermission(sourcePackage, outputUri,
                                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                            Reflection.invoke(notifyResult, receiver, 30);
                                            diagnostics.hit("native_selected_image", "preview="
                                                    + thumbnail.getWidth() + "x" + thumbnail.getHeight());
                                            return null;
                                        } else {
                                            throw new IllegalArgumentException("Missing native selected content");
                                        }
                                        data.putInt("singleLevel", 4);
                                        Reflection.invoke(setData, response, data);
                                    } catch (Throwable failure) {
                                        Reflection.invoke(setCode, response, -200);
                                        diagnostics.failure("native selected output rejected", failure);
                                    }
                                } else if (!matches) {
                                    Reflection.invoke(setCode, response, -200);
                                }
                                diagnostics.hit("native_selection_result", "code="
                                        + Reflection.invoke(getCode, response) + " sourceMatches=" + matches);
                            }
                            return Reflection.invoke(resultCallback, originalCallback, response);
                        });
                diagnostics.hit("native_selection_request:" + sourcePackage, "source=" + sourcePackage
                        + " point=" + pointX + "," + pointY + " clientHook=false");
                return chain.proceed(new Object[]{adapted, wrapped});
            });
            diagnostics.info("native capture caller deopt=" + module.deoptimize(start));
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private static Bitmap readPreview(Context context, Uri uri) throws Exception {
        BitmapFactory.Options dimensions = new BitmapFactory.Options();
        dimensions.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, dimensions);
        }
        if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0
                || (long) dimensions.outWidth * dimensions.outHeight > 32000000L) {
            throw new IllegalArgumentException("Invalid native image output");
        }
        BitmapFactory.Options preview = new BitmapFactory.Options();
        preview.inSampleSize = 1;
        while (Math.max(dimensions.outWidth, dimensions.outHeight) / preview.inSampleSize > 512) {
            preview.inSampleSize *= 2;
        }
        Bitmap thumbnail;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            thumbnail = BitmapFactory.decodeStream(input, null, preview);
        }
        if (thumbnail == null) throw new IllegalArgumentException("Invalid native preview");
        return thumbnail;
    }
}
