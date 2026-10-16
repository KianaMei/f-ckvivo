import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.*;
import java.nio.ByteBuffer;

public class DfpProbe2 {
    static final String CONFIG = "IBkgIP8PAAgAAAAAVQ2DoXJB_jfvXfvucHppveENtBpK5gcKotYxl6p8JSuKO478LZSUXxzfnD4UyzrGQHd3DQdNvtrsxSl2jsiZV29eHisERdXEfFR7EKwdXWy1le0khrF-4E7G06gzpppxFQQz8dA6X3A1AlRu7ELRiaF4XQubsDJu8WOhNGaqGpK0RepcyVbjfwy-7V-sknKcjQ5jEDh7gxKXvaF6tK5-G8Q0SRek4LGSae4bgSLvG4TrzYNFe-bFOC0rw2Pw_Gxy63ypx18sLgx21JZnNZ5sbI2RGwiVeKRM6vuI401OAw6w8CvsYqgyOYmWZhEA30_2vvZzNzfNHjmxIWF_CaBUkg==";
    static final String PUBKEY_B64 = "MIIBIDANBgkqhkiG9w0BAQEFAAOCAQ0AMIIBCAKCAQEArH6tTQEDtKMyyYE+E/sBaHJK+UTANlh70HJbYyhQ2yNjBUjeEMFKh23FBTEWbIirtXN34hkzZPj8vH4ULtxjGOn79aybb/amhxI7ZFpXqHpoh9c+awK/Soqa+v4w8GG4ow7jwCvF4DVAkVgmYtXIvfFXtdcFEL3KGKdZs4B7lovXgrZGWcs+AouO15leSnyRmGozjB0lEYwtZM23WslMALJF4i6m5k0+TZTyMge8XBjRlH2l6ci0fj4NMZSkKMHqoFWdiOTXprNRPWhHJE7k1U5rFKkgCr4A0GhbZLEV7nQJ8E0ZRLeao7ohcUw+w87twGOz6bCCGRSAnBvw2jziQIBAw==";

    static void L(String m) { android.util.Log.i("DFPX2", m); System.out.println(m); }

    static byte[] wrap(byte[] b) {
        ByteBuffer bb = ByteBuffer.allocate(4224);
        byte[] copy = new byte[4096];
        bb.putInt(8193);
        System.arraycopy(b, 0, copy, 0, Math.min(b.length, 4096));
        bb.putInt(b.length);
        bb.put(copy);
        return bb.array();
    }

    static byte[] buildJson(String appHash) throws Exception {
        String j = "{\"pacakage_name\":\"com.bbk.updater\",\"app_hash\":\"" + appHash + "\",\"token\":\"" + CONFIG + "\",\"time\":\"" + System.currentTimeMillis() + "\"}";
        return j.getBytes("UTF-8");
    }

    public static void main(String[] args) {
        try { System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true)); } catch (Throwable t) {}
        try { run(); } catch (Throwable t) { L("FATAL " + t); t.printStackTrace(System.out); }
    }

    static void run() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method wfd = sm.getDeclaredMethod("waitForDeclaredService", String.class);
        wfd.setAccessible(true);
        Object binder = wfd.invoke(null, "vendor.vivo.hardware.sectee.ISecTee/default");
        L("STEP1 waitForDeclared ok binder=" + binder);
        if (binder == null) {
            Method gs = sm.getDeclaredMethod("getService", String.class);
            gs.setAccessible(true);
            binder = gs.invoke(null, "vendor.vivo.hardware.sectee.ISecTee/default");
            L("STEP1b getService binder=" + binder);
        }
        if (binder == null) { L("NO BINDER - stop"); return; }

        Class<?> stub = Class.forName("vendor.vivo.hardware.sectee.ISecTee$Stub");
        Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        Object svc = asInterface.invoke(null, binder);
        L("STEP2 svc=" + (svc == null ? "null" : svc.getClass().getName()));
        if (svc == null) return;

        Class<?> cbCls = Class.forName("vendor.vivo.hardware.sectee.ISecurityKeyCB");
        final Object[][] captured = new Object[1][];
        Binder cbBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
                L("CB onTransact code=" + code);
                try {
                    if (code == 1) {
                        data.enforceInterface("vendor.vivo.hardware.sectee.ISecurityKeyCB");
                        int rc = data.readInt();
                        byte[] out = data.createByteArray();
                        int odl = data.readInt();
                        int kv = data.readInt();
                        String uid = data.readString();
                        byte[] pkh = data.createByteArray();
                        int nuk = data.readInt();
                        captured[0] = new Object[]{rc, out, odl, kv, uid, pkh, nuk};
                        L("CB onValues rc=" + rc + " outLen=" + (out == null ? -1 : out.length) + " odl=" + odl + " kv=" + kv + " uid=" + uid);
                        if (reply != null) reply.writeNoException();
                        return true;
                    } else if (code == 16777215) {
                        if (reply != null) { reply.writeNoException(); reply.writeInt(4); }
                        return true;
                    } else if (code == 16777214) {
                        if (reply != null) { reply.writeNoException(); reply.writeString("6257ac98364dd01f28349740cd8dc7197b9e4434"); }
                        return true;
                    } else if (code == IBinder.INTERFACE_TRANSACTION) {
                        if (reply != null) reply.writeString("vendor.vivo.hardware.sectee.ISecurityKeyCB");
                        return true;
                    }
                } catch (Throwable t) { L("CB transact err " + t); }
                return super.onTransact(code, data, reply, flags);
            }
        };
        final Binder cbBinderRef = cbBinder;
        final Object cb = Proxy.newProxyInstance(cbCls.getClassLoader(), new Class[]{cbCls}, (p, m, a) -> {
            if (m.getName().equals("asBinder")) return cbBinderRef;
            L("CB-proxy " + m.getName() + " -> default");
            if (m.getReturnType() == int.class) return 4;
            if (m.getReturnType() == String.class) return "6257ac98364dd01f28349740cd8dc7197b9e4434";
            if (m.getReturnType() == boolean.class) return false;
            return null;
        });

        Method target = null;
        StringBuilder sig = new StringBuilder();
        for (Method m : svc.getClass().getMethods()) {
            if (m.getName().equals("vivoSecurityKeyOperate")) {
                target = m;
                for (Class<?> pt : m.getParameterTypes()) sig.append(pt.getSimpleName()).append(" ");
                break;
            }
        }
        L("STEP3 method=" + (target != null) + " params=[" + sig + "]");
        if (target == null) return;

        byte[] payload = wrap(buildJson("hash_null"));
        try {
            Object ret = target.invoke(svc, 2, 11, -1, 0, "com.bbk.updater", 0, null, PUBKEY_B64, payload, payload.length, cb);
            L("STEP4 ret=" + ret);
        } catch (InvocationTargetException ite) {
            Throwable c = ite.getCause();
            L("STEP4-EXC " + c.getClass().getName() + " : " + c.getMessage());
            if (c.getCause() != null) L("STEP4-EXC-CAUSE " + c.getCause());
        }
        L("captured=" + (captured[0] == null ? "none" : (captured[0][0] + "/" + captured[0][3])));
        if (captured[0] != null && captured[0][1] != null) {
            byte[] out = (byte[]) captured[0][1];
            int odl = (Integer) captured[0][2];
            ByteBuffer ob = ByteBuffer.wrap(out);
            if (odl >= 8) {
                int skip = ob.getInt();
                int len2 = ob.getInt();
                byte[] inner = new byte[Math.min(Math.max(0, len2), odl - 8)];
                ob.get(inner, 0, inner.length);
                L("OPDATA=" + new String(inner, "UTF-8"));
            }
        }
    }
}
