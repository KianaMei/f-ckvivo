package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.media.DownloadCancelled
import io.github.nuke.oss.app.shared.media.DownloadConsole
import io.github.nuke.oss.app.shared.media.DownloadConsoles
import io.github.nuke.oss.app.shared.media.DownloadQueue
import io.github.nuke.oss.app.shared.media.DownloadTask
import io.github.nuke.oss.app.shared.media.TaskStage
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
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntries
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntry
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
    private val entries: SharePanelEntries,
    private val storyMenu: StoryDownloadMenu,
    options: OptionValues,
    private val queue: DownloadQueue = DownloadQueue(),
) : RuntimeFeature, UsesDexQueries, DownloadConsole {
    override val id = ID
    override val dexQueries = listOf(DownloadQueries.VIDEO_PLAY) + ENTRY_QUERIES.flatten()
    override fun unusable(missing: List<DexQuery>) =
        DownloadQueries.VIDEO_PLAY in missing || ENTRY_QUERIES.all { route -> route.any { it in missing } }

    private val template = options.text(TEMPLATE).ifBlank { MediaNaming.DEFAULT_TEMPLATE }
    private val folder = MediaNaming.folder(options.text(FOLDER))
    // A SAF folder the user picked; empty means the gallery folders below.
    private val tree = options.text(TREE).takeIf { it.startsWith("content://") }
    private val getVideo = NamedCall("getVideo")
    private val author = NamedField("author")
    private val nickname = NamedField("nickname")
    private val desc = NamedField("desc")
    private val awemeId = NamedFieldAlias("aid", "aweme_id")
    private val storyLabel = NamedFieldAlias("storyInteractiveLabel", "story_interactive_label")
    private val urlList = NamedFieldAlias("urlList", "url_list")
    private val images = NamedField("images")

    override fun install(scope: FeatureScope) {
        val play = resolver.methods(hostClassLoader, DownloadQueries.VIDEO_PLAY).single().apply { isAccessible = true }
        DownloadConsoles.register(this)
        scope.own(AutoCloseable {
            DownloadConsoles.clear(this)
            queue.close()
        })
        val storyReady = installEntry("story") {
            storyMenu.install(scope) { aweme ->
                if (text(aweme) { storyLabel.of(it)?.get(it) } != LIMITED_STORY_LABEL) null
                else { context -> enqueue(context, aweme, play) }
            }
        }
        val longPressReady = installEntry("native_save") { installNativeSave(scope, play) }
        val shareReady = installEntry("share") { installShare(scope, play) }
        check(storyReady || longPressReady || shareReady) { "No download entry is supported by this host" }
    }

    private fun installEntry(entry: String, install: () -> Unit): Boolean = try {
        install()
        true
    } catch (failure: Exception) {
        logger.log("feature=$id entry_unavailable entry=$entry ${failure.javaClass.simpleName}")
        false
    }

    private fun installNativeSave(scope: FeatureScope, play: java.lang.reflect.Method) {
        val (click, post) = resolver.methods(hostClassLoader, NATIVE_SAVE_QUERIES)
        listOf(click, post).forEach { it.isAccessible = true }
        scope.hooks.install(click, HookSpec(id = "TakeOver", requiredCapabilities = setOf(HookCapability.EARLY_RETURN), before = { call ->
            // Everything is read before the host call is cancelled: a post this feature cannot save keeps the host's
            // own behavior instead of loading nothing at all.
            val view = call.argument(0) ?: return@HookSpec
            val context = runCatching { view.callByName("getContext") }.getOrNull() ?: return@HookSpec
            val module = call.receiver ?: return@HookSpec
            val aweme = runCatching { post.invoke(module) }.getOrNull() ?: return@HookSpec
            if (!enqueue(context, aweme, play)) return@HookSpec
            call.returnEarly(null)
        }))
    }

    private fun installShare(scope: FeatureScope, play: java.lang.reflect.Method) {
        // The share panel gets its own row, so a download does not need the long-press panel at all.
        entries.add(scope, ID, SharePanelEntry(SHARE_ENTRY, R.string.host_share_download_entry, android.R.drawable.ic_menu_save) { activity, aweme, dismiss ->
            dismiss()
            try {
                enqueue(activity, aweme, play)
            } catch (failure: Exception) {
                logger.log("feature=$id share_failed ${failure.message}")
            }
        })
    }

    // Resolves what the post carries and queues it. False when nothing could be resolved, so the host keeps the tap.
    private fun enqueue(context: Any, aweme: Any, play: java.lang.reflect.Method): Boolean {
        val video = runCatching { getVideo.of(aweme)?.invoke(aweme) }.getOrNull()
        val model = video?.let { runCatching { play.invoke(it) }.getOrNull() }
        val addresses = model?.let { runCatching { urlList.of(it)?.get(it) }.getOrNull() } as? List<*>
        val urls = addresses?.let { mediaUrls(it.filterIsInstance<String>()) }.orEmpty()
        if (urls.isEmpty()) {
            // An image post has no video; its pictures are taken from the same entry when the host has them.
            val pictures = pictures(aweme, play)
            if (pictures.isEmpty()) return false
            for ((index, picture) in pictures.withIndex()) {
                val name = name(aweme, index + 1, "image")
                queue.submit(name, TaskStage.DOWNLOAD) { progress ->
                    savePicture(context, picture, name, progress::update, progress::isCancelled)
                }
            }
            return true
        }
        val name = name(aweme, 1, "video")
        queue.submit(name, TaskStage.DOWNLOAD) { progress ->
            save(context, urls, name, ImageFolder.VIDEO, progress::update, progress::isCancelled)
        }
        return true
    }

    // The first picture of an image post, each of the rest, or the single video: the index is what keeps a set apart.
    private fun name(aweme: Any, index: Int, kind: String): String {
        val user = runCatching { author.of(aweme)?.get(aweme) }.getOrNull()
        return MediaNaming.fileName(
            template,
            mapOf(
                "author" to text(user) { nickname.of(it)?.get(it) },
                "desc" to text(aweme) { desc.of(it)?.get(it) },
                "aid" to text(aweme) { awemeId.of(it)?.get(it) },
                "type" to kind,
            ),
            extension = if (kind == "video") "mp4" else "png",
            index = if (kind == "video") 0 else index,
        )
    }

    // A still, with the short video a live photo carries next to it.
    private class Picture(val image: String, val video: String?)

    // Every picture the post carries, in order; a live photo keeps the address of its video. A post without any keeps the
    // host's own handling.
    private fun pictures(aweme: Any, play: java.lang.reflect.Method): List<Picture> {
        val list = runCatching { images.of(aweme)?.get(aweme) as? List<*> }.getOrNull() ?: return emptyList()
        val found = ArrayList<Picture>(list.size)
        for (image in list) {
            image ?: continue
            val urls = runCatching { urlList.of(image)?.get(image) }.getOrNull() as? List<*> ?: continue
            val still = mediaUrls(urls.filterIsInstance<String>()).firstOrNull() ?: continue
            val clip = runCatching { getVideo.of(image)?.invoke(image) }
                .getOrNull()
                ?.let { runCatching { play.invoke(it) }.getOrNull() }
                ?.let { runCatching { urlList.of(it)?.get(it) as? List<*> }.getOrNull() }
                ?.let { mediaUrls(it.filterIsInstance<String>()).firstOrNull() }
            found += Picture(still, clip)
        }
        return found
    }

    // A live photo becomes one Motion Photo file; a plain picture takes the ordinary path.
    private fun savePicture(
        context: Any,
        picture: Picture,
        name: String,
        progress: (bytes: Long, total: Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val video = picture.video
        try {
            if (video == null) {
                files().save(
                    context, listOf(picture.image), audio = false, folder = ImageFolder.IMAGE, name = name,
                    progress = progress, cancelled = cancelled, tree = tree,
                )
            } else {
                files().saveMotionPhoto(
                    context, listOf(picture.image), listOf(video), folder = ImageFolder.IMAGE, name = name,
                    progress = progress, cancelled = cancelled, tree = tree,
                )
            }
            logger.log("feature=$id saved")
        } catch (cancelled: DownloadCancelled) {
            logger.log("feature=$id cancelled")
            throw cancelled
        } catch (error: Exception) {
            logger.log("feature=$id save_failed ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    private fun text(value: Any?, read: (Any) -> Any?): String {
        if (value == null) return ""
        return runCatching { read(value) as? CharSequence }.getOrNull()?.toString()?.trim().orEmpty()
    }

    private fun save(
        context: Any,
        addresses: List<String>,
        name: String,
        folder: ImageFolder,
        progress: (bytes: Long, total: Long) -> Unit,
        cancelled: () -> Boolean,
    ) {
        try {
            files().save(
                context, addresses, audio = false, folder = folder, name = name,
                progress = progress, cancelled = cancelled, tree = tree,
            )
            // The name comes from the post, so only its presence is logged.
            logger.log("feature=$id saved")
        } catch (cancelled: DownloadCancelled) {
            logger.log("feature=$id cancelled")
            throw cancelled
        } catch (error: Exception) {
            logger.log("feature=$id save_failed ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    // The settings screen reads the same queue; the copy it sees never changes once taken.
    override fun snapshot(): List<DownloadTask> = queue.snapshot()

    override fun recent(): List<DownloadTask> = queue.recent()

    override fun cancel(id: Long): Boolean = queue.cancel(id)

    override fun clearFinished() = queue.clearFinished()

    companion object : TikTokFeature {
        const val ID = "DownloadVideoWithName"
        const val TEMPLATE = "template"
        const val FOLDER = "folder"
        const val TREE = "tree"
        const val SHARE_ENTRY = "download"
        private const val LIMITED_STORY_LABEL = "限时日常"
        private val NATIVE_SAVE_QUERIES = listOf(DownloadQueries.PANEL_DOWNLOAD_CLICK, DownloadQueries.PANEL_AWEME)
        private val ENTRY_QUERIES = listOf(NATIVE_SAVE_QUERIES, SharePanelEntries.QUERIES, StoryDownloadMenu.QUERIES)
        val OPTIONS = listOf(
            TextOption(TEMPLATE, default = MediaNaming.DEFAULT_TEMPLATE, maxLength = 120),
            TextOption(FOLDER, default = MediaNaming.DEFAULT_FOLDER, maxLength = 40),
            // Only written by the folder picker in the settings screen, never typed.
            TextOption(TREE, maxLength = 500),
        )

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(DownloadVideoWithName(resolver, hostClassLoader, mediaFiles, logger, sharePanelEntries(), storyDownloadMenu(), options(ID, OPTIONS)))
        }
    }
}
