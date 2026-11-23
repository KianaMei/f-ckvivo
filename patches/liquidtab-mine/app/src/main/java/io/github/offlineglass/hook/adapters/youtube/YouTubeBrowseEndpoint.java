package io.github.offlineglass.hook.adapters.youtube;

import java.nio.charset.StandardCharsets;

/** Reads the native BrowseEndpoint field, never a substring of arbitrary payload data. */
final class YouTubeBrowseEndpoint {
    static String browseId(byte[] data) {
        if (data == null || data.length > 65536) return null;
        try {
            // navigation_endpoint: browseEndpoint (field 48687626), browseId (field 2).
            byte[] browse = field(data, 48687626);
            byte[] id = browse == null ? null : field(browse, 2);
            return id == null ? null : new String(id, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) { return null; }
    }
    private static byte[] field(byte[] data, int wanted) {
        int[] pos = {0}; byte[] result = null; int count = 0;
        while (pos[0] < data.length) {
            if (++count > 1024) throw new IllegalArgumentException();
            long tag = varint(data, pos); int wire = (int)(tag & 7), number = (int)(tag >>> 3);
            if (number == 0) throw new IllegalArgumentException();
            if (wire == 0) varint(data, pos);
            else if (wire == 1 || wire == 5) pos[0] += wire == 1 ? 8 : 4;
            else if (wire == 2) {
                long length = varint(data, pos);
                if (length > data.length - pos[0]) throw new IllegalArgumentException();
                int end = pos[0] + (int)length;
                if (number == wanted) {
                    if (result != null) throw new IllegalArgumentException();
                    result = java.util.Arrays.copyOfRange(data, pos[0], end);
                }
                pos[0] = end;
            } else throw new IllegalArgumentException();
            if (pos[0] > data.length) throw new IllegalArgumentException();
        }
        return result;
    }
    private static long varint(byte[] data, int[] pos) {
        long value = 0;
        for (int shift = 0; shift < 63 && pos[0] < data.length; shift += 7) {
            int next = data[pos[0]++] & 255; value |= (long)(next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IllegalArgumentException();
    }
}
