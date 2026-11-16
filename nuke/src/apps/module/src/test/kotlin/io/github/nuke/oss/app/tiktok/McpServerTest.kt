package io.github.nuke.oss.app.tiktok

import com.bytedance.ies.im.core.sdk.testConversationModel
import com.bytedance.im.core.model.ConversationListModel
import io.github.nuke.oss.app.shared.data.MiniJson
import io.github.nuke.oss.app.shared.dex.DexQueryUnavailable
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.HostFriend
import io.github.nuke.oss.app.shared.settings.HostGroup
import io.github.nuke.oss.app.shared.settings.McpInputException
import io.github.nuke.oss.app.shared.settings.McpLimits
import io.github.nuke.oss.app.shared.settings.McpListening
import io.github.nuke.oss.app.shared.settings.McpPermission
import io.github.nuke.oss.app.shared.settings.McpProblem
import io.github.nuke.oss.app.shared.settings.McpTokenDraft
import io.github.nuke.oss.app.shared.settings.MemoryFeatureMemory
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessTarget
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkConversation
import io.github.nuke.oss.app.tiktok.feature.general.McpServer
import io.github.nuke.oss.app.tiktok.mcp.DouyinMcpHost
import io.github.nuke.oss.app.tiktok.mcp.RewardCounts
import io.github.nuke.oss.app.tiktok.mcp.RewardSource
import io.github.nuke.oss.app.tiktok.mcp.RewardTarget
import io.github.nuke.oss.app.tiktok.mcp.closenessRewards
import io.github.nuke.oss.app.tiktok.mcp.sparkRewards
import io.github.nuke.oss.app.tiktok.query.ContactQueries
import io.github.nuke.oss.app.tiktok.protocol.PacketEndpoint
import io.github.nuke.oss.app.tiktok.protocol.PacketResponse
import io.github.nuke.oss.app.tiktok.protocol.ProtocolError
import io.github.nuke.oss.app.tiktok.protocol.ProtocolErrorCode
import io.github.nuke.oss.app.tiktok.protocol.ProtocolQueries
import io.github.nuke.oss.app.tiktok.protocol.ProtocolResult
import io.github.nuke.oss.app.tiktok.query.ChatUploadQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ContentSender
import io.github.nuke.oss.app.tiktok.sdk.chat.ConversationModels
import io.github.nuke.oss.app.tiktok.sdk.chat.ImageContents
import io.github.nuke.oss.app.tiktok.sdk.chat.ImageUploadFailed
import io.github.nuke.oss.app.tiktok.sdk.chat.ReadMarker
import io.github.nuke.oss.app.tiktok.sdk.chat.SendResult
import io.github.nuke.oss.core.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Contract tests for the MCP server feature against the real native server: a client speaks HTTP to the loopback port,
// the Rust core calls back through JNI, and only the app behind it (the account, the contacts and the sender) is a
// stand-in; the claims run for real over scripted transports. The protocol and token rules themselves are covered by
// the crate's own tests.
class McpServerTest : HookHarness() {
    private val memory = MemoryFeatureMemory()
    private val logs = Collections.synchronizedList(ArrayList<String>())
    private val sent = Collections.synchronizedList(ArrayList<String>())
    @Volatile private var account = 42L
    @Volatile private var contacts: () -> HostContactList = {
        HostContactList(
            friends = listOf(HostFriend("1001", "Alice", null, 3, "c-alice"), HostFriend("1002", "Bob", null, null, "c-bob")),
            groups = listOf(HostGroup("c-team", "Team", null)),
        )
    }
    @Volatile private var answer: () -> SendResult = { SendResult.Sent("client-1", 77L, "ok") }
    private val sender = object : ContentSender {
        override fun send(uid: String, content: Any, timeoutMillis: Long): SendResult = answer().also { sent += "uid=$uid $content" }
        override fun sendToConversation(conversationId: String, content: Any, timeoutMillis: Long): SendResult =
            answer().also { sent += "conversation=$conversationId $content" }
    }
    private val readAccount = { if (account > 0) account else error("Not logged in") }
    private val pictures = Files.createTempDirectory("nuke-mcp-pictures").toFile()
    private val uploaded = Collections.synchronizedList(ArrayList<ByteArray>())
    @Volatile private var upload: () -> Unit = {}
    private val images = ImageContents { image, _ ->
        upload()
        uploaded += image.file.readBytes()
        "image:${image.file.extension}:${image.width}x${image.height}:${image.original}"
    }
    @Volatile private var now = 1_000_000_000_000L
    private val requests = Collections.synchronizedList(ArrayList<String>())
    @Volatile private var response: (PacketEndpoint) -> ProtocolResult<PacketResponse> = { ProtocolResult.Ok(PacketResponse(it, byteArrayOf(0x18, 0))) }
    private val sparkTransport = FakeSparkTransport(account = 42)
    private val petTransport = FakePetTransport(account = 42).apply { shortIds["c-alice"] = "101"; shortIds["c-bob"] = "102" }
    // The loaded private chats and the streak friends as the claims' own lookups give them, in no particular order
    // and with a chat that is no contact.
    private val sparks = sparkRewards(sparkTransport) { listOf(SparkConversation("c-bob", "2", 1), SparkConversation("c-other", "3", 1), SparkConversation("c-alice", "1", 1)) }
    @Volatile private var closeness = closenessRewards(petTransport) { listOf(ClosenessTarget("c-bob"), ClosenessTarget("c-other"), ClosenessTarget("c-alice")) }
    private val read = Collections.synchronizedList(ArrayList<Long>())
    @Volatile private var readAll: (Long) -> Unit = { read += it }
    // What a stand-in claim does while it runs.
    @Volatile private var during: () -> Unit = {}
    private val server = McpServer(
        DouyinMcpHost(readAccount, { contacts() }, sender, { "text:$it" }, images, pictures,
            { cmd, inboxType, body -> requests += "$cmd/$inboxType ${body.joinToString(",")}"; response(PacketEndpoint(cmd, inboxType)) },
            sparks, { owner, chats -> closeness.targets(owner, chats) }, { readAll(it) },
            { logs += it }, McpServer.ID, clock = { now },
        ),
        readAccount, memory, Logger { logs += it }, addresses = { listOf("192.168.1.20") },
    )

    private var registered = false

    @After fun stop() {
        if (registered) disable(server)
        pictures.deleteRecursively()
    }

    private fun switchOn() {
        registered = true
        enable(server)
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    // The default port may be taken on the machine that runs the tests, so every test starts on a free one.
    private fun start(): Int {
        val port = freePort()
        server.setPort(port)
        switchOn()
        assertEquals(McpListening.On(port), server.state().listening)
        return port
    }

    private fun token(vararg permissions: McpPermission, allowAll: Boolean = true, conversations: Set<String> = emptySet(), limit: Int = 60) =
        server.create(McpTokenDraft("client", permissions = permissions.toSet(), allowAll = allowAll, conversations = conversations, limit = limit)).text

    private fun post(port: Int, token: String?, body: String): Pair<Int, Map<String, Any?>?> {
        val connection = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Content-Type", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val text = (connection.errorStream ?: connection.inputStream).use { it.readBytes().toString(Charsets.UTF_8) }
        return status to MiniJson.obj(MiniJson.parse(text))
    }

    private fun rpc(port: Int, token: String, method: String, params: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val (status, answer) = post(port, token, MiniJson.write(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params)))
        assertEquals(200, status)
        return checkNotNull(answer)
    }

    // The tool's own result, and whether the tool reported it as a failure.
    private fun call(port: Int, token: String, tool: String, arguments: Map<String, Any?>): Pair<Map<String, Any?>, Boolean> {
        val result = checkNotNull(MiniJson.obj(rpc(port, token, "tools/call", mapOf("name" to tool, "arguments" to arguments))["result"]))
        val content = MiniJson.obj(MiniJson.array(result["content"])!!.single())!!
        return checkNotNull(MiniJson.obj(MiniJson.parse(content["text"] as String))) to (result["isError"] == true)
    }

    private fun errorCode(answer: Map<String, Any?>): Any? = MiniJson.obj(MiniJson.obj(answer["error"])?.get("data"))?.get("code")

    @Test fun `tokens are prepared while the server is off and only their hash is kept`() {
        val text = token(McpPermission.SEND_TEXT)
        assertTrue(text.startsWith("nuke_mcp_"))
        val state = server.state()
        assertEquals(McpListening.Off, state.listening)
        assertEquals("42", state.tokens.single().account)
        val stored = memory.stored.values.single()
        assertFalse(stored.contains(text.substringAfter('.')))
        assertTrue(stored.contains("secretHash"))
        assertFalse(logs.any { it.contains(text.substringAfter('.')) })
    }

    @Test fun `every request needs a token and a token lists only the tools it may call`() {
        val reader = token(McpPermission.READ_CONTACTS)
        val port = start()
        val request = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        assertEquals(401, post(port, null, request).first)
        assertEquals(401, post(port, reader.dropLast(1) + if (reader.last() == '0') '1' else '0', request).first)
        val initialized = MiniJson.obj(rpc(port, reader, "initialize", mapOf("protocolVersion" to "2025-06-18"))["result"])!!
        assertEquals("2025-06-18", initialized["protocolVersion"])
        val tools = MiniJson.array(MiniJson.obj(rpc(port, reader, "tools/list")["result"])!!["tools"])!!.map { MiniJson.obj(it)!!["name"] }
        assertEquals(listOf("douyin_find_contact", "douyin_list_contacts"), tools)
    }

    @Test fun `text goes through the host sender only to a chat the token reaches`() {
        val port = start()
        val limited = token(McpPermission.READ_CONTACTS, McpPermission.SEND_TEXT, allowAll = false, conversations = setOf("c-alice", "c-team"))

        val (found, _) = call(port, limited, "douyin_find_contact", mapOf("name" to "ali"))
        assertEquals("c-alice", MiniJson.obj(MiniJson.array(found["contacts"])!!.single())!!["conversationId"])
        val (listed, _) = call(port, limited, "douyin_list_contacts", emptyMap())
        assertEquals(2L, listed["total"])

        val (friend, friendFailed) = call(port, limited, "douyin_send_text", mapOf("conversationId" to "c-alice", "text" to "hello"))
        assertFalse(friendFailed)
        assertEquals("sent", friend["status"])
        assertEquals("77", friend["messageId"])
        val (group, _) = call(port, limited, "douyin_send_text", mapOf("conversationId" to "c-team", "text" to "hi all"))
        assertEquals("sent", group["status"])
        assertEquals(listOf("uid=1001 text:hello", "conversation=c-team text:hi all"), sent.toList())

        val (refused, refusedFailed) = call(port, limited, "douyin_send_text", mapOf("conversationId" to "c-bob", "text" to "psst"))
        assertTrue(refusedFailed)
        assertEquals("CONVERSATION_NOT_ALLOWED", refused["errorCode"])
        assertEquals(2, sent.size)

        // The record names the tool, the chat and the outcome, newest first, and never the text.
        val state = server.state()
        assertEquals(listOf("CONVERSATION_NOT_ALLOWED", "OK", "OK"), state.calls.take(3).map { it.code })
        assertEquals("c-bob", state.calls.first().target)
        assertEquals("client", state.calls.first().tokenName)
        assertTrue(state.lastUsed.containsKey(state.tokens.single().id))
        assertFalse(logs.any { it.contains("hello") || it.contains("c-alice") || it.contains("1001") })
    }

    @Test fun `a picture is stored, uploaded and sent through the host sender`() {
        val port = start()
        val painter = token(McpPermission.SEND_IMAGE)
        val file = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13) +
            "IHDR".toByteArray() + byteArrayOf(0, 0, 2, 0x80.toByte(), 0, 0, 1, 0xe0.toByte())
        val encoded = Base64.getEncoder().encodeToString(file)
        val tools = MiniJson.array(MiniJson.obj(rpc(port, painter, "tools/list")["result"])!!["tools"])!!.map { MiniJson.obj(it)!!["name"] }
        assertEquals(listOf("douyin_send_image"), tools)

        val (friend, friendFailed) = call(port, painter, "douyin_send_image", mapOf("conversationId" to "c-alice", "image" to encoded))
        assertFalse(friendFailed)
        assertEquals("sent", friend["status"])
        val (group, _) = call(port, painter, "douyin_send_image", mapOf("conversationId" to "c-team", "image" to encoded, "original" to true))
        assertEquals("sent", group["status"])
        assertEquals(listOf("uid=1001 image:png:640x480:false", "conversation=c-team image:png:640x480:true"), sent.toList())
        assertTrue(uploaded.all { it.contentEquals(file) })
        // The host's chat row shows the local file, so it stays for a day and goes with the next picture after that.
        assertEquals(2, pictures.listFiles()!!.size)
        now += 25 * 60 * 60 * 1000L
        pictures.listFiles()!!.forEach { it.setLastModified(1_000_000_000_000L) }
        call(port, painter, "douyin_send_image", mapOf("conversationId" to "c-alice", "image" to encoded))
        assertEquals(1, pictures.listFiles()!!.size)

        // A picture that did not upload was not sent, and its file is not kept.
        upload = { throw ImageUploadFailed("1001: too large") }
        val (failed, uploadFailed) = call(port, painter, "douyin_send_image", mapOf("conversationId" to "c-alice", "image" to encoded))
        assertTrue(uploadFailed)
        assertEquals("UPLOAD_FAILED", failed["errorCode"])
        assertEquals("1001: too large", failed["message"])
        upload = { throw DexQueryUnavailable("ChatUploadQueries.UPLOAD_IMAGE") }
        assertEquals("NOT_ADAPTED", call(port, painter, "douyin_send_image", mapOf("conversationId" to "c-alice", "image" to encoded)).first["errorCode"])
        assertEquals(3, sent.size)
        assertEquals(1, pictures.listFiles()!!.size)

        assertEquals("PERMISSION_DENIED", errorCode(rpc(port, painter, "tools/call", mapOf("name" to "douyin_send_text", "arguments" to mapOf("conversationId" to "c-alice", "text" to "hi")))))
        assertFalse(logs.any { it.contains(encoded) || it.contains("c-alice") })
    }

    @Test fun `a raw request reaches the host as given and its response comes back whole`() {
        val port = start()
        val raw = token(McpPermission.SEND_PROTOCOL)
        val body = Base64.getEncoder().encodeToString(byteArrayOf(1, 2))
        val (answer, failed) = call(port, raw, "douyin_send_protocol", mapOf("cmd" to 609, "inboxType" to 0, "bodyTag" to 5, "body" to body))
        assertFalse(failed)
        assertEquals("GAA=", answer["response"])
        assertEquals(0L, answer["statusCode"])
        assertEquals(listOf("609/0 42,2,1,2"), requests.toList())
        assertEquals("cmd 609", server.state().calls.first().target)

        response = { ProtocolResult.Err(ProtocolError(ProtocolErrorCode.TIMEOUT, "Host callback timed out; the queued request may still be sent")) }
        val (timeout, timedOut) = call(port, raw, "douyin_send_protocol", mapOf("cmd" to 100, "body" to ""))
        assertTrue(timedOut)
        assertEquals("TIMEOUT", timeout["errorCode"])
        response = { throw DexQueryUnavailable("ProtocolQueries.RAW_DISPATCH") }
        assertEquals("NOT_ADAPTED", call(port, raw, "douyin_send_protocol", mapOf("cmd" to 100, "body" to "")).first["errorCode"])

        // The chats a token names cannot bound a body nobody reads, so such a token may not send one at all.
        val narrow = token(McpPermission.SEND_PROTOCOL, allowAll = false, conversations = setOf("c-alice"))
        assertEquals("ALL_CHATS_REQUIRED", call(port, narrow, "douyin_send_protocol", mapOf("cmd" to 100, "body" to "")).first["errorCode"])
        assertEquals(3, requests.size)
        assertFalse(logs.any { it.contains(body) })
    }

    @Test fun `spark rewards are claimed in the private chats the token reaches and nowhere else`() {
        val port = start()
        val claimer = token(McpPermission.CLAIM_REWARDS)
        // Alice has one earned reward and Bob none; the chats are taken in the order of their IDs.
        sparkTransport.gets += sparkList(sparkTask())
        sparkTransport.posts += 0L
        sparkTransport.gets += sparkList()
        val (all, failed) = call(port, claimer, "douyin_claim_sparks", emptyMap())
        assertFalse(failed)
        assertEquals(listOf(2L, 2L, 1L, 0L), listOf(all["targets"], all["scanned"], all["claimed"], all["unknown"]))
        assertNull(all["next"])
        fun asked() = sparkTransport.urls.filter { "conversation_short_id=" in it }.map { it.substringAfter("conversation_short_id=").substringBefore('&') }
        assertEquals(listOf("1", "2"), asked())
        assertEquals("c-alice", MiniJson.obj(sparkTransport.bodies.single()["conversation_info"])!!["conversation_id"])

        sparkTransport.gets += sparkList()
        val narrow = token(McpPermission.CLAIM_REWARDS, allowAll = false, conversations = setOf("c-bob", "c-team"))
        assertEquals(1L, call(port, narrow, "douyin_claim_sparks", emptyMap()).first["targets"])
        assertEquals("CONVERSATION_NOT_ALLOWED", call(port, narrow, "douyin_claim_sparks", mapOf("conversationId" to "c-alice")).first["errorCode"])
        sparkTransport.gets += sparkList()
        assertEquals(1L, call(port, claimer, "douyin_claim_sparks", mapOf("conversationId" to "c-alice")).first["scanned"])
        assertEquals(listOf("1", "2", "2", "1"), asked())
        assertEquals("c-alice", server.state().calls.first().target)

        // A lookup that cannot be made is the tool's failure, with the reason; nothing was asked then.
        contacts = { throw HostContactsUnavailable("The friend list is not loaded") }
        assertEquals("CONTACTS_UNAVAILABLE", call(port, claimer, "douyin_claim_sparks", emptyMap()).first["errorCode"])
        assertEquals("PERMISSION_DENIED", errorCode(rpc(port, token(McpPermission.SEND_TEXT), "tools/call", mapOf("name" to "douyin_claim_sparks"))))
        assertEquals(4, asked().size)
        assertFalse(logs.any { it.contains("c-alice") || it.contains("c-bob") })
    }

    @Test fun `a closeness submission that got no answer ends the run and is never repeated`() {
        val port = start()
        val claimer = token(McpPermission.CLAIM_REWARDS)
        petTransport.reads += pet(behavior(5))
        petTransport.claims += IOException("timed out")
        val (stopped, failed) = call(port, claimer, "douyin_claim_closeness", emptyMap())
        assertFalse(failed)
        assertEquals(listOf(2L, 1L, 0L, 1L, 1L), listOf(stopped["targets"], stopped["scanned"], stopped["claimed"], stopped["unknown"], stopped["next"]))
        assertEquals(listOf("timed out"), MiniJson.array(stopped["details"]))

        // Going on from where it stopped reads Bob alone.
        petTransport.reads += pet(behavior(6))
        petTransport.claims += 0L
        val (rest, _) = call(port, claimer, "douyin_claim_closeness", mapOf("skip" to 1))
        assertEquals(listOf(1L, 1L), listOf(rest["scanned"], rest["claimed"]))
        assertNull(rest["next"])
        assertEquals(2, petTransport.sent.count { it.first == "/ttgame/social_pet/closeness/claim" })
    }

    @Test fun `a claim stops when its time is up, when the account changes and while another one runs`() {
        val port = start()
        val claimer = token(McpPermission.CLAIM_REWARDS)
        contacts = { HostContactList(listOf("a", "b", "c").map { HostFriend("10$it", it, null, 1, "c-$it") }, emptyList()) }
        val claimed = Collections.synchronizedList(ArrayList<String>())
        during = { now += 30_000 }
        closeness = RewardSource { _, chats ->
            chats.map { chat -> RewardTarget(chat) { claimed += chat; during(); RewardCounts(1, 1, 0, 0, 0, 0, emptyList()) } }
        }
        val (first, _) = call(port, claimer, "douyin_claim_closeness", emptyMap())
        assertEquals(listOf(3L, 2L, 2L), listOf(first["targets"], first["claimed"], first["next"]))
        val (second, _) = call(port, claimer, "douyin_claim_closeness", mapOf("skip" to first["next"]))
        assertEquals(1L, second["claimed"])
        assertNull(second["next"])
        assertEquals(listOf("c-a", "c-b", "c-c"), claimed.toList())

        // The token belongs to account 42, so the run ends where another account appears.
        during = { account = 43 }
        val (changed, _) = call(port, claimer, "douyin_claim_closeness", emptyMap())
        assertEquals(listOf(1L, 1L), listOf(changed["claimed"], changed["next"]))
        assertEquals("The account changed; the claim ended early", MiniJson.array(changed["details"])!!.first())
        account = 42

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        during = { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        val running = thread { call(port, claimer, "douyin_claim_closeness", mapOf("conversationId" to "c-a")) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val (busy, refused) = call(port, claimer, "douyin_claim_sparks", emptyMap())
        assertTrue(refused)
        assertEquals("BUSY", busy["errorCode"])
        release.countDown()
        running.join(10_000)
        assertTrue(sparkTransport.urls.isEmpty())
    }

    @Test fun `marking everything read runs the host's own step for the signed-in account`() {
        val port = start()
        val marker = token(McpPermission.MARK_READ)
        val (answer, failed) = call(port, marker, "douyin_mark_all_read", emptyMap())
        assertFalse(failed)
        assertEquals("submitted", answer["status"])
        assertEquals(listOf(42L), read.toList())

        readAll = { throw IllegalStateException("The native conversation model belongs to another account") }
        val (unready, unreadyFailed) = call(port, marker, "douyin_mark_all_read", emptyMap())
        assertTrue(unreadyFailed)
        assertEquals("HOST_NOT_READY", unready["errorCode"])
        readAll = { throw NoSuchMethodException("markAllConversationRead") }
        assertEquals("NOT_ADAPTED", call(port, marker, "douyin_mark_all_read", emptyMap()).first["errorCode"])

        // Every chat is marked, so a token that names its chats may not do it.
        val narrow = token(McpPermission.MARK_READ, allowAll = false, conversations = setOf("c-alice"))
        assertEquals("ALL_CHATS_REQUIRED", call(port, narrow, "douyin_mark_all_read", emptyMap()).first["errorCode"])
        assertEquals(1, read.size)
    }

    @Test fun `the server opens to the local network and closes again without a restart`() {
        val port = start()
        val reader = token(McpPermission.READ_CONTACTS)
        val list = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        assertFalse(server.state().lan)
        assertTrue(server.state().addresses.isEmpty())

        val open = server.setLan(true)
        assertTrue(open.lan)
        assertEquals(McpListening.On(port), open.listening)
        assertEquals(listOf("192.168.1.20"), open.addresses)
        assertEquals(200, post(port, reader, list).first)
        assertEquals(401, post(port, null, list).first)
        assertTrue(memory.stored.values.single().contains("\"lan\":true"))

        val closed = server.setLan(false)
        assertFalse(closed.lan)
        assertTrue(closed.addresses.isEmpty())
        assertEquals(200, post(port, reader, list).first)

        // The choice is kept while the server is off, and no address is offered until it listens.
        disable(server)
        assertTrue(server.setLan(true).lan)
        assertTrue(server.state().addresses.isEmpty())
    }

    @Test fun `a send the host did not confirm is reported as it came`() {
        val port = start()
        val writer = token(McpPermission.SEND_TEXT)
        answer = { SendResult.Unknown("No answer in time") }
        val (unknown, unknownFailed) = call(port, writer, "douyin_send_text", mapOf("conversationId" to "c-alice", "text" to "hello"))
        assertTrue(unknownFailed)
        assertEquals("unknown", unknown["status"])
        assertEquals("SEND_UNCONFIRMED", unknown["errorCode"])
        answer = { SendResult.Failed("Blocked") }
        val (failed, _) = call(port, writer, "douyin_send_text", mapOf("conversationId" to "c-alice", "text" to "hello"))
        assertEquals("SEND_FAILED", failed["errorCode"])
        assertEquals("Blocked", failed["detail"])
    }

    @Test fun `a host that cannot answer fails the call with its reason and the server goes on`() {
        val port = start()
        val all = token(McpPermission.READ_CONTACTS, McpPermission.SEND_TEXT)
        val working = contacts
        contacts = { throw HostContactsUnavailable("The friend list is not loaded") }
        val (unavailable, unavailableFailed) = call(port, all, "douyin_list_contacts", emptyMap())
        assertTrue(unavailableFailed)
        assertEquals("CONTACTS_UNAVAILABLE", unavailable["errorCode"])
        assertEquals("The friend list is not loaded", unavailable["message"])
        contacts = { throw DexQueryUnavailable("ContactQueries.FRIENDS") }
        assertEquals("NOT_ADAPTED", call(port, all, "douyin_list_contacts", emptyMap()).first["errorCode"])
        contacts = { throw UnsupportedOperationException("secret detail") }
        val (crashed, _) = call(port, all, "douyin_list_contacts", emptyMap())
        assertEquals("HOST_FAILED", crashed["errorCode"])
        assertEquals("UnsupportedOperationException", crashed["message"])

        contacts = working
        answer = { error("The sender is not ready") }
        assertEquals("HOST_NOT_READY", call(port, all, "douyin_send_text", mapOf("conversationId" to "c-alice", "text" to "hello")).first["errorCode"])
        answer = { SendResult.Sent(null, null, "ok") }
        assertEquals("sent", call(port, all, "douyin_send_text", mapOf("conversationId" to "c-alice", "text" to "hello")).first["status"])
    }

    @Test fun `a token acts only as the account it was made for`() {
        val port = start()
        val mine = token(McpPermission.READ_CONTACTS)
        account = 43
        assertEquals("ACCOUNT_MISMATCH", errorCode(rpc(port, mine, "tools/call", mapOf("name" to "douyin_list_contacts"))))
        account = 0
        val (signedOut, signedOutFailed) = call(port, mine, "douyin_list_contacts", emptyMap())
        assertTrue(signedOutFailed)
        assertEquals("ACCOUNT_UNAVAILABLE", signedOut["errorCode"])
        assertNull(server.account())
        assertEquals(McpProblem.NO_ACCOUNT, assertThrows(McpInputException::class.java) { token(McpPermission.READ_CONTACTS) }.problem)
    }

    @Test fun `a change to a token reaches the running server at once`() {
        val port = start()
        val first = token(McpPermission.READ_CONTACTS)
        val id = server.state().tokens.single().id
        val list = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        assertEquals(200, post(port, first, list).first)

        server.update(id, McpTokenDraft("client", enabled = false, permissions = setOf(McpPermission.READ_CONTACTS), allowAll = true))
        assertEquals(401, post(port, first, list).first)
        server.update(id, McpTokenDraft("renamed", permissions = setOf(McpPermission.SEND_TEXT), allowAll = true))
        val tools = MiniJson.array(MiniJson.obj(rpc(port, first, "tools/list")["result"])!!["tools"])!!.map { MiniJson.obj(it)!!["name"] }
        assertEquals(listOf("douyin_send_text"), tools)

        val second = server.renew(id).text
        assertEquals(id, server.state().tokens.single().id)
        assertEquals(401, post(port, first, list).first)
        assertEquals(200, post(port, second, list).first)

        assertTrue(server.revoke(id).tokens.isEmpty())
        assertEquals(401, post(port, second, list).first)
        assertEquals(McpProblem.NO_TOKEN, assertThrows(McpInputException::class.java) { server.revoke(id) }.problem)
        assertEquals(McpProblem.NO_TOKEN, assertThrows(McpInputException::class.java) { server.renew(id) }.problem)
    }

    @Test fun `a token over its requests per minute is told to wait`() {
        val port = start()
        val slow = token(McpPermission.READ_CONTACTS, limit = 1)
        val list = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        assertEquals(200, post(port, slow, list).first)
        assertEquals(429, post(port, slow, list).first)
    }

    @Test fun `the port moves without a restart and a port that is taken keeps the old one`() {
        val port = start()
        val reader = token(McpPermission.READ_CONTACTS)
        val list = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { taken ->
            val refused = assertThrows(McpInputException::class.java) { server.setPort(taken.localPort) }
            assertEquals(McpProblem.PORT_UNAVAILABLE, refused.problem)
        }
        assertEquals(McpListening.On(port), server.state().listening)
        assertEquals(port, server.state().port)
        assertEquals(200, post(port, reader, list).first)

        val next = freePort()
        assertEquals(McpListening.On(next), server.setPort(next).listening)
        assertEquals(200, post(next, reader, list).first)
        assertThrows(ConnectException::class.java) { post(port, reader, list) }
        assertEquals(McpProblem.PORT, assertThrows(McpInputException::class.java) { server.setPort(80) }.problem)
    }

    @Test fun `a port that is taken at the start is reported and can be changed`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { taken ->
            server.setPort(taken.localPort)
            switchOn()
            assertTrue(server.state().listening is McpListening.Failed)
            val next = freePort()
            assertEquals(McpListening.On(next), server.setPort(next).listening)
        }
    }

    @Test fun `switching the feature off frees the port and keeps the tokens`() {
        val port = start()
        val reader = token(McpPermission.READ_CONTACTS)
        val list = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        disable(server)
        assertEquals(McpListening.Off, server.state().listening)
        assertThrows(ConnectException::class.java) { post(port, reader, list) }
        assertEquals(1, server.state().tokens.size)
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close()
    }

    @Test fun `a draft the screen would refuse is refused by the host too`() {
        fun problem(draft: McpTokenDraft) = assertThrows(McpInputException::class.java) { server.create(draft) }.problem
        assertEquals(McpProblem.NAME, problem(McpTokenDraft("  ")))
        assertEquals(McpProblem.NAME, problem(McpTokenDraft("n".repeat(McpLimits.MAX_NAME_CHARS + 1))))
        assertEquals(McpProblem.LIMIT, problem(McpTokenDraft("client", limit = 0)))
        assertEquals(McpProblem.LIMIT, problem(McpTokenDraft("client", limit = McpLimits.MAX_REQUESTS + 1)))
        val many = (0..McpLimits.MAX_CONVERSATIONS).map { "c-$it" }.toSet()
        assertEquals(McpProblem.TOO_MANY_CONVERSATIONS, problem(McpTokenDraft("client", conversations = many)))
        assertTrue(server.state().tokens.isEmpty())

        // A new token starts with no permission and no chat.
        val made = server.create(McpTokenDraft(" client ")).state.tokens.single()
        assertEquals("client", made.name)
        assertTrue(made.permissions.isEmpty())
        assertFalse(made.allowAll)
        repeat(McpLimits.MAX_TOKENS - 1) { server.create(McpTokenDraft("client $it")) }
        assertEquals(McpProblem.TOO_MANY_TOKENS, problem(McpTokenDraft("one more")))
    }

    @Test fun `only the streak count, the picture uploader and the raw request path are optional among the host targets`() {
        assertFalse(server.unusable(listOf(ContactQueries.STREAK_DAYS)))
        assertFalse(server.unusable(listOf(ChatUploadQueries.UPLOAD_IMAGE, ChatUploadQueries.UPLOAD_ERROR_CODE, ContactQueries.STREAK_DAYS)))
        assertFalse(server.unusable(listOf(ProtocolQueries.RAW_DISPATCH, ProtocolQueries.REQUEST_FIELD)))
        assertTrue(server.unusable(listOf(ChatUploadQueries.UPLOAD_IMAGE, ContactQueries.FRIENDS)))
        assertTrue(server.unusable(listOf(ContactQueries.FRIENDS)))
        assertTrue(server.unusable(listOf(ContactQueries.STREAK_DAYS, ContactQueries.SINGLE_CONVERSATION_ID)))
    }
}

// The mark-all step the MCP server and the message tab menu share, against the stand-ins for the host's model lookup.
class ReadMarkerTest {
    private val loader = checkNotNull(javaClass.classLoader)
    private val model = ConversationListModel().apply { uid = 42 }
    private val marker = ReadMarker(loader, ConversationModels(loader))

    @After fun dropAccountModel() { testConversationModel = null }

    @Test fun `only the model of the account it was asked for is marked`() {
        testConversationModel = model
        marker.markAll(42)
        assertEquals(1, model.marked)
        assertThrows(IllegalStateException::class.java) { marker.markAll(43) }
        model.failure = IllegalArgumentException("closed")
        assertThrows(IllegalStateException::class.java) { marker.markAll(42) }
        testConversationModel = null
        assertThrows(IllegalStateException::class.java) { marker.markAll(42) }
        assertEquals(1, model.marked)
    }
}
