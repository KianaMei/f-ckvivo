package dev.ganvivo.twspopup;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;

import java.util.Locale;

final class PopupParams {

    static final int STATE_CONNECT = 1;
    static final int STATE_CONNECTING = 60;
    static final int STATE_CONNECTED = 80;

    static final int LEFT = 1;
    static final int RIGHT = 2;
    static final int CASE = 4;

    static final String FALLBACK_MAC = "AA:BB:CC:DD:EE:FF";

    int model;
    int state;
    int left;
    int right;
    int box;
    int charging;
    int inCase;
    boolean firstPair;
    boolean updateOnly;
    int timeoutSec;
    String title;
    String mac;
    BluetoothDevice btDevice;

    boolean sameDevice(PopupParams o) {
        return o != null && model == o.model && mac.equals(o.mac);
    }

    static PopupParams fromIntent(Intent in, TwsConfigStore cfg) {
        Bundle x = (in != null && in.getExtras() != null) ? in.getExtras() : Bundle.EMPTY;
        PopupParams p = new PopupParams();
        p.model = getInt(x, "model", cfg.defaultModel);
        p.state = parseState(x.get("state"));
        p.left = clampBat(getInt(x, "left", 85));
        p.right = clampBat(getInt(x, "right", 90));
        p.box = clampBat(getInt(x, "box", 100));
        p.charging = getInt(x, "charging", 0) & (LEFT | RIGHT | CASE);
        p.inCase = getInt(x, "incase", LEFT | RIGHT) & (LEFT | RIGHT);
        p.firstPair = getBool(x, "first", false);
        p.updateOnly = getBool(x, "update", false);
        p.timeoutSec = Math.max(0, getInt(x, "timeout", 0));
        String t = getStr(x, "title");
        p.title = (t != null && !t.isEmpty()) ? t : (cfg.customTitle.isEmpty() ? null : cfg.customTitle);
        p.mac = sanitizeMac(getStr(x, "mac"));
        return p;
    }

    static PopupParams forConnectedDevice(
            BluetoothDevice dev,
            String name,
            int left,
            int right,
            int box,
            int charging,
            int inCase,
            boolean updateOnly,
            TwsConfigStore cfg) {
        PopupParams p = new PopupParams();
        p.model = cfg.defaultModel;
        p.state = STATE_CONNECTED;
        p.left = clampBat(left);
        p.right = clampBat(right);
        p.box = clampBat(box);
        p.charging = charging & (LEFT | RIGHT | CASE);
        p.inCase = inCase & (LEFT | RIGHT);
        p.firstPair = false;
        p.updateOnly = updateOnly;
        p.timeoutSec = 0;
        p.title = !cfg.customTitle.isEmpty() ? cfg.customTitle : name;
        p.mac = dev != null ? sanitizeMac(dev.getAddress()) : FALLBACK_MAC;
        p.btDevice = dev;
        return p;
    }

    @Override
    public String toString() {
        return "model=" + model + " state=" + state + " L=" + left + " R=" + right + " box=" + box
                + " chg=" + charging + " title=" + title;
    }

    private static int clampBat(int v) {
        return v < 0 ? -1 : Math.min(100, v);
    }

    private static int parseState(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v == null) {
            return STATE_CONNECTED;
        }
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        if ("connect".equals(s) || "pair".equals(s)) {
            return STATE_CONNECT;
        }
        if ("connecting".equals(s)) {
            return STATE_CONNECTING;
        }
        if ("connected".equals(s) || "battery".equals(s)) {
            return STATE_CONNECTED;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return STATE_CONNECTED;
        }
    }

    private static int getInt(Bundle x, String key, int def) {
        Object v = x.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static boolean getBool(Bundle x, String key, boolean def) {
        Object v = x.get(key);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v == null) {
            return def;
        }
        String s = v.toString().trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private static String getStr(Bundle x, String key) {
        Object v = x.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static String sanitizeMac(String raw) {
        if (raw == null) {
            return FALLBACK_MAC;
        }
        String up = raw.toUpperCase(Locale.ROOT);
        return BluetoothAdapter.checkBluetoothAddress(up) ? up : FALLBACK_MAC;
    }
}
