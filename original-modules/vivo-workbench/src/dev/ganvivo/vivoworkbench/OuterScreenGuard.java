package dev.ganvivo.vivoworkbench;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.Collection;

/**
 * 折叠屏外屏与系统受限状态守卫。
 * 复用系统 VivoMultiTaskConstants 黑名单与 Settings.Secure 状态，
 * 确保仅在「折叠屏外屏且无安全/模式限制」时放行原子工作台。
 */
final class OuterScreenGuard {

    private static final String CONSTANTS_CLASS = "com.vivo.multitask.VivoMultiTaskConstants";

    private OuterScreenGuard() {
    }

    static boolean isOriginOs() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class)
                    .invoke(null, "ro.vivo.os.build.display.id");
            String id = v == null ? "" : String.valueOf(v);
            if (!id.isEmpty()) {
                return id.startsWith("OriginOS");
            }
        } catch (Throwable ignored) {
        }
        String brand = android.os.Build.BRAND == null ? "" : android.os.Build.BRAND.toLowerCase();
        String mfr = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER.toLowerCase();
        return brand.contains("vivo") || brand.contains("iqoo") || mfr.contains("vivo");
    }

    static boolean isFoldableDevice(ClassLoader cl) {
        Class<?> constants = Hooks.findClassOrNull(CONSTANTS_CLASS, cl);
        if (constants != null) {
            return Hooks.getStaticBool(constants, "IS_FOLD", false);
        }
        try {
            Class<?> ft = Class.forName("android.util.FtDeviceInfo", false, cl);
            Object type = Hooks.callStatic(ft, "getDeviceType", new Class<?>[0]);
            return "foldable".equalsIgnoreCase(String.valueOf(type));
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isFeatureSupported(ClassLoader cl) {
        try {
            Class<?> ft = Class.forName("android.util.FtFeature", false, cl);
            Object r = Hooks.callStatic(ft, "isFeatureSupport",
                    new Class<?>[]{String.class}, "vivo.software.multitask");
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 检查当前是否处于系统禁止工作台的受限模式（Xspace / 超级省电 / 儿童模式 / 简易桌面）。
     */
    static boolean isSystemModeBlocked(Context context) {
        if (context == null) {
            return true;
        }
        try {
            ContentResolver cr = context.getContentResolver();
            if (getSecureInt(cr, "xspace_launcher_status", 0) == 1) {
                return true;
            }
            if (getSecureInt(cr, "sup_power_save_type", 0) == 1) {
                return true;
            }
            String child = getSecureString(cr, "vivo_children_mode_enable");
            if ("true".equals(child)) {
                return true;
            }
            if (getSecureInt(cr, "system_simple_style", 0) == 1) {
                return true;
            }
            return false;
        } catch (Throwable t) {
            Logx.w("system mode check failed, keep blocked", t);
            return true;
        }
    }

    /**
     * 通过 DisplayManager 或 DisplayMetrics 判断当前默认屏是否为折叠屏窄外屏（高宽比 >= 2.0）。
     * 本机 X Fold6：外屏 1120x2528 (2.257)，内屏 2312x2504 (1.083)。
     */
    static boolean isCurrentDefaultDisplayOuter(Context context) {
        if (context == null) {
            return false;
        }
        try {
            Rect bounds = getBoundsFromConfig(context.getResources().getConfiguration());
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                int longSide = Math.max(bounds.width(), bounds.height());
                int shortSide = Math.min(bounds.width(), bounds.height());
                return ((double) longSide / (double) shortSide) >= 1.95d;
            }
        } catch (Throwable ignored) {
        }
        try {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
                int longSide = Math.max(dm.widthPixels, dm.heightPixels);
                int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
                return ((double) longSide / (double) shortSide) >= 1.95d;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 查找外屏物理宽高 [width, height]（高宽比 >= 2.0 的内置屏幕，与原厂内屏判定 < 2.0 对称）。
     */
    static int[] findOuterPhysicalSize(Context context) {
        if (context == null) {
            return null;
        }
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(DisplayManager.class);
            if (dm != null) {
                Display[] displays = dm.getDisplays(
                        "android.hardware.display.category.ALL_INCLUDING_DISABLED");
                if (displays != null) {
                    for (Display d : displays) {
                        if (d == null) {
                            continue;
                        }
                        Object typeObj = Hooks.call(d, "getType", new Class<?>[0]);
                        int type = (typeObj instanceof Integer) ? ((Integer) typeObj).intValue() : -1;
                        if (type != 1) {
                            continue;
                        }
                        Display.Mode mode = d.getMode();
                        if (mode == null) {
                            continue;
                        }
                        int pw = mode.getPhysicalWidth();
                        int ph = mode.getPhysicalHeight();
                        if (pw > 0 && ph > 0) {
                            double ratio = (double) Math.max(pw, ph) / (double) Math.min(pw, ph);
                            if (ratio >= 2.0d) {
                                return new int[]{pw, ph};
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Logx.w("findOuterPhysicalSize failed", t);
        }
        return null;
    }

    static Rect getBoundsFromConfig(Configuration config) {
        if (config == null) {
            return null;
        }
        try {
            Object wc = Hooks.getFieldOrNull(config, "windowConfiguration");
            if (wc != null) {
                Object r = Hooks.call(wc, "getBounds", new Class<?>[0]);
                if (r instanceof Rect) {
                    return new Rect((Rect) r);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 复刻 VivoMultiTaskManagerService.isPkgSupportEnterMultitask() 的包名/Activity 黑名单校验。
     * 用于在 Recents 侧不碰 system_server 的情况下保留原厂安全限制。
     */
    static boolean isPkgAllowedByConstants(ClassLoader cl, String packageName, String activityName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        Class<?> constants = Hooks.findClassOrNull(CONSTANTS_CLASS, cl);
        if (constants == null) {
            return false;
        }
        String normalizedActivity = normalizeActivityName(activityName);
        String pkgNoChannel = packageName;
        try {
            Object r = Hooks.callStatic(constants, "getPkgNameWithoutChanel",
                    new Class<?>[]{String.class}, packageName);
            if (r instanceof String) {
                pkgNoChannel = (String) r;
            }
        } catch (Throwable ignored) {
        }

        if (listContains(constants, "NOT_SUPPORT_ENTER_VIVO_MULTITASK_PKG_LIST", packageName, pkgNoChannel)
                || listContains(constants, "NOT_SUPPORT_APP_LIST", packageName, pkgNoChannel)
                || listContains(constants, "NOT_SUPPORT_SPECIAL_APP_LIST", packageName, pkgNoChannel)) {
            return false;
        }

        if (normalizedActivity != null && !normalizedActivity.isEmpty()) {
            if (listContains(constants, "ENTER_BLACK_LIST", normalizedActivity, null)
                    || listContains(constants, "NOT_SUPPORT_ENTER_VIVO_MULTITASK_LIST", normalizedActivity, null)
                    || listContains(constants, "COMPONENT_NAME_APP_TIME_CONTROL", normalizedActivity, null)
                    || listContains(constants, "NOT_SUPPORT_APP_TIME_CONTROL_REST_REMIND", normalizedActivity, null)
                    || listContains(constants, "NOT_SUPPORT_MULTI_TASK_ACTIVITY_LIST", normalizedActivity, null)) {
                return false;
            }
            if (isPasswordOrFingerprintActivity(normalizedActivity)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPasswordOrFingerprintActivity(String activityName) {
        return "com.vivo.settings.secret.PasswordActivity".equals(activityName)
                || "com.vivo.settings.secret.PasswordActivityUD".equals(activityName)
                || "com.vivo.settings.secret.PasswordActivityMultiWindow".equals(activityName)
                || "com.vivo.settings.secret.PasswordActivityMultiWindowUD".equals(activityName)
                || "com.vivo.fingerprint.activity.enroll.FingerprintEnrollActivity".equals(activityName)
                || "com.vivo.fingerprint.activity.fingerprintverify.FingerprintAuthActivity".equals(activityName);
    }

    private static String normalizeActivityName(String activityName) {
        if (activityName == null || activityName.isEmpty()) {
            return activityName;
        }
        int slash = activityName.indexOf('/');
        if (slash < 0) {
            return activityName;
        }
        String pkgPart = activityName.substring(0, slash);
        String clsPart = activityName.substring(slash + 1);
        if (clsPart.startsWith(".") && !pkgPart.isEmpty()) {
            return pkgPart + clsPart;
        }
        return clsPart;
    }

    private static boolean listContains(Class<?> constants, String fieldName, String a, String b) {
        Object obj = Hooks.getStaticFieldOrNull(constants, fieldName);
        if (!(obj instanceof Collection<?>)) {
            return false;
        }
        Collection<?> col = (Collection<?>) obj;
        if (a != null && col.contains(a)) {
            return true;
        }
        return b != null && col.contains(b);
    }

    static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int getSecureInt(ContentResolver cr, String key, int def) {
        try {
            Object v = Hooks.callStatic(Settings.Secure.class, "getIntForUser",
                    new Class<?>[]{ContentResolver.class, String.class, int.class, int.class},
                    cr, key, Integer.valueOf(def), Integer.valueOf(-2));
            if (v instanceof Integer) {
                return ((Integer) v).intValue();
            }
        } catch (Throwable ignored) {
        }
        return Settings.Secure.getInt(cr, key, def);
    }

    private static String getSecureString(ContentResolver cr, String key) {
        try {
            Object v = Hooks.callStatic(Settings.Secure.class, "getStringForUser",
                    new Class<?>[]{ContentResolver.class, String.class, int.class},
                    cr, key, Integer.valueOf(-2));
            if (v instanceof String) {
                return (String) v;
            }
        } catch (Throwable ignored) {
        }
        return Settings.Secure.getString(cr, key);
    }
}
