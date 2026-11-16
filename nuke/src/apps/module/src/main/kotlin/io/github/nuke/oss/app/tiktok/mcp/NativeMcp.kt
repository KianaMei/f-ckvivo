package io.github.nuke.oss.app.tiktok.mcp

// JNI binding of the MCP server in the Rust core (apps/module/src/main/rust, mcp). The server owns its socket, tokens
// and protocol; this side lends it threads and answers what it asks of the app. Nothing throws across the boundary and
// strings are copied; see NativeProtocol for how the library is found in each loading mode.
internal object NativeMcp {
    private const val LIBRARY = "nuke_protocol_core"

    @Volatile private var loaded = false

    // Throws IllegalStateException with the reason when the library, or its MCP part, is not there.
    @Synchronized fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary(LIBRARY)
            entropyBytes()
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("The native MCP server is not loadable: ${error.message}", error)
        }
        loaded = true
    }

    // Binds the port (0 takes any free one) with the stored configuration, on 127.0.0.1 or, with lan, on every IPv4
    // interface, where only private, link-local and loopback addresses are answered. Answers JSON: ok with the server
    // handle, the bound port and the number of tokens taken, or not ok with a code and a message.
    external fun open(port: Int, lan: Boolean, config: String): String?

    // Makes the calling thread a worker until close(server). host is called on this thread only.
    external fun serve(server: Long, host: McpHost)

    // Replaces the tokens; the number taken, or -1 for a handle that is not open.
    external fun configure(server: Long, config: String): Int

    // The port, the record of tool calls and the last-used times as JSON; null for a handle that is not open.
    external fun snapshot(server: Long): String?

    // Workers return from serve on their own; the port is free once the last one has.
    external fun close(server: Long)

    // A credential from entropyBytes() of secure randomness, as JSON with id, secretHash and token. With id, a new
    // secret for that token. Null when the arguments do not fit.
    external fun issue(entropy: ByteArray, id: String?): String?
    external fun entropyBytes(): Int
}

// What the native server asks of the app: an operation (account, contacts, sendText, sendImage, sendProtocol, claim or
// markAllRead) with JSON arguments and, for a picture or a raw request, the bytes of the file or the body, answered
// with JSON that is either ok with the result or not ok with a code and a message. Called from native code by name on the server's worker threads, so the signature is kept in
// step with bridge.rs and the R8 rules.
internal fun interface McpHost {
    fun request(operation: String, arguments: String, payload: ByteArray?): String
}
