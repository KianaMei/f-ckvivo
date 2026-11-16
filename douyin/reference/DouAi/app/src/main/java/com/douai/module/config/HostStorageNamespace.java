package com.douai.module.config;

import com.douai.module.BuildConfig;

/** Private host storage of the local unified build must not upgrade the installed upstream module's data. */
public final class HostStorageNamespace {
    private HostStorageNamespace() { }
    public static String name(String original) { return name(original, BuildConfig.APPLICATION_ID.endsWith(".adapt412")); }
    static String name(String original, boolean unified) {
        if (original == null || original.isEmpty() || original.contains("/") || original.contains("\\"))
            throw new IllegalArgumentException("Private storage name required");
        if (!unified || original.endsWith("_adapt412") || original.endsWith("-adapt412")) return original;
        return original + (original.equals("douai-analytics") ? "-adapt412" : "_adapt412");
    }
}
