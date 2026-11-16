package com.douai.module.feature;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.douai.module.config.SettingsRepository;
import com.douai.module.config.WebDavSettings;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** Queues a separate cancellable upload only after local output has been committed. */
final class WebDavUploads {
    private WebDavUploads() { }
    static void saved(Context context, String destination, String identifier, long size, String suggestedName) {
        // Uploads never call DownloadTransfer.saved, so completion cannot recursively enqueue itself.
        try {
            WebDavSettings settings = new WebDavSettings(new SettingsRepository(context));
            if (!settings.enabled || settings.collection == null) return;
            String location = identifier;
            if ("download_manager_status_successful".equals(destination)) {
                android.app.DownloadManager manager = (android.app.DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                Uri downloaded = manager == null ? null : manager.getUriForDownloadedFile(Long.parseLong(identifier));
                if (downloaded == null) return;
                location = downloaded.toString();
            }
            final String savedIdentifier = location;
            if (!savedIdentifier.startsWith("content://") && !new File(savedIdentifier).isAbsolute()) return;
            DownloadTasks.submit(context, "webdav\n" + savedIdentifier, "WebDAV · " + suggestedName, transfer -> {
                Uri uri = savedIdentifier.startsWith("content://") ? Uri.parse(savedIdentifier) : null;
                String name = suggestedName;
                long length = size;
                if (uri != null) {
                    try (Cursor cursor = context.getContentResolver().query(uri,
                            new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (column >= 0 && !cursor.isNull(column)) name = cursor.getString(column);
                            column = cursor.getColumnIndex(OpenableColumns.SIZE);
                            if (column >= 0 && !cursor.isNull(column)) length = cursor.getLong(column);
                        }
                    }
                } else { File file = new File(savedIdentifier); name = file.getName(); length = file.length(); }
                transfer.file(name, 1);
                transfer.phase("正在上传 WebDAV");
                try (InputStream input = uri == null ? new FileInputStream(savedIdentifier)
                        : context.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("无法读取已保存文件");
                    new WebDavClient().upload(settings.collection, settings.username, settings.password, name,
                            uri == null ? null : context.getContentResolver().getType(uri), length, input, transfer);
                }
                transfer.fileDone();
                return "WebDAV 上传完成，本地文件已保留";
            }, null);
        } catch (RuntimeException ignored) {
            // Invalid upload configuration must never fail or delete a completed local download.
        }
    }
}
