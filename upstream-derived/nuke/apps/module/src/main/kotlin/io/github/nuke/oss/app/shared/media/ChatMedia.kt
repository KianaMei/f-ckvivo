package io.github.nuke.oss.app.shared.media

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Where the addresses in a chat message's content point, and what to call each one. The content is the IM SDK's JSON:
// media lists appear as url_list arrays, and single files as a url field. Anything that is not http(s) is dropped.
internal object ChatMediaUrls {
    private val ADDRESS = Regex("""https?://[^"\\\s]+""")
    private val MEDIA_EXTENSION = Regex("""\.(jpe?g|png|webp|gif|heic|heif|avif|mp4|mov|m4v|m4a|amr|mp3|aac|wav|silk)(\?|$)""", RegexOption.IGNORE_CASE)

    // In the order they appear, without duplicates or obvious thumbnails (a small ``~tplv`` variant).
    fun of(content: String?): List<String> {
        if (content.isNullOrBlank()) return emptyList()
        return ADDRESS.findAll(content)
            .map { it.value.trimEnd(',', '}', ']', '"', '\\') }
            .filter { it.contains("~tplv") || MEDIA_EXTENSION.containsMatchIn(it) }
            .filterNot { it.contains("~tplv") && THUMBNAIL.containsMatchIn(it) }
            .distinct()
            .toList()
    }

    // The extension from the address, or bin when none is recognisable; content types are not read here.
    fun extension(address: String): String {
        val path = address.substringBefore('?')
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.length - 1) return "bin"
        val extension = path.substring(dot + 1).lowercase()
        return if (extension.all { it.isLetterOrDigit() } && extension.length <= 5) extension else "bin"
    }

    // A message keeps its own directory, so files from different chats never mix.
    fun directoryName(conversationId: String): String =
        conversationId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(64)
            .ifEmpty { "chat" }

    // A thumbnail variant of the same address; those are the ones not worth keeping.
    private val THUMBNAIL = Regex("""[?&](width|height|resize|w|h)=\d""", RegexOption.IGNORE_CASE)
}

// Downloads one address into a file; a failure returns false instead of throwing, so one bad address never stops a message.
internal fun interface ChatMediaFetcher {
    fun fetch(address: String, target: File): Boolean
}

// The plain implementation: http(s) only, a size cap, and a timeout so a stalled server cannot hold the caller.
internal class HttpChatMediaFetcher(private val maxBytes: Long = 64L * 1024 * 1024) : ChatMediaFetcher {
    override fun fetch(address: String, target: File): Boolean = try {
        val url = URL(address)
        require(url.protocol == "https" || url.protocol == "http") { "Unsupported address" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) return false
                        output.write(buffer, 0, count)
                    }
                    total > 0
                }
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: java.io.IOException) {
        target.delete()
        false
    } catch (_: IllegalArgumentException) {
        target.delete()
        false
    }
}
