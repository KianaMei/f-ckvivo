package dev.ganvivo.dragall;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides whether the vendor native extraction chain can work on a source app by
 * scanning its APK bytes for the member names the chain reflects into. Result is
 * cached per (package, versionCode); probing runs off the drag path, so the first
 * drag of an unknown client falls back to vendor behavior and later drags benefit.
 */
final class ClientCompat {
    private static final String[] NEEDLES = {
            "Lorg/telegram/ui/Cells/ChatMessageCell;",
            "Lorg/telegram/messenger/ImageReceiver;",
            "getMessageObject", "isInsideImage", "messageText", "caption"};
    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean> PROBING = new ConcurrentHashMap<>();

    private ClientCompat() {}

    static boolean canExtract(Context context, String packageName) {
        String key;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            key = packageName + "@" + info.getLongVersionCode();
        } catch (Exception failure) {
            Log.w("DragAll", "client probe: package info unavailable for " + packageName, failure);
            return false;
        }
        Boolean cached = CACHE.get(key);
        if (cached != null) return cached;
        AtomicBoolean guard = PROBING.computeIfAbsent(key, ignored -> new AtomicBoolean());
        if (guard.compareAndSet(false, true)) {
            new Thread(() -> {
                boolean result = probe(context, packageName);
                CACHE.put(key, result);
                PROBING.remove(key);
                Log.i("DragAll", "client probe: " + key + " compatible=" + result);
            }, "DragAllClientProbe").start();
        }
        return false;
    }

    private static boolean probe(Context context, String packageName) {
        String path;
        try {
            path = context.getPackageManager().getApplicationInfo(packageName, 0).sourceDir;
        } catch (Exception failure) {
            return false;
        }
        int longest = 0;
        for (String needle : NEEDLES) longest = Math.max(longest, needle.length());
        boolean[] found = new boolean[NEEDLES.length];
        byte[] buffer = new byte[1 << 20];
        try (InputStream input = new FileInputStream(new File(path))) {
            int carry = 0;
            int count;
            while ((count = input.read(buffer, carry, buffer.length - carry)) != -1) {
                int length = carry + count;
                for (int index = 0; index < NEEDLES.length; index++) {
                    if (!found[index] && indexOf(buffer, length, NEEDLES[index]) >= 0) {
                        found[index] = true;
                    }
                }
                boolean all = true;
                for (boolean value : found) all &= value;
                if (all) return true;
                carry = Math.min(longest - 1, length);
                System.arraycopy(buffer, length - carry, buffer, 0, carry);
            }
        } catch (IOException failure) {
            Log.w("DragAll", "client probe: read failed for " + packageName, failure);
            return false;
        }
        return false;
    }

    private static int indexOf(byte[] haystack, int length, String needle) {
        int needleLength = needle.length();
        outer:
        for (int start = 0; start + needleLength <= length; start++) {
            for (int offset = 0; offset < needleLength; offset++) {
                if (haystack[start + offset] != needle.charAt(offset)) continue outer;
            }
            return start;
        }
        return -1;
    }
}
