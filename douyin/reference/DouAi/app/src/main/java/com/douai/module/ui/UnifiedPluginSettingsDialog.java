package com.douai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.UnifiedPluginControl;

/** Lists the host's own installed plugins and edits future load restrictions. */
final class UnifiedPluginSettingsDialog {
    private UnifiedPluginSettingsDialog() { }
    static Dialog create(Context context, SettingsStore store) {
        LinearLayout form = new LinearLayout(context); form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density); form.setPadding(padding, padding / 2, padding, padding / 2);
        TextView list = new TextView(context);
        java.util.List<String> names = UnifiedPluginControl.installedNames();
        list.setText("已安装或观察到的官方插件：\n" + (names.isEmpty() ? "尚未读取到插件" : String.join("\n", names)));
        form.addView(list);
        TextView note = new TextView(context); note.setText("禁止加载的完整插件包名（每行一个）。只阻止之后的 Mira 加载请求；已加载插件需重启抖音。屏蔽插件可能影响直播、小游戏等对应功能，可清空恢复。"); form.addView(note);
        EditText blocked = new EditText(context); blocked.setMinLines(3); blocked.setText(store.getString(UnifiedPluginControl.BLOCKED, "")); form.addView(blocked);
        Dialog result = new AlertDialog.Builder(context).setTitle("官方热加载插件管理").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    java.util.Set<String> values = new java.util.TreeSet<>();
                    for (String name : blocked.getText().toString().split("[\\n,;]")) if (!name.trim().isEmpty()) values.add(name.trim());
                    store.putString(UnifiedPluginControl.BLOCKED, String.join("\n", values));
                }).create();
        com.douai.module.feature.UnifiedPopupFilterHook.markModule(result);
        return result;
    }
}
