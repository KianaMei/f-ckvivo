package io.github.nuke.oss.app.shared.settings

// What an access token may do. The names are the ones the stored configuration and the native server use.
// SEND_PROTOCOL sends raw IM requests whose bodies nobody reads, so the chats a token names cannot bound it: the
// server lets only a token that reaches all chats use it. MARK_READ marks every chat read at once, so it is bounded
// the same way. CLAIM_REWARDS claims spark rewards and pet closeness in the private chats a token reaches.
internal enum class McpPermission { READ_CONTACTS, SEND_TEXT, SEND_IMAGE, CLAIM_REWARDS, MARK_READ, SEND_PROTOCOL }

// One access token as the settings screen sees it. The secret is never part of it: the host keeps only its hash, and
// the text of a token is shown once, when it is made or given a new secret. A token belongs to the account that was
// signed in when it was made, and reaches the chats it names by conversation ID unless it allows all of them.
internal data class McpToken(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val account: String,
    val permissions: Set<McpPermission>,
    val allowAll: Boolean,
    val conversations: Set<String>,
    val limit: Int,
    val createdAt: Long,
)

// The part of a token the user chooses.
internal data class McpTokenDraft(
    val name: String,
    val enabled: Boolean = true,
    val permissions: Set<McpPermission> = emptySet(),
    val allowAll: Boolean = false,
    val conversations: Set<String> = emptySet(),
    val limit: Int = McpLimits.DEFAULT_REQUESTS,
)

internal object McpLimits {
    const val DEFAULT_PORT = 8765
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    const val MAX_TOKENS = 64
    const val MAX_NAME_CHARS = 64
    const val MAX_CONVERSATIONS = 2000
    const val DEFAULT_REQUESTS = 30
    const val MAX_REQUESTS = 600
}

// Whether the server answers in this process. Off: the feature is not running here, because it is switched off or the
// host has not restarted since it was switched on. Failed: it runs but could not take its port.
internal sealed interface McpListening {
    data object Off : McpListening
    data class On(val port: Int) : McpListening
    data class Failed(val reason: String) : McpListening
}

// One tool call the server answered: for which token, which tool, which chat when the tool had one, and how it ended.
// Never the text of a message. Kept in memory since the host started.
internal data class McpCall(val at: Long, val tokenName: String, val tool: String, val target: String?, val ok: Boolean, val code: String)

// lan is the choice to serve the local networks instead of this device alone; addresses are the IPv4 addresses a
// client on those networks would use, read when the state was, and empty unless the server listens for them.
// lastUsed is by token ID, in milliseconds since the epoch, since the host started.
internal data class McpState(
    val listening: McpListening,
    val port: Int,
    val tokens: List<McpToken>,
    val lan: Boolean = false,
    val addresses: List<String> = emptyList(),
    val lastUsed: Map<String, Long> = emptyMap(),
    val calls: List<McpCall> = emptyList(),
)

// A token that was just made or given a new secret. text is the only copy of the credential there will ever be.
internal class McpIssued(val text: String, val state: McpState)

internal enum class McpProblem { NAME, LIMIT, PORT, PORT_UNAVAILABLE, TOO_MANY_TOKENS, TOO_MANY_CONVERSATIONS, NO_ACCOUNT, NO_TOKEN, UNAVAILABLE }

internal class McpInputException(val problem: McpProblem, detail: String? = null) : IllegalArgumentException(detail ?: problem.name)

// The MCP server of a host, for the settings screen: where it listens and who may use it. Tokens can be prepared while
// the server is off. A change to a token, revoking included, reaches a running server at once; so do the port and the
// local network choice, which move the listener. Every call blocks, so callers run it off the main thread, and every change answers with the
// state after it. A change that cannot be made throws McpInputException naming the problem.
internal interface McpConsole {
    fun state(): McpState

    // The signed-in account new tokens would belong to, or null without one.
    fun account(): String?

    // Keeps the previous port, and the listener on it, when the new one cannot be taken.
    fun setPort(port: Int): McpState

    // Serves the local networks, or this device alone again. Keeps the previous choice, and the listener, when the
    // port cannot be taken the other way.
    fun setLan(enabled: Boolean): McpState

    fun create(draft: McpTokenDraft): McpIssued
    fun update(id: String, draft: McpTokenDraft): McpState

    // A new secret under the same ID; the old one stops working at once.
    fun renew(id: String): McpIssued
    fun revoke(id: String): McpState
}
