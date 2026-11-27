package dev.xhook.timeline;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.xhook.Config;
import dev.xhook.Runtime;

/**
 * 源头 FeatureSwitch 净化（零混淆类名依赖）。
 *
 * <p>Hook {@code com.x.featureswitches.FeatureSwitchesRepositoryImpl} 的
 * {@code getBoolean} 与 {@code peekBoolean}，按模块设置动态关闭三类开关：</p>
 * <ul>
 *   <li>Google SSP 程序化广告开关（随 {@link Config#KEY_AD_FILTER}）；</li>
 *   <li>Premium 升级/付费墙/认证推销开关（随 {@link Config#KEY_HIDE_UPSELLS}）；</li>
 *   <li>帖子内 Grok 提示/分析/Imagine 开关（随 {@link Config#KEY_HIDE_GROK_PROMPTS}）
 *       —— <b>刻意不触碰 {@code android_webview_grok_tab_enabled}</b>，
 *       避免破坏 LiquidTab 对 X 底部 5 槽导航栏的槽位契约；</li>
 *   <li>12.45+ 首页顶栏双击刷新开关（随 {@link Config#KEY_BLOCK_HOME_RESELECT}）。</li>
 * </ul>
 */
public final class FeatureSwitchHook {
    private static final String REPO_CLASS =
            "com.x.featureswitches.FeatureSwitchesRepositoryImpl";

    private static final String TITLE_TAP_REFRESH_SWITCH =
            "home_timeline_navigation_tap_to_refresh_at_top_enabled";

    private static final Set<String> SSP_AD_SWITCHES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "android_x_lite_ssp_ads_enabled",
                    "ssp_ads_home_enabled",
                    "ssp_ads_tweet_details",
                    "ssp_ads_immersive",
                    "ssp_ads_profile",
                    "ssp_ads_spotlight",
                    "ssp_ads_home_client_only_integration",
                    "ssp_ads_immersive_client_only_integration",
                    "ssp_ads_profile_client_only_integration_enabled",
                    "ssp_ads_tweet_details_client_only_integration"
            )));

    private static final Set<String> UPSELL_SWITCHES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "subscriptions_upsells_premium_home_nav_enabled",
                    "subscriptions_upsells_premium_home_nav_offer_enabled",
                    "subscriptions_upsells_home_nav_follow_button_enabled",
                    "premium_paywall_on_app_load_journey_enabled",
                    "subscriptions_upsells_drawer_discount_enabled",
                    "subscriptions_upsells_get_verified_drawer_card_enabled",
                    "subscriptions_upsells_get_verified_drawer_card_discount_enabled",
                    "subscriptions_upsells_get_verified_drawer_pill_enabled",
                    "subscriptions_upsells_get_verified_profile",
                    "subscriptions_upsells_get_verified_profile_card",
                    "subscriptions_upsells_get_verified_profile_discount_own_enabled",
                    "subscriptions_upsells_get_verified_profile_discount_visitor_enabled",
                    "subscriptions_upsells_verified_profile_visitor_upsell_enabled",
                    "subscriptions_upsells_verified_profile_visitor_upsell_redesign_enabled",
                    "subscriptions_upsell_visitor_verified_profile",
                    "subscriptions_upsells_analytics_profile_enabled",
                    "subscriptions_upsells_bookmark_folders_enabled",
                    "subscriptions_upsells_long_video_upload_composer_enabled",
                    "subscriptions_upsells_articles_post_composer_promo_variant_enabled",
                    "android_composer_boost_upsell_enabled",
                    "x_lite_dismiss_ad_premium_upsell_enabled",
                    "x_lite_quick_promote_enabled",
                    "x_lite_quick_promote_nux_enabled"
            )));

    /** 仅清理帖子/媒体内 Grok 提示与按钮，不隐藏底栏 Grok Tab 以保证与 LiquidTab 5 槽兼容。 */
    private static final Set<String> GROK_PROMPT_SWITCHES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "is_grok_analysis_button_enabled",
                    "grok_android_analyze_timelines_enabled",
                    "grok_android_analyze_long_press_menu_enabled",
                    "grok_android_imagine_cta_enabled",
                    "grok_android_imagine_cta_profile_enabled",
                    "grok_android_download_grok_cta_enabled",
                    "grok_android_imagine_create_your_own_enabled",
                    "grok_android_grok_bot_post_cta_enabled",
                    "grok_android_grok_bot_reply_cta_enabled",
                    "grok_edit_with_grok_button_under_post_focal_enabled",
                    "grok_edit_with_grok_button_under_post_preview_enabled",
                    "grok_android_tab_badge_enabled"
            )));

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static volatile boolean installed;

    private FeatureSwitchHook() {
    }

    public static void install(ClassLoader classLoader) {
        if (installed) {
            return;
        }
        installed = true;
        int hooked = 0;
        try {
            Class<?> repo = Class.forName(REPO_CLASS, false, classLoader);
            for (Method method : repo.getDeclaredMethods()) {
                String name = method.getName();
                if (!"getBoolean".equals(name) && !"peekBoolean".equals(name)) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 1 || params[0] != String.class
                        || method.getReturnType() != boolean.class) {
                    continue;
                }
                method.setAccessible(true);
                if (Runtime.hook(method, chain -> {
                    try {
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof String) {
                            String key = (String) arg0;
                            if (shouldForceFalse(key)) {
                                if (LOGGED.add(key)) {
                                    Runtime.log("FeatureSwitch forced false: " + key);
                                }
                                return Boolean.FALSE;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }) != null) {
                    hooked++;
                }
            }
            Runtime.log("FeatureSwitchHook installed, hooked=" + hooked);
        } catch (Throwable throwable) {
            Runtime.error("FeatureSwitchHook unavailable", throwable);
        }
    }

    public static boolean shouldForceFalse(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (SSP_AD_SWITCHES.contains(key)) {
            return Runtime.bool(Config.KEY_AD_FILTER, true);
        }
        if (UPSELL_SWITCHES.contains(key)) {
            return Runtime.bool(Config.KEY_HIDE_UPSELLS, true);
        }
        if (GROK_PROMPT_SWITCHES.contains(key)) {
            return Runtime.bool(Config.KEY_HIDE_GROK_PROMPTS, false);
        }
        if (TITLE_TAP_REFRESH_SWITCH.equals(key)) {
            return Runtime.bool(Config.KEY_BLOCK_HOME_RESELECT, true);
        }
        return false;
    }
}
