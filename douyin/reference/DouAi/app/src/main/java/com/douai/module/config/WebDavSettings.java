package com.douai.module.config;

import java.net.URI;

/** Optional post-save upload; credentials never enter task snapshots. */
public final class WebDavSettings {
    public static final String ENABLED = "webdav_enabled";
    public static final String URL = "webdav_url";
    public static final String USER = "webdav_username";
    public static final String PASSWORD = "webdav_password";
    public static final String ALLOW_HTTP = "webdav_allow_http";
    public final boolean enabled;
    public final URI collection;
    public final String username, password;

    public WebDavSettings(SettingsStore store) {
        enabled = store.getBoolean(ENABLED, false);
        String address = store.getString(URL, "").trim();
        collection = address.isEmpty() ? null : validate(address, store.getBoolean(ALLOW_HTTP, false));
        username = store.getString(USER, "");
        password = store.getString(PASSWORD, "");
        if (username.contains(":")) throw new IllegalArgumentException("WebDAV 用户名不能包含冒号");
    }

    public static URI validate(String address, boolean allowHttp) {
        URI value;
        try { value = URI.create(address.trim()); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("WebDAV 地址无效"); }
        if (value.getHost() == null || value.getRawUserInfo() != null || value.getRawQuery() != null
                || value.getRawFragment() != null || (!"https".equalsIgnoreCase(value.getScheme())
                && !(allowHttp && "http".equalsIgnoreCase(value.getScheme())))) {
            throw new IllegalArgumentException("请填写 HTTPS WebDAV 目录地址；HTTP 需单独开启");
        }
        String text = value.toASCIIString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }
}
