package io.github.nuke.oss.app.shared.net

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The client is plain java.net, so the server in these tests is the JDK's own: no emulator and no network.
class WebDavClientTest {
    private val servers = ArrayList<HttpServer>()

    @After fun stopServers() {
        for (server in servers) server.stop(0)
        servers.clear()
    }

    private class Recorded(val method: String, val path: String, val authorization: String?, val body: ByteArray)

    private fun server(handle: (Recorded) -> Int): Pair<HttpServer, MutableList<Recorded>> {
        val recorded = ArrayList<Recorded>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.use { it.readBytes() }
            val request = Recorded(
                exchange.requestMethod, exchange.requestURI.rawPath,
                exchange.requestHeaders.getFirst("Authorization"), body,
            )
            recorded += request
            val status = handle(request)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        servers += server
        return server to recorded
    }

    private fun file(text: String): File = Files.createTempFile("upload", ".png").toFile().apply {
        writeBytes(text.toByteArray())
        deleteOnExit()
    }

    private fun base(server: HttpServer): String = "http://127.0.0.1:${server.address.port}/dav"

    @Test fun `a file is put into the collection that already exists`() {
        val (server, requests) = server { if (it.method == "MKCOL") 405 else 201 }
        val result = WebDavClient(base(server), "me", "secret", "pictures").upload(file("image bytes"), "a.png")
        assertTrue("$result", result is WebDavResult.Uploaded)
        assertEquals(201, (result as WebDavResult.Uploaded).status)
        assertEquals(listOf("MKCOL", "PUT"), requests.map { it.method })
        assertEquals("/dav/pictures", requests[0].path)
        assertEquals("/dav/pictures/a.png", requests[1].path)
        assertEquals("image bytes", String(requests[1].body))
        val expected = "Basic " + Base64.getEncoder().encodeToString("me:secret".toByteArray())
        assertEquals(listOf(expected, expected), requests.map { it.authorization })
    }

    @Test fun `a missing collection is created first`() {
        val (server, requests) = server { if (it.method == "MKCOL") 201 else 201 }
        assertTrue(WebDavClient(base(server), "me", "secret", "a/b").upload(file("x"), "b.png") is WebDavResult.Uploaded)
        assertEquals("/dav/a/b", requests[0].path)
        assertEquals("/dav/a/b/b.png", requests[1].path)
    }

    @Test fun `a rejected file keeps the status the server answered`() {
        val (server, _) = server { if (it.method == "MKCOL") 405 else 401 }
        val result = WebDavClient(base(server), "me", "secret").upload(file("x"), "b.png")
        assertEquals(WebDavResult.Failed("upload rejected", 401), result)
    }

    @Test fun `a collection that cannot be created stops the upload`() {
        val (server, requests) = server { 403 }
        val result = WebDavClient(base(server), "me", "secret", "private").upload(file("x"), "b.png")
        assertEquals(WebDavResult.Failed("collection not created", 403), result)
        assertEquals(listOf("MKCOL"), requests.map { it.method })
    }

    @Test fun `a name with spaces and characters is escaped in the path`() {
        val (server, requests) = server { if (it.method == "MKCOL") 405 else 201 }
        WebDavClient(base(server), "me", "secret").upload(file("x"), "评论 图1.png")
        // No folder, so the collection already exists as the address itself and only the PUT is made.
        assertEquals("/dav/%E8%AF%84%E8%AE%BA%20%E5%9B%BE1.png", requests[0].path)
    }

    @Test fun `a redirect to another origin does not carry the credentials`() {
        val (other, otherRequests) = server { 201 }
        // The first server redirects the PUT; the client must re-send it to the second one without the header.
        val redirecting = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes() }
                if (exchange.requestMethod == "MKCOL") {
                    exchange.sendResponseHeaders(405, -1)
                } else {
                    exchange.responseHeaders.add("Location", "http://127.0.0.1:${other.address.port}/elsewhere/a.png")
                    exchange.sendResponseHeaders(307, -1)
                }
                exchange.close()
            }
            start()
        }
        servers += redirecting
        val result = WebDavClient("http://127.0.0.1:${redirecting.address.port}/dav", "me", "secret")
            .upload(File.createTempFile("redirect", ".png").apply { writeBytes("x".toByteArray()) }, "a.png")
        assertEquals(201, (result as WebDavResult.Uploaded).status)
        assertEquals(null, otherRequests.single().authorization)
        assertEquals(listOf("PUT"), otherRequests.map { it.method })
    }

    @Test fun `an address that is not http is refused before any request`() {
        assertEquals(
            WebDavResult.Failed("unsupported address"),
            WebDavClient("ftp://example.com/dav", "me", "secret").upload(file("x"), "b.png"),
        )
        assertEquals(WebDavResult.Failed("unsupported address"), WebDavClient("", "", "").upload(file("x"), "b.png"))
    }

    @Test fun `a file that is gone is reported instead of uploaded`() {
        val missing = File(Files.createTempDirectory("gone").toFile(), "nothing.png")
        assertEquals(WebDavResult.Failed("no file to upload"), WebDavClient("https://example.com", "", "").upload(missing, "x"))
    }
}
