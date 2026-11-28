package dev.ganvivo.dmsilencer;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 针对 com.mobiletools.systemhelper（中国移动信号助手 3.0.1-20251230）的定向静音：
 * 1. 阻断全部 7 个 BroadcastReceiver（含 15 分钟一次拉取 6 位小数 GPS 的 GetSignalInfoReceiver）；
 * 2. 阻断 RegisterIntentService.onHandleIntent、RegisterJobService.onStartJob；
 * 3. 阻断 GetSignalInfoReceiver 的百度定位启动（d(...)）与用户授权校验（b(Context) -> false）。
 * 保持 DMApplication 与 RegisterHelperService 生命周期壳完整，避免系统反复拉起报错。
 */
final class SystemHelperBlocker {

    private static final String[] RECEIVERS = {
            "com.mobiletools.systemhelper.control.GetSignalInfoReceiver",
            "com.mobiletools.systemhelper.control.SignalInfoRegisterReceiver",
            "com.mobiletools.systemhelper.control.ScheduleRegisterReceiver",
            "com.mobiletools.systemhelper.control.AppInfoRegisterReceiver",
            "com.mobiletools.systemhelper.control.ConnectStateReceiver",
            "com.mobiletools.systemhelper.control.OnBootReceiver",
            "com.mobiletools.systemhelper.control.SIMStateReceiver",
    };

    private SystemHelperBlocker() {
    }

    static int install(XposedModule module, ClassLoader cl) {
        int count = 0;

        for (String clsName : RECEIVERS) {
            Class<?> cls = Hooks.findClassOrNull(clsName, cl);
            final String shortName = clsName.substring(clsName.lastIndexOf('.') + 1);
            count += Hooks.hookAllByName(module, cls, "onReceive", chain -> {
                Intent intent = (chain.getArgs().size() >= 2 && chain.getArgs().get(1) instanceof Intent)
                        ? (Intent) chain.getArgs().get(1) : null;
                String action = intent != null ? intent.getAction() : "null";
                Logx.block("syshelper.rx." + shortName, shortName + ".onReceive action=" + action);
                return null;
            });
        }

        // 双重锁死 GetSignalInfoReceiver 的定位与权限检查
        Class<?> sigRx = Hooks.findClassOrNull(
                "com.mobiletools.systemhelper.control.GetSignalInfoReceiver", cl);
        if (sigRx != null) {
            count += Hooks.hookAllByName(module, sigRx, "b", chain -> {
                if (chain.getExecutable() instanceof Method
                        && ((Method) chain.getExecutable()).getReturnType() == Boolean.TYPE) {
                    Logx.block("syshelper.perm", "GetSignalInfoReceiver.b -> false");
                    return Boolean.FALSE;
                }
                return chain.proceed();
            });
            count += Hooks.hookAllByName(module, sigRx, "d", chain -> {
                Logx.block("syshelper.gps", "GetSignalInfoReceiver.d (BaiduLoc) blocked");
                return null;
            });
        }

        // 阻断 IntentService 上报执行
        Class<?> intentSvc = Hooks.findClassOrNull(
                "com.mobiletools.systemhelper.service.RegisterIntentService", cl);
        count += Hooks.hookAllByName(module, intentSvc, "onHandleIntent", chain -> {
            Logx.block("syshelper.svc.intent", "RegisterIntentService.onHandleIntent blocked");
            return null;
        });

        // 阻断 JobService 调度执行
        Class<?> jobSvc = Hooks.findClassOrNull(
                "com.mobiletools.systemhelper.service.RegisterJobService", cl);
        count += Hooks.hookAllByName(module, jobSvc, "onStartJob", chain -> {
            Logx.block("syshelper.svc.job", "RegisterJobService.onStartJob blocked");
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

        // 兜底拦截核心触发器 e1.b.a(Context, String)（实际发送 S/R/A 报告入口）
        Class<?> reporter = Hooks.findClassOrNull("e1.b", cl);
        if (reporter != null) {
            count += Hooks.hookAllByName(module, reporter, "a", chain -> {
                if (chain.getArgs().size() == 2 && chain.getArgs().get(1) instanceof String) {
                    Logx.block("syshelper.report", "e1.b.a type=" + chain.getArgs().get(1));
                    return null;
                }
                return chain.proceed();
            });
        }

        Logx.i("SystemHelperBlocker installed=" + count);
        return count;
    }
}
