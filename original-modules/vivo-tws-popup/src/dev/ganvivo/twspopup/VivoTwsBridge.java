package dev.ganvivo.twspopup;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 封装 com.android.vivo.tws.vivotws（实测 9.2.41.6）主进程原生弹窗 API 与 assets/tws_config.json 模型校验。
 */
final class VivoTwsBridge {

    static final String PACKAGE = "com.android.vivo.tws.vivotws";
    static final String TRIGGER_RECEIVER = "com.vivo.service.dongle.DongleBroadcastReceiver";

    private static final String CLS_MANAGER = "com.android.vivo.tws.fastpair.vipc.TwsFastPairViewServerManager";
    private static final String CLS_BEAN = "com.android.vivo.tws.fastpair.bean.TwsFastPairDeviceBean";
    private static final String CLS_BITMAP_NAMES = "com.vivo.commonbase.bean.EarbudFastPair$BitmapNameBean";

    private static final String[] ART_KEYS = {"enter", "change", "circle", "connecting", "left", "right", "box"};
    private static final String[] ART_SETTERS = {"setEnter", "setChange", "setCircle", "setConnecting", "setLeft", "setRight", "setBox"};

    final Class<?> triggerReceiverCls;
    final Class<?> managerCls;
    final Method onClickMethod;

    private final Method getInstance;
    private final Method onFoundTwsOptionView;
    private final Method onFoundTwsNeedDismissView;
    private final Method unbindPairViewService;
    private final Method clearPairData;
    private final Constructor<?> beanCtor;
    private final Constructor<?> bitmapNamesCtor;

    private static JSONArray sCachedModels;

    VivoTwsBridge(ClassLoader cl) throws ReflectiveOperationException {
        managerCls = cl.loadClass(CLS_MANAGER);
        Class<?> beanCls = cl.loadClass(CLS_BEAN);
        Class<?> bitmapNamesCls = cl.loadClass(CLS_BITMAP_NAMES);
        triggerReceiverCls = cl.loadClass(TRIGGER_RECEIVER);

        getInstance = Hooks.findMethod(managerCls, "getInstance", Application.class);
        onFoundTwsOptionView = Hooks.findMethod(managerCls, "onFoundTwsOptionView", beanCls);
        onFoundTwsNeedDismissView = Hooks.findMethod(managerCls, "onFoundTwsNeedDismissView", beanCls);
        unbindPairViewService = Hooks.findMethod(managerCls, "unbindPairViewService");
        clearPairData = Hooks.findMethod(managerCls, "clearPairData");
        onClickMethod = Hooks.findMethod(managerCls, "onClick", String.class);

        beanCtor = beanCls.getDeclaredConstructor(BluetoothDevice.class, BluetoothDevice.class);
        beanCtor.setAccessible(true);
        bitmapNamesCtor = bitmapNamesCls.getDeclaredConstructor();
        bitmapNamesCtor.setAccessible(true);
    }

    Object getManager(Application app) {
        return Hooks.invoke(getInstance, null, app);
    }

    Object getCurrentData(Object manager) {
        return Hooks.getField(manager, "mData");
    }

    String getBindState(Object manager) {
        return String.valueOf(Hooks.getField(manager, "mServiceBindState"));
    }

    boolean isBound(Object manager) {
        return !"IDLE".equals(getBindState(manager));
    }

    void push(Object manager, Object rawBean) {
        Hooks.invoke(onFoundTwsOptionView, manager, rawBean);
    }

    void close(Object manager) {
        if ("CONNECTED".equals(getBindState(manager)) && getCurrentData(manager) != null) {
            Hooks.invoke(onFoundTwsNeedDismissView, manager, new Object[]{null});
        } else {
            reset(manager);
        }
    }

    void reset(Object manager) {
        Hooks.invoke(unbindPairViewService, manager);
        Hooks.invoke(clearPairData, manager);
    }

    /** 构造已通过 assets/flash_connect 完整校验的原生 TwsFastPairDeviceBean。 */
    Object buildBean(Context ctx, PopupParams req) throws Exception {
        int model = req.model;
        JSONObject entry = findModelEntry(ctx, model);
        if (entry == null) {
            model = 32;
            entry = findModelEntry(ctx, model);
        }
        if (entry == null) {
            Logx.w("tws_config.json has no usable model");
            return null;
        }

        ArtBundle art = resolveArt(ctx, model, entry);
        if (art == null) {
            // 回退到内置 86 帧完整动画模型 32 (vivo TWS 2e)
            model = 32;
            entry = findModelEntry(ctx, model);
            art = entry != null ? resolveArt(ctx, model, entry) : null;
        }
        if (art == null) {
            Logx.w("no bundled flash_connect art available");
            return null;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Logx.w("BluetoothAdapter is null");
            return null;
        }
        BluetoothDevice btDev = req.btDevice != null ? req.btDevice : adapter.getRemoteDevice(req.mac);

        Object raw = beanCtor.newInstance(btDev, null);
        Object bmp = bitmapNamesCtor.newInstance();
        Hooks.call(bmp, "setFileModel", new Class<?>[]{int.class}, Integer.valueOf(art.fileModel));
        for (int i = 0; i < ART_SETTERS.length; i++) {
            Hooks.call(bmp, ART_SETTERS[i], new Class<?>[]{String.class}, art.names[i]);
        }
        Hooks.call(raw, "setBitmapNameBean", new Class<?>[]{bmp.getClass()}, bmp);

        String title = (req.title != null && !req.title.isEmpty())
                ? req.title : entry.optString("name", "Bluetooth Audio");
        Hooks.call(raw, "setModel", new Class<?>[]{int.class}, Integer.valueOf(model));
        Hooks.call(raw, "setDeviceType", new Class<?>[]{int.class}, Integer.valueOf(entry.optInt("device_type", 1)));
        Hooks.call(raw, "setTitle", new Class<?>[]{String.class}, title);
        Hooks.call(raw, "setDeviceName", new Class<?>[]{String.class}, title);

        applyDynamicState(raw, req, req.state);
        return raw;
    }

    void applyDynamicState(Object raw, PopupParams req, int viewState) {
        int pairState = viewState >= PopupParams.STATE_CONNECTED ? 2
                : (viewState >= PopupParams.STATE_CONNECTING ? 1 : 0);
        Hooks.call(raw, "setViewState", new Class<?>[]{int.class}, Integer.valueOf(viewState));
        Hooks.call(raw, "setPairState", new Class<?>[]{int.class}, Integer.valueOf(pairState));
        Hooks.call(raw, "setLeftBattery", new Class<?>[]{int.class}, Integer.valueOf(req.left));
        Hooks.call(raw, "setRightBattery", new Class<?>[]{int.class}, Integer.valueOf(req.right));
        Hooks.call(raw, "setBaseBattery", new Class<?>[]{int.class}, Integer.valueOf(req.box));
        Hooks.call(raw, "setChargeState", new Class<?>[]{int.class}, Integer.valueOf(req.charging));
        Hooks.call(raw, "setBaseStateEarbudsCount", new Class<?>[]{int.class}, Integer.valueOf(req.inCase));
        try {
            Hooks.setField(raw, "mFirstConnected", Boolean.valueOf(req.firstPair));
        } catch (Throwable ignored) {
        }
    }

    private static synchronized JSONArray loadModels(Context ctx) throws Exception {
        if (sCachedModels == null) {
            InputStream in = ctx.getAssets().open("tws_config.json");
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                sCachedModels = new JSONObject(out.toString("UTF-8")).getJSONArray("tws_config");
            } finally {
                in.close();
            }
        }
        return sCachedModels;
    }

    private static JSONObject findModelEntry(Context ctx, int model) throws Exception {
        JSONArray all = loadModels(ctx);
        JSONObject base = null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.getJSONObject(i);
            int m = e.optInt("model", -1);
            if (m == model) {
                return e;
            }
            if (m == (model / 4) * 4) {
                base = e;
            }
        }
        return base;
    }

    private static final class ArtBundle {
        int fileModel;
        final String[] names = new String[ART_KEYS.length];
    }

    private static ArtBundle resolveArt(Context ctx, int model, JSONObject entry) throws IOException {
        AssetManager assets = ctx.getAssets();
        JSONObject data = entry.optJSONObject("bitmap_data");
        if (data == null) {
            return null;
        }
        int fm = data.optInt("file_model", model);
        if (fm <= 0 && model != 0) {
            fm = model;
        }
        String[] rootDirs = assets.list("flash_connect");
        if (rootDirs == null || !Arrays.asList(rootDirs).contains(String.valueOf(fm))) {
            fm = (fm / 4) * 4;
        }
        String[] files = assets.list("flash_connect/" + fm);
        if (files == null || files.length == 0) {
            files = assets.list("flash_connect/default");
        }
        if (files == null || files.length == 0) {
            return null;
        }
        ArtBundle art = new ArtBundle();
        art.fileModel = fm;
        for (int i = 0; i < ART_KEYS.length; i++) {
            String name = data.optString(ART_KEYS[i], "");
            if (name.isEmpty()) {
                return null;
            }
            boolean found = false;
            for (String f : files) {
                if (f.startsWith(name)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return null;
            }
            art.names[i] = name;
        }
        return art;
    }
}
