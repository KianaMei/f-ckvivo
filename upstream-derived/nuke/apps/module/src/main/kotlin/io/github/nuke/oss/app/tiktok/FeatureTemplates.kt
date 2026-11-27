package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.init.FeatureTemplate

// The templates the first-launch setup offers, smallest first. They hold features that work without any option being
// set, change nothing another account can see and send nothing on the user's behalf. TikTokHostAdapterTest checks that
// every ID is a registered feature; the dialog labels a template by its ID.
private val ESSENTIALS = linkedSetOf(
    "SkipSplashAd",
    "RemoveShortDramaAds",
    "SkipChapterAds",
    "SkipMiniAppInterstitialAds",
    "HideTeenModeDialog",
    "BlockNotificationPrompts",
    "DisableUpdateChecks",
)

private val RECOMMENDED = ESSENTIALS + linkedSetOf(
    "ForceHighVideoQuality",
    "AlwaysShowProgress",
    "ProgressTime",
    "ShowPreciseCounts",
    "HideRelatedRecommendations",
    "HideCommentSearch",
    "CleanCommentCopy",
    "CleanShareLink",
    "OpenExternalLinks",
    "AllowAlbumQrScan",
    "AllowFeedDownloads",
    "AllowCaptionCopy",
    "DownloadOriginalVideo",
    "DownloadOriginalImages",
    "CommentOriginalImage",
)

internal const val TEMPLATE_ESSENTIALS = "essentials"
internal const val TEMPLATE_RECOMMENDED = "recommended"

internal val featureTemplates: List<FeatureTemplate> = listOf(
    FeatureTemplate(TEMPLATE_ESSENTIALS, ESSENTIALS),
    FeatureTemplate(TEMPLATE_RECOMMENDED, RECOMMENDED),
)
