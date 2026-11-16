package com.douai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.WebDavSettings;
import java.util.LinkedHashMap;
import java.util.Map;

/** One shared settings surface for the integrated post-download WebDAV feature. */
final class WebDavSettingsDialog {
    private WebDavSettingsDialog() { }
    static Dialog create(Context context, SettingsStore store) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, padding / 2);
        TextView description = new TextView(context);
        description.setText("下载成功后，在独立任务中上传至 WebDAV 的 DouAi 目录。可在下载任务中取消；上传失败会保留本地文件。");
        form.addView(description);
        Switch enabled = toggle(form, context, "下载后自动上传", store.getBoolean(WebDavSettings.ENABLED, false));
        EditText address = field(form, context, "WebDAV 目录地址，例如 https://host/dav/", store.getString(WebDavSettings.URL, ""), false);
        EditText username = field(form, context, "用户名", store.getString(WebDavSettings.USER, ""), false);
        EditText password = field(form, context, "密码或应用密码", store.getString(WebDavSettings.PASSWORD, ""), true);
        Switch http = toggle(form, context, "允许 HTTP（文件与凭据无传输加密）", store.getBoolean(WebDavSettings.ALLOW_HTTP, false));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("WebDAV 自动上传")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                String url = address.getText().toString().trim();
                if (!url.isEmpty()) WebDavSettings.validate(url, http.isChecked());
                if (enabled.isChecked() && url.isEmpty()) throw new IllegalArgumentException("请先填写 WebDAV 目录地址");
                if (username.getText().toString().contains(":")) throw new IllegalArgumentException("用户名不能包含冒号");
                Map<String, ?> previous = store.snapshot();
                Map<String, Object> values = new LinkedHashMap<>(previous);
                values.put(WebDavSettings.URL, url);
                values.put(WebDavSettings.USER, username.getText().toString());
                values.put(WebDavSettings.PASSWORD, password.getText().toString());
                values.put(WebDavSettings.ALLOW_HTTP, http.isChecked());
                values.put(WebDavSettings.ENABLED, enabled.isChecked());
                store.replaceSnapshot(previous, values);
                dialog.dismiss();
            } catch (RuntimeException failure) {
                Toast.makeText(context, failure.getMessage() == null ? "无法保存设置" : failure.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }));
        com.douai.module.feature.UnifiedPopupFilterHook.markModule(dialog);
        return dialog;
    }
    private static EditText field(LinearLayout form, Context context, String hint, String value, boolean secret) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_NORMAL));
        input.setText(value);
        form.addView(input);
        return input;
    }
    private static Switch toggle(LinearLayout form, Context context, String label, boolean checked) {
        Switch view = new Switch(context); view.setText(label); view.setChecked(checked); form.addView(view); return view;
    }
}
