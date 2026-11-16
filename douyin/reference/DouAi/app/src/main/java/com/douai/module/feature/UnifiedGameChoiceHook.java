package com.douai.module.feature;

import com.douai.module.config.SettingsStore;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Selects only a named game asset in the host's actual emoji selector, never process-wide RNG. */
public final class UnifiedGameChoiceHook implements AutoCloseable {
    public static final String DICE = "unified_dice_result";
    public static final String RPS = "unified_rps_result";
    private final XposedInterface.HookHandle handle;
    private UnifiedGameChoiceHook(XposedInterface.HookHandle handle) { this.handle = handle; }

    public static UnifiedGameChoiceHook install(XposedInterface framework, ClassLoader loader, SettingsStore store) throws Exception {
        Class<?> owner = Class.forName("com.ss.android.ugc.aweme.im.sdk.chat.model.EmojiContent", false, loader);
        Method selected = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != List.class
                    || !"com.ss.android.ugc.aweme.emoji.model.IDUrlModel".equals(method.getReturnType().getName())) continue;
            if (selected != null) throw new IllegalStateException("Emoji selector is ambiguous");
            selected = method;
        }
        if (selected == null) throw new NoSuchMethodException("EmojiContent native game selector");
        XposedInterface.HookHandle handle = framework.hook(selected).setId("DouAi.Unified.GameChoice")
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    Object candidates = chain.getArg(0);
                    if (!(candidates instanceof List)) return chain.proceed();
                    String dice = store.getString(DICE, "0"), rps = store.getString(RPS, "random");
                    String wantedDice = dice.matches("[1-6]") ? "骰子" + dice : "";
                    String wantedRps = java.util.Arrays.asList("剪刀", "石头", "布").contains(rps) ? rps : "";
                    Object winner = null;
                    for (Object candidate : (List<?>) candidates) {
                        Object model = HostTargets.read(candidate, "urlModel");
                        Object urls = DownloadActionHook.jsonValue(model, "url_list");
                        if (urls == null) urls = HostTargets.read(model, "urlList");
                        boolean matches = false;
                        if (urls instanceof List) for (Object url : (List<?>) urls) {
                            if (url instanceof String && (matchesOutcome((String) url, wantedDice)
                                    || matchesOutcome((String) url, wantedRps))) matches = true;
                        }
                        if (!matches) continue;
                        if (winner != null && winner != candidate) return chain.proceed();
                        winner = candidate;
                    }
                    return winner == null ? chain.proceed() : winner;
                });
        if (handle == null) throw new IllegalStateException("Game selector hook unavailable");
        return new UnifiedGameChoiceHook(handle);
    }

    static boolean matchesOutcome(String url, String outcome) {
        if (url == null || outcome == null || outcome.isEmpty()) return false;
        try {
            String path = URLDecoder.decode(url.split("[?#]", 2)[0], StandardCharsets.UTF_8.name());
            StringBuilder hex = new StringBuilder();
            for (byte value : outcome.getBytes(StandardCharsets.UTF_8)) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            return path.toLowerCase(Locale.ROOT).contains("-ts-" + hex + "2e")
                    || path.endsWith("/" + outcome + ".png") || path.endsWith("/" + outcome + ".webp");
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException invalid) { return false; }
    }
    @Override public void close() { handle.unhook(); }
}
