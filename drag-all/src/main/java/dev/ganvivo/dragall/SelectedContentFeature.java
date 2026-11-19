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
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import io.github.libxposed.api.XposedModule;

final class SelectedContentFeature {
    private static final int CATCH_ITEM = 20;
    private static final int CATCH_SUCCESS = 21;
    private static final int CATCH_FAILED = 22;
    private static final int CATCH_DOWNLOAD = 23;
    private static final String REQUEST_ID = "widget_req_id_key";
    private final Map<String, Bundle> pending = new ConcurrentHashMap<>();
    private final Context context;
    private final Method uriForFile;
    private final Diagnostics diagnostics;
    private final Map<String, Integer> sourceUids = new ConcurrentHashMap<>();
    private final AtomicReference<PiliBroReply> piliBroReply = new AtomicReference<>();
    /** The image file Bilibili sent with its latest request, keyed by that request's id. */
    private volatile Map.Entry<String, File> original;

    private SelectedContentFeature(ClassLoader loader, Context context, Diagnostics diagnostics) throws Throwable {
        this.context = context;
        this.diagnostics = diagnostics;
        for (String source : Arrays.asList(Config.FLUTTER_SOURCE, Config.TELEGRAM_SOURCE, Config.BILI)) {
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
        Class<?> capture = Reflection.load(loader, "vb.p");
        Class<?> drag = Reflection.load(loader, "com.vivo.common.dragdrop.data.bean.DragInfo");
        Field currentRequest = Reflection.field(capture, "d", String.class);
        Method current = Reflection.method(capture, "r", drag);
        Method setUri = Reflection.method(drag, "setUri", void.class, Uri.class);
        Method setWidgetType = Reflection.method(drag, "setWidgetType", void.class, String.class);
        Method setType = Reflection.method(drag, "setType", void.class, String.class);
        Method setBitmap = Reflection.method(drag, "setLocalBitmap", void.class, Bitmap.class);
        Method result = Reflection.method(Reflection.load(loader, "com.vivo.dragdrop.service.DragService"),
                "j", void.class, int.class);
        HookGroup group = new HookGroup(module, diagnostics, "selected_content");
        try {
            group.hook(receive, chain -> {
                Message message = (Message) chain.getArg(0);
                if (config.enabled(Config.DRAG) && message.what == CATCH_ITEM
                        && !feature.accept(message)) {
                    return null;
                }
                return chain.proceed();
            });
            group.hook(result, chain -> {
                Map.Entry<String, File> sent = feature.original;
                if ((int) chain.getArg(0) != 31 || sent == null || !config.enabled(Config.DRAG)
                        || !sent.getKey().equals(currentRequest.get(null))) return chain.proceed();
                feature.original = null;
                File image = sent.getValue();
                Object state = Reflection.invoke(current, null);
                Uri uri = (Uri) Reflection.invoke(feature.uriForFile, null, context, image);
                Reflection.invoke(setUri, state, uri);
                Reflection.invoke(setWidgetType, state, "10");
                Reflection.invoke(setType, state, "image");
                Reflection.invoke(setBitmap, state, preview(image));
                context.grantUriPermission(Config.BILI, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                diagnostics.info("original_image " + image.getName() + " bytes=" + image.length());
                // The host caught nothing, but Bilibili sent the file: let the host finish as a success.
                return chain.proceed(new Object[]{30});
            });
            group.hook(send, chain -> {
                Message message = (Message) chain.getArg(0);
                if ((message.what == CATCH_SUCCESS || message.what == CATCH_DOWNLOAD
                        || message.what == CATCH_FAILED) && config.enabled(Config.DRAG)) {
                    String request = message.getData().getCharSequence(REQUEST_ID, "").toString();
                    feature.completePiliBroReply(message, request);
                    Bundle content = feature.pending.remove(request);
                    if (content != null && message.what != CATCH_FAILED) {
                        try {
                            feature.replace(message, content);
                        } catch (Throwable failure) {
                            message.what = CATCH_FAILED;
                            diagnostics.failure("selected content failed; full surface refused", failure);
                        }
                    }
                }
                return chain.proceed();
            });
            feature.installPiliBroDelivery(loader, group, module, config, currentRequest, current, result,
                    setUri, setWidgetType, setType, setBitmap);
            diagnostics.info("selected content caller deopt=" + module.deoptimize(receive));
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    private boolean accept(Message message) throws IOException {
        Bundle data = message.getData();
        String source = data.getCharSequence("widget_package_key", "").toString();
        String request = data.getCharSequence(REQUEST_ID, "").toString();
        Bundle content = data.getBundle(FlutterSelectionFeature.EXTRA);
        Integer sourceUid = sourceUids.get(source);
        boolean verified = !request.isEmpty() && sourceUid != null && message.sendingUid == sourceUid;
        if (Config.FLUTTER_SOURCE.equals(source)) {
            if (verified && hasPiliBroContent(content)) return true;
            diagnostics.hit("flutter_drag_cancel", "no verified source for this press");
            if (message.replyTo != null) {
                Message reply = Message.obtain();
                reply.what = CATCH_FAILED;
                reply.getData().putCharSequence(REQUEST_ID, request);
                try {
                    message.replyTo.send(reply);
                } catch (RemoteException failure) {
                    diagnostics.failure("Flutter drag cancellation not delivered", failure);
                }
            }
            return false;
        }
        if (verified && content != null) {
            if (Config.BILI.equals(source) && BiliOriginalImageFeature.KIND.equals(content.getString("kind"))) {
                original = Map.entry(request, store(content));
            } else {
                if (pending.size() >= 8) pending.clear();
                Bundle selected = new Bundle(content);
                selected.putString("source", source);
                pending.put(request, selected);
            }
        }
        return true;
    }

    private static boolean hasPiliBroContent(Bundle content) {
        if (content == null) return false;
        Rect bounds = content.getParcelable("bounds");
        if (bounds == null || bounds.isEmpty()) return false;
        String kind = content.getString("kind");
        if (PiliBroImageSource.KIND.equals(kind)) {
            return content.get("file") instanceof ParcelFileDescriptor descriptor
                    && descriptor.getStatSize() > 0 && descriptor.getStatSize() <= PiliBroImageSource.MAX_BYTES;
        }
        String text = content.getString("text", "");
        return kind == null && !content.containsKey("file") && !text.trim().isEmpty() && text.length() <= 20000;
    }

    private void installPiliBroDelivery(ClassLoader loader, HookGroup group, XposedModule module,
            FeatureConfig config, Field currentRequest, Method current, Method result,
            Method setUri, Method setWidgetType, Method setType, Method setBitmap) throws Throwable {
        Class<?> captureClass = Reflection.load(loader, "vb.o");
        Class<?> drag = current.getReturnType();
        Class<?> widget = Reflection.load(loader, "com.vivo.common.dragdrop.data.bean.WidgetItemBean");
        Class<?> unitClass = Reflection.load(loader, "ei.p");
        Object unit = Reflection.field(unitClass, "a", unitClass).get(null);
        Field incoming = Reflection.field(captureClass, "k", Message.class);
        Field service = Reflection.field(current.getDeclaringClass(), "n", WeakReference.class);
        Method capture = Reflection.method(captureClass, "p", Object.class, Object.class);
        Constructor<?> createWidget = widget.getDeclaredConstructor();
        Method setWidget = Reflection.method(drag, "setCurrentWidgetItem", void.class, widget);
        Method setBounds = Reflection.method(drag, "setVisibleRect", void.class, Rect.class);
        Method setCatchItem = Reflection.method(drag, "setCatchItem", void.class, String.class);
        Method setSelection = Reflection.method(drag, "setSelectionContent", void.class, String.class);
        Method setPackage = Reflection.method(widget, "setPackageName", void.class, String.class);
        Method setActivity = Reflection.method(widget, "setActivityName", void.class, String.class);
        Method setComponent = Reflection.method(widget, "setWidgetName", void.class, String.class);
        group.hook(capture, chain -> {
            Message message = (Message) incoming.get(chain.getThisObject());
            Bundle request = message == null ? null : message.peekData();
            if (!config.enabled(Config.DRAG) || request == null
                    || !Config.FLUTTER_SOURCE.contentEquals(request.getCharSequence("widget_package_key", ""))) {
                return chain.proceed();
            }
            String id = request.getCharSequence(REQUEST_ID, "").toString();
            Bundle content = request.getBundle(FlutterSelectionFeature.EXTRA);
            ParcelFileDescriptor descriptor = content == null ? null : content.getParcelable("file");
            WeakReference<?> reference = (WeakReference<?>) service.get(null);
            Object receiver = reference == null ? null : reference.get();
            if (receiver == null || !id.equals(currentRequest.get(null))) {
                if (descriptor != null) descriptor.close();
                return unit;
            }
            File storedImage = null;
            Bitmap thumbnail = null;
            try (ParcelFileDescriptor file = descriptor) {
                Integer sourceUid = sourceUids.get(Config.FLUTTER_SOURCE);
                if (sourceUid == null || message.sendingUid != sourceUid || !hasPiliBroContent(content)) {
                    throw new IllegalArgumentException("Missing verified PiliBro source");
                }
                Object state = Reflection.invoke(current, null);
                if (state == null) throw new IllegalStateException("Missing drag state");
                Uri uri = null;
                if (file != null) {
                    storedImage = storePiliBroImage(file);
                    thumbnail = preview(storedImage);
                    if (thumbnail == null) throw new IOException("Cannot decode PiliBro image preview");
                    uri = (Uri) Reflection.invoke(uriForFile, null, context, storedImage);
                }
                if (!id.equals(currentRequest.get(null)) || Reflection.invoke(current, null) != state) return unit;
                Object source = createWidget.newInstance();
                Reflection.invoke(setPackage, source, Config.FLUTTER_SOURCE);
                Reflection.invoke(setActivity, source, request.getCharSequence("widget_activity_name_key", "").toString());
                Reflection.invoke(setComponent, source, request.getCharSequence("widget_component_name_key", "").toString());
                Reflection.invoke(setWidget, state, source);
                Rect bounds = content.getParcelable("bounds");
                Reflection.invoke(setBounds, state, bounds);
                if (uri != null) {
                    context.grantUriPermission(Config.FLUTTER_SOURCE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Reflection.invoke(setUri, state, uri);
                    Reflection.invoke(setWidgetType, state, "10");
                    Reflection.invoke(setType, state, "image");
                    Reflection.invoke(setBitmap, state, thumbnail);
                    piliBroReply.set(new PiliBroReply(id, uri, null));
                    diagnostics.hit("pilibro_original_image", "bytes=" + storedImage.length());
                } else {
                    String text = content.getString("text");
                    Reflection.invoke(setCatchItem, state, text);
                    Reflection.invoke(setSelection, state, text);
                    Reflection.invoke(setWidgetType, state, "9");
                    Reflection.invoke(setType, state, "text");
                    piliBroReply.set(new PiliBroReply(id, null, text));
                    diagnostics.hit("pilibro_source_text", "chars=" + text.length());
                }
                Reflection.invoke(result, receiver, 30);
                storedImage = null;
                thumbnail = null;
            } catch (Throwable failure) {
                diagnostics.failure("PiliBro source could not be delivered", failure);
                if (id.equals(currentRequest.get(null))) Reflection.invoke(result, receiver, 31);
            } finally {
                if (storedImage != null) storedImage.delete();
                if (thumbnail != null) thumbnail.recycle();
            }
            return unit;
        });
        diagnostics.info("pilibro delivery deopt=" + module.deoptimize(capture));
    }

    private record PiliBroReply(String request, Uri image, String text) {}

    private void completePiliBroReply(Message message, String request) {
        PiliBroReply reply = piliBroReply.get();
        if (reply == null || !reply.request.equals(request) || !piliBroReply.compareAndSet(reply, null)) return;
        if (message.what != CATCH_SUCCESS) return;
        Bundle data = message.getData();
        ClipData clip = data.getParcelable("drag_clip_data_key");
        if (reply.image != null) {
            if (clip == null || clip.getItemCount() != 1 || !reply.image.equals(clip.getItemAt(0).getUri())) {
                message.what = CATCH_FAILED;
                return;
            }
            data.putString(PiliBroImageSource.KIND, request);
        } else {
            ClipData exact = ClipData.newPlainText("选中文字", reply.text);
            if (clip != null) exact.getDescription().setExtras(clip.getDescription().getExtras());
            data.putParcelable("drag_clip_data_key", exact);
            data.remove("drag_clip_data_image");
        }
    }

    private File storePiliBroImage(ParcelFileDescriptor descriptor) throws IOException {
        long expected = descriptor.getStatSize();
        if (expected <= 0 || expected > PiliBroImageSource.MAX_BYTES) throw new IOException("Invalid image size");
        File output = File.createTempFile("pilibro-", ".image", directory());
        try {
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup());
                    FileOutputStream destination = new FileOutputStream(output)) {
                byte[] buffer = new byte[16384];
                long remaining = expected;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                    if (read < 0) throw new IOException("Incomplete PiliBro image");
                    destination.write(buffer, 0, read);
                    remaining -= read;
                }
                if (input.read() != -1) throw new IOException("PiliBro image grew while being copied");
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream image = new FileInputStream(output)) {
                BitmapFactory.decodeStream(image, null, options);
            }
            String suffix = switch (String.valueOf(options.outMimeType)) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif" -> ".gif";
                case "image/avif" -> ".avif";
                case "image/heif", "image/heic" -> ".heic";
                default -> throw new IOException("Unsupported PiliBro image format");
            };
            if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image bounds");
            File image = new File(output.getParentFile(), output.getName() + suffix);
            Files.move(output.toPath(), image.toPath());
            return image;
        } finally {
            Files.deleteIfExists(output.toPath());
        }
    }

    /** Copies the file Bilibili fetched next to the host's own drag files, unchanged. */
    private File store(Bundle content) throws IOException {
        File image = new File(directory(), content.getString("name"));
        ParcelFileDescriptor descriptor = content.getParcelable("file");
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            Files.copy(input, image.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return image;
    }

    /** A downsampled copy for the drag shadow; the dragged file itself is never decoded. */
    private static Bitmap preview(File image) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getPath(), options);
        options.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(options.outWidth, options.outHeight) / 256));
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(image.getPath(), options);
    }

    private File directory() {
        File directory = new File(context.getExternalCacheDir(), "drag-all-selected");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Image directory unavailable");
        File[] previous = directory.listFiles();
        if (previous != null) {
            for (File file : previous) {
                if (file.isFile() && System.currentTimeMillis() - file.lastModified() > 86400000L) file.delete();
            }
        }
        return directory;
    }

    private void replace(Message message, Bundle content) throws Throwable {
        Rect bounds = content.getParcelable("bounds");
        String text = content.getString("text", "");
        byte[] image = content.getByteArray("image");
        Bundle data = new Bundle(message.getData());
        ClipData original = data.getParcelable("drag_clip_data_key");
        if (bounds == null || bounds.isEmpty() || (text.isEmpty() && image == null)) {
            message.what = CATCH_FAILED;
            diagnostics.hit("selected_content_rejected", "no precise content; full surface refused");
            return;
        }
        if (!text.isEmpty() && text.length() <= 20000) {
            data.putParcelable("drag_clip_data_key", ClipData.newPlainText("选中文字", text));
            data.remove("drag_clip_data_image");
            data.putCharSequence("widget_type_key", "9");
            message.what = CATCH_SUCCESS;
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
            String mime = "image/png".equals(content.getString("mime")) ? "image/png" : "image/jpeg";
            File imageFile = new File(directory(), UUID.randomUUID() + ("image/png".equals(mime) ? ".png" : ".jpg"));
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
