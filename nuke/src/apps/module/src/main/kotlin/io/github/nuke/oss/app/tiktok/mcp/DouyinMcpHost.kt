package io.github.nuke.oss.app.tiktok.mcp

import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.shared.dex.DexQueryUnavailable
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.tiktok.protocol.PacketResponse
import io.github.nuke.oss.app.tiktok.protocol.ProtocolResult
import io.github.nuke.oss.app.tiktok.sdk.chat.ContentSender
import io.github.nuke.oss.app.tiktok.sdk.chat.ImageContents
import io.github.nuke.oss.app.tiktok.sdk.chat.ImageFile
import io.github.nuke.oss.app.tiktok.sdk.chat.ImageUploadFailed
import io.github.nuke.oss.app.tiktok.sdk.chat.SendResult
import io.github.nuke.oss.core.Logger
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// One raw IM request, a whole RequestBody, through the host's own IM SDK. Blocks until the host answers or gives up.
internal fun interface RawRequests {
    fun send(cmd: Int, inboxType: Int, requestBody: ByteArray): ProtocolResult<PacketResponse>
}

// Douyin as the MCP server sees it: the signed-in IM account, the friend and group lists, the host's own content
// sender, the path its chat screen uses, and its uploader for pictures. A picture arrives as the bytes of a file the
// server already checked; it is kept in the feature's private directory for the upload and for the host's own chat
// row, which shows the local file, and removed a day later. A raw request goes to the host's IM SDK as it came and its
// Response comes back whole. Rewards are claimed a chat at a time, in the chats the server named and no others, one run
// at a time and for a bounded time: a run that stops early says how far it came, and the next one goes on from there.
// Marking everything read is the IM SDK's own mark-all step. Runs on the server's worker threads, never on the main
// thread. Every failure is an answer with a code, so nothing is thrown into native code; the server decides who may
// ask for what, and this side only does it. Logs name the operation and the outcome, never a recipient or a text.
internal class DouyinMcpHost(
    private val account: () -> Long,
    private val contacts: HostContacts,
    private val sender: ContentSender,
    private val text: (String) -> Any,
    private val images: ImageContents,
    private val pictures: File,
    private val raw: RawRequests,
    private val sparks: RewardSource,
    private val closeness: RewardSource,
    private val readAll: (account: Long) -> Unit,
    private val logger: Logger,
    private val owner: String,
    // Whether the server still runs; a claim stops between two requests once it does not.
    private val serving: () -> Boolean = { true },
    private val timeoutMillis: Long = SEND_TIMEOUT_MILLIS,
    private val uploadTimeoutMillis: Long = UPLOAD_TIMEOUT_MILLIS,
    private val claimMillis: Long = CLAIM_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : McpHost {
    // One send at a time: the workers answer different clients, and the host hears them in the order they arrived.
    private val sending = Any()

    // One claim at a time, of either kind: a second one is refused instead of holding its worker for minutes.
    private val claiming = AtomicBoolean()

    override fun request(operation: String, arguments: String, payload: ByteArray?): String = MiniJson.write(
        try {
            when (operation) {
                "account" -> mapOf("ok" to true, "account" to account().toString())
                "contacts" -> contacts()
                "sendText" -> sendText(arguments)
                "sendImage" -> sendImage(arguments, payload)
                "sendProtocol" -> sendProtocol(arguments, payload)
                "claim" -> claim(arguments)
                "markAllRead" -> markAllRead()
                else -> failure("UNKNOWN_OPERATION", operation)
            }
        } catch (error: DexQueryUnavailable) {
            failed(operation, "NOT_ADAPTED", "Not adapted to this Douyin build: ${error.message}")
        } catch (error: HostContactsUnavailable) {
            failed(operation, "CONTACTS_UNAVAILABLE", error.message)
        } catch (error: IllegalStateException) {
            // The account reader and the sender both report a host that is not ready this way, with the reason.
            failed(operation, if (operation == "account") "ACCOUNT_UNAVAILABLE" else "HOST_NOT_READY", error.message)
        } catch (error: ImageUploadFailed) {
            failed(operation, "UPLOAD_FAILED", error.message)
        } catch (error: IOException) {
            failed(operation, "HOST_FAILED", "The picture could not be stored")
        } catch (error: ReflectiveOperationException) {
            // A host member the claims or the read marker look up by name is not there, or not as it was.
            failed(operation, "NOT_ADAPTED", "Not adapted to this Douyin build: ${error.javaClass.simpleName}")
        } catch (error: RuntimeException) {
            failed(operation, "HOST_FAILED", error.javaClass.simpleName)
        },
    )

    private fun contacts(): Map<String, Any?> {
        val list = contacts.load()
        return mapOf(
            "ok" to true,
            "contacts" to list.friends.map { mapOf("type" to "friend", "conversationId" to it.conversationId, "uid" to it.uid, "name" to it.name) } +
                list.groups.map { mapOf("type" to "group", "conversationId" to it.conversationId, "name" to it.name) },
        )
    }

    private fun sendText(arguments: String): Map<String, Any?> {
        val request = MiniJson.obj(MiniJson.parse(arguments)) ?: return failure("HOST_FAILED", "Unreadable arguments")
        val body = request["text"] as? String ?: return failure("HOST_FAILED", "No text")
        return deliver(request, "text") { text(body) }
    }

    // The upload is part of the send: a picture that did not upload was not sent, and its file is not kept.
    private fun sendImage(arguments: String, payload: ByteArray?): Map<String, Any?> {
        val request = MiniJson.obj(MiniJson.parse(arguments)) ?: return failure("HOST_FAILED", "Unreadable arguments")
        val bytes = payload?.takeIf { it.isNotEmpty() } ?: return failure("HOST_FAILED", "No picture")
        val extension = (request["extension"] as? String)?.takeIf { it in EXTENSIONS } ?: return failure("HOST_FAILED", "Unknown picture format")
        val width = (request["width"] as? Long)?.toInt()?.takeIf { it > 0 } ?: return failure("HOST_FAILED", "No picture width")
        val height = (request["height"] as? Long)?.toInt()?.takeIf { it > 0 } ?: return failure("HOST_FAILED", "No picture height")
        return deliver(request, "image") {
            val file = store(bytes, extension)
            try {
                images.content(ImageFile(file, width, height, request["original"] == true), uploadTimeoutMillis)
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
    }

    // A friend is addressed by user ID and a group by its conversation, as the host's own sender takes them. The
    // content is built only once the recipient reads, and inside the same turn as its send.
    private inline fun deliver(request: Map<String, Any?>, kind: String, content: () -> Any): Map<String, Any?> {
        val result = synchronized(sending) {
            when (request["type"]) {
                "friend" -> sender.send(request["uid"] as? String ?: return failure("HOST_FAILED", "No user ID"), content(), timeoutMillis)
                "group" -> sender.sendToConversation(
                    request["conversationId"] as? String ?: return failure("HOST_FAILED", "No conversation ID"), content(), timeoutMillis,
                )
                else -> return failure("HOST_FAILED", "Unknown contact type")
            }
        }
        logger.log("feature=$owner send kind=$kind type=${request["type"]} result=${result.javaClass.simpleName}")
        return when (result) {
            is SendResult.Sent -> mapOf("ok" to true, "status" to "sent", "detail" to result.detail, "messageId" to result.serverMessageId?.toString())
            is SendResult.Failed -> mapOf("ok" to true, "status" to "failed", "detail" to result.detail)
            is SendResult.Unknown -> mapOf("ok" to true, "status" to "unknown", "detail" to result.detail)
        }
    }

    // Never retried, and a request that timed out may still have gone out; the code says which it was. The log
    // names the command and the outcome, never the bytes.
    private fun sendProtocol(arguments: String, payload: ByteArray?): Map<String, Any?> {
        val request = MiniJson.obj(MiniJson.parse(arguments)) ?: return failure("HOST_FAILED", "Unreadable arguments")
        val cmd = (request["cmd"] as? Long)?.toInt() ?: return failure("HOST_FAILED", "No command")
        val inboxType = (request["inboxType"] as? Long)?.toInt() ?: return failure("HOST_FAILED", "No inbox type")
        val result = synchronized(sending) { raw.send(cmd, inboxType, payload ?: ByteArray(0)) }
        return when (result) {
            is ProtocolResult.Ok -> {
                val bytes = result.value.response
                logger.log("feature=$owner protocol cmd=$cmd bytes=${bytes.size}")
                mapOf("ok" to true, "response" to Base64.getEncoder().encodeToString(bytes))
            }
            is ProtocolResult.Err -> failed("sendProtocol", result.error.code.name, result.error.message)
        }
    }

    // The chats that may hold the reward are put in one order, so that skip means the same chats in every run. A run
    // ends at the last of them, when its time is up, when the server stops or the account changes, or when a claim
    // says nothing more may be asked; reached is how far it came.
    private fun claim(arguments: String): Map<String, Any?> {
        val request = MiniJson.obj(MiniJson.parse(arguments)) ?: return failure("HOST_FAILED", "Unreadable arguments")
        val source = when (request["reward"]) {
            "sparks" -> sparks
            "closeness" -> closeness
            else -> return failure("HOST_FAILED", "Unknown reward")
        }
        val conversations = (request["conversations"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: return failure("HOST_FAILED", "No chats")
        val skip = (request["skip"] as? Long)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: return failure("HOST_FAILED", "No start")
        if (!claiming.compareAndSet(false, true)) return failure("BUSY", "Another claim is still running; wait for it to end")
        try {
            val signedIn = account()
            val targets = source.targets(signedIn, conversations).sortedBy { it.conversation }
            val deadline = clock() + claimMillis
            val totals = IntArray(6)
            val details = ArrayList<String>()
            var reached = minOf(skip, targets.size)
            while (reached < targets.size) {
                val stop = when {
                    !serving() -> "The server stopped; the claim ended early"
                    (try { account() } catch (_: IllegalStateException) { 0L }) != signedIn -> "The account changed; the claim ended early"
                    else -> null
                }
                if (stop != null) {
                    details.add(0, stop)
                    break
                }
                if (clock() >= deadline) break
                val counts = targets[reached++].claim(serving)
                listOf(counts.scanned, counts.claimed, counts.already, counts.failed, counts.unknown, counts.unavailable)
                    .forEachIndexed { index, count -> totals[index] += count }
                details += counts.details
                if (counts.halt) break
            }
            logger.log("feature=$owner claim reward=${request["reward"]} targets=${targets.size} reached=$reached scanned=${totals[0]} " +
                "claimed=${totals[1]} already=${totals[2]} failed=${totals[3]} unknown=${totals[4]} unavailable=${totals[5]}")
            return mapOf(
                "ok" to true, "targets" to targets.size, "reached" to reached, "scanned" to totals[0], "claimed" to totals[1],
                "already" to totals[2], "failed" to totals[3], "unknown" to totals[4], "unavailable" to totals[5],
                "details" to details.take(MAX_DETAILS),
            )
        } finally {
            claiming.set(false)
        }
    }

    private fun markAllRead(): Map<String, Any?> {
        readAll(account())
        logger.log("feature=$owner read_all_submitted")
        return mapOf("ok" to true)
    }

    private fun store(bytes: ByteArray, extension: String): File {
        if (!pictures.isDirectory && !pictures.mkdirs()) throw IOException("The picture directory could not be made")
        val expired = clock() - KEEP_MILLIS
        pictures.listFiles()?.filter { it.isFile && it.lastModified() < expired }?.forEach { it.delete() }
        return File(pictures, "${UUID.randomUUID()}.$extension").apply { writeBytes(bytes) }
    }

    private fun failed(operation: String, code: String, message: String?): Map<String, Any?> {
        logger.log("feature=$owner $operation failed $code")
        return failure(code, message)
    }

    private fun failure(code: String, message: String?): Map<String, Any?> = mapOf("ok" to false, "code" to code, "message" to message.orEmpty())

    private companion object {
        const val SEND_TIMEOUT_MILLIS = 10_000L
        const val UPLOAD_TIMEOUT_MILLIS = 45_000L
        const val KEEP_MILLIS = 24 * 60 * 60 * 1000L
        // Short enough for a client that waits a minute for a tool; a longer run goes on in the next call.
        const val CLAIM_MILLIS = 40_000L
        const val MAX_DETAILS = 3
        val EXTENSIONS = setOf("jpg", "png")
    }
}
