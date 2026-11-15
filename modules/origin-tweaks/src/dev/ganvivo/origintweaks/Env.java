package dev.ganvivo.origintweaks;

/**
 * Environment checks that do not pull in any classic Xposed entry type.
 *
 * Vector 2.2 refuses to load IXposedHookLoadPackage for modules that target
 * libxposed API 101+, so the classic entry class had to be removed from the
 * dex entirely; only the plain helper below remains.
 */
final class Env {

    private Env() {
    }

    static boolean isOriginOs() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object value = sp.getMethod("get", String.class)
                    .invoke(null, "ro.vivo.os.build.display.id");
            String id = value == null ? "" : String.valueOf(value);
            if (id.length() > 0) {
                return id.startsWith("OriginOS");
            }
        } catch (Throwable ignored) {
        }
        String brand = android.os.Build.BRAND == null ? "" : android.os.Build.BRAND.toLowerCase();
        String manufacturer = android.os.Build.MANUFACTURER == null
                ? "" : android.os.Build.MANUFACTURER.toLowerCase();
        return brand.contains("vivo") || brand.contains("iqoo") || manufacturer.contains("vivo");
    }
}
