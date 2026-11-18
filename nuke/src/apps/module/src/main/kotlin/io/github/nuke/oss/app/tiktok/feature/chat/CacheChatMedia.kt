package io.github.nuke.oss.app.tiktok.feature.chat

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.media.ChatMediaFetcher
import io.github.nuke.oss.app.shared.media.ChatMediaUrls
import io.github.nuke.oss.app.shared.media.HttpChatMediaFetcher
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// Keeps the pictures, videos and voice files a chat message carries in the module's own directory, so a message that is
// later recalled or a file the host cleans up still has its media. The raw message's content is JSON; the addresses are
// taken from it without asking the host for a download. Everything runs on one background thread and never blocks the
// chat, and the feature is off by default because it keeps files the user did not explicitly save.
@HookEntry
internal class CacheChatMedia(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val directory: File,
    private val logger: Logger,
    private val fetcher: ChatMediaFetcher = HttpChatMediaFetcher(),
    private val worker: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nuke-chat-media").apply { isDaemon = true }
    },
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(ChatQueries.INCOMING_MESSAGE)
    private val content = NamedField("content")
    private val conversationId = NamedCall("getConversationId")
    private val messageId = NamedCall("getMsgId")

    override fun install(scope: FeatureScope) {
        directory.mkdirs()
        val handler = resolver.methods(hostClassLoader, ChatQueries.INCOMING_MESSAGE).single()
        check(handler.parameterCount >= 3) { "${handler.declaringClass.name}.${handler.name} does not take the raw message third" }
        handler.isAccessible = true
        scope.own(AutoCloseable { (worker as? java.util.concurrent.ExecutorService)?.shutdownNow() })
        scope.hooks.install(handler, HookSpec(id = "Cache", after = { call ->
            if (call.outcome !is CallOutcome.Returned) return@HookSpec
            val raw = call.argument(2) ?: return@HookSpec
            val message = (call.outcome as CallOutcome.Returned).value ?: return@HookSpec
            val json = (content.of(raw)?.get(raw) as? String) ?: return@HookSpec
            val addresses = ChatMediaUrls.of(json)
            if (addresses.isEmpty()) return@HookSpec
            val chat = (conversationId.of(message)?.invoke(message) as? String) ?: "chat"
            val id = (messageId.of(message)?.invoke(message) as? Number)?.toLong() ?: System.currentTimeMillis()
            worker.execute { save(chat, id, addresses) }
        }))
    }

    private fun save(chat: String, id: Long, addresses: List<String>) {
        val target = File(directory, ChatMediaUrls.directoryName(chat))
        if (!target.isDirectory && !target.mkdirs()) {
            logger.log("feature=$id directory_failed")
            return
        }
        var saved = 0
        for ((index, address) in addresses.take(MAX_PER_MESSAGE).withIndex()) {
            val file = File(target, "${id}_$index.${ChatMediaUrls.extension(address)}")
            if (file.isFile) continue
            if (fetcher.fetch(address, file)) saved++ else file.delete()
        }
        if (saved > 0) logger.log("feature=$id saved count=$saved")
    }

    companion object : TikTokFeature {
        const val ID = "CacheChatMedia"
        private const val MAX_PER_MESSAGE = 10

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CacheChatMedia(resolver, hostClassLoader, File(featureFiles, ID), logger))
        }
    }
}
