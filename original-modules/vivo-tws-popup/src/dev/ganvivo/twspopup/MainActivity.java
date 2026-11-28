package dev.ganvivo.twspopup;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 原生蓝牙全系弹窗配置与实时预览页（通过显式广播直接唤醒并同步到 com.android.vivo.tws.vivotws）。
 */
public final class MainActivity extends Activity {

    private static final String UI_PREFS = "twspopup_ui";

    private static final int[] MODEL_IDS = {
            32, 29, 16, 0, 60, 61, 64, 48, 68, 72, 84, 96, 97, 52
    };

    private static final String[] MODEL_LABELS = {
            "32 · vivo TWS 2e（纯白 · 86帧完整动画 · 推荐）",
            "29 · vivo TWS 2（星际蓝 · 85帧完整动画）",
            "16 · vivo TWS Neo（皓月白 · 88帧完整动画）",
            "0  · vivo TWS 1（悬浮白 · 72帧完整动画）",
            "60 · vivo TWS 3（纯白 · 入耳式）",
            "61 · vivo TWS 3（深蓝 · 入耳式）",
            "64 · vivo TWS 3 Pro（原石白 · 旗舰入耳）",
            "48 · vivo TWS Air（元气白 · 半入耳）",
            "68 · iQOO TWS Air（经典黄黑 · 半入耳）",
            "72 · vivo TWS Air Pro（元气白 · 半入耳）",
            "84 · iQOO TWS Air Pro（半入耳降噪）",
            "96 · iQOO TWS 1（赛道黑黄 · 入耳式）",
            "97 · iQOO TWS 1（传奇白 · 入耳式）",
            "52 · vivo Wireless Sport 2（颈挂/单电量）",
    };

    private Switch swAuto;
    private Switch swAirpods;
    private Spinner spModel;
    private EditText etFilter;
    private EditText etTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences sp = getSharedPreferences(UI_PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0D1117"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(46), pad, dp(36));

        TextView title = new TextView(this);
        title.setText("原生蓝牙全系弹窗 " + LibXposedEntry.VERSION);
        title.setTextColor(Color.parseColor("#F1F5F9"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("仅作用域 com.android.vivo.tws.vivotws · 免 Hook 蓝牙服务 · 免重启");
        sub.setTextColor(Color.parseColor("#A78BFA"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        // 配置卡片
        LinearLayout card = makeCard();
        swAuto = new Switch(this);
        swAuto.setText("蓝牙耳机连接时自动弹出原生卡片");
        swAuto.setTextColor(Color.parseColor("#E2E8F0"));
        swAuto.setChecked(sp.getBoolean("auto", true));
        swAuto.setPadding(0, dp(4), 0, dp(10));
        card.addView(swAuto);

        swAirpods = new Switch(this);
        swAirpods.setText("AirPods 连接瞬间短扫 BLE 解析三电量");
        swAirpods.setTextColor(Color.parseColor("#E2E8F0"));
        swAirpods.setChecked(sp.getBoolean("airpods_ble", true));
        swAirpods.setPadding(0, dp(4), 0, dp(14));
        card.addView(swAirpods);

        card.addView(makeLabel("弹窗外观与动画模型（均为本机内置完整素材）"));
        spModel = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_dropdown_item, MODEL_LABELS);
        spModel.setAdapter(adapter);
        int savedModel = sp.getInt("model", 32);
        for (int i = 0; i < MODEL_IDS.length; i++) {
            if (MODEL_IDS[i] == savedModel) {
                spModel.setSelection(i);
                break;
            }
        }
        card.addView(spModel);

        card.addView(makeLabel("触发设备过滤（留空=所有非 vivo 蓝牙音频耳机；可填名称关键词或 MAC，逗号分隔）"));
        etFilter = makeEdit("例如：AirPods, Sony, 草饲（留空表示全部）", sp.getString("filter", ""));
        card.addView(etFilter);

        card.addView(makeLabel("自定义弹窗标题（留空=自动显示耳机蓝牙真名）"));
        etTitle = makeEdit("留空自动读取设备名", sp.getString("title", ""));
        card.addView(etTitle);

        root.addView(card);

        // 操作按钮
        Button btnSave = makeButton("保存并同步配置到 vivo 耳机服务", "#2563EB");
        btnSave.setOnClickListener(v -> {
            saveAndSync();
            Toast.makeText(this, "配置已同步（免重启即刻生效）", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSave);

        Button btnPreview = makeButton("✨ 立即预览电量弹窗（已连接态）", "#059669");
        btnPreview.setOnClickListener(v -> {
            saveAndSync();
            sendPreview(PopupParams.STATE_CONNECTED);
        });
        root.addView(btnPreview);

        Button btnPreviewPair = makeButton("🎬 预览完整三段动画（配对 → 连接中 → 电量）", "#7C3AED");
        btnPreviewPair.setOnClickListener(v -> {
            saveAndSync();
            sendPreview(PopupParams.STATE_CONNECT);
        });
        root.addView(btnPreviewPair);

        TextView hint = new TextView(this);
        hint.setTextColor(Color.parseColor("#94A3B8"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        hint.setLineSpacing(dp(3), 1.0f);
        hint.setPadding(0, dp(16), 0, 0);
        hint.setText(
                "启用步骤：\n"
                + "1. 在 LSPosed 管理器中启用本模块，仅勾选「vivo 耳机 / com.android.vivo.tws.vivotws」；\n"
                + "2. 首次启用后重拉一次 vivotws 进程（或点上方预览按钮唤醒）；\n"
                + "3. 之后连上你的第三方蓝牙耳机即可自动弹出原生卡片，无需再动蓝牙服务或重启手机。\n"
                + "日志标签：VivoTwsPopup（仅 logcat）。"
        );
        root.addView(hint);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void saveAndSync() {
        int model = MODEL_IDS[spModel.getSelectedItemPosition()];
        boolean auto = swAuto.isChecked();
        boolean ble = swAirpods.isChecked();
        String filter = etFilter.getText().toString().trim();
        String title = etTitle.getText().toString().trim();

        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                .putInt("model", model)
                .putBoolean("auto", auto)
                .putBoolean("airpods_ble", ble)
                .putString("filter", filter)
                .putString("title", title)
                .apply();

        Intent sync = new Intent(PopupEngine.ACTION_CONFIG)
                .setComponent(new ComponentName(VivoTwsBridge.PACKAGE, VivoTwsBridge.TRIGGER_RECEIVER))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("model", model)
                .putExtra("auto", auto)
                .putExtra("airpods_ble", ble)
                .putExtra("filter", filter)
                .putExtra("title", title);
        sendBroadcast(sync);
    }

    private void sendPreview(int state) {
        int model = MODEL_IDS[spModel.getSelectedItemPosition()];
        String title = etTitle.getText().toString().trim();
        Intent show = new Intent(PopupEngine.ACTION_SHOW)
                .setComponent(new ComponentName(VivoTwsBridge.PACKAGE, VivoTwsBridge.TRIGGER_RECEIVER))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("model", model)
                .putExtra("state", state)
                .putExtra("left", 85)
                .putExtra("right", 90)
                .putExtra("box", 100)
                .putExtra("charging", PopupParams.CASE)
                .putExtra("incase", PopupParams.LEFT | PopupParams.RIGHT);
        if (!title.isEmpty()) {
            show.putExtra("title", title);
        }
        sendBroadcast(show);
        Toast.makeText(this, "已发送原生弹窗预览广播（模型 " + model + "）", Toast.LENGTH_SHORT).show();
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#161D2C"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#28354D"));
        card.setBackground(bg);
        int p = dp(16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#94A3B8"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(0, dp(10), 0, dp(4));
        return tv;
    }

    private EditText makeEdit(String hint, String value) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value);
        et.setTextColor(Color.parseColor("#F1F5F9"));
        et.setHintTextColor(Color.parseColor("#64748B"));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#0F1522"));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.parseColor("#334155"));
        et.setBackground(bg);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        return et;
    }

    private Button makeButton(String text, String hexColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hexColor));
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        btn.setLayoutParams(lp);
        btn.setPadding(dp(14), dp(12), dp(14), dp(12));
        return btn;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
