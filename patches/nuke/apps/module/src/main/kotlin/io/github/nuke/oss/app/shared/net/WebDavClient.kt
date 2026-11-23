package io.github.nuke.oss.app.shared.net

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// The outcome of one upload. A failure keeps the status the server answered with, so the log tells a wrong password
// (401) from a missing collection (409) without repeating the attempt.
internal sealed interface WebDavResult {
    data class Uploaded(val url: String, val status: Int) : WebDavResult

    data class Failed(val reason: String, val status: Int = 0) : WebDavResult
}

// A small WebDAV client: it makes sure the collection exists and puts one file into it. Basic authentication only,
// and only for the origin the settings named; a redirect to another host is followed without the credentials.
internal class WebDavClient(
    baseUrl: String,
    private val user: String,
    private val password: String,
    private val directory: String = "",
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 60_000,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private val origin = originOf(base)

    // Returns where the file was written, or why it was not. Never throws.
    fun upload(file: File, name: String): WebDavResult {
        if (base.isEmpty() || origin == null) return WebDavResult.Failed("unsupported address")
        if (!file.isFile) return WebDavResult.Failed("no file to upload")
        val segments = directory.split('/').filter { it.isNotBlank() }
        val collection = segments.joinToString("/") { encode(it) }
        val target = (segments.map(::encode) + encode(name)).joinToString("/")
        try {
            if (collection.isNotEmpty()) {
                // 405 and 301 mean the collection is already there, which is what this asks for.
                val status = createCollection("$base/$collection")
                if (status !in 200..299 && status != 405 && status != 301) {
                    return WebDavResult.Failed("collection not created", status)
                }
            }
            val status = request("PUT", "$base/$target", file)
            if (status !in 200..299) return WebDavResult.Failed("upload rejected", status)
            return WebDavResult.Uploaded("$base/$target", status)
        } catch (error: IOException) {
            return WebDavResult.Failed(error.javaClass.simpleName + ": " + error.message.orEmpty().take(120))
        } catch (error: RuntimeException) {
            return WebDavResult.Failed(error.javaClass.simpleName + ": " + error.message.orEmpty().take(120))
        }
    }

    // MKCOL over a socket of our own: HttpURLConnection refuses every method outside a short list, and MKCOL is one of
    // them. A TLS connection verifies the host name like any other request does.
    private fun createCollection(address: String): Int {
        val uri = URI(address)
        val scheme = uri.scheme.lowercase()
        val host = uri.host ?: throw IOException("no host in $address")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?" + it } ?: "")
        val socket = if (scheme == "https") {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, port) as SSLSocket
        } else {
            Socket(host, port)
        }
        socket.use { open ->
            if (open is SSLSocket) {
                open.sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "HTTPS" }
                open.startHandshake()
            }
            open.soTimeout = readTimeoutMillis
            val header = StringBuilder()
                .append("MKCOL ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
            if (credentialsFor(URL(address))) header.append("Authorization: Basic ").append(basicToken()).append("\r\n")
            header.append("Content-Length: 0\r\n").append("Connection: close\r\n\r\n")
            open.getOutputStream().write(header.toString().toByteArray(Charsets.ISO_8859_1))
            open.getOutputStream().flush()
            val reader = open.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val statusLine = reader.readLine() ?: throw IOException("no response to MKCOL")
            return statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: $statusLine")
        }
    }

    // One request, following the redirects that keep the method; the credentials stay on their own origin.
    private fun request(method: String, address: String, body: File, redirects: Int = 0): Int {
        val url = URL(address)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Accept", "*/*")
            if (credentialsFor(url)) connection.setRequestProperty("Authorization", "Basic " + basicToken())
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.length())
            connection.outputStream.use { output -> body.inputStream().use { it.copyTo(output) } }
            val status = connection.responseCode
            // 307/308 keep the method and the body; 301/302/303 would turn a PUT into a GET, so they are not followed.
            if (status in listOf(307, 308) && redirects < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location") ?: return status
                return request(method, URL(url, location).toString(), body, redirects + 1)
            }
            return status
        } finally {
            connection.disconnect()
        }
    }

    private fun credentialsFor(url: URL): Boolean {
        val given = origin ?: return false
        return given == originOf(url.toString())
    }

    private fun basicToken(): String =
        Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private companion object {
        const val MAX_REDIRECTS = 3

        fun originOf(address: String): String? = try {
            val uri = URI(address)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
            "$scheme://${host.lowercase()}:$port"
        } catch (_: RuntimeException) {
            null
        }
    }
}
