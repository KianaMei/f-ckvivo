package dev.ganvivo.dragall;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.File;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import io.github.libxposed.api.XposedModule;

/**
 * Bilibili's fullscreen image viewer paints through its own GPU pipeline, so vivo's collector finds
 * the node but no bitmap. The viewer knows the address of the page it shows, and that address
 * without its "@..." processing suffix is the uploaded file. When vivo asks for the pressed content,
 * this fetches that file and sends it along with the request, byte for byte: nothing is decoded,
 * re-encoded or captured from the screen. SelectedContentFeature receives it in the host.
 *
 * Bilibili renames its classes on every build, but the coroutine classes of ImagePageItem keep their
 * names and the names of what they capture. The viewer's classes are taken from those fields and
 * their members are found by type.
 */
final class BiliOriginalImageFeature {
    /** Marks the request payload that carries the fetched file. */
    static final String KIND = "original";
    private static final String PAGE = "kntr.common.photonic.aphro.ui.preview.ImagePageItemKt$ImagePageItem$";
    /** The pointer input block of a viewer page: it runs for every touch event that page receives. */
    static final String GESTURE = PAGE + "5$5$1$1";
    /** The image loading block of a viewer page, which captures the page item and its asset. */
    private static final String LOADER = PAGE + "5$1$1";
    /** Down time of the press in progress, written by BiliViewerDragFeature. */
    static volatile long pressDown;

    private final File cache;
    private final Diagnostics diagnostics;
    private final Method resume;
    private final Field state;
    private final Field pager;
    private final Field pages;
    private final Field asset;
    private final Method currentPage;
    private final Method identifier;
    private volatile WeakReference<Object> viewer = new WeakReference<>(null);
    private volatile long viewerTouched;

    private BiliOriginalImageFeature(ClassLoader loader, File cache, Diagnostics diagnostics) throws Throwable {
        this.cache = cache;
        this.diagnostics = diagnostics;
        Class<?> gesture = Reflection.load(loader, GESTURE);
        Class<?> loading = Reflection.load(loader, LOADER);
        Class<?> pagerState = Reflection.load(loader, "androidx.compose.foundation.pager.PagerState");
        Class<?> assetType = loading.getDeclaredField("$asset").getType();
        resume = Reflection.method(gesture, "invokeSuspend", Object.class, Object.class);
        state = gesture.getDeclaredField("$state");
        state.setAccessible(true);
        pager = field(state.getType(), pagerState);
        pages = field(state.getType(), List.class);
        asset = field(loading.getDeclaredField("$item").getType(), assetType);
        currentPage = pagerState.getMethod("getCurrentPage");
        identifier = assetType.getMethod("getIdentifier");
        identifier.setAccessible(true);
    }

    static HookGroup install(XposedModule module, ClassLoader loader, Context context,
            FeatureConfig config, Diagnostics diagnostics) throws Throwable {
        BiliOriginalImageFeature feature = new BiliOriginalImageFeature(loader, context.getCacheDir(), diagnostics);
        Method request = Reflection.method(Reflection.load(loader, "com.vivo.vdragdrop.DragClientProxy"),
                "requestClipDataFromServer", void.class, String.class, String.class, String.class,
                String.class, String.class, PointF.class, String.class, Rect.class, List.class,
                int.class, String.class, Reflection.load(loader, "com.vivo.vdragdrop.bean.ViewTree"));
        Method send = Reflection.method(Messenger.class, "send", void.class, Message.class);
        CallerScope requests = new CallerScope();
        HookGroup group = new HookGroup(module, diagnostics, "bili_original");
        try {
            group.hook(feature.resume, chain -> {
                Object viewerState = feature.state.get(chain.getThisObject());
                if (feature.viewer.get() != viewerState) feature.viewer = new WeakReference<>(viewerState);
                feature.viewerTouched = SystemClock.uptimeMillis();
                return chain.proceed();
            });
            group.hook(send, chain -> {
                Message message = (Message) chain.getArg(0);
                if (!requests.active() || message.what != 20) return chain.proceed();
                // The transaction duplicates the descriptor, so ours is closed once the request is sent.
                try (ParcelFileDescriptor file = feature.attach(message)) {
                    return chain.proceed();
                }
            });
            group.scope(request, requests, config, Config.DRAG);
            return group.commit();
        } catch (Throwable failure) {
            group.close();
            throw failure;
        }
    }

    /** Runs on vivo's drag worker thread, so fetching here delays nothing but this drag. */
    private ParcelFileDescriptor attach(Message message) throws Exception {
        Object viewerState = viewer.get();
        // A page's pointer input runs for this press only if the finger went down on its picture.
        if (viewerState == null || viewerTouched < pressDown) return null;
        List<?> items = (List<?>) pages.get(viewerState);
        Object item = items.get((int) currentPage.invoke(pager.get(viewerState)));
        String address = (String) identifier.invoke(asset.get(item));
        // Anything else names local media, such as the pictures of the upload picker.
        if (!address.startsWith("http")) return null;
        // The image servers resize or transcode on an "@..." suffix; without it they return the upload.
        int suffix = address.indexOf('@', address.lastIndexOf('/'));
        if (suffix > 0) address = address.substring(0, suffix);
        if (address.startsWith("http://")) address = "https://" + address.substring(7);
        ParcelFileDescriptor descriptor;
        File file = File.createTempFile("original", null, cache);
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } finally {
            // An open descriptor keeps the data readable after the name is gone.
            file.delete();
        }
        Bundle payload = new Bundle();
        payload.putString("kind", KIND);
        payload.putString("name", address.substring(address.lastIndexOf('/') + 1));
        payload.putParcelable("file", descriptor);
        message.getData().putBundle(FlutterSelectionFeature.EXTRA, payload);
        diagnostics.info("bili_original " + payload.getString("name") + " bytes=" + descriptor.getStatSize());
        return descriptor;
    }

    private static Field field(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        for (Field field : owner.getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException(type.getName() + " in " + owner.getName());
    }
}
