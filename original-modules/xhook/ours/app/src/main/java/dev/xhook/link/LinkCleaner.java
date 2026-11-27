package dev.xhook.link;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.xhook.Config;
import dev.xhook.Runtime;

/**
 * 链接净化器（纯 Android 框架层 Hook，零混淆依赖，与 LiquidTab 无任何重叠）。
 *
 * <ol>
 *   <li><b>分享/复制链接去追踪与可选域名替换</b>：拦截 {@link ClipboardManager#setPrimaryClip}
 *       与 {@link Instrumentation#execStartActivity}（{@code ACTION_SEND}/{@code ACTION_CHOOSER}），
 *       剥离 {@code x.com}/{@code twitter.com} URL 上的 {@code s}/{@code t}/{@code utm_*} 等追踪参数，
 *       并按设置替换分享域名（如 {@code fxtwitter.com}/{@code vxtwitter.com} 仅作用于 {@code /status/}
 *       帖子链接以兼容外部富媒体预览）。</li>
 *   <li><b>外跳 {@code t.co} 短链后台直达</b>：拦截外部 {@code ACTION_VIEW} 中的 {@code t.co}
 *       短链，在后台线程跟随 3xx 重定向解出真实 URL 并清理追踪参数，带 LRU 内存缓存与超时回退。</li>
 * </ol>
 */
public final class LinkCleaner {
    private static final String EXTRA_BYPASS = "dev.xhook.tco_bypass";
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"'\\]\\)]+");

    private static final Set<String> X_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "x.com", "www.x.com", "mobile.x.com",
                    "twitter.com", "www.twitter.com", "mobile.twitter.com"
            )));

    private static final Set<String> TRACKING_PARAMS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "s", "t", "ref_src", "ref_url", "src", "cxt",
                    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
                    "fbclid", "gclid", "igshid", "mc_cid", "mc_eid"
            )));

    private static final Map<String, String> TCO_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 256;
                }
            });
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ExecutorService RESOLVER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "XHook-TcoResolve");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean installed;

    private LinkCleaner() {
    }

    public static void install(ClassLoader classLoader) {
        if (installed) {
            return;
        }
        installed = true;
        int clipHooks = hookClipboard();
        int actHooks = hookStartActivity();
        Runtime.log("LinkCleaner installed: clipboard=" + clipHooks + " startActivity=" + actHooks);
    }

    private static int hookClipboard() {
        int hooked = 0;
        try {
            Method method = ClipboardManager.class.getDeclaredMethod("setPrimaryClip", ClipData.class);
            if (Runtime.hook(method, chain -> {
                try {
                    boolean clean = Runtime.bool(Config.KEY_CLEAN_SHARE_LINKS, true);
                    String domain = Runtime.string(Config.KEY_SHARE_DOMAIN, "").trim();
                    if (clean || !domain.isEmpty()) {
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof ClipData) {
                            ClipData rewritten = rewriteClipData((ClipData) arg0, clean, domain);
                            if (rewritten != null) {
                                Object[] args = chain.getArgs().toArray();
                                args[0] = rewritten;
                                return chain.proceed(args);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            }) != null) {
                hooked++;
            }
        } catch (Throwable throwable) {
            Runtime.error("LinkCleaner clipboard hook failed", throwable);
        }
        return hooked;
    }

    private static int hookStartActivity() {
        int hooked = 0;
        try {
            for (Method method : Instrumentation.class.getDeclaredMethods()) {
                if (!"execStartActivity".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                int ctxIdx = -1;
                int intentIdx = -1;
                for (int i = 0; i < params.length; i++) {
                    if (ctxIdx < 0 && params[i] == Context.class) {
                        ctxIdx = i;
                    } else if (intentIdx < 0 && params[i] == Intent.class) {
                        intentIdx = i;
                    }
                }
                if (ctxIdx < 0 || intentIdx < 0) {
                    continue;
                }
                final int cIdx = ctxIdx;
                final int iIdx = intentIdx;
                if (Runtime.hook(method, chain -> {
                    try {
                        Object rawIntent = chain.getArg(iIdx);
                        if (rawIntent instanceof Intent) {
                            Intent intent = (Intent) rawIntent;
                            if (shouldHandleShare(intent)) {
                                boolean clean = Runtime.bool(Config.KEY_CLEAN_SHARE_LINKS, true);
                                String domain = Runtime.string(Config.KEY_SHARE_DOMAIN, "").trim();
                                if (clean || !domain.isEmpty()) {
                                    rewriteShareIntent(intent, clean, domain);
                                }
                            } else if (Runtime.bool(Config.KEY_EXPAND_TCO, true)
                                    && shouldHandleTco(intent)) {
                                Context ctx = (Context) chain.getArg(cIdx);
                                if (ctx != null && interceptTco(ctx, intent)) {
                                    return null;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }) != null) {
                    hooked++;
                }
            }
        } catch (Throwable throwable) {
            Runtime.error("LinkCleaner startActivity hook failed", throwable);
        }
        return hooked;
    }

    private static ClipData rewriteClipData(ClipData clip, boolean stripTracking, String domain) {
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        boolean changed = false;
        ClipData result = null;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            CharSequence text = item == null ? null : item.getText();
            ClipData.Item nextItem = item;
            if (text != null) {
                String original = text.toString();
                String cleaned = cleanShareText(original, stripTracking, domain);
                if (!cleaned.equals(original)) {
                    changed = true;
                    nextItem = new ClipData.Item(cleaned, item.getHtmlText(),
                            item.getIntent(), item.getUri());
                }
            }
            if (i == 0) {
                result = new ClipData(clip.getDescription(), nextItem);
            } else if (result != null) {
                result.addItem(nextItem);
            }
        }
        return changed ? result : null;
    }

    private static boolean shouldHandleShare(Intent intent) {
        String action = intent.getAction();
        return Intent.ACTION_SEND.equals(action) || Intent.ACTION_CHOOSER.equals(action);
    }

    private static void rewriteShareIntent(Intent intent, boolean stripTracking, String domain) {
        if (Intent.ACTION_CHOOSER.equals(intent.getAction())) {
            Intent target = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (target != null) {
                rewriteShareIntent(target, stripTracking, domain);
            }
            return;
        }
        CharSequence extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (extra == null) {
            return;
        }
        String original = extra.toString();
        String cleaned = cleanShareText(original, stripTracking, domain);
        if (!cleaned.equals(original)) {
            intent.putExtra(Intent.EXTRA_TEXT, cleaned);
        }
    }

    private static boolean shouldHandleTco(Intent intent) {
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }
        if (intent.getBooleanExtra(EXTRA_BYPASS, false)) {
            return false;
        }
        Uri uri = intent.getData();
        if (uri == null) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return "t.co".equals(lower) || "www.t.co".equals(lower);
    }

    /** 返回 true 表示已接管并在后台解析，需中止原启动调用。 */
    private static boolean interceptTco(Context context, Intent intent) {
        Uri uri = intent.getData();
        if (uri == null) {
            return false;
        }
        String source = uri.toString();
        String cached = TCO_CACHE.get(source);
        if (cached != null && !cached.equals(source)) {
            intent.setData(Uri.parse(cached));
            return false;
        }
        if (!IN_FLIGHT.add(source)) {
            return true;
        }
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Intent copy = new Intent(intent);
        RESOLVER.execute(() -> {
            try {
                String resolved = expandTco(source);
                Intent launch = new Intent(copy);
                launch.putExtra(EXTRA_BYPASS, true);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (resolved != null && !resolved.equals(source)) {
                    String cleaned = stripTrackingParams(resolved, false);
                    TCO_CACHE.put(source, cleaned);
                    launch.setData(Uri.parse(cleaned));
                    Runtime.log("t.co resolved: " + hostOf(cleaned));
                }
                app.startActivity(launch);
            } catch (Throwable throwable) {
                Runtime.error("t.co resolve error", throwable);
                try {
                    Intent fallback = new Intent(copy);
                    fallback.putExtra(EXTRA_BYPASS, true);
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(fallback);
                } catch (Throwable ignored) {
                }
            } finally {
                IN_FLIGHT.remove(source);
            }
        });
        return true;
    }

    private static String expandTco(String startUrl) {
        String current = startUrl;
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(current);
                if (!"https".equalsIgnoreCase(url.getProtocol())
                        && !"http".equalsIgnoreCase(url.getProtocol())) {
                    break;
                }
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(4_000);
                conn.setReadTimeout(4_000);
                conn.setRequestMethod("HEAD");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
                int code = conn.getResponseCode();
                if (code == 405 || code == 403) {
                    conn.disconnect();
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(4_000);
                    conn.setReadTimeout(4_000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Range", "bytes=0-0");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
                    code = conn.getResponseCode();
                }
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        break;
                    }
                    current = new URL(url, location).toString();
                    continue;
                }
                return current;
            } catch (Throwable ignored) {
                return current;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return current;
    }

    /** 纯文本链接清洗（无 Android 依赖，便于 JVM 单元测试）。 */
    public static String cleanShareText(String text, boolean stripTracking, String replaceDomain) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        StringBuffer out = new StringBuffer(text.length());
        boolean changed = false;
        while (matcher.find()) {
            String raw = matcher.group();
            String cleaned = cleanShareUrl(raw, stripTracking, replaceDomain);
            if (!cleaned.equals(raw)) {
                changed = true;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(cleaned));
        }
        if (!changed) {
            return text;
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 清洗单条 X 分享链接（剥离追踪参数 + 可选域名替换）。 */
    public static String cleanShareUrl(String rawUrl, boolean stripTracking, String replaceDomain) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return rawUrl;
        }
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            if (host == null || !X_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
                return rawUrl;
            }
            String nextHost = host;
            String domain = replaceDomain == null ? "" : replaceDomain.trim().toLowerCase(Locale.ROOT);
            if (!domain.isEmpty()) {
                String path = uri.getRawPath() == null ? "" : uri.getRawPath();
                boolean isStatus = path.contains("/status/");
                if ("twitter.com".equals(domain) || "x.com".equals(domain) || isStatus) {
                    nextHost = domain;
                }
            }
            String nextQuery = stripTracking ? filterQuery(uri.getRawQuery(), true) : uri.getRawQuery();
            return rebuildUri(uri, nextHost, nextQuery);
        } catch (Throwable ignored) {
            return rawUrl;
        }
    }

    public static String stripTrackingParams(String rawUrl, boolean xOnly) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return rawUrl;
        }
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            if (host == null) {
                return rawUrl;
            }
            boolean isX = X_HOSTS.contains(host.toLowerCase(Locale.ROOT));
            if (xOnly && !isX) {
                return rawUrl;
            }
            String nextQuery = filterQuery(uri.getRawQuery(), isX);
            return rebuildUri(uri, host, nextQuery);
        } catch (Throwable ignored) {
            return rawUrl;
        }
    }

    private static String filterQuery(String rawQuery, boolean isXHost) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        StringBuilder kept = new StringBuilder();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = (eq >= 0 ? pair.substring(0, eq) : pair).toLowerCase(Locale.ROOT);
            if (isTrackingKey(key, isXHost)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(pair);
        }
        return kept.length() == 0 ? null : kept.toString();
    }

    private static boolean isTrackingKey(String key, boolean isXHost) {
        if (key.startsWith("utm_")) {
            return true;
        }
        if ("s".equals(key) || "t".equals(key)) {
            return isXHost;
        }
        return TRACKING_PARAMS.contains(key);
    }

    private static String rebuildUri(URI uri, String host, String rawQuery) {
        StringBuilder sb = new StringBuilder();
        if (uri.getScheme() != null) {
            sb.append(uri.getScheme()).append("://");
        }
        if (uri.getRawUserInfo() != null) {
            sb.append(uri.getRawUserInfo()).append('@');
        }
        sb.append(host);
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        if (rawQuery != null && !rawQuery.isEmpty()) {
            sb.append('?').append(rawQuery);
        }
        if (uri.getRawFragment() != null) {
            sb.append('#').append(uri.getRawFragment());
        }
        return sb.toString();
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "?" : host;
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
