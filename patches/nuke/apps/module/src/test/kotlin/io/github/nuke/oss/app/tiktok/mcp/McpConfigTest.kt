package io.github.nuke.oss.app.tiktok.mcp

import io.github.nuke.oss.app.shared.settings.McpLimits
import io.github.nuke.oss.app.shared.settings.McpPermission
import io.github.nuke.oss.app.shared.settings.McpToken
import org.junit.Assert.assertEquals
import org.junit.Test

class McpConfigTest {
    private val stored = McpStoredToken(
        McpToken("a1b2", "Desk \"client\"", true, "42", setOf(McpPermission.READ_CONTACTS, McpPermission.SEND_TEXT, McpPermission.SEND_IMAGE), false, setOf("c-1", "c-2"), 30, 1_700_000_000_000),
        "ab".repeat(32),
    )

    @Test fun `a configuration reads back as it was written`() {
        val config = McpConfig(9000, listOf(stored, stored.copy(token = stored.token.copy(id = "c3d4", enabled = false, allowAll = true))))
        assertEquals(config, McpConfig.decode(config.encode()))
        assertEquals(config.copy(lan = true), McpConfig.decode(config.copy(lan = true).encode()))
    }

    @Test fun `text that does not read is the empty configuration`() {
        assertEquals(McpConfig(), McpConfig.decode(null))
        assertEquals(McpConfig(), McpConfig.decode("not json"))
        assertEquals(McpConfig(), McpConfig.decode("[1]"))
        assertEquals(McpLimits.DEFAULT_PORT, McpConfig.decode("""{"port":80}""").port)
        assertEquals(false, McpConfig.decode("""{"port":9000,"lan":"yes"}""").lan)
    }

    @Test fun `a token that does not read in full is left out and grants nothing by default`() {
        val text = """{"port":9000,"tokens":[
            {"id":"a","name":"no hash","account":"42","limit":30},
            {"id":"b","name":"no limit","secretHash":"x","account":"42"},
            {"id":"c","name":"bare","secretHash":"x","account":"42","limit":30,"permissions":["SEND_TEXT","ADMIN",7]},
            {"id":"c","name":"same id","secretHash":"y","account":"42","limit":30},
            "junk"
        ]}"""
        val token = McpConfig.decode(text).tokens.single().token
        assertEquals("bare", token.name)
        assertEquals(false, token.enabled)
        assertEquals(false, token.allowAll)
        assertEquals(setOf(McpPermission.SEND_TEXT), token.permissions)
        assertEquals(emptySet<String>(), token.conversations)
    }
}
