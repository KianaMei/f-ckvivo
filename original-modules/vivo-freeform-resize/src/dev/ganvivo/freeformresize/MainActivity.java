package dev.ganvivo.freeformresize;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0D1117"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(48), pad, dp(32));

        TextView title = new TextView(this);
        title.setText("小窗无极调比例 " + LibXposedEntry.VERSION);
        title.setTextColor(Color.parseColor("#F1F5F9"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        boolean enabled = true;
        try {
            enabled = Settings.Global.getInt(getContentResolver(),
                    FreeformResizeHooks.SETTING_ENABLED, 1) != 0;
        } catch (Throwable ignored) {
        }

        TextView sub = new TextView(this);
        sub.setText("纯 SystemUI WMShell 作用域 · 零 system_server Hook · 当前全局开关："
                + (enabled ? "已开启" : "已关闭"));
        sub.setTextColor(Color.parseColor(enabled ? "#38BDF8" : "#F87171"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        TextView body = new TextView(this);
        body.setTextColor(Color.parseColor("#CBD5E1"));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setLineSpacing(dp(4), 1.0f);
        body.setText(
                "请在 LSPosed 管理器中启用本模块，并仅勾选：\n"
                + "• 系统界面（com.android.systemui）\n"
                + "（绝不需要勾选 system / 系统框架！）\n\n"
                + "无极调比例操作方式（折叠屏内屏 & 外屏均支持）：\n"
                + "• 拖动左/右边缘：单独调节小窗宽度（高度不变）\n"
                + "• 拖动底部边缘：单独调节小窗高度（宽度不变）\n"
                + "• 拖动左下/右下角：宽高完全独立无极调节任意比例\n"
                + "• 拖动四角缩至极小：自动进入迷你挂起小窗（单边拉扁不误触发）\n"
                + "• 拖动四角拉过上限：自动触发全屏展开\n\n"
                + "松手后通过系统原生 TRANSIT 1510 下发真实任务宽高，App 自动重新排版而非拉伸变形。\n\n"
                + "临时免重启关闭/恢复（新开小窗立即生效）：\n"
                + "settings put global ganvivo_freeform_resize_enabled 0 / 1\n\n"
                + "日志标签：VivoFreeformResize（仅 logcat，不写设备文件）。"
        );
        root.addView(body);

        scroll.addView(root);
        setContentView(scroll);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
