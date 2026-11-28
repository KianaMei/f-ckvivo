package dev.ganvivo.dmsilencer;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 针对 com.vivo.devicereg（实测 DeviceReg 5.5.0.0）的四层定向静音：
 * 1. 总开关层：DeviceRegActivity.e(Context)=false, ctrl.a.p()=false, ctrl.a.t()=false, ctrl.a.k() 置空；
 * 2. 广播与调度层：CmRegReceiver / DmAlarmReceiver / DmOnlineConfigReceiver / DmOnlineConfigJobService /
 *    DMBroadCastReceiver / DynamicBroadcast；保留 RegReceiver 工程暗码（666/0000）入口不误伤；
 * 3. 彩讯 DM SDK 层：DMController 18 项采集上报方法全部短路；
 * 4. 数据库层：cn.richinfo.dm.db.DbManager 全部 insert/update 写入方法置空。
 */
final class DeviceRegBlocker {

    private static final String[] DM_CONTROLLER_METHODS = {
            "onAppReport", "onAppReportRetry", "appInfoReport",
            "onHeartbeat", "onHeartbeatRetry", "onHeartbeatSuccess", "reportHeartbeat",
            "onNetworkCollect", "reportNetworkMsg",
            "onSimStateChange", "onRegisterRetry", "onRegSuccess",
            "onBluetoothChange", "onConnectivityChange", "onSnapshotRetry",
            "onBatteryChange", "onThirdRequest", "onVolteChange",
    };

    private static final String[] DB_MANAGER_WRITE_METHODS = {
            "insert",
            "insertAppInfo",
            "insertBatteryInfo",
            "insertNetworkInfo",
            "insertWifiInfo",
            "insertTimeRelatedTrafficRecord",
            "updateAllAppInfo",
            "updateAppUseInfo",
            "updateBluetoothInfo",
    };

    private static final String[] SILENT_RECEIVERS = {
            "com.dmyk.android.telephony.receiver.CmRegReceiver",
            "com.vivo.devicereg.receiver.DmAlarmReceiver",
            "com.vivo.devicereg.ctrl.DmOnlineConfigReceiver",
            "cn.richinfo.dm.receiver.DMBroadCastReceiver",
            "cn.richinfo.dm.receiver.DynamicBroadcast",
    };

    private DeviceRegBlocker() {
    }

    static int install(XposedModule module, ClassLoader cl) {
        int count = 0;

        // 1. 总开关：让 RegApp / RegReceiver / CmRegReceiver 认为 DM 自注册处于关闭状态
        Class<?> regAct = Hooks.findClassOrNull("com.vivo.devicereg.ui.DeviceRegActivity", cl);
        if (regAct != null) {
            count += Hooks.hookAllByName(module, regAct, "e", chain -> {
                if (chain.getExecutable() instanceof Method) {
                    Method m = (Method) chain.getExecutable();
                    if (m.getReturnType() == Boolean.TYPE && m.getParameterTypes().length == 1) {
                        Logx.block("devicereg.master", "DeviceRegActivity.e -> false");
                        return Boolean.FALSE;
                    }
                }
                return chain.proceed();
            });
        }

        Class<?> ctrlA = Hooks.findClassOrNull("com.vivo.devicereg.ctrl.a", cl);
        if (ctrlA != null) {
            count += Hooks.hookAllByName(module, ctrlA, "p", chain -> {
                Logx.block("devicereg.cmcc", "ctrl.a.p -> false");
                return Boolean.FALSE;
            });
            count += Hooks.hookAllByName(module, ctrlA, "t", chain -> {
                Logx.block("devicereg.ctcc", "ctrl.a.t -> false");
                return Boolean.FALSE;
            });
            count += Hooks.hookAllByName(module, ctrlA, "k", chain -> {
                Logx.block("devicereg.init", "ctrl.a.k skipped");
                return null;
            });
        }

        // 2. 静音各广播接收器（RegReceiver 保留暗码界面入口，阻断其余一切自注册动作）
        for (String clsName : SILENT_RECEIVERS) {
            Class<?> cls = Hooks.findClassOrNull(clsName, cl);
            final String shortName = clsName.substring(clsName.lastIndexOf('.') + 1);
            count += Hooks.hookAllByName(module, cls, "onReceive", chain -> {
                Intent intent = (chain.getArgs().size() >= 2 && chain.getArgs().get(1) instanceof Intent)
                        ? (Intent) chain.getArgs().get(1) : null;
                String action = intent != null ? intent.getAction() : "null";
                Logx.block("devicereg.rx." + shortName, shortName + ".onReceive action=" + action);
                return null;
            });
        }

        Class<?> regRx = Hooks.findClassOrNull("com.vivo.devicereg.receiver.RegReceiver", cl);
        count += Hooks.hookAllByName(module, regRx, "onReceive", chain -> {
            Intent intent = (chain.getArgs().size() >= 2 && chain.getArgs().get(1) instanceof Intent)
                    ? (Intent) chain.getArgs().get(1) : null;
            String action = intent != null ? intent.getAction() : "";
            if ("android.provider.Telephony.VIVO_SECRET_CODE".equals(action)
                    || "android.provider.Telephony.SECRET_CODE".equals(action)) {
                return chain.proceed();
            }
            Logx.block("devicereg.rx.RegReceiver", "RegReceiver.onReceive action=" + action);
            return null;
        });

        // 在线配置 JobService 立即结束不重试
        Class<?> cfgJob = Hooks.findClassOrNull("com.vivo.devicereg.ctrl.DmOnlineConfigJobService", cl);
        count += Hooks.hookAllByName(module, cfgJob, "onStartJob", chain -> {
            Logx.block("devicereg.job", "DmOnlineConfigJobService.onStartJob blocked");
            if (chain.getThisObject() instanceof JobService
                    && !chain.getArgs().isEmpty()
                    && chain.getArgs().get(0) instanceof JobParameters) {
                try {
                    ((JobService) chain.getThisObject())
                            .jobFinished((JobParameters) chain.getArgs().get(0), false);
                } catch (Throwable ignored) {
                }
            }
            return Boolean.FALSE;
        });

        // 3. 彩讯 DMController 18/18 采集与上报方法全拦截
        Class<?> dmController = Hooks.findClassOrNull("cn.richinfo.dm.DMController", cl);
        if (dmController != null) {
            for (final String mName : DM_CONTROLLER_METHODS) {
                count += Hooks.hookAllByName(module, dmController, mName, chain -> {
                    Logx.block("devicereg.dm." + mName, "DMController." + mName);
                    Class<?> rt = (chain.getExecutable() instanceof Method)
                            ? ((Method) chain.getExecutable()).getReturnType() : Void.TYPE;
                    return Hooks.defaultReturnFor(rt);
                });
            }
        }

        // 4. DbManager 全部写入入口拦截
        Class<?> dbManager = Hooks.findClassOrNull("cn.richinfo.dm.db.DbManager", cl);
        if (dbManager != null) {
            for (final String mName : DB_MANAGER_WRITE_METHODS) {
                count += Hooks.hookAllByName(module, dbManager, mName, chain -> {
                    Object arg0 = chain.getArgs().isEmpty() ? "" : chain.getArgs().get(0);
                    Logx.block("devicereg.db." + mName, "DbManager." + mName + " arg0=" + arg0);
                    Class<?> rt = (chain.getExecutable() instanceof Method)
                            ? ((Method) chain.getExecutable()).getReturnType() : Void.TYPE;
                    return Hooks.defaultReturnFor(rt);
                });
            }
        }

        Logx.i("DeviceRegBlocker installed=" + count);
        return count;
    }
}
