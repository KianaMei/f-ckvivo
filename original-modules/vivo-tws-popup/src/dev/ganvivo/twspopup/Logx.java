package dev.ganvivo.twspopup;

import android.util.Log;

final class Logx {

    static final String TAG = "VivoTwsPopup";

    private Logx() {
    }

    static void i(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    static void w(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    static void w(String msg, Throwable t) {
        try {
            Log.w(TAG, msg, t);
        } catch (Throwable ignored) {
        }
    }
}
