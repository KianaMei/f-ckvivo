import android.content.Context;
import android.content.ContextWrapper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class DfpProbe {
    static final String CONFIG = "IBkgIP8PAAgAAAAAVQ2DoXJB_jfvXfvucHppveENtBpK5gcKotYxl6p8JSuKO478LZSUXxzfnD4UyzrGQHd3DQdNvtrsxSl2jsiZV29eHisERdXEfFR7EKwdXWy1le0khrF-4E7G06gzpppxFQQz8dA6X3A1AlRu7ELRiaF4XQubsDJu8WOhNGaqGpK0RepcyVbjfwy-7V-sknKcjQ5jEDh7gxKXvaF6tK5-G8Q0SRek4LGSae4bgSLvG4TrzYNFe-bFOC0rw2Pw_Gxy63ypx18sLgx21JZnNZ5sbI2RGwiVeKRM6vuI401OAw6w8CvsYqgyOYmWZhEA30_2vvZzNzfNHjmxIWF_CaBUkg==";

    static byte[] wrap(byte[] b) {
        ByteBuffer bb = ByteBuffer.allocate(4224);
        byte[] copy = new byte[4096];
        bb.putInt(8193);
        System.arraycopy(b, 0, copy, 0, Math.min(b.length, 4096));
        bb.putInt(b.length);
        bb.put(copy);
        return bb.array();
    }

    static String sha256Upper(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) { String h = Integer.toHexString(x & 255); if (h.length() == 1) sb.append('0'); sb.append(h); }
        return sb.toString().toUpperCase();
    }

    static void L(String m) { android.util.Log.i("DFPX", m); System.out.println(m); }

    public static void main(String[] args) throws Exception {
        try { run(args); } catch (Throwable t) { L("FATAL " + t); t.printStackTrace(System.out); }
        System.out.flush();
    }

    public static void run(String[] args) throws Exception {
        try { android.os.Looper.prepareMainLooper(); L("LOOPER-OK"); } catch (Throwable t) { L("LOOPER-FAIL " + t); }
        String imei = args.length > 0 ? args[0] : "000000000000000";
        String emmcid = args.length > 1 ? args[1] : "";

        Class<?> cls = Class.forName("com.vivo.services.cipher.utils.SecurityKeyUtils");
        try { java.security.ProtectionDomain pd = cls.getProtectionDomain(); L("SKU-CLASS-OK pd=" + (pd != null && pd.getCodeSource() != null ? pd.getCodeSource().getLocation() : "n/a")); }
        catch (Throwable t) { L("SKU-CLASS-OK pd-check-fail " + t); }
        try { L("RES=" + cls.getClassLoader().getResource("com/vivo/services/cipher/utils/SecurityKeyUtils.class")); }
        catch (Throwable t) { L("RES-FAIL " + t); }
        for (Method m : cls.getDeclaredMethods()) {
            StringBuilder psb = new StringBuilder();
            for (Class<?> p : m.getParameterTypes()) psb.append(p.getSimpleName()).append(",");
            System.out.println("  M " + m.getReturnType().getSimpleName() + " " + m.getName() + "(" + psb + ")");
        }

        Context sys = null;
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Constructor<?> ctor = atCls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object at = ctor.newInstance();
            Method gsc = atCls.getDeclaredMethod("getSystemContext");
            gsc.setAccessible(true);
            sys = (Context) gsc.invoke(at);
            System.out.println("CTX via new ActivityThread OK pkg=" + sys.getPackageName());
        } catch (Throwable t2) {
            System.out.println("NEWAT-FAIL " + (t2.getCause() != null ? t2.getCause() : t2));
        }
        System.out.println("SYSCTX=" + (sys != null));
        if (sys == null) return;

        final Context fsys = sys;
        Context fake = new ContextWrapper(fsys) {
            @Override public String getPackageName() { return "com.bbk.updater"; }
            @Override public Context getApplicationContext() { return this; }
        };

        Method getInstance = cls.getMethod("getInstance", Context.class);
        Object sku = getInstance.invoke(null, fake);
        System.out.println("SKU-OBJ=" + (sku == null ? "null" : sku.getClass().getName()));
        if (sku == null) return;

        Method invokeCmd = sku.getClass().getMethod("invokeCmd", byte[].class);
        invokeCmd.setAccessible(true);

        try {
            String j1 = "{\"pacakage_name\":\"com.bbk.updater\",\"app_hash\":\"hash_null\",\"token\":\"" + CONFIG + "\",\"time\":\"" + System.currentTimeMillis() + "\"}";
            Object r1 = invokeCmd.invoke(sku, wrap(j1.getBytes("UTF-8")));
            dump("T1", r1);
        } catch (Throwable t) { System.out.println("T1-EXC " + (t.getCause() != null ? t.getCause() : t)); }

        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String appTime = f.format(new Date());
        String mapConcat = appTime + emmcid + "PD2545MA" + imei + "PD2545";
        String appHash = sha256Upper(mapConcat);
        System.out.println("APP_TIME=" + appTime + " APP_HASH=" + appHash);
        try {
            String j2 = "{\"pacakage_name\":\"com.bbk.updater\",\"app_hash\":\"" + appHash + "\",\"token\":\"" + CONFIG + "\",\"time\":\"" + System.currentTimeMillis() + "\"}";
            Object r2 = invokeCmd.invoke(sku, wrap(j2.getBytes("UTF-8")));
            dump("T2", r2);
        } catch (Throwable t) { System.out.println("T2-EXC " + (t.getCause() != null ? t.getCause() : t)); }
        try {
            Method invokeCmd2 = sku.getClass().getMethod("invokeCmd", int.class, byte[].class);
            String j3 = "{\"pacakage_name\":\"com.bbk.updater\",\"app_hash\":\"" + appHash + "\",\"token\":\"" + CONFIG + "\",\"time\":\"" + System.currentTimeMillis() + "\"}";
            for (int cmd = 0; cmd <= 10; cmd++) {
                try {
                    Object r3 = invokeCmd2.invoke(sku, cmd, wrap(j3.getBytes("UTF-8")));
                    if (r3 instanceof byte[] && ((byte[]) r3).length >= 8) {
                        java.nio.ByteBuffer rb = java.nio.ByteBuffer.wrap((byte[]) r3);
                        L("CMD" + cmd + " code=" + rb.getInt() + " l1=" + rb.getInt());
                    } else { L("CMD" + cmd + " result=" + r3); }
                } catch (Throwable tt) { L("CMD" + cmd + " EXC " + (tt.getCause() != null ? tt.getCause() : tt)); }
            }
        } catch (Throwable t) { L("CMD-LOOP-FAIL " + t); }
    }

    static void dump(String tag, Object r) {
        if (!(r instanceof byte[])) { System.out.println(tag + "-RESULT=" + r); return; }
        byte[] rb = (byte[]) r;
        System.out.println(tag + "-BYTES len=" + rb.length);
        if (rb.length < 24) { System.out.println(tag + "-SHORT " + new String(rb)); return; }
        ByteBuffer bb = ByteBuffer.wrap(rb);
        int code = bb.getInt(); int l1 = bb.getInt();
        byte[] d1 = new byte[Math.max(0, l1)];
        if (l1 > 0 && l1 <= bb.remaining()) bb.get(d1);
        int keyVer = bb.getInt(); int l2 = bb.getInt();
        byte[] d2 = new byte[Math.max(0, l2)];
        if (l2 > 0 && l2 <= bb.remaining()) bb.get(d2);
        System.out.println(tag + "-CODE=" + code + " keyVer=" + keyVer + " pubkhash=" + new String(d2));
        if (d1.length >= 8) {
            ByteBuffer ob = ByteBuffer.wrap(d1);
            int skip = ob.getInt(); int l3 = ob.getInt();
            byte[] d3 = new byte[Math.max(0, l3)];
            if (l3 > 0 && l3 <= ob.remaining()) ob.get(d3);
            System.out.println(tag + "-OPDATA=" + new String(d3));
        }
    }
}
