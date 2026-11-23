package dev.ganvivo.origintweaks;

import android.util.Log;

final class Logx {

    static final String TAG = "OriginTweaks";

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
