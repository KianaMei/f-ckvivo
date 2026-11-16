package com.douai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.UnifiedGameChoiceHook;
import com.douai.module.feature.UnifiedSessionTools;
import java.util.LinkedHashMap;
import java.util.Map;

/** Consolidates features previously scattered across the other five module settings. */
final class UnifiedExtrasSettingsDialog {
    private UnifiedExtrasSettingsDialog() { }
    static Dialog create(Context context, SettingsStore store) {
        LinearLayout form = new LinearLayout(context); form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * context.getResources().getDisplayMetrics().density); form.setPadding(padding, padding / 2, padding, padding / 2);
        EditText timer = minutes(form, context, "定时退出（0 关闭；3～1440 分钟）", store.getString(UnifiedSessionTools.TIMER, "0"));
        EditText idle = minutes(form, context, "无操作退出（0 关闭；3～1440 分钟）", store.getString(UnifiedSessionTools.IDLE, "0"));
        Switch kill = new Switch(context); kill.setText("到时停止抖音进程（关闭则仅返回桌面）"); kill.setChecked(store.getBoolean(UnifiedSessionTools.KILL, false)); form.addView(kill);
        Switch info = new Switch(context); info.setText("视频上显示完整数据与发布信息入口"); info.setChecked(store.getBoolean("unified_work_info_overlay", false)); form.addView(info);
        Switch rainbow = new Switch(context); rainbow.setText("彩虹弹幕（保留宿主描边与位置）"); rainbow.setChecked(store.getBoolean("unified_rainbow_danmaku", false)); form.addView(rainbow);
        Switch chapter = new Switch(context); chapter.setText("自动跳过宿主已标注的章节广告"); chapter.setChecked(store.getBoolean("unified_skip_chapter_ads", false)); form.addView(chapter);
        Switch read = new Switch(context); read.setText("屏蔽已读回执"); read.setChecked(store.getBoolean("unified_disable_read", false)); form.addView(read);
        Switch typing = new Switch(context); typing.setText("屏蔽正在输入状态"); typing.setChecked(store.getBoolean("unified_disable_typing", false)); form.addView(typing);
        Switch media = new Switch(context); media.setText("私信长按消息工具（保存、分享、复制）"); media.setChecked(store.getBoolean("unified_message_media_tools", false)); form.addView(media);
        Switch forward = new Switch(context); forward.setText("启用原生语音转发入口"); forward.setChecked(store.getBoolean("unified_voice_forward", false)); form.addView(forward);
        Switch popup = new Switch(context); popup.setText("按关键词过滤抖音弹窗"); popup.setChecked(store.getBoolean("unified_popup_filter_enabled", false)); form.addView(popup);
        EditText popupWords = new EditText(context); popupWords.setHint("弹窗关键词：每行一个，留空不拦截"); popupWords.setMinLines(2); popupWords.setText(store.getString("unified_popup_keywords", "")); form.addView(popupWords);
        Switch confirm = new Switch(context); confirm.setText("手动关注前二次确认"); confirm.setChecked(store.getBoolean("unified_confirm_follow", false)); form.addView(confirm);
        Switch emoji = new Switch(context); emoji.setText("显示宿主已有素材的隐藏表情"); emoji.setChecked(store.getBoolean("unified_show_hidden_emoji", false)); form.addView(emoji);
        Switch commentTime = new Switch(context); commentTime.setText("显示完整评论时间"); commentTime.setChecked(store.getBoolean("unified_full_comment_time", false)); form.addView(commentTime);
        Switch messageTime = new Switch(context); messageTime.setText("显示完整私信时间"); messageTime.setChecked(store.getBoolean("unified_full_message_time", false)); form.addView(messageTime);
        Spinner dice = choices(form, context, "发送骰子时选择结果", new String[]{"原生随机", "1", "2", "3", "4", "5", "6"});
        int die = integer(store.getString(UnifiedGameChoiceHook.DICE, "0")); dice.setSelection(die >= 0 && die <= 6 ? die : 0);
        String[] rpsValues = {"random", "剪刀", "布", "石头"};
        Spinner rps = choices(form, context, "发送猜拳时选择结果", new String[]{"原生随机", "剪刀", "布", "石头"});
        for (int index = 0; index < rpsValues.length; index++) if (rpsValues[index].equals(store.getString(UnifiedGameChoiceHook.RPS, "random"))) rps.setSelection(index);
        TextView note = new TextView(context); note.setText("游戏结果只选择宿主提供且能确认名称的资源；资源不存在时保留原生随机。不会修改其他随机数，也不会自动发送消息。"); form.addView(note);
        ScrollView scroll = new ScrollView(context); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("统一增强")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int a = integer(timer.getText().toString()), b = integer(idle.getText().toString());
                if (!(a == 0 || a >= 3 && a <= 1440) || !(b == 0 || b >= 3 && b <= 1440)) throw new IllegalArgumentException("时间应为 0 或 3～1440 分钟");
                Map<String, ?> previous = store.snapshot(); Map<String, Object> values = new LinkedHashMap<>(previous);
                values.put(UnifiedSessionTools.TIMER, Integer.toString(a)); values.put(UnifiedSessionTools.IDLE, Integer.toString(b));
                values.put(UnifiedSessionTools.KILL, kill.isChecked()); values.put("unified_work_info_overlay", info.isChecked());
                values.put("unified_rainbow_danmaku", rainbow.isChecked());
                values.put("unified_skip_chapter_ads", chapter.isChecked());
                values.put("unified_disable_read", read.isChecked()); values.put("unified_disable_typing", typing.isChecked());
                values.put("unified_message_media_tools", media.isChecked()); values.put("unified_voice_forward", forward.isChecked());
                values.put("unified_popup_filter_enabled", popup.isChecked()); values.put("unified_popup_keywords", popupWords.getText().toString());
                values.put("unified_confirm_follow", confirm.isChecked()); values.put("unified_show_hidden_emoji", emoji.isChecked());
                values.put("unified_full_comment_time", commentTime.isChecked()); values.put("unified_full_message_time", messageTime.isChecked());
                values.put(UnifiedGameChoiceHook.DICE, Integer.toString(dice.getSelectedItemPosition()));
                values.put(UnifiedGameChoiceHook.RPS, rpsValues[rps.getSelectedItemPosition()]);
                store.replaceSnapshot(previous, values); dialog.dismiss();
            } catch (RuntimeException failure) { Toast.makeText(context, failure.getMessage(), Toast.LENGTH_SHORT).show(); }
        }));
        com.douai.module.feature.UnifiedPopupFilterHook.markModule(dialog);
        return dialog;
    }
    private static int integer(String value) { try { return Integer.parseInt(value.trim()); } catch (RuntimeException ignored) { return -1; } }
    private static EditText minutes(LinearLayout form, Context context, String label, String value) {
        TextView text = new TextView(context); text.setText(label); form.addView(text);
        EditText input = new EditText(context); input.setSingleLine(true); input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); input.setText(value); form.addView(input); return input;
    }
    private static Spinner choices(LinearLayout form, Context context, String label, String[] choices) {
        TextView text = new TextView(context); text.setText(label); form.addView(text);
        Spinner spinner = new Spinner(context); spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, choices)); form.addView(spinner); return spinner;
    }
}
