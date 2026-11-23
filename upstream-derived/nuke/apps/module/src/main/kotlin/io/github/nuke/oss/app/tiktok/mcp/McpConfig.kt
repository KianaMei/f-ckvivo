package io.github.nuke.oss.app.tiktok.mcp

import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.shared.settings.McpLimits
import io.github.nuke.oss.app.shared.settings.McpPermission
import io.github.nuke.oss.app.shared.settings.McpToken

// A token with what proves its secret: the SHA-256 the native server compares with. The secret itself is never kept.
internal data class McpStoredToken(val token: McpToken, val secretHash: String)

// What the MCP server keeps across restarts, as one JSON document. The native server reads the tokens from the same
// text, so the member names here are its contract; it ignores the port, the local network choice and the creation
// time, which reach it as arguments or not at all.
internal data class McpConfig(val port: Int = McpLimits.DEFAULT_PORT, val tokens: List<McpStoredToken> = emptyList(), val lan: Boolean = false) {
    fun encode(): String = MiniJson.write(mapOf(
        "port" to port,
        "lan" to lan,
        "tokens" to tokens.map { (token, secretHash) ->
            mapOf(
                "id" to token.id,
                "name" to token.name,
                "secretHash" to secretHash,
                "enabled" to token.enabled,
                "account" to token.account,
                "permissions" to token.permissions.map { it.name },
                "allowAll" to token.allowAll,
                "conversations" to token.conversations.toList(),
                "limit" to token.limit,
                "createdAt" to token.createdAt,
            )
        },
    ))

    companion object {
        // Like the native reader: a token that does not read in full is left out, and text that does not read at all
        // is the empty configuration. Nothing here grants access by default.
        fun decode(text: String?): McpConfig {
            val root = MiniJson.obj(text?.let(MiniJson::parse)) ?: return McpConfig()
            val port = (root["port"] as? Long)?.toInt()?.takeIf { it in McpLimits.MIN_PORT..McpLimits.MAX_PORT } ?: McpLimits.DEFAULT_PORT
            val tokens = MiniJson.array(root["tokens"]).orEmpty().mapNotNull { token(MiniJson.obj(it)) }
            return McpConfig(port, tokens.distinctBy { it.token.id }.take(McpLimits.MAX_TOKENS), lan = root["lan"] == true)
        }

        private fun token(entry: Map<String, Any?>?): McpStoredToken? {
            entry ?: return null
            val names = McpPermission.entries.associateBy { it.name }
            fun strings(key: String) = (entry[key] as? List<*>).orEmpty().filterIsInstance<String>()
            return McpStoredToken(
                McpToken(
                    id = entry["id"] as? String ?: return null,
                    name = entry["name"] as? String ?: return null,
                    enabled = entry["enabled"] == true,
                    account = entry["account"] as? String ?: return null,
                    permissions = strings("permissions").mapNotNull(names::get).toSet(),
                    allowAll = entry["allowAll"] == true,
                    conversations = strings("conversations").toSet(),
                    limit = (entry["limit"] as? Long)?.toInt()?.takeIf { it in 1..McpLimits.MAX_REQUESTS } ?: return null,
                    createdAt = entry["createdAt"] as? Long ?: 0L,
                ),
                secretHash = entry["secretHash"] as? String ?: return null,
            )
        }
    }
}
