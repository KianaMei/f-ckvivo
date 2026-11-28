package dev.ganvivo.twspopup;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * 持久化配置（vivotws 进程内常驻私有 SharedPreferences，仅模块自身读取，零 /sdcard 或 /data/local/tmp 临时文件）。
 */
final class TwsConfigStore {

    private static final String PREFS_NAME = "ganvivo_twspopup_cfg";

    int defaultModel = 32;          // vivo TWS 2e（内置 86 帧完整流畅动画）
    boolean autoEnabled = true;     // 自动为非 vivo 蓝牙音频耳机弹窗
    boolean airpodsBle = true;      // 连接瞬间短扫 8 秒解析 AirPods 左/右/盒三电量
    String filterKeywords = "";     // 留空=全部非 vivo 蓝牙音频耳机；逗号分隔关键词或 MAC
    String customTitle = "";        // 留空=显示耳机真实蓝牙名称

    static TwsConfigStore load(Context ctx) {
        TwsConfigStore s = new TwsConfigStore();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            s.defaultModel = sp.getInt("model", 32);
            s.autoEnabled = sp.getBoolean("auto", true);
            s.airpodsBle = sp.getBoolean("airpods_ble", true);
            s.filterKeywords = sp.getString("filter", "");
            s.customTitle = sp.getString("title", "");
        } catch (Throwable ignored) {
        }
        // 可选 Settings.Global 覆盖（方便 adb 快速调试）
        try {
            String gm = Settings.Global.getString(ctx.getContentResolver(), "ganvivo_twspopup_model");
            if (gm != null && !gm.trim().isEmpty()) {
                s.defaultModel = Integer.parseInt(gm.trim());
            }
            String gf = Settings.Global.getString(ctx.getContentResolver(), "ganvivo_twspopup_filter");
            if (gf != null) {
                s.filterKeywords = gf.trim();
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    static TwsConfigStore saveFromIntent(Context ctx, Intent in) {
        TwsConfigStore s = load(ctx);
        if (in.hasExtra("model")) {
            s.defaultModel = in.getIntExtra("model", s.defaultModel);
        }
        if (in.hasExtra("auto")) {
            s.autoEnabled = in.getBooleanExtra("auto", s.autoEnabled);
        }
        if (in.hasExtra("airpods_ble")) {
            s.airpodsBle = in.getBooleanExtra("airpods_ble", s.airpodsBle);
        }
        if (in.hasExtra("filter")) {
            String f = in.getStringExtra("filter");
            s.filterKeywords = f == null ? "" : f.trim();
        }
        if (in.hasExtra("title")) {
            String t = in.getStringExtra("title");
            s.customTitle = t == null ? "" : t.trim();
        }
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putInt("model", s.defaultModel)
                    .putBoolean("auto", s.autoEnabled)
                    .putBoolean("airpods_ble", s.airpodsBle)
                    .putString("filter", s.filterKeywords)
                    .putString("title", s.customTitle)
                    .apply();
        } catch (Throwable t) {
            Logx.w("save prefs failed", t);
        }
        return s;
    }
}
