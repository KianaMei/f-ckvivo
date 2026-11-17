package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.media.MediaNaming
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.TextOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.chat.threadRunner
import io.github.nuke.oss.app.tiktok.query.DownloadQueries
import io.github.nuke.oss.app.tiktok.sdk.media.ImageFolder
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
import io.github.nuke.oss.app.tiktok.sdk.media.mediaUrls
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Saves the post's video with the module's own downloader instead of the host's: the long-press "save" item is taken
// over, the video is fetched from its playable address, and the file lands in Movies/douyin/<folder> under a name built
// from the template. The host's dialog, its address choice and its own privacy/download checks are skipped, so the
// feature is off by default; a post whose video or address does not resolve is left entirely to the host. The address
// is the playable one, the same source the download-original feature uses, never the watermarked download address.
@HookEntry
internal class DownloadVideoWithName(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val files: () -> MediaFiles,
    private val logger: Logger,
    options: OptionValues,
    private val runner: BackgroundRunner = threadRunner,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(
        DownloadQueries.PANEL_DOWNLOAD_CLICK, DownloadQueries.PANEL_AWEME, DownloadQueries.VIDEO_PLAY,
    )
    private val template = options.text(TEMPLATE).ifBlank { MediaNaming.DEFAULT_TEMPLATE }
    private val folder = MediaNaming.folder(options.text(FOLDER))
    private val getVideo = NamedCall("getVideo")
    private val author = NamedField("author")
    private val nickname = NamedField("nickname")
    private val desc = NamedField("desc")
    private val awemeId = NamedFieldAlias("aid", "aweme_id")
    private val urlList = NamedFieldAlias("urlList", "url_list")

    override fun install(scope: FeatureScope) {
        val (click, post, play) = resolver.methods(
            hostClassLoader, DownloadQueries.PANEL_DOWNLOAD_CLICK, DownloadQueries.PANEL_AWEME, DownloadQueries.VIDEO_PLAY,
        )
        listOf(click, post, play).forEach { it.isAccessible = true }
        scope.hooks.install(click, HookSpec(id = "TakeOver", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
            // Everything is read before the host call is cancelled: a post this feature cannot save keeps the host's
            // own behavior instead of loading nothing at all.
            val view = call.argument(0) ?: return@HookSpec
            val context = runCatching { view.callByName("getContext") }.getOrNull() ?: return@HookSpec
            val module = call.receiver ?: return@HookSpec
            val aweme = runCatching { post.invoke(module) }.getOrNull() ?: return@HookSpec
            val video = runCatching { getVideo.of(aweme)?.invoke(aweme) }.getOrNull() ?: return@HookSpec
            val model = runCatching { play.invoke(video) }.getOrNull() ?: return@HookSpec
            val addresses = runCatching { urlList.of(model)?.get(model) }.getOrNull() as? List<*> ?: return@HookSpec
            val url = mediaUrls(addresses.filterIsInstance<String>()).firstOrNull() ?: return@HookSpec
            val name = name(aweme)
            call.returnEarly(null)
            runner.run(0) { save(context, url, name) }
        }))
    }

    private fun name(aweme: Any): String {
        val user = runCatching { author.of(aweme)?.get(aweme) }.getOrNull()
        return MediaNaming.fileName(
            template,
            mapOf(
                "author" to text(user) { nickname.of(it)?.get(it) },
                "desc" to text(aweme) { desc.of(it)?.get(it) },
                "aid" to text(aweme) { awemeId.of(it)?.get(it) },
                "type" to "video",
            ),
            extension = "mp4",
        )
    }

    private fun text(value: Any?, read: (Any) -> Any?): String {
        if (value == null) return ""
        return runCatching { read(value) as? CharSequence }.getOrNull()?.toString()?.trim().orEmpty()
    }

    private fun save(context: Any, url: String, name: String) {
        try {
            files().save(context, listOf(url), audio = false, folder = ImageFolder.VIDEO, name = name)
            // The name comes from the post, so only its presence is logged.
            logger.log("feature=$id saved")
        } catch (error: Exception) {
            logger.log("feature=$id save_failed ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    companion object : TikTokFeature {
        const val ID = "DownloadVideoWithName"
        const val TEMPLATE = "template"
        const val FOLDER = "folder"
        val OPTIONS = listOf(
            TextOption(TEMPLATE, default = MediaNaming.DEFAULT_TEMPLATE, maxLength = 120),
            TextOption(FOLDER, default = MediaNaming.DEFAULT_FOLDER, maxLength = 40),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(DownloadVideoWithName(resolver, hostClassLoader, mediaFiles, logger, options(ID, OPTIONS)))
        }
    }
}
