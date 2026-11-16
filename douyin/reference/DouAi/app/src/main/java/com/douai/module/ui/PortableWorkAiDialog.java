package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import com.douai.module.config.AiModelSettings;
import com.douai.module.feature.ChatImage;
import com.douai.module.feature.DouyinSearch;
import java.util.List;

/** Reuses the existing cancellable AI chat, attachments and provider pipeline for manual work analysis. */
public final class PortableWorkAiDialog {
    private PortableWorkAiDialog() { }
    public static Dialog show(Activity activity, AiModelSettings settings, String document,
                              String prompt, List<ChatImage> images) throws ReflectiveOperationException {
        return show(activity, settings, document, prompt, images, null);
    }
    public static Dialog show(Activity activity, AiModelSettings settings, String document,
                              String prompt, List<ChatImage> images, DouyinSearch search) throws ReflectiveOperationException {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        HostNativeChatViews views = HostNativeChatViews.create(activity);
        AiTestChatDialog dialog = new AiTestChatDialog(activity, settings.selected(), true, false, views, search);
        dialog.setOwnerActivity(activity);
        dialog.addDocument("当前作品 / 评论资料", document == null ? "" : document);
        if (images != null) for (ChatImage image : images) dialog.addImage(image);
        views.input.setText(prompt == null ? "总结当前作品" : prompt);
        dialog.show();
        // The user explicitly chose the manual analysis action. Submit exactly one prepared turn.
        views.send.post(() -> { if (dialog.isShowing()) views.send.performClick(); });
        return dialog;
    }
}
