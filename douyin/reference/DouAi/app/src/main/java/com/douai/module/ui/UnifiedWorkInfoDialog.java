package com.douai.module.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Exact counters from the selected work, without a third-party location lookup. */
public final class UnifiedWorkInfoDialog {
    private UnifiedWorkInfoDialog() { }
    public static void show(Context context, Object aweme) {
        String text = describe(aweme);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("作品完整信息").setMessage(text).setNegativeButton("关闭", null)
                .setPositiveButton("复制", (ignored, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("作品信息", text));
                }).create();
        com.douai.module.feature.UnifiedPopupFilterHook.markModule(dialog);
        dialog.show();
    }
    static String describe(Object aweme) {
        StringBuilder out = new StringBuilder();
        append(out, "作品 ID", read(aweme, "aid", "aweme_id"));
        append(out, "文案", read(aweme, "desc"));
        Object author = read(aweme, "author");
        append(out, "作者", read(author, "nickname")); append(out, "作者 ID", read(author, "uid", "id"));
        Object stats = read(aweme, "statistics");
        append(out, "播放", read(stats, "play_count")); append(out, "点赞", read(stats, "digg_count"));
        append(out, "评论", read(stats, "comment_count")); append(out, "收藏", read(stats, "collect_count"));
        append(out, "分享", read(stats, "share_count"));
        Object created = read(aweme, "createTime", "create_time");
        if (created instanceof Number) {
            try { created = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(((Number) created).longValue())); }
            catch (RuntimeException ignored) { }
        }
        append(out, "发布时间", created);
        Object city = read(aweme, "city");
        String region = com.douai.module.feature.UnifiedCityLookup.resolve(city == null ? null : String.valueOf(city));
        if (!region.isEmpty()) append(out, "发布地", region);
        append(out, "发布地区码", city);
        return out.length() == 0 ? "当前作品未提供可读取的数据" : out.toString();
    }
    private static void append(StringBuilder out, String label, Object value) { if (value != null) out.append(label).append("：").append(value).append('\n'); }
    private static Object read(Object owner, String... names) {
        if (owner == null) return null;
        for (String name : names) {
            for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    boolean match = field.getName().equals(name);
                    for (java.lang.annotation.Annotation annotation : field.getDeclaredAnnotations()) {
                        if (!annotation.annotationType().getName().equals("com.google.gson.annotations.SerializedName")) continue;
                        try { match |= name.equals(annotation.annotationType().getMethod("value").invoke(annotation)); } catch (Exception ignored) { }
                    }
                    if (match) try { field.setAccessible(true); return field.get(owner); } catch (ReflectiveOperationException ignored) { }
                }
            }
        }
        return null;
    }
}
