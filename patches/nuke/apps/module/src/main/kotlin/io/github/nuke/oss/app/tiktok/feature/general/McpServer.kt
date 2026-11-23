package io.github.nuke.oss.app.tiktok.feature.general

import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.FeatureMemory
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.shared.settings.McpCall
import io.github.nuke.oss.app.shared.settings.McpConsole
import io.github.nuke.oss.app.shared.settings.McpInputException
import io.github.nuke.oss.app.shared.settings.McpIssued
import io.github.nuke.oss.app.shared.settings.McpLimits
import io.github.nuke.oss.app.shared.settings.McpListening
import io.github.nuke.oss.app.shared.settings.McpProblem
import io.github.nuke.oss.app.shared.settings.McpState
import io.github.nuke.oss.app.shared.settings.McpToken
import io.github.nuke.oss.app.shared.settings.McpTokenDraft
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.claims.LoadedSparkConversations
import io.github.nuke.oss.app.tiktok.feature.chat.claims.PetHost
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkHost
import io.github.nuke.oss.app.tiktok.feature.chat.claims.closenessTargets
import io.github.nuke.oss.app.tiktok.mcp.DouyinMcpHost
import io.github.nuke.oss.app.tiktok.mcp.McpConfig
import io.github.nuke.oss.app.tiktok.mcp.McpHost
import io.github.nuke.oss.app.tiktok.mcp.McpStoredToken
import io.github.nuke.oss.app.tiktok.mcp.NativeMcp
import io.github.nuke.oss.app.tiktok.mcp.closenessRewards
import io.github.nuke.oss.app.tiktok.mcp.sparkRewards
import io.github.nuke.oss.app.tiktok.protocol.PacketEndpoint
import io.github.nuke.oss.app.tiktok.protocol.PacketExecutor
import io.github.nuke.oss.app.tiktok.protocol.PacketRequest
import io.github.nuke.oss.app.tiktok.protocol.ProtocolQueries
import io.github.nuke.oss.app.tiktok.query.ContactQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.HostContentSender
import io.github.nuke.oss.app.tiktok.sdk.chat.HostImageContents
import io.github.nuke.oss.app.tiktok.sdk.chat.ConversationModels
import io.github.nuke.oss.app.tiktok.sdk.chat.ImAccount
import io.github.nuke.oss.app.tiktok.sdk.chat.ReadMarker
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinContacts
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

// An MCP server inside Douyin's main process, so that an AI client the user gave a token to can look up friends and
// groups, send text and pictures, claim the spark rewards and the pet closeness of its streaks, mark every chat read
// and, with a permission of its own, send raw IM requests as the signed-in account. The server itself is native
// (NativeMcp): it listens on 127.0.0.1 unless the user opens it to the local networks, speaks Streamable HTTP on /mcp
// and decides by token who may call which tool for which chat. This feature lends it worker threads while the switch is on, keeps its
// configuration, and is what the settings screen talks to. Nothing is hooked. Any app on the phone can reach a
// loopback port, and any device on the network an open one, which is why every request needs a token, why a token
// starts with no permission and no chat, and why nothing the server says before the token check names it. The
// transport is plain HTTP: on a network, whoever can read its traffic can read a token, which the screen says before
// the user opens the server to one.
// The configuration lives in the feature's memory, not in its options, so a configuration export never carries a token.
@HookEntry
internal class McpServer(
    private val host: McpHost,
    private val account: () -> Long,
    private val memory: FeatureMemory,
    private val logger: Logger,
    private val entropy: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val addresses: () -> List<String> = ::localAddresses,
    // Whether the feature is on, written under the lock. The host's claims read it too and stop once it is off.
    private val active: AtomicBoolean = AtomicBoolean(),
) : RuntimeFeature, UsesDexQueries, McpConsole {
    override val id = ID
    override val dexQueries = HostContentSender.QUERIES + HostImageContents.QUERIES + PROTOCOL_QUERIES +
        listOf(ContactQueries.FRIENDS, ContactQueries.SINGLE_CONVERSATION_ID, ContactQueries.STREAK_DAYS)

    // Guards the listener and the stored configuration. The workers never take it, so stopping can wait for them.
    private val lock = Any()
    private var listener: Listener? = null
    private var failure: String? = null

    private class Listener(val server: Long, val port: Int, val workers: List<Thread>)

    // Every tool needs the contacts, and sending needs the sender. The streak count beside a friend is optional, and
    // so are the uploader and the raw request path: without one only its own tool fails, with the reason. Without
    // the streak count no friend is known to have a streak, so the claims find no chat to look at.
    override fun unusable(missing: List<DexQuery>) =
        missing.any { it !== ContactQueries.STREAK_DAYS && it !in HostImageContents.QUERIES && it !in PROTOCOL_QUERIES }

    // A port that cannot be taken does not fail the install: the settings screen shows why nothing listens and can
    // move the server to another port without a restart. A native library that does not load does fail it.
    override fun install(scope: FeatureScope) {
        NativeMcp.ensureLoaded()
        synchronized(lock) {
            active.set(true)
            listen(stored())
        }
        scope.own(AutoCloseable {
            synchronized(lock) {
                active.set(false)
                close()
            }
        })
    }

    private fun listen(config: McpConfig) {
        val answer = MiniJson.obj(NativeMcp.open(config.port, config.lan, config.encode())?.let(MiniJson::parse))
        val server = answer?.takeIf { it["ok"] == true }?.get("server") as? Long
        if (answer == null || server == null) {
            failure = answer?.get("message") as? String ?: "The native server gave no answer"
            logger.log("feature=$ID listen_failed port=${config.port} lan=${config.lan} ${answer?.get("code")}")
            return
        }
        val workers = List(WORKERS) { index ->
            Thread({ NativeMcp.serve(server, host) }, "Nuke-MCP-$index").apply {
                isDaemon = true
                start()
            }
        }
        listener = Listener(server, (answer["port"] as? Long)?.toInt() ?: config.port, workers)
        failure = null
        logger.log("feature=$ID listening port=${listener?.port} lan=${config.lan} tokens=${answer["tokens"]}")
    }

    // A worker that is waiting for the host to answer a send finishes that request first, so it is waited for only
    // briefly; until it returns the old port stays taken.
    private fun close() {
        val closing = listener ?: return
        listener = null
        NativeMcp.close(closing.server)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_WAIT_MILLIS)
        try {
            for (worker in closing.workers) worker.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        logger.log("feature=$ID stopped port=${closing.port}")
    }

    private fun stored(): McpConfig = McpConfig.decode(memory.read(ID, CONFIG))

    // The running server takes the tokens as they are written, so a revoked token is gone before this returns.
    private fun store(config: McpConfig) {
        val text = config.encode()
        memory.write(ID, CONFIG, text)
        val taken = listener?.let { NativeMcp.configure(it.server, text) } ?: return
        if (taken != config.tokens.size) logger.log("feature=$ID tokens_rejected stored=${config.tokens.size} taken=$taken")
    }

    override fun state(): McpState = synchronized(lock) { state(stored()) }

    private fun state(config: McpConfig): McpState {
        val current = listener
        val listening = when {
            current != null -> McpListening.On(current.port)
            active.get() -> McpListening.Failed(failure.orEmpty())
            else -> McpListening.Off
        }
        val native = current?.let { MiniJson.obj(NativeMcp.snapshot(it.server)?.let(MiniJson::parse)) }
        val lastUsed = MiniJson.obj(native?.get("lastUsed")).orEmpty().mapNotNull { (id, at) -> (at as? Long)?.let { id to it } }.toMap()
        val calls = MiniJson.array(native?.get("audit")).orEmpty().mapNotNull { entry ->
            val call = MiniJson.obj(entry) ?: return@mapNotNull null
            McpCall(
                at = call["at"] as? Long ?: return@mapNotNull null,
                tokenName = call["name"] as? String ?: return@mapNotNull null,
                tool = call["tool"] as? String ?: return@mapNotNull null,
                target = call["target"] as? String,
                ok = call["ok"] == true,
                code = call["code"] as? String ?: return@mapNotNull null,
            )
        }
        val reachable = if (config.lan && current != null) addresses() else emptyList()
        return McpState(listening, config.port, config.tokens.map { it.token }, config.lan, reachable, lastUsed, calls)
    }

    override fun account(): String? = try {
        account.invoke().toString()
    } catch (_: IllegalStateException) {
        null
    }

    override fun setPort(port: Int): McpState = synchronized(lock) {
        if (port !in McpLimits.MIN_PORT..McpLimits.MAX_PORT) throw McpInputException(McpProblem.PORT)
        val before = stored()
        move(before, before.copy(port = port))
    }

    override fun setLan(enabled: Boolean): McpState = synchronized(lock) {
        val before = stored()
        move(before, before.copy(lan = enabled))
    }

    // Moves a running listener to where the configuration now says, and back when it cannot be taken there.
    private fun move(before: McpConfig, after: McpConfig): McpState {
        // The same place is taken again only when nothing listens on it, which is how a failed start is retried.
        if (!active.get() || (before.port == after.port && before.lan == after.lan && listener != null)) {
            store(after)
            return state(after)
        }
        close()
        listen(after)
        val refused = failure.takeIf { listener == null }
        if (refused != null) {
            listen(before)
            throw McpInputException(McpProblem.PORT_UNAVAILABLE, refused)
        }
        store(after)
        return state(after)
    }

    override fun create(draft: McpTokenDraft): McpIssued = synchronized(lock) {
        val config = stored()
        if (config.tokens.size >= McpLimits.MAX_TOKENS) throw McpInputException(McpProblem.TOO_MANY_TOKENS)
        val chosen = checked(draft)
        val owner = account() ?: throw McpInputException(McpProblem.NO_ACCOUNT)
        val (id, secretHash, text) = issue(null)
        val token = McpToken(id, chosen.name, chosen.enabled, owner, chosen.permissions, chosen.allowAll, chosen.conversations, chosen.limit, clock())
        val after = config.copy(tokens = config.tokens + McpStoredToken(token, secretHash))
        store(after)
        logger.log("feature=$ID token_created tokens=${after.tokens.size}")
        McpIssued(text, state(after))
    }

    override fun update(id: String, draft: McpTokenDraft): McpState = synchronized(lock) {
        val chosen = checked(draft)
        change(id) { stored ->
            stored.copy(token = stored.token.copy(
                name = chosen.name, enabled = chosen.enabled, permissions = chosen.permissions, allowAll = chosen.allowAll,
                conversations = chosen.conversations, limit = chosen.limit,
            ))
        }
    }

    override fun renew(id: String): McpIssued = synchronized(lock) {
        val (_, secretHash, text) = issue(id)
        McpIssued(text, change(id) { it.copy(secretHash = secretHash) })
    }

    override fun revoke(id: String): McpState = synchronized(lock) {
        val config = stored()
        if (config.tokens.none { it.token.id == id }) throw McpInputException(McpProblem.NO_TOKEN)
        val after = config.copy(tokens = config.tokens.filterNot { it.token.id == id })
        store(after)
        logger.log("feature=$ID token_revoked tokens=${after.tokens.size}")
        state(after)
    }

    private fun change(id: String, edit: (McpStoredToken) -> McpStoredToken): McpState {
        val config = stored()
        if (config.tokens.none { it.token.id == id }) throw McpInputException(McpProblem.NO_TOKEN)
        val after = config.copy(tokens = config.tokens.map { if (it.token.id == id) edit(it) else it })
        store(after)
        return state(after)
    }

    private fun checked(draft: McpTokenDraft): McpTokenDraft {
        val name = draft.name.trim()
        if (name.isEmpty() || name.length > McpLimits.MAX_NAME_CHARS) throw McpInputException(McpProblem.NAME)
        if (draft.limit !in 1..McpLimits.MAX_REQUESTS) throw McpInputException(McpProblem.LIMIT)
        val conversations = draft.conversations.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (conversations.size > McpLimits.MAX_CONVERSATIONS) throw McpInputException(McpProblem.TOO_MANY_CONVERSATIONS)
        return draft.copy(name = name, conversations = conversations)
    }

    // The ID, the secret's hash and the token text, built by the native side from this platform's randomness so that
    // what is issued here is exactly what the server later checks.
    private fun issue(id: String?): Triple<String, String, String> {
        val issued = try {
            NativeMcp.ensureLoaded()
            MiniJson.obj(NativeMcp.issue(entropy(NativeMcp.entropyBytes()), id)?.let(MiniJson::parse))
        } catch (error: IllegalStateException) {
            throw McpInputException(McpProblem.UNAVAILABLE, error.message)
        }
        return Triple(
            issued?.get("id") as? String ?: throw McpInputException(if (id == null) McpProblem.UNAVAILABLE else McpProblem.NO_TOKEN),
            issued["secretHash"] as String,
            issued["token"] as String,
        )
    }

    companion object : TikTokFeature {
        const val ID = "McpServer"
        private const val CONFIG = "config"
        private const val PICTURES = "pictures"
        // What the host's raw request path (PacketHost) resolves on first use.
        private val PROTOCOL_QUERIES = listOf(
            ProtocolQueries.SDK_CONTEXT, ProtocolQueries.COMMAND_ROUTE, ProtocolQueries.RAW_DISPATCH, ProtocolQueries.HANDLE_RESPONSE,
            ProtocolQueries.CHECK_RESPONSE, ProtocolQueries.BUILD_BODY, ProtocolQueries.RESPONSE_FIELD, ProtocolQueries.REQUEST_FIELD,
        )
        // Each worker answers one connection at a time, so this is also how many clients are served at once.
        private const val WORKERS = 4
        private const val STOP_WAIT_MILLIS = 1_500L

        override fun create(services: TikTokFeatureServices) = with(services) {
            val im = ImAccount(hostClassLoader)
            val account = im::id
            val active = AtomicBoolean()
            val sender = HostContentSender(resolver, hostClassLoader)
            // Opened on the first tool call, so a host without the contact targets fails that call with the reason.
            val contacts by lazy { DouyinContacts.open(resolver, hostClassLoader, logger, ID) }
            // Built on the first raw request; the worker that asked waits for the host's answer.
            val executor by lazy { PacketExecutor(packetTransport()) }
            // The claims of the message tab menu, with this feature's switch as theirs.
            val models = ConversationModels(hostClassLoader)
            val friends = HostContacts { contacts.load() }
            val host = DouyinMcpHost(
                account, friends, sender, sender::text, HostImageContents(resolver, hostClassLoader), File(featureFiles, "$ID/$PICTURES"),
                { cmd, inboxType, body -> runBlocking { executor.request(PacketRequest(PacketEndpoint(cmd, inboxType), body)) } },
                sparkRewards(SparkHost(hostClassLoader, im, active::get), LoadedSparkConversations(friends, models, im, hostClassLoader)),
                closenessRewards(PetHost(hostClassLoader, im, models, active::get, logger)) { owner -> closenessTargets(friends, owner) },
                ReadMarker(hostClassLoader, models)::markAll, logger, ID, active::get,
            )
            listOf(McpServer(host, account, memory, logger, active = active))
        }
    }
}

// The IPv4 addresses of this device on its local networks, the ones a server open to them answers on.
private fun localAddresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress || it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
        .toList()
} catch (_: SocketException) {
    emptyList()
}
