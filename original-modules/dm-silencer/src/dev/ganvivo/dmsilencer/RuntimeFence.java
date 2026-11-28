package dev.ganvivo.dmsilencer;

import android.app.AlarmManager;
import android.app.job.JobScheduler;
import android.database.sqlite.SQLiteDatabase;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.telephony.SmsManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Collections;

import io.github.libxposed.api.XposedModule;

/**
 * 进程级底层围栏（仅运行在被勾选的目标遥测进程内部，绝不影响其他任何应用）：
 * 即使未来 ROM 升级导致混淆类名/方法名变化，也能在系统 API 层兜底掐断：
 * - 出网连接（URL.openConnection / Socket.connect 非回环地址）
 * - 本地写库（SQLiteDatabase.insert* / replace*）
 * - 定位获取（LocationManager.request* / getLastKnownLocation / getCurrentLocation）
 * - 周边 Wi-Fi 扫描（WifiManager.startScan / getScanResults）
 * - 后台定时唤醒（AlarmManager.set* / JobScheduler.schedule）
 * - 静默自注册短信（SmsManager.send*）
 */
final class RuntimeFence {

    private RuntimeFence() {
    }

    static int install(XposedModule module, final String pkg) {
        int count = 0;

        // 1. 断网围栏：URL.openConnection
        count += Hooks.hookAllByName(module, URL.class, "openConnection", chain -> {
            String url = String.valueOf(chain.getThisObject());
            Logx.block(pkg + ".net.url", "URL.openConnection " + url);
            throw new IOException("Blocked by DmSilencer (" + pkg + ")");
        });

        // 2. 断网围栏：Socket.connect（放行 loopback，掐断全部外网）
        count += Hooks.hookAllByName(module, Socket.class, "connect", chain -> {
            Object ep = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
            if (ep instanceof InetSocketAddress) {
                InetSocketAddress isa = (InetSocketAddress) ep;
                if (isa.getAddress() != null && isa.getAddress().isLoopbackAddress()) {
                    return chain.proceed();
                }
            }
            Logx.block(pkg + ".net.sock", "Socket.connect " + ep);
            throw new IOException("Blocked by DmSilencer (" + pkg + ")");
        });

        // 3. 禁止写入 SQLite 数据库（cmcc_dm.db / signalhelper.db / systemhelper.db）
        String[] dbMethods = {"insert", "insertOrThrow", "insertWithOnConflict", "replace", "replaceOrThrow"};
        for (final String mName : dbMethods) {
            count += Hooks.hookAllByName(module, SQLiteDatabase.class, mName, chain -> {
                Object tbl = chain.getArgs().isEmpty() ? "?" : chain.getArgs().get(0);
                Logx.block(pkg + ".db." + mName, "SQLiteDatabase." + mName + " table=" + tbl);
                return Long.valueOf(-1L);
            });
        }

        // 4. 禁止定位（LocationManager）
        String[] locVoidMethods = {"requestLocationUpdates", "requestSingleUpdate", "getCurrentLocation"};
        for (final String mName : locVoidMethods) {
            count += Hooks.hookAllByName(module, LocationManager.class, mName, chain -> {
                Logx.block(pkg + ".loc." + mName, "LocationManager." + mName);
                return null;
            });
        }
        count += Hooks.hookAllByName(module, LocationManager.class, "getLastKnownLocation", chain -> {
            Logx.block(pkg + ".loc.last", "LocationManager.getLastKnownLocation -> null");
            return null;
        });

        // 5. 禁止扫描周边 Wi-Fi 列表
        count += Hooks.hookAllByName(module, WifiManager.class, "startScan", chain -> {
            Logx.block(pkg + ".wifi.scan", "WifiManager.startScan -> false");
            return Boolean.FALSE;
        });
        count += Hooks.hookAllByName(module, WifiManager.class, "getScanResults", chain -> {
            Logx.block(pkg + ".wifi.list", "WifiManager.getScanResults -> empty");
            return Collections.emptyList();
        });

        // 6. 禁止注册定时唤醒（AlarmManager）
        String[] alarmMethods = {
                "set", "setExact", "setExactAndAllowWhileIdle", "setAndAllowWhileIdle",
                "setRepeating", "setInexactRepeating", "setWindow"
        };
        for (final String mName : alarmMethods) {
            count += Hooks.hookAllByName(module, AlarmManager.class, mName, chain -> {
                Logx.block(pkg + ".alarm." + mName, "AlarmManager." + mName);
                return null;
            });
        }

        // 7. 禁止静默注册短信（SmsManager）
        String[] smsMethods = {"sendTextMessage", "sendDataMessage", "sendMultipartTextMessage"};
        for (final String mName : smsMethods) {
            count += Hooks.hookAllByName(module, SmsManager.class, mName, chain -> {
                Logx.block(pkg + ".sms." + mName, "SmsManager." + mName);
                return null;
            });
        }

        // 8. 禁止 JobScheduler 排程（注意 JobScheduler 是抽象类，hook 具体实现类或常见方法）
        Class<?> jobSchedImpl = Hooks.findClassOrNull("android.app.JobSchedulerImpl",
                JobScheduler.class.getClassLoader());
        if (jobSchedImpl != null) {
            count += Hooks.hookAllByName(module, jobSchedImpl, "schedule", chain -> {
                Logx.block(pkg + ".job.schedule", "JobScheduler.schedule -> RESULT_FAILURE");
                return Integer.valueOf(JobScheduler.RESULT_FAILURE);
            });
        }

        Logx.i("RuntimeFence installed pkg=" + pkg + " hooks=" + count);
        return count;
    }
}
