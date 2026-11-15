package dev.ganvivo.fuckdrag;

import android.util.Log;

final class Logx {

    static final String TAG = "FuckDrag";

    private Logx() {
    }

    static void i(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    static void w(String message, Throwable t) {
        try {
            Log.w(TAG, message, t);
        } catch (Throwable ignored) {
        }
    }
}
