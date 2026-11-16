package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.HostFriend
import io.github.nuke.oss.app.shared.settings.MemoryFeatureMemory
import io.github.nuke.oss.app.tiktok.feature.chat.claims.AutoSparkClaim
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkClaim
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkConversation
import io.github.nuke.oss.app.tiktok.feature.chat.claims.streakFriends
import io.github.nuke.oss.core.Logger
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSparkClaimTest : HookHarness() {
    private val transport = FakeSparkTransport(account = 42)
    private val memory = MemoryFeatureMemory()
    private val chat = SparkConversation("0:1:7:42", "555", 1)
    private var chats: () -> List<SparkConversation> = { listOf(chat) }
    private var account: Long? = 42L
    private var now = at("2026-11-15T21:30:00Z")
    private var asked: Set<Long>? = null

    private fun at(time: String) = Instant.parse(time).toEpochMilli()

    private fun feature(vararg saved: Pair<String, Any>) = AutoSparkClaim(
        values(AutoSparkClaim.ID, AutoSparkClaim.OPTIONS, *saved), memory, Logger {},
        { account ?: throw IllegalStateException("The IM SDK is not logged in") },
        { friends, _ ->
            asked = friends
            SparkClaim(transport) { chats() }
        },
        { now }, { ZoneOffset.UTC }, { AutoCloseable {} },
    )

    private fun earned() {
        transport.gets += sparkList(sparkTask())
        transport.posts += 0L
    }

    @Test fun `a round after the daily time claims once and waits for the next day`() {
        val feature = feature()
        enable(feature)
        earned()
        assertEquals(at("2026-11-16T21:00:00Z") - now, feature.round())
        assertEquals(1, transport.bodies.size)
        // The day is read back from the feature's memory, so a restarted host does not claim again.
        assertTrue(memory.read(AutoSparkClaim.ID, AutoSparkClaim.LAST_RUN)!!.endsWith(":${now / 86_400_000}"))
        assertEquals(at("2026-11-16T21:00:00Z") - now, feature.round())
        assertEquals(1, transport.bodies.size)
        now = at("2026-11-16T21:00:00Z")
        earned()
        feature.round()
        assertEquals(2, transport.bodies.size)
    }

    @Test fun `nothing is asked before the daily time`() {
        now = at("2026-11-15T20:59:00Z")
        val feature = feature()
        enable(feature)
        assertEquals(60_000L, feature.round())
        assertTrue(transport.urls.isEmpty())
    }

    @Test fun `the chosen time and friends are the ones used`() {
        now = at("2026-11-15T08:29:00Z")
        val feature = feature(AutoSparkClaim.FRIENDS to listOf("7", "9"), AutoSparkClaim.HOUR to 8, AutoSparkClaim.MINUTE to 30)
        enable(feature)
        assertEquals(setOf(7L, 9L), asked)
        assertEquals(60_000L, feature.round())
        now = at("2026-11-15T08:30:00Z")
        earned()
        feature.round()
        assertEquals(1, transport.bodies.size)
    }

    @Test fun `a run that found no loaded chat is tried again later the same day`() {
        chats = { emptyList() }
        val feature = feature()
        enable(feature)
        assertEquals(AutoSparkClaim.RETRY_MILLIS, feature.round())
        assertNull(memory.read(AutoSparkClaim.ID, AutoSparkClaim.LAST_RUN))
        chats = { listOf(chat) }
        earned()
        now += 60_000
        assertEquals(AutoSparkClaim.RETRY_MILLIS - 60_000, feature.round())
        assertTrue(transport.bodies.isEmpty())
        now += AutoSparkClaim.RETRY_MILLIS
        feature.round()
        assertEquals(1, transport.bodies.size)
    }

    @Test fun `a run that reached no reward list is tried again and one that was answered is not`() {
        transport.failGet = IOException("offline")
        val feature = feature()
        enable(feature)
        assertEquals(AutoSparkClaim.RETRY_MILLIS, feature.round())
        assertNull(memory.read(AutoSparkClaim.ID, AutoSparkClaim.LAST_RUN))
        transport.failGet = null
        transport.gets += sparkList()
        now += AutoSparkClaim.RETRY_MILLIS
        feature.round()
        val requests = transport.urls.size
        feature.round()
        assertEquals(requests, transport.urls.size)
    }

    @Test fun `contacts that cannot be read are tried again`() {
        chats = { throw HostContactsUnavailable("not located") }
        val feature = feature()
        enable(feature)
        assertEquals(AutoSparkClaim.RETRY_MILLIS, feature.round())
        assertTrue(transport.urls.isEmpty())
    }

    @Test fun `a submission whose outcome is unknown is not repeated that day`() {
        transport.gets += sparkList(sparkTask())
        transport.posts += IOException("timed out")
        val feature = feature()
        enable(feature)
        feature.round()
        now += AutoSparkClaim.RETRY_MILLIS
        feature.round()
        assertEquals(1, transport.bodies.size)
    }

    @Test fun `the remembered day belongs to one account and holds no user ID`() {
        val feature = feature()
        enable(feature)
        earned()
        feature.round()
        assertTrue("42" != memory.read(AutoSparkClaim.ID, AutoSparkClaim.LAST_RUN)!!.substringBefore(':'))
        account = 43
        transport.account = 43
        earned()
        feature.round()
        assertEquals(2, transport.bodies.size)
    }

    @Test fun `without a signed-in account nothing is asked until it signs in`() {
        account = null
        val feature = feature()
        enable(feature)
        assertEquals(15_000L, feature.round())
        assertTrue(transport.urls.isEmpty())
    }

    @Test fun `a turned-off feature does not claim`() {
        val feature = feature()
        enable(feature)
        disable(feature)
        feature.round()
        assertTrue(transport.urls.isEmpty())
    }

    @Test fun `a friend ID that does not read keeps the feature from installing`() {
        failsToInstall(feature(AutoSparkClaim.FRIENDS to listOf("7", "x")), "valid user ID")
    }

    @Test fun `chosen friends narrow the friends with a streak and none chosen keeps them all`() {
        fun friend(uid: String, days: Int?) = HostFriend(uid, uid, null, days, "0:1:$uid:42")
        val contacts = HostContacts { HostContactList(listOf(friend("7", 3), friend("8", 5), friend("9", 0), friend("42", 9)), emptyList()) }
        assertEquals(listOf("7", "8"), streakFriends(contacts, 42).map { it.first })
        assertEquals(listOf("8"), streakFriends(contacts, 42, setOf(8L, 9L)).map { it.first })
    }
}
