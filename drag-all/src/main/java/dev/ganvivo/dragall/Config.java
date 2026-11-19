package dev.ganvivo.dragall;

final class Config {
    static final String TARGET = "com.vivo.globaldragdrop";
    static final String SUPPORTED_VERSION = "4.1.3.0";
    static final String CATCHER = "com.vivo.accessibilityenhance";
    static final String CATCHER_VERSION = "3.0.3.2";
    static final String FLUTTER_SOURCE = "org.BroTech.gege.pilibro";
    static final String BILI = "tv.danmaku.bili";
    static final String TELEGRAM_SOURCE = "com.exteraless.app";
    static final String GROUP = "drag_all";
    static final String DRAG = "drag_all";
    static final String APPS = "show_launcher_apps";
    static final String SHARE = "system_share";
    static final String GLASS = "island_glass";
    static final String BLUR = "island_blur_strength";
    static final String TINT = "island_tint_opacity";
    static final int DEFAULT_BLUR = 30;
    static final int DEFAULT_TINT = 25;
    static final String[] KEYS = {DRAG, APPS, SHARE, GLASS};

    private Config() {}

    static boolean defaultValue(String key) {
        return true;
    }

    static boolean acceptsProcess(String processName) {
        return TARGET.equals(processName)
                || CATCHER.equals(processName)
                || FLUTTER_SOURCE.equals(processName)
                || BILI.equals(processName)
                || (processName != null && processName.startsWith(TARGET + ":"));
    }

    static boolean supportsVersion(String packageName, String version) {
        if (TARGET.equals(packageName)) return SUPPORTED_VERSION.equals(version);
        if (CATCHER.equals(packageName)) return CATCHER_VERSION.equals(version);
        if (FLUTTER_SOURCE.equals(packageName)) return "2.7.9".equals(version) || "2.8.0".equals(version);
        return BILI.equals(packageName);
    }

    static boolean isPreciseSource(String packageName) {
        return FLUTTER_SOURCE.equals(packageName) || TELEGRAM_SOURCE.equals(packageName);
    }
}
