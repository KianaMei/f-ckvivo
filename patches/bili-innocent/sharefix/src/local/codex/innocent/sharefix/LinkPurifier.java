package local.codex.innocent.sharefix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链接 / 文案净化，语义对齐原 Bilibili-Innocent 模块的 n53：
 * 仅处理 http/https 且宿主属于 B 站自家域名的链接，剥离全部查询参数，
 * 只保留 p（页码，纯数字）、t（秒，纯数字）、start_progress（毫秒，/1000 转 t）。
 * 无变化时返回 null，调用方据此决定是否替换。
 */
final class LinkPurifier {
    private static final List<String> HOST_WHITELIST =
            Arrays.asList("bilibili.com", "b23.tv", "bili2233.cn", "bilibili.tv");
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s，。！？、；：）】》\"'<>]+");

    private LinkPurifier() {
    }

    /** 净化单个 URL；不是 B 站链接或无需改动时返回 null。 */
    static String purifyLink(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        int len = value.length();
        if (len == 0 || len > 4096) {
            return null;
        }
        int schemeEnd = value.indexOf("://");
        if (schemeEnd <= 0) {
            return null;
        }
        String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        int afterScheme = schemeEnd + 3;
        int hostEnd = len;
        for (int i = afterScheme; i < len; i++) {
            char ch = value.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#') {
                hostEnd = i;
                break;
            }
        }
        String hostWithPort = value.substring(afterScheme, hostEnd);
        String host = hostWithPort.contains(":")
                ? hostWithPort.substring(0, hostWithPort.indexOf(':'))
                : hostWithPort;
        String[] hostParts = host.split("\\.");
        String baseDomain = hostParts.length >= 2
                ? hostParts[hostParts.length - 2] + "." + hostParts[hostParts.length - 1]
                : host;
        boolean hostAllowed = false;
        for (String allowed : HOST_WHITELIST) {
            if (baseDomain.equals(allowed) || host.equals(allowed)
                    || host.endsWith("." + allowed)) {
                hostAllowed = true;
                break;
            }
        }
        if (!hostAllowed) {
            return null;
        }

        int fragmentStart = value.indexOf('#', hostEnd);
        if (fragmentStart < 0) {
            fragmentStart = len;
        }
        String fragment = value.substring(fragmentStart);

        int queryStart = value.indexOf('?', hostEnd);
        String path;
        String keptQuery = "";
        if (queryStart < 0 || queryStart >= fragmentStart) {
            path = value.substring(hostEnd, fragmentStart);
        } else {
            path = value.substring(hostEnd, queryStart);
            String query = value.substring(queryStart + 1, fragmentStart);
            keptQuery = filterQuery(query);
        }

        StringBuilder sb = new StringBuilder(scheme).append("://")
                .append(hostWithPort).append(path);
        if (!keptQuery.isEmpty()) {
            sb.append('?').append(keptQuery);
        }
        sb.append(fragment);
        String result = sb.toString();
        return result.equals(value) ? null : result;
    }

    private static String filterQuery(String query) {
        String p = null;
        String t = null;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                continue;
            }
            String key = pair.substring(0, eq);
            String val = pair.substring(eq + 1);
            if (key.equals("p") && p == null && isDigits(val)) {
                p = val;
            } else if (key.equals("t") && t == null && isDigits(val)) {
                t = val;
            } else if (key.equals("start_progress") && t == null) {
                Long millis = parseLong(val);
                if (millis != null && millis >= 0) {
                    t = String.valueOf(millis / 1000L);
                }
            }
        }
        List<String> kept = new ArrayList<String>();
        if (p != null) {
            kept.add("p=" + p);
        }
        if (t != null) {
            kept.add("t=" + t);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(kept.get(i));
        }
        return sb.toString();
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Long parseLong(String s) {
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 净化整段文案里出现的所有 B 站链接；无任何可净化链接时返回 null。 */
    static String purifyContent(String text) {
        if (text == null || text.length() == 0 || text.length() > 16384) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String url = matcher.group();
            String purified = purifyLink(url);
            if (purified != null) {
                changed = true;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(purified));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(url));
            }
        }
        if (!changed) {
            return null;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
