package dev.ganvivo.dragall;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedModule;

final class SelectedContentFeature {
    private final Map<String, Bundle> pending = new ConcurrentHashMap<>();
    private final Context context;
    private final Method uriForFile;
    private final Diagnostics diagnostics;
    private final Map<String, Integer> sourceUids = new ConcurrentHashMap<>();

    private SelectedContentFeature(ClassLoader loader, Context context, Diagnostics diagnostics) throws Throwable {
        this.context = context;
        this.diagnostics = diagnostics;
        for (String source : Arrays.asList(Config.FLUTTER_SOURCE, Config.TELEGRAM_SOURCE)) {
            try {
                sourceUids.put(source, context.getPackageManager().getApplicationInfo(source, 0).uid);
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            }
        }
        uriForFile = Reflection.method(Reflection.load(loader, "com.vivo.common.dragdrop.SharedFileProvider"),
                "c", Uri.class, Context.class, File.class);
    }

    static HookGroup install(XposedModule module, ClassLoader loader, Context context,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        SelectedContentFeature feature = new SelectedContentFeature(loader, context, diagnostics);
        Method receive = Reflection.method(Reflection.load(loader, "yb.f"), "a", void.class, Message.class);
        Method send = Reflection.method(Messenger.class, "send", void.class, Message.class);
        HookGroup group = new HookGroup(module, diagnostics, "selected_content");
        try {
            group.hook(receive, chain -> {
                Message message = (Message) chain.getArg(0);
                if (config.enabled(Config.DRAG) && message.what == 20) {
                    Bundle data = message.getData();
                    String source = data.getCharSequence("widget_package_key", "").toString();
                    Integer sourceUid = feature.sourceUids.get(source);
                    Bundle content = data.getBundle(FlutterSelectionFeature.EXTRA);
                    String request = data.getCharSequence("widget_req_id_key", "").toString();
                    if (content != null && !request.isEmpty() && sourceUid != null && message.sendingUid == sourceUid) {
                        if (feature.pending.size() >= 8) feature.pending.clear();
                        Bundle selected = new Bundle(content);
                        selected.putString("source", source);
                        feature.pending.put(request, selected);
                    }
                }
                return chain.proceed();
            });
            group.hook(send, chain -> {
                Message message = (Message) chain.getArg(0);
                if (message.what == 21 && config.enabled(Config.DRAG)) {
                    String request = message.getData().getCharSequence("widget_req_id_key", "").toString();
                    Bundle content = feature.pending.remove(request);
                    if (content != null) {
                        try {
                            feature.replace(message, content);
                        } catch (Throwable failure) {
                            message.what = 22;
                            diagnostics.failure("selected content failed; full surface refused", failure);
                        }
                    }
                }
                return chain.proceed();
            });
            diagnostics.info("selected content caller deopt=" + module.deoptimize(receive));
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private void replace(Message message, Bundle content) throws Throwable {
        Rect bounds = content.getParcelable("bounds");
        String text = content.getString("text", "");
        byte[] image = content.getByteArray("image");
        Bundle data = new Bundle(message.getData());
        ClipData original = data.getParcelable("drag_clip_data_key");
        if (bounds == null || bounds.isEmpty() || (text.isEmpty() && image == null)) {
            message.what = 22;
            diagnostics.hit("selected_content_rejected", "no precise content; full surface refused");
            return;
        }
        if (!text.isEmpty() && text.length() <= 20000) {
            data.putParcelable("drag_clip_data_key", ClipData.newPlainText("选中文字", text));
            data.remove("drag_clip_data_image");
            data.putCharSequence("widget_type_key", "9");
            diagnostics.hit("selected_text", "chars=" + text.length() + " rect=" + bounds.toShortString());
        } else {
            if (image == null || image.length > 350000) throw new IllegalArgumentException("Invalid selected image");
            BitmapFactory.Options dimensions = new BitmapFactory.Options();
            dimensions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(image, 0, image.length, dimensions);
            if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0
                    || dimensions.outWidth > 1024 || dimensions.outHeight > 1024) {
                throw new IllegalArgumentException("Selected image dimensions invalid");
            }
            File directory = new File(context.getExternalCacheDir(), "drag-all-selected");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Image directory unavailable");
            File[] previous = directory.listFiles();
            if (previous != null) {
                for (File file : previous) {
                    if (file.isFile() && System.currentTimeMillis() - file.lastModified() > 86400000L) file.delete();
                }
            }
            String mime = "image/png".equals(content.getString("mime")) ? "image/png" : "image/jpeg";
            File imageFile = new File(directory, UUID.randomUUID() + ("image/png".equals(mime) ? ".png" : ".jpg"));
            try (FileOutputStream output = new FileOutputStream(imageFile)) {
                output.write(image);
            }
            Uri uri = (Uri) Reflection.invoke(uriForFile, null, context, imageFile);
            String source = content.getString("source");
            if (!Config.isPreciseSource(source)) throw new IllegalArgumentException("Unknown image source");
            context.grantUriPermission(source, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            data.putParcelable("drag_clip_data_key", new ClipData(
                    new ClipDescription("选中图片", new String[]{mime}), new ClipData.Item(uri)));
            Bitmap bitmap = BitmapFactory.decodeByteArray(image, 0, image.length);
            if (bitmap == null) throw new IllegalArgumentException("Selected image decode failed");
            float scale = Math.min(1, 256f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            Bitmap thumbnail = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (thumbnail != bitmap) bitmap.recycle();
            data.putParcelable("drag_clip_data_image", thumbnail);
            data.putCharSequence("widget_type_key", "1");
            diagnostics.hit("selected_image", "size=" + dimensions.outWidth + "x" + dimensions.outHeight
                    + " rect=" + bounds.toShortString());
        }
        ClipData replacement = data.getParcelable("drag_clip_data_key");
        if (original != null && replacement != null && original.getDescription().getExtras() != null) {
            replacement.getDescription().setExtras(original.getDescription().getExtras());
        }
        message.setData(data);
    }
}
