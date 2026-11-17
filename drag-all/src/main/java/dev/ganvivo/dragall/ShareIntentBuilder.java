package dev.ganvivo.dragall;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ShareIntentBuilder {
    private ShareIntentBuilder() {}

    static Intent build(Context context, ClipData clip) {
        ArrayList<Uri> streams = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        Set<Uri> seen = new LinkedHashSet<>();
        String html = null;
        for (int index = 0; index < clip.getItemCount(); index++) {
            ClipData.Item item = clip.getItemAt(index);
            Uri uri = item.getUri();
            if (uri != null && "content".equals(uri.getScheme()) && seen.add(uri)) streams.add(uri);
            if (item.getText() != null) texts.add(item.getText().toString());
            else if (uri != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                texts.add(uri.toString());
            }
            if (clip.getItemCount() == 1) html = item.getHtmlText();
        }
        if (streams.isEmpty() && texts.isEmpty() && html == null) return null;
        Intent send = new Intent(streams.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
        if (streams.size() > 1) send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
        else if (streams.size() == 1) send.putExtra(Intent.EXTRA_STREAM, streams.get(0));
        if (!texts.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, String.join("\n", texts));
        if (html != null) {
            send.putExtra(Intent.EXTRA_HTML_TEXT, html);
            if (texts.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, html);
        }
        if (streams.isEmpty()) {
            send.setType(html == null ? "text/plain" : "text/html");
        } else {
            send.setType(streamMime(context, streams, clip.getDescription()));
            ClipData grants = new ClipData(clip.getDescription(), new ClipData.Item(streams.get(0)));
            for (int index = 1; index < streams.size(); index++) grants.addItem(new ClipData.Item(streams.get(index)));
            send.setClipData(grants);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        Intent chooser = Intent.createChooser(send, "分享");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (send.getClipData() != null) {
            chooser.setClipData(send.getClipData());
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return chooser;
    }

    private static String streamMime(Context context, List<Uri> streams, ClipDescription description) {
        String common = null;
        for (Uri uri : streams) {
            String mime = null;
            try {
                mime = context.getContentResolver().getType(uri);
            } catch (RuntimeException ignored) {
            }
            if (mime == null && description != null) {
                for (int index = 0; index < description.getMimeTypeCount(); index++) {
                    String candidate = description.getMimeType(index);
                    if (!candidate.startsWith("text/")) {
                        mime = candidate;
                        break;
                    }
                }
            }
            if (mime == null || !mime.contains("/")) return "*/*";
            if (common == null) common = mime;
            else if (!common.equals(mime)) {
                String major = common.substring(0, common.indexOf('/'));
                if (!mime.startsWith(major + "/")) return "*/*";
                common = major + "/*";
            }
        }
        return common == null ? "*/*" : common;
    }
}
