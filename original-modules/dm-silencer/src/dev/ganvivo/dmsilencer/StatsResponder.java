package dev.ganvivo.dmsilencer;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 在目标遥测进程内动态注册轻量只读查询接收器：
 * - 仅响应 dev.ganvivo.dmsilencer.ACTION_QUERY_STATS 有序广播；
 * - App 端携带 FLAG_RECEIVER_REGISTERED_ONLY 查询，进程未运行时绝不会唤醒；
 * - 全程纯内存快照返回，零磁盘写入、零后台轮询。
 */
final class StatsResponder {

    static final String ACTION_QUERY_STATS = "dev.ganvivo.dmsilencer.ACTION_QUERY_STATS";
    static final String EXTRA_CLEAR_RING = "clear_ring";

    private static volatile boolean sRegistered = false;

    private StatsResponder() {
    }

    static int install(XposedModule module) {
        int count = 0;
        try {
            count += Hooks.hookAllByName(module, Application.class, "onCreate", chain -> {
                Object res = chain.proceed();
                if (chain.getThisObject() instanceof Application) {
                    registerOnce((Application) chain.getThisObject());
                }
                return res;
            });
        } catch (Throwable t) {
            Logx.w("StatsResponder hook Application.onCreate failed", t);
        }

        // 若注入发生在 Application.onCreate 之后，兜底尝试立刻取 currentApplication 注册
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method curApp = atCls.getDeclaredMethod("currentApplication");
            curApp.setAccessible(true);
            Object app = curApp.invoke(null);
            if (app instanceof Application) {
                registerOnce((Application) app);
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static void registerOnce(final Application app) {
        if (sRegistered || app == null) {
            return;
        }
        synchronized (StatsResponder.class) {
            if (sRegistered) {
                return;
            }
            try {
                IntentFilter filter = new IntentFilter(ACTION_QUERY_STATS);
                BroadcastReceiver rx = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null || !ACTION_QUERY_STATS.equals(intent.getAction())) {
                            return;
                        }
                        if (intent.getBooleanExtra(EXTRA_CLEAR_RING, false)) {
                            Logx.clearRing();
                        }
                        if (isOrderedBroadcast()) {
                            Bundle snap = Logx.buildSnapshotBundle();
                            setResultCode(Activity.RESULT_OK);
                            setResultExtras(snap);
                        }
                    }
                };
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED);
                } else {
                    app.registerReceiver(rx, filter);
                }
                sRegistered = true;
                Logx.i("StatsResponder registered in " + app.getPackageName());
            } catch (Throwable t) {
                Logx.w("StatsResponder register failed", t);
            }
        }
    }
}
