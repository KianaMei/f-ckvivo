package dev.ganvivo.vivoworkbench;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 阶段一极简状态页（无危险权限、不写任何外部文件）。
 * 后续阶段二将在此扩展基于 libxposed RemotePreferences 的工作台布局调节界面。
 */
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#111418"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(48), pad, dp(32));

        TextView title = new TextView(this);
        title.setText("原子工作台增强 " + LibXposedEntry.VERSION);
        title.setTextColor(Color.parseColor("#EEF2F6"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("阶段一：OriginOS 7 折叠屏外屏原子工作台解锁（零 system_server 作用域）");
        sub.setTextColor(Color.parseColor("#7FC8A9"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        TextView body = new TextView(this);
        body.setTextColor(Color.parseColor("#C7D0DC"));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setLineSpacing(dp(4), 1.0f);
        body.setText(
                "请在 Vector 管理器中：\n"
                + "1. 启用本模块（dev.ganvivo.vivoworkbench）；\n"
                + "2. 在作用域中勾选：\n"
                + "   • 系统界面（com.android.systemui）\n"
                + "   • 后台近期应用（com.vivo.recents）\n"
                + "   • 系统桌面（com.bbk.launcher2，仅加载内置 recents 插件）\n\n"
                + "已解锁的外屏入口（无需 hook system）：\n"
                + "• 后台最近任务卡片菜单 → 原子工作台\n"
                + "• 小窗 / 分屏顶部小白条菜单 → 原子工作台\n"
                + "• 桌面原子工作台快捷组合图标\n\n"
                + "布局策略：\n"
                + "• 保留 sIsFold=true，直接复用原厂直板机竖屏工作台排版；\n"
                + "• 折叠切换与进入前自动补刷外屏尺寸资源与可用区域。\n\n"
                + "日志标签：VivoWorkbench（仅输出 logcat，不写设备文件）。"
        );
        root.addView(body);

        scroll.addView(root);
        setContentView(scroll);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
