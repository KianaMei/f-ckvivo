package dev.ganvivo.origintweaks;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setTextSize(16f);
        view.setPadding(56, 56, 56, 56);
        view.setText("OriginTweaks 0.1.6\n\n"
                + "运行时 hook（不写系统分区、不改分区、不主动重启）：\n\n"
                + "1. 拖放直达：绕过应用/Activity 白名单\n"
                + "2. 超级拖拽菜单：新增“系统分享”选项（Android 系统 chooser）\n"
                + "3. 超级拖拽设置页：窗口模糊/半透明（液态玻璃）\n"
                + "4. 原子工作台：外屏解锁（仅折叠屏，保留其他限制）\n"
                + "5. 多分屏：外屏分屏槽位从 2 放开到 4\n"
                + "6. 后台样式：内屏也可使用“堆叠”（com.vivo.recents）\n"
                + "7. 超级拖拽设置页：列出全部已安装的桌面 App（开关默认开）\n\n"
                + "作用域：system / com.vivo.globaldragdrop / com.bbk.launcher2 / com.vivo.recents\n"
                + "staticScope=true 只收窄 scope 列表，仍需在 Vector 里手动勾选目标。\n"
                + "生效方式：软重启（不丢 root）；不要硬重启。\n\n"
                + "日志 tag：OriginTweaks（加载后再看 libxposed onPackageLoaded）");
        setContentView(view);
    }
}
