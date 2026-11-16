package com.douai.module.feature;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import io.github.libxposed.api.XposedInterface;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Optional 41.2 message privacy, user-selected media saving, and native audio forwarding. */
public final class UnifiedMessageTools412Hook implements AutoCloseable {
    public static final String DISABLE_READ = "unified_disable_read";
    public static final String DISABLE_TYPING = "unified_disable_typing";
    public static final String MEDIA_TOOLS = "unified_message_media_tools";
    public static final String VOICE_FORWARD = "unified_voice_forward";
    private static final String MENU_TAG = "douai.unified.message.tools";
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    interface SavedOutput { void saved(Context context, String destination, String identifier, long bytes, String name); }
    static SavedOutput savedOutput = WebDavUploads::saved;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Set<String> supported = ConcurrentHashMap.newKeySet();
    private final List<WeakReference<TextView>> menuButtons = new ArrayList<>();
    private final Map<ViewGroup, WeakReference<MessageTimeLabel>> timeLabels = new java.util.WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong mediaEpoch = new AtomicLong();
    private final Consumer<String> log;
    private volatile LocalOptions options;
    private volatile boolean closed;

    UnifiedMessageTools412Hook(LocalOptions options, Consumer<String> log) {
        this.options = options == null ? new LocalOptions(Collections.emptyMap()) : options;
        this.log = log == null ? ignored -> { } : log;
    }

    public static UnifiedMessageTools412Hook install(XposedInterface framework, ClassLoader loader,
                                                     LocalOptions options, Consumer<String> log) {
        UnifiedMessageTools412Hook hook = new UnifiedMessageTools412Hook(options, log);
        hook.stage(DISABLE_READ, () -> {
            Class<?> owner = Class.forName("X.1bIz", false, loader);
            Class<?> conversation = Class.forName("com.bytedance.im.core.model.Conversation", false, loader);
            Class<?> parameters = Class.forName("X.1bQk", false, loader);
            for (Method method : new Method[]{owner.getDeclaredMethod("q3", long.class, conversation),
                    owner.getDeclaredMethod("p3", parameters)}) {
                hook.add(framework, method, chain -> hook.options.enabled(DISABLE_READ)
                        ? null : chain.proceed());
            }
        });
        hook.stage(DISABLE_TYPING, () -> {
            Class<?> owner = Class.forName("X.1Mv8", false, loader);
            hook.add(framework, owner.getDeclaredMethod("handleMsg", android.os.Message.class), chain -> {
                Object message = chain.getArg(0);
                if (hook.options.enabled(DISABLE_TYPING) && message instanceof android.os.Message
                        && ((android.os.Message) message).what == 1) return null;
                return chain.proceed();
            });
        });
        hook.stage(MEDIA_TOOLS, () -> {
            Class<?> manager = Class.forName("com.ss.android.ugc.aweme.im.business.chat.msgcell.longclick.LongClickWindowManagerNew", false, loader);
            Class<?> message = Class.forName("com.bytedance.im.core.model.Message", false, loader);
            Field selected = manager.getDeclaredField("currentMessage");
            selected.setAccessible(true);
            if (selected.getType() != message) throw new IllegalStateException("Message menu owner changed");
            Method group = manager.getDeclaredMethod("LJI");
            Method popup = manager.getDeclaredMethod("LJIIIIZZ");
            Field row = group.getReturnType().getDeclaredField("LIZ");
            Field count = group.getReturnType().getDeclaredField("LJII");
            row.setAccessible(true); count.setAccessible(true); group.setAccessible(true); popup.setAccessible(true);
            if (!ViewGroup.class.isAssignableFrom(row.getType()) || count.getType() != int.class) {
                throw new IllegalStateException("Native message menu group changed");
            }
            hook.add(framework, manager.getDeclaredMethod("LIZ", boolean.class), chain -> {
                Object menu = group.invoke(chain.getThisObject());
                Object value = menu == null ? null : row.get(menu);
                Object current = selected.get(chain.getThisObject());
                if (value instanceof ViewGroup && current != null) {
                    ViewGroup target = (ViewGroup) value;
                    TextView button = target.findViewWithTag(MENU_TAG);
                    if (!hook.options.enabled(MEDIA_TOOLS)) {
                        if (button != null) target.removeView(button);
                    } else if (target.getChildCount() > 0) {
                        if (button == null) {
                            button = new TextView(target.getContext()); button.setTag(MENU_TAG);
                            button.setText("消息工具"); button.setGravity(Gravity.CENTER);
                            button.setTextSize(13); button.setTextColor(Color.GRAY);
                            TextView sample = firstText(target, 0);
                            if (sample != null) button.setTextColor(sample.getTextColors());
                            int unit = Math.round(target.getResources().getDisplayMetrics().density);
                            button.setPadding(8 * unit, 6 * unit, 8 * unit, 6 * unit);
                            button.setMinWidth(64 * unit); button.setMinHeight(48 * unit);
                            target.addView(button);
                            hook.menuButtons.add(new WeakReference<>(button));
                        }
                        Object owner = chain.getThisObject();
                        button.setOnClickListener(view -> {
                            try { Object p = popup.invoke(owner); if (p instanceof PopupWindow) ((PopupWindow) p).dismiss(); }
                            catch (ReflectiveOperationException ignored) { }
                            hook.showTools(view.getContext(), current);
                        });
                    }
                    count.setInt(menu, target.getChildCount());
                }
                return chain.proceed();
            });
        });
        hook.stage(VOICE_FORWARD, () -> hook.installVoiceForward(framework, loader));
        hook.stage(UnifiedInteraction412Hook.FULL_MESSAGE_TIME, () -> {
            Class<?> adapter = Class.forName("com.ss.android.ugc.aweme.im.business.chat.msgcell.common.CellComposeAdapter", false, loader);
            Class<?> holder = Class.forName("androidx.recyclerview.widget.RecyclerView$ViewHolder", false, loader);
            Class<?> message = Class.forName("com.bytedance.im.core.model.Message", false, loader);
            Method selected = adapter.getDeclaredMethod("LJLJLLL", int.class);
            Method createdAt = message.getDeclaredMethod("getCreatedAt");
            if (selected.getReturnType() != message || createdAt.getReturnType() != long.class) throw new IllegalStateException("Message time model changed");
            hook.add(framework, adapter.getDeclaredMethod("onBindViewHolder", holder, int.class, List.class), chain -> {
                Object result = chain.proceed();
                if (hook.closed || !hook.options.enabled(UnifiedInteraction412Hook.FULL_MESSAGE_TIME)) return result;
                Object view = HostTargets.read(chain.getArg(0), "itemView");
                if (view instanceof ViewGroup) {
                    ViewGroup row = (ViewGroup)view;
                    Object item = selected.invoke(chain.getThisObject(), ((Number)chain.getArg(1)).intValue());
                    long raw = item == null ? -1 : ((Number)createdAt.invoke(item)).longValue();
                    hook.main.post(() -> hook.bindTime(row, raw));
                }
                return result;
            });
        });
        return hook;
    }

    private void bindTime(ViewGroup row, long raw) {
        WeakReference<MessageTimeLabel> reference = timeLabels.get(row);
        MessageTimeLabel binding = reference == null ? null : reference.get();
        String value = options.enabled(UnifiedInteraction412Hook.FULL_MESSAGE_TIME)
                ? UnifiedInteraction412Hook.fullTimestamp(raw, java.util.TimeZone.getDefault()) : null;
        if (closed || value == null) {
            if (binding != null) binding.close(); timeLabels.remove(row); return;
        }
        if (binding == null) { binding = new MessageTimeLabel(row); timeLabels.put(row, new WeakReference<>(binding)); }
        binding.label.setText(value); binding.layout();
    }

    private static final class MessageTimeLabel implements View.OnLayoutChangeListener {
        final WeakReference<ViewGroup> root;
        final TextView label;
        final int left, top, right, bottom;
        MessageTimeLabel(ViewGroup view) {
            root = new WeakReference<>(view); left = view.getPaddingLeft(); top = view.getPaddingTop();
            right = view.getPaddingRight(); bottom = view.getPaddingBottom();
            label = new TextView(view.getContext()); label.setTextSize(10); label.setTextColor(0xff888888);
            label.setGravity(Gravity.CENTER); label.setClickable(false); label.setFocusable(false);
            label.setTag("douai.message.full_time");
            view.getOverlay().add(label); view.addOnLayoutChangeListener(this);
        }
        void layout() {
            ViewGroup view = root.get(); if (view == null) return;
            int width = view.getWidth(); if (width <= 0) return;
            label.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width - left - right), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int height = label.getMeasuredHeight();
            if (view.getPaddingTop() != top + height) view.setPadding(left, top + height, right, bottom);
            int x = Math.max(left, (width - label.getMeasuredWidth()) / 2);
            label.layout(x, top, x + label.getMeasuredWidth(), top + height);
        }
        @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) { layout(); }
        void close() {
            ViewGroup view = root.get(); if (view == null) return;
            view.removeOnLayoutChangeListener(this); view.getOverlay().remove(label); view.setPadding(left, top, right, bottom);
        }
    }

    private void installVoiceForward(XposedInterface framework, ClassLoader loader) throws Exception {
        Class<?> audio = Class.forName("com.ss.android.ugc.aweme.im.sdk.chat.model.AudioContent", false, loader);
        Class<?> voiceCapabilities = Class.forName("ryx.AcS848S0300000_33", false, loader);
        Class<?> baseCapabilities = voiceCapabilities.getSuperclass();
        Method originalPolicy = baseCapabilities.getDeclaredMethod("LJIJJLI");
        Field branch = voiceCapabilities.getDeclaredField("$t");
        Field payload = voiceCapabilities.getDeclaredField("l1");
        Field interaction = voiceCapabilities.getDeclaredField("l2");
        branch.setAccessible(true); payload.setAccessible(true); interaction.setAccessible(true);
        add(framework, voiceCapabilities.getDeclaredMethod("LJIJJLI"), chain -> {
            Object owner = chain.getThisObject();
            Object cell = interaction.get(owner);
            // Branch 10 alone hard-disables audio forwarding; retain the parent's other restrictions.
            if (options.enabled(VOICE_FORWARD) && branch.getInt(owner) == 10
                    && audio.isInstance(payload.get(owner)) && cell != null
                    && cell.getClass().getName().equals("com.ss.android.ugc.aweme.im.business.chat.msgcell.common.content.root.voice.VoiceCellInteract")) {
                return framework.getInvoker(originalPolicy).setType(XposedInterface.Invoker.Type.ORIGIN)
                        .invokeSpecial(owner);
            }
            return chain.proceed();
        });
        Class<?> base = Class.forName("com.ss.android.ugc.aweme.im.sdk.chat.model.BaseContent", false, loader);
        Class<?> pack = Class.forName("com.ss.android.ugc.aweme.sharer.ui.SharePackage", false, loader);
        Class<?> builder = Class.forName("com.ss.android.ugc.aweme.sharer.ui.SharePackage$Builder", false, loader);
        Method itemType = builder.getMethod("itemType", String.class);
        Method factory = base.getDeclaredMethod("LJIILIIL");
        if (factory.getReturnType() != pack) throw new IllegalStateException("Audio forward share contract changed");
        add(framework, factory, chain -> {
            if (!options.enabled(VOICE_FORWARD) || !audio.isInstance(chain.getThisObject())) return chain.proceed();
            Object result = chain.proceed();
            if (result != null) return result;
            Object params = builder.getConstructor().newInstance();
            itemType.invoke(params, "text");
            return pack.getConstructor(builder).newInstance(params);
        });
    }

    static boolean containsAudio(Object target, Class<?> audio, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (target == null || depth > 5 || visited.size() >= 64 || visited.put(target, true) != null) return false;
        if (audio.isInstance(target)) return true;
        for (Class<?> cls = target.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                boolean payload = field.getType().getName().startsWith("com.ss.android.ugc.aweme.im.sdk.chat.model.");
                if (!payload && !field.getName().equals("delegate")) continue;
                try { field.setAccessible(true); if (containsAudio(field.get(target), audio, visited, depth + 1)) return true; }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
        return false;
    }

    private void showTools(Context context, Object message) {
        if (closed || !options.enabled(MEDIA_TOOLS)) return;
        AlertDialog messageMenu = new AlertDialog.Builder(context).setTitle("消息工具")
                .setItems(new String[]{"保存图片 / 视频 / 表情 / 语音", "分享媒体文件", "复制消息原始内容"}, (dialog, which) -> {
                    if (which == 2) {
                        Object text = HostTargets.invoke(message, "getContent");
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("消息内容", String.valueOf(text)));
                        return;
                    }
                    save(context, message, which == 1);
                }).setNegativeButton("取消", null).create();
        UnifiedPopupFilterHook.markModule(messageMenu);
        messageMenu.show();
    }

    private void save(Context context, Object message, boolean share) {
        if (closed || !options.enabled(MEDIA_TOOLS)) return;
        long epoch = mediaEpoch.get();
        toast(context, "正在读取消息媒体");
        worker.execute(() -> {
            try {
                Object rawType = HostTargets.invoke(message, "getMsgType");
                int type = rawType instanceof Number ? ((Number) rawType).intValue() : -1;
                Object rawLocal = HostTargets.invoke(message, "getLocalExt");
                String local = rawLocal instanceof Map<?, ?> && ((Map<?, ?>) rawLocal).get("localpath") instanceof String
                        ? (String) ((Map<?, ?>) rawLocal).get("localpath") : null;
                String content = String.valueOf(HostTargets.invoke(message, "getContent"));
                if (content.length() > 2_000_000) throw new IllegalArgumentException("消息数据过大");
                List<Source> sources = mediaSources(type, content, local);
                if (sources.isEmpty()) throw new IllegalStateException("未找到可保存媒体，请先加载或播放该消息");
                ArrayList<Uri> saved = new ArrayList<>(); String mime = null;
                int index = 0;
                for (Source source : sources) {
                    if (closed || !options.enabled(MEDIA_TOOLS) || mediaEpoch.get() != epoch) return;
                    Saved result = copy(context, source, ++index, epoch);
                    saved.add(result.uri); mime = mime == null ? result.mime : mime.equals(result.mime) ? mime : "*/*";
                }
                String finalMime = mime;
                main.post(() -> {
                    if (closed || !options.enabled(MEDIA_TOOLS) || mediaEpoch.get() != epoch) return;
                    if (share) {
                        Intent intent = new Intent(saved.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
                        intent.setType(finalMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        if (saved.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, saved.get(0));
                        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, saved);
                        ClipData clip = ClipData.newRawUri("消息媒体", saved.get(0));
                        for (int i = 1; i < saved.size(); i++) clip.addItem(new ClipData.Item(saved.get(i)));
                        intent.setClipData(clip);
                        context.startActivity(Intent.createChooser(intent, "分享消息媒体"));
                    } else toast(context, "已保存 " + saved.size() + " 个消息媒体");
                });
            } catch (Exception failure) {
                if (!closed && options.enabled(MEDIA_TOOLS) && mediaEpoch.get() == epoch) {
                    toast(context, "媒体保存失败：" + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
                }
            }
        });
    }

    static List<Source> mediaSources(int type, String content, String local) throws Exception {
        boolean audio = type == 17 || type == 109;
        if (local != null && new File(local).isFile()) return Collections.singletonList(
                new Source(local, audio ? "audio" : type == 27 || type == 30 ? "video" : "image"));
        JSONObject root = new JSONObject(content);
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        String kind = audio ? "audio" : type == 27 || type == 30 || root.has("video") || root.has("video_url") ? "video" : "image";
        if (audio) collectUrls(root.opt("resource_url"), urls, 0);
        if (urls.isEmpty()) for (String key : new String[]{"url", "video_url", "display_url", "origin_url", "resource_url", "video", "images", "image_list", "image", "url_list"}) {
            collectUrls(root.opt(key), urls, 0); if (!urls.isEmpty()) break;
        }
        ArrayList<Source> result = new ArrayList<>();
        for (String url : urls) { result.add(new Source(url, kind)); if (result.size() == 20) break; }
        return result;
    }

    private static void collectUrls(Object value, LinkedHashSet<String> urls, int depth) {
        if (value == null || depth > 6 || urls.size() >= 20) return;
        if (value instanceof String) { if (MediaStoreDownloader.isHttpUrl((String) value)) urls.add((String) value); return; }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < Math.min(20, array.length()); i++) collectUrls(array.opt(i), urls, depth + 1);
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray array = object.optJSONArray("url_list");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) if (MediaStoreDownloader.isHttpUrl(array.optString(i))) {
                    urls.add(array.optString(i)); break; // CDN mirrors represent one media item.
                }
                return;
            }
            for (String key : new String[]{"url", "video_url", "play_addr", "download_addr", "origin_url"}) collectUrls(object.opt(key), urls, depth + 1);
        }
    }

    Saved copy(Context context, Source source, int index, long epoch) throws Exception {
        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            if (new File(source.path).isAbsolute()) stream = new FileInputStream(source.path);
            else {
                URI uri = URI.create(source.path);
                if (!MediaStoreDownloader.isHttpUrl(source.path)) throw new IllegalArgumentException("无效媒体地址");
                connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new IllegalStateException("媒体地址已失效或加载失败");
                if (connection.getContentLengthLong() > MAX_BYTES) throw new IllegalStateException("媒体超过512MB");
                String contentType = connection.getContentType();
                if (contentType != null) {
                    String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
                    if (normalized.startsWith("text/") || normalized.contains("json") || normalized.contains("xml")) {
                        throw new IllegalStateException("媒体地址返回错误页面，请重新加载消息");
                    }
                }
                stream = connection.getInputStream();
            }
            byte[] prefix = new byte[16];
            int prefixLength = 0;
            while (prefixLength < prefix.length) {
                int read = stream.read(prefix, prefixLength, prefix.length - prefixLength);
                if (read < 0) break;
                if (read == 0) continue;
                prefixLength += read;
            }
            if (prefixLength != prefix.length) prefix = java.util.Arrays.copyOf(prefix, prefixLength);
            if (prefix.length == 0 || prefix[0] == '<') throw new IllegalStateException("未返回媒体文件");
            String mime = mime(prefix, source.kind);
            String extension = mime.substring(mime.indexOf('/') + 1).replace("jpeg", "jpg").replace("mpeg", "mp3").replace("mp4", source.kind.equals("audio") ? "m4a" : "mp4");
            String name = "DouAi_message_" + System.currentTimeMillis() + "_" + index + "." + extension;
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name); values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            Uri collection;
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DouAi/Messages");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            } else {
                if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Context candidate = context;
                    for (int i = 0; i < 12 && candidate instanceof ContextWrapper && !(candidate instanceof android.app.Activity); i++) {
                        Context next = ((ContextWrapper) candidate).getBaseContext(); if (next == candidate) break; candidate = next;
                    }
                    if (candidate instanceof android.app.Activity) {
                        android.app.Activity activity = (android.app.Activity) candidate;
                        main.post(() -> activity.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0xDA12));
                    }
                    throw new IllegalStateException("请授权存储后重新保存");
                }
                File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DouAi/Messages");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建消息保存目录");
                values.put(MediaStore.MediaColumns.DATA, new File(directory, name).getAbsolutePath());
                collection = MediaStore.Files.getContentUri("external");
            }
            Uri saved = context.getContentResolver().insert(collection, values);
            if (saved == null) throw new IllegalStateException("无法创建保存文件");
            boolean complete = false;
            long length = prefix.length;
            try {
                try (OutputStream output = context.getContentResolver().openOutputStream(saved, "w")) {
                    if (output == null) throw new IllegalStateException("无法写入媒体");
                    output.write(prefix); byte[] buffer = new byte[16_384]; int size;
                    while ((size = stream.read(buffer)) != -1) {
                        if (closed || !options.enabled(MEDIA_TOOLS) || mediaEpoch.get() != epoch
                                || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        length += size; if (length > MAX_BYTES) throw new IllegalStateException("媒体超过512MB");
                        output.write(buffer, 0, size);
                    }
                    output.flush();
                }
                if (closed || !options.enabled(MEDIA_TOOLS) || mediaEpoch.get() != epoch) throw new InterruptedException();
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    if (context.getContentResolver().update(saved, values, null, null) != 1) throw new IllegalStateException("保存未完成");
                }
                complete = true;
                // Local bytes and IS_PENDING publication are committed before an optional upload is queued.
                savedOutput.saved(context, "mediastore", saved.toString(), length, name);
                return new Saved(saved, mime);
            } finally { if (!complete) context.getContentResolver().delete(saved, null, null); }
        } finally { if (stream != null) stream.close(); if (connection != null) connection.disconnect(); }
    }

    static String mime(byte[] bytes, String kind) {
        if (bytes.length >= 4 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        if (bytes.length >= 12 && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        if (bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            String brand = new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (brand.equals("heic") || brand.equals("heix") || brand.equals("mif1")) return "image/heic";
            if (brand.equals("avif")) return "image/avif";
            return "audio".equals(kind) ? "audio/mp4" : "video/mp4";
        }
        if ("audio".equals(kind)) {
            if (bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') return "audio/ogg";
            if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E') return "audio/wav";
            if (bytes.length >= 5 && bytes[0] == '#' && bytes[1] == '!' && bytes[2] == 'A' && bytes[3] == 'M' && bytes[4] == 'R') return "audio/amr";
            if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 246) == 240) return "audio/aac";
            return "audio/mpeg";
        }
        return "video".equals(kind) ? "video/mp4" : "image/jpeg";
    }

    static final class Source { final String path, kind; Source(String path, String kind) { this.path = path; this.kind = kind; } }
    static final class Saved { final Uri uri; final String mime; Saved(Uri uri, String mime) { this.uri = uri; this.mime = mime; } }
    public void setOptions(LocalOptions options) {
        LocalOptions next = options == null ? new LocalOptions(Collections.emptyMap()) : options;
        if (this.options.enabled(MEDIA_TOOLS) != next.enabled(MEDIA_TOOLS)) mediaEpoch.incrementAndGet();
        this.options = next;
        if (!this.options.enabled(MEDIA_TOOLS)) main.post(this::removeMenuButtons);
        if (!this.options.enabled(UnifiedInteraction412Hook.FULL_MESSAGE_TIME)) main.post(this::removeTimeLabels);
    }
    public boolean supports(String key) { return !closed && supported.contains(key); }
    private interface Stage { void run() throws Exception; }
    private void stage(String name, Stage action) {
        int before = handles.size(); supported.remove(name);
        try {
            action.run(); if (handles.size() == before) throw new IllegalStateException("No message handles installed"); supported.add(name);
        } catch (Exception | LinkageError failure) {
            for (int i = handles.size() - 1; i >= before; i--) {
                XposedInterface.HookHandle handle = handles.remove(i);
                try { handle.unhook(); } catch (RuntimeException | LinkageError rollback) { failure.addSuppressed(rollback); }
            }
            try { log.accept("Message tool " + name + " unavailable: " + failure.getClass().getSimpleName()); } catch (RuntimeException ignored) { }
        }
    }
    private void add(XposedInterface framework, Method target, XposedInterface.Hooker callback) {
        XposedInterface.HookHandle handle = framework.hook(target).setId("DouAi.Unified412.Message." + handles.size())
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(callback);
        if (handle == null) throw new IllegalStateException("Empty message tool hook"); handles.add(handle);
    }
    private static TextView firstText(View view, int depth) { if (depth > 8) return null; if (view instanceof TextView) return (TextView) view; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = firstText(((ViewGroup) view).getChildAt(i), depth + 1); if (found != null) return found; } return null; }
    private void toast(Context context, String message) { main.post(() -> { if (!closed) Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }); }
    private void removeMenuButtons() {
        for (WeakReference<TextView> reference : menuButtons) {
            TextView button = reference.get();
            if (button != null && button.getParent() instanceof ViewGroup) ((ViewGroup) button.getParent()).removeView(button);
        }
        menuButtons.clear();
    }
    private void removeTimeLabels() {
        for (WeakReference<MessageTimeLabel> reference : timeLabels.values()) { MessageTimeLabel label = reference.get(); if (label != null) label.close(); }
        timeLabels.clear();
    }
    @Override public void close() {
        closed = true; supported.clear(); worker.shutdownNow();
        for (XposedInterface.HookHandle handle : handles) try { handle.unhook(); } catch (RuntimeException ignored) { }
        handles.clear(); main.post(() -> { removeMenuButtons(); removeTimeLabels(); });
    }
}
