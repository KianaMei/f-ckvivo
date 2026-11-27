package dev.xhook.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.xhook.Config;
import dev.xhook.Runtime;

/**
 * 推广 entryId 规则 + 机器人/垃圾帖规则引擎。
 *
 * <p>规则语法（每行一条，支持注释与多类型）：</p>
 * <ul>
 *   <li>{@code # ...} 或 {@code ; ...}：注释行；</li>
 *   <li>{@code !@username} 或 {@code @@username}：作者白名单（命中则永不拦截）；</li>
 *   <li>{@code @username} 或 {@code user:username}：按作者 handle 拦截；</li>
 *   <li>{@code /pattern/flags}：正则匹配（支持 {@code i/m/s} 标志，作用于原始及归一化正文）；</li>
 *   <li>其余非空行：剥离 Unicode FORMAT/CONTROL 零宽隐形字符 + 空白折叠 + 小写子串匹配。</li>
 * </ul>
 */
public final class Rules {
    private static final Pattern REGEX_LINE = Pattern.compile("^/(.+)/([a-zA-Z]*)$");

    private static final String[] PROMOTED_PREFIXES = {
            "promoted-tweet-",
            "promotedTweet-",
            "superhero-",
            "rtb-image-ad-",
    };

    private static final Set<String> PREMIUM_UPSELLS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "messageprompt-ads-sharing-x-premium-upsell-candidate",
                    "messageprompt-generic-non-premium-inline-prompt",
                    "messageprompt-premium-announcement-inline-prompt",
                    "messageprompt-premium-grok2-upsell-prompt",
                    "messageprompt-premium-plus-upsell-prompt"
            )));

    public static final class Compiled {
        final List<String> literals;
        final List<Pattern> regexes;
        final Set<String> blockedUsers;
        final Set<String> whitelistedUsers;

        Compiled(List<String> literals,
                 List<Pattern> regexes,
                 Set<String> blockedUsers,
                 Set<String> whitelistedUsers) {
            this.literals = literals;
            this.regexes = regexes;
            this.blockedUsers = blockedUsers;
            this.whitelistedUsers = whitelistedUsers;
        }

        public boolean isEmpty() {
            return literals.isEmpty() && regexes.isEmpty() && blockedUsers.isEmpty();
        }
    }

    private static volatile String cachedRaw;
    private static volatile Compiled cachedCompiled = new Compiled(
            Collections.emptyList(), Collections.emptyList(),
            Collections.emptySet(), Collections.emptySet());

    private Rules() {
    }

    public static boolean isPromoted(String entryId) {
        if (entryId == null || entryId.isEmpty()) {
            return false;
        }
        for (String prefix : PROMOTED_PREFIXES) {
            if (entryId.startsWith(prefix)) {
                return true;
            }
        }
        if (entryId.contains("-promoted-tweet-")) {
            return true;
        }
        return PREMIUM_UPSELLS.contains(entryId);
    }

    public static boolean isConversation(String entryId) {
        return entryId != null && entryId.startsWith("conversationthread-");
    }

    /** 剥离全部 Unicode 隐形格式/控制符 + 空白折叠 + 小写：对抗“插入零宽字符绕过关键词”。 */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        boolean lastSpace = false;
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.FORMAT || (type == Character.CONTROL && !Character.isWhitespace(cp))) {
                continue;
            }
            if (Character.isWhitespace(cp)) {
                if (!lastSpace && builder.length() > 0) {
                    builder.append(' ');
                }
                lastSpace = true;
                continue;
            }
            lastSpace = false;
            builder.appendCodePoint(Character.toLowerCase(cp));
        }
        return builder.toString().trim();
    }

    public static boolean hasActiveRules() {
        return !compiled().isEmpty();
    }

    public static boolean matches(String text) {
        return shouldBlock(text, null, compiled());
    }

    public static boolean shouldBlock(String text, String authorScreenName) {
        return shouldBlock(text, authorScreenName, compiled());
    }

    public static boolean shouldBlock(String text, String authorScreenName, Compiled rules) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }
        String handle = normalizeHandle(authorScreenName);
        if (!handle.isEmpty() && rules.whitelistedUsers.contains(handle)) {
            return false;
        }
        if (!handle.isEmpty() && rules.blockedUsers.contains(handle)) {
            return true;
        }
        if (text == null || text.isEmpty()) {
            return false;
        }
        String normalized = normalize(text);
        if (!normalized.isEmpty()) {
            for (String literal : rules.literals) {
                if (normalized.contains(literal)) {
                    return true;
                }
            }
        }
        for (Pattern pattern : rules.regexes) {
            if (pattern.matcher(text).find()
                    || (!normalized.isEmpty() && pattern.matcher(normalized).find())) {
                return true;
            }
        }
        return false;
    }

    public static Compiled compile(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Compiled(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptySet(), Collections.emptySet());
        }
        List<String> literals = new ArrayList<>();
        List<Pattern> regexes = new ArrayList<>();
        Set<String> blockedUsers = new HashSet<>();
        Set<String> whitelistedUsers = new HashSet<>();

        for (String rawLine : raw.split("\\r?\\n")) {
            String line = stripInvisible(rawLine).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("!@") || line.startsWith("@@")) {
                String handle = normalizeHandle(line.substring(2));
                if (!handle.isEmpty()) {
                    whitelistedUsers.add(handle);
                }
                continue;
            }
            if (line.startsWith("@")) {
                String handle = normalizeHandle(line.substring(1));
                if (!handle.isEmpty()) {
                    blockedUsers.add(handle);
                }
                continue;
            }
            if (line.toLowerCase(Locale.ROOT).startsWith("user:")) {
                String handle = normalizeHandle(line.substring(5));
                if (!handle.isEmpty()) {
                    blockedUsers.add(handle);
                }
                continue;
            }
            if (line.length() >= 3 && line.startsWith("/")) {
                Matcher matcher = REGEX_LINE.matcher(line);
                if (matcher.matches()) {
                    String body = matcher.group(1);
                    String flagsRaw = matcher.group(2);
                    int flags = Pattern.UNICODE_CASE;
                    if (flagsRaw != null) {
                        if (flagsRaw.indexOf('i') >= 0 || flagsRaw.indexOf('I') >= 0) {
                            flags |= Pattern.CASE_INSENSITIVE;
                        }
                        if (flagsRaw.indexOf('m') >= 0 || flagsRaw.indexOf('M') >= 0) {
                            flags |= Pattern.MULTILINE;
                        }
                        if (flagsRaw.indexOf('s') >= 0 || flagsRaw.indexOf('S') >= 0) {
                            flags |= Pattern.DOTALL;
                        }
                    }
                    try {
                        regexes.add(Pattern.compile(body, flags));
                    } catch (Throwable ignored) {
                        Runtime.log("Rules: invalid regex ignored: " + line);
                    }
                    continue;
                }
            }
            String normalized = normalize(line);
            if (!normalized.isEmpty()) {
                literals.add(normalized);
            }
        }
        return new Compiled(
                Collections.unmodifiableList(literals),
                Collections.unmodifiableList(regexes),
                Collections.unmodifiableSet(blockedUsers),
                Collections.unmodifiableSet(whitelistedUsers)
        );
    }

    private static Compiled compiled() {
        String raw = Runtime.string(Config.KEY_KEYWORDS, "");
        if (raw.equals(cachedRaw)) {
            return cachedCompiled;
        }
        Compiled next = compile(raw);
        cachedCompiled = next;
        cachedRaw = raw;
        Runtime.log("rules reloaded: literals=" + next.literals.size()
                + " regex=" + next.regexes.size()
                + " users=" + next.blockedUsers.size()
                + " whitelist=" + next.whitelistedUsers.size());
        return next;
    }

    private static String normalizeHandle(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.startsWith("@")) {
            s = s.substring(1).trim();
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static String stripInvisible(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.FORMAT || type == Character.CONTROL) {
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    public static String summary() {
        Compiled c = compiled();
        return "promoted=" + PROMOTED_PREFIXES.length
                + ", literals=" + c.literals.size()
                + ", regex=" + c.regexes.size()
                + ", whitelist=" + c.whitelistedUsers.size();
    }
}
