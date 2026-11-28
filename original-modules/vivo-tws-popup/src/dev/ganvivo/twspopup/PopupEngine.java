package dev.ganvivo.twspopup;

import android.app.Application;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.github.libxposed.api.XposedModule;

/**
 * 原生弹窗核心控制器（完全运行在 com.android.vivo.tws.vivotws 主进程内，免 Hook com.android.bluetooth）：
 * 1. 监听本进程内动态注册的 A2DP/HFP 连接与电量变化广播，自动为任意非 vivo 第三方蓝牙耳机触发原生弹窗；
 * 2. 监听 DongleBroadcastReceiver 自定义显式广播（SHOW / DISMISS / CONFIG），供本模块 App 预览与热改配置；
 * 3. 接管 TwsFastPairViewServerManager.onClick(String)，让第三方弹窗的关闭按钮、点击空白与自动关闭完全生效。
 */
final class PopupEngine {

    static final String ACTION_PREFIX = "dev.ganvivo.twspopup.";
    static final String ACTION_SHOW = ACTION_PREFIX + "SHOW";
    static final String ACTION_DISMISS = ACTION_PREFIX + "DISMISS";
    static final String ACTION_CONFIG = ACTION_PREFIX + "CONFIG";

    private static final String A2DP_STATE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String HEADSET_STATE = "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED";
    private static final String BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL";

    private static final int CLICK_CLOSE = 1;
    private static final long REBIND_DELAY_MS = 650L;
    private static final long CONNECTING_MS = 1600L;
    private static final long SAME_CONN_DEBOUNCE_MS = 15_000L;
    private static final long BATTERY_FOLLOW_UP_MS = 22_000L;
    private static final long SETTLE_DELAY_MS = 1_100L;

    private static final String[] NATIVE_VIVO_PREFIXES = {
            "vivo tws", "vivo buds", "vivo wireless", "iqoo tws", "iqoo wireless"
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> LAST_SHOWN = new HashMap<String, Long>();

    private static VivoTwsBridge sBridge;
    private static Session sSession;
    private static int sGen;
    private static volatile boolean sBtReceiverRegistered;

    private static final class Session {
        final Object manager;
        final Object rawBean;
        final Object dataRef;
        PopupParams params;
        int state;

        Session(Object manager, Object rawBean, Object dataRef, PopupParams params) {
            this.manager = manager;
            this.rawBean = rawBean;
            this.dataRef = dataRef;
            this.params = params;
            this.state = params.state;
        }
    }

    private PopupEngine() {
    }

    static void install(XposedModule module, ClassLoader cl) throws ReflectiveOperationException {
        if (sBridge != null) {
            return;
        }
        sBridge = new VivoTwsBridge(cl);

        // 1. Hook DongleBroadcastReceiver.onReceive：处理本模块 App 的预览/关闭/配置同步命令
        Method onReceive = Hooks.findMethod(sBridge.triggerReceiverCls, "onReceive", Context.class, Intent.class);
        Hooks.hook(module, onReceive, chain -> {
            Context ctx = (Context) chain.getArgs().get(0);
            Intent in = (Intent) chain.getArgs().get(1);
            String action = in != null ? in.getAction() : null;
            if (ctx == null || action == null || !action.startsWith(ACTION_PREFIX)) {
                return chain.proceed();
            }
            try {
                Application app = (Application) ctx.getApplicationContext();
                ensureBtReceiverRegistered(app);
                handleCommand(app, action, in);
            } catch (Throwable t) {
                Logx.w("command failed: " + action, t);
            }
            return null;
        });

        // 2. Hook TwsFastPairViewServerManager.onClick(String)：响应弹窗上的关闭/连接按钮
        Hooks.hook(module, sBridge.onClickMethod, chain -> {
            Object res = chain.proceed();
            try {
                onPopupClick(chain.getThisObject(), String.valueOf(chain.getArgs().get(0)));
            } catch (Throwable t) {
                Logx.w("onPopupClick failed", t);
            }
            return res;
        });

        // 3. Hook Application.onCreate：主进程一启动即注册蓝牙自动弹窗监听（免 Hook com.android.bluetooth）
        Method appOnCreate = Hooks.findMethod(Application.class, "onCreate");
        Hooks.hook(module, appOnCreate, chain -> {
            Object res = chain.proceed();
            if (chain.getThisObject() instanceof Application) {
                ensureBtReceiverRegistered((Application) chain.getThisObject());
            }
            return res;
        });

        // 4. 顺带在 TwsFastPairViewServerManager.getInstance 兜底确保已注册（防止 onCreate 早于 hook）
        Method getInst = Hooks.findMethod(sBridge.managerCls, "getInstance", Application.class);
        Hooks.hook(module, getInst, chain -> {
            Object res = chain.proceed();
            if (!chain.getArgs().isEmpty() && chain.getArgs().get(0) instanceof Application) {
                ensureBtReceiverRegistered((Application) chain.getArgs().get(0));
            }
            return res;
        });

        Logx.i("PopupEngine installed cleanly in " + VivoTwsBridge.PACKAGE);
    }

    private static void ensureBtReceiverRegistered(final Application app) {
        if (sBtReceiverRegistered || app == null) {
            return;
        }
        synchronized (PopupEngine.class) {
            if (sBtReceiverRegistered) {
                return;
            }
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(A2DP_STATE);
                filter.addAction(HEADSET_STATE);
                filter.addAction(BATTERY_CHANGED);
                BroadcastReceiver rx = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        onBluetoothEvent(app, intent);
                    }
                };
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED);
                } else {
                    app.registerReceiver(rx, filter);
                }
                sBtReceiverRegistered = true;
                Logx.i("In-process Bluetooth watcher registered (zero bluetooth-process hook)");
            } catch (Throwable t) {
                Logx.w("register Bluetooth watcher failed", t);
            }
        }
    }

    private static void onBluetoothEvent(final Application app, Intent in) {
        if (in == null || in.getAction() == null) {
            return;
        }
        try {
            final TwsConfigStore cfg = TwsConfigStore.load(app);
            if (!cfg.autoEnabled) {
                return;
            }
            final BluetoothDevice dev = in.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev == null) {
                return;
            }
            final String name = deviceNameOf(dev);
            if (!shouldPopupForDevice(dev, name, cfg)) {
                return;
            }

            final String mac = dev.getAddress();
            long now = SystemClock.elapsedRealtime();
            Long prev = LAST_SHOWN.get(mac);

            if (BATTERY_CHANGED.equals(in.getAction())) {
                if (prev != null && (now - prev.longValue()) < BATTERY_FOLLOW_UP_MS) {
                    int bat = in.getIntExtra(EXTRA_BATTERY, -1);
                    if (bat >= 0) {
                        PopupParams update = PopupParams.forConnectedDevice(
                                dev, name, bat, bat, -1, 0,
                                PopupParams.LEFT | PopupParams.RIGHT, true, cfg);
                        show(app, update);
                    }
                }
                return;
            }

            int state = in.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            if (state != BluetoothProfile.STATE_CONNECTED) {
                return;
            }
            if (prev != null && (now - prev.longValue()) < SAME_CONN_DEBOUNCE_MS) {
                return;
            }
            LAST_SHOWN.put(mac, Long.valueOf(now));

            // 留给 vivo 原厂 BLE 弹窗 1.1s 抢先机会；若原厂已经在弹（mData!=null 且非本模块 session）则让位
            MAIN.postDelayed(() -> {
                try {
                    Object mgr = sBridge.getManager(app);
                    Object curData = sBridge.getCurrentData(mgr);
                    if (curData != null && (sSession == null || sSession.dataRef != curData)) {
                        Logx.i("Native vivo TWS popup already showing, yield for " + name);
                        return;
                    }
                    int bat = queryHfpBattery(dev);
                    PopupParams req = PopupParams.forConnectedDevice(
                            dev, name, bat, bat, -1, 0,
                            PopupParams.LEFT | PopupParams.RIGHT, false, cfg);
                    show(app, req);

                    // 若启用 AirPods BLE 解析，连接瞬间短扫 8 秒升级为真实左/右/盒三电量
                    if (cfg.airpodsBle) {
                        AirPodsBleScanner.startBurstScan((l, r, b, chg, inC) -> MAIN.post(() -> {
                            try {
                                PopupParams enriched = PopupParams.forConnectedDevice(
                                        dev, name, l, r, b, chg, inC, true, cfg);
                                show(app, enriched);
                            } catch (Throwable ignored) {
                            }
                        }));
                    }
                } catch (Throwable t) {
                    Logx.w("auto popup dispatch failed", t);
                }
            }, SETTLE_DELAY_MS);
        } catch (Throwable t) {
            Logx.w("onBluetoothEvent error", t);
        }
    }

    private static boolean shouldPopupForDevice(BluetoothDevice dev, String name, TwsConfigStore cfg) {
        String filter = cfg.filterKeywords == null ? "" : cfg.filterKeywords.trim();
        if (!filter.isEmpty()) {
            String lowName = name == null ? "" : name.toLowerCase(Locale.ROOT);
            String mac = dev.getAddress() == null ? "" : dev.getAddress().toLowerCase(Locale.ROOT);
            for (String token : filter.split("[,，;；\\s]+")) {
                String k = token.trim().toLowerCase(Locale.ROOT);
                if (!k.isEmpty() && (lowName.contains(k) || mac.equals(k))) {
                    return true;
                }
            }
            return false;
        }

        // 未填关键词：放行全部非 vivo 原厂的音频设备
        if (name != null) {
            String low = name.toLowerCase(Locale.ROOT);
            for (String prefix : NATIVE_VIVO_PREFIXES) {
                if (low.startsWith(prefix)) {
                    return false;
                }
            }
        }
        try {
            BluetoothClass btClass = dev.getBluetoothClass();
            if (btClass != null && btClass.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO
                    && btClass.getMajorDeviceClass() != BluetoothClass.Device.Major.UNCATEGORIZED) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static void handleCommand(Application app, String action, Intent in) throws Exception {
        if (ACTION_CONFIG.equals(action)) {
            TwsConfigStore saved = TwsConfigStore.saveFromIntent(app, in);
            Logx.i("Config synced: model=" + saved.defaultModel
                    + " auto=" + saved.autoEnabled
                    + " airpodsBle=" + saved.airpodsBle
                    + " filter=" + saved.filterKeywords);
            return;
        }
        if (ACTION_DISMISS.equals(action)) {
            closeSession("dismiss command");
            return;
        }
        if (ACTION_SHOW.equals(action)) {
            TwsConfigStore cfg = TwsConfigStore.load(app);
            show(app, PopupParams.fromIntent(in, cfg));
        }
    }

    private static void show(final Application app, final PopupParams req) throws Exception {
        final Object mgr = sBridge.getManager(app);
        Object curData = sBridge.getCurrentData(mgr);
        boolean bound = sBridge.isBound(mgr);

        Session s = sSession;
        if (s != null && bound && curData == s.dataRef && s.state == req.state && s.params.sameDevice(req)) {
            s.params = req;
            sBridge.applyDynamicState(s.rawBean, req, s.state);
            sBridge.push(mgr, s.rawBean);
            Logx.i("Popup updated in-place: " + req);
            return;
        }
        if (req.updateOnly) {
            return;
        }

        final int gen = ++sGen;
        sSession = null;
        if (curData != null || bound) {
            sBridge.reset(mgr);
        }
        Runnable openTask = () -> {
            if (gen == sGen) {
                openNow(app, mgr, req, gen);
            }
        };
        if (bound) {
            MAIN.postDelayed(openTask, REBIND_DELAY_MS);
        } else {
            openTask.run();
        }
    }

    private static void openNow(Application app, Object mgr, PopupParams req, final int gen) {
        try {
            Object rawBean = sBridge.buildBean(app, req);
            if (rawBean == null) {
                return;
            }
            sBridge.push(mgr, rawBean);
            Object data = sBridge.getCurrentData(mgr);
            if (data == null) {
                Logx.w("vivotws manager rejected popup data for " + req);
                return;
            }
            sSession = new Session(mgr, rawBean, data, req);
            Logx.i("Popup shown: " + req);
            if (req.timeoutSec > 0) {
                MAIN.postDelayed(() -> {
                    if (gen == sGen) {
                        closeSession("timeout " + req.timeoutSec + "s");
                    }
                }, req.timeoutSec * 1000L);
            }
        } catch (Throwable t) {
            Logx.w("openNow failed for " + req, t);
        }
    }

    private static void onPopupClick(Object mgr, String codeStr) {
        Session s = sSession;
        if (s == null || sBridge.getCurrentData(mgr) != s.dataRef) {
            return;
        }
        int code;
        try {
            code = Integer.parseInt(codeStr);
        } catch (NumberFormatException ignored) {
            return;
        }
        if (code != CLICK_CLOSE && s.state < PopupParams.STATE_CONNECTING) {
            final int gen = sGen;
            s.state = PopupParams.STATE_CONNECTING;
            sBridge.applyDynamicState(s.rawBean, s.params, s.state);
            sBridge.push(s.manager, s.rawBean);
            MAIN.postDelayed(() -> {
                if (gen != sGen || sSession != s) {
                    return;
                }
                try {
                    s.state = PopupParams.STATE_CONNECTED;
                    sBridge.applyDynamicState(s.rawBean, s.params, s.state);
                    sBridge.push(s.manager, s.rawBean);
                } catch (Throwable ignored) {
                }
            }, CONNECTING_MS);
        } else {
            closeSession("popup click=" + code);
        }
    }

    private static void closeSession(String reason) {
        AirPodsBleScanner.stop();
        Session s = sSession;
        sSession = null;
        sGen++;
        if (s != null) {
            try {
                sBridge.close(s.manager);
                Logx.i("Popup closed: " + reason);
            } catch (Throwable t) {
                Logx.w("closeSession error", t);
            }
        }
    }

    private static String deviceNameOf(BluetoothDevice dev) {
        try {
            String alias = Build.VERSION.SDK_INT >= 30 ? dev.getAlias() : null;
            if (alias != null && !alias.trim().isEmpty()) {
                return alias.trim();
            }
            return dev.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int queryHfpBattery(BluetoothDevice dev) {
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object v = m.invoke(dev);
            return (v instanceof Integer) ? ((Integer) v).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
