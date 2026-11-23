package io.github.nuke.oss.app.tiktok.feature.chat.claims

import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.FeatureMemory
import io.github.nuke.oss.app.shared.settings.FriendsOption
import io.github.nuke.oss.app.shared.settings.HostContacts
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ContactQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ConversationModels
import io.github.nuke.oss.app.tiktok.sdk.chat.ImAccount
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinContacts
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

// Claims earned spark rewards on a schedule: once a day, from the chosen time, it runs SparkClaim, the claim of the
// message tab's menu, over the chosen friends, or over every friend with a positive streak when none is chosen. The
// rules are SparkClaim's own: only loaded private chats, only rewards already earned, no submission sent twice.
// A worker thread sleeps until the time, in steps of at most a minute so a changed clock is noticed, and stops with
// the feature. A host started after the time catches up the current day. The day counts as done once the server
// answered for at least one chat, and is remembered per account so a restart does not claim again; a run that
// reached nothing (no chat loaded yet, contacts or network unavailable) is tried again later the same day.
// Nothing runs while the host process is not running. Logs carry counts, never a friend or an account.
@HookEntry
internal class AutoSparkClaim(
    private val options: OptionValues,
    private val memory: FeatureMemory,
    private val logger: Logger,
    private val accountId: () -> Long,
    private val claims: (friends: Set<Long>, active: () -> Boolean) -> SparkClaim,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val startWorker: (task: () -> Unit) -> AutoCloseable = ::daemon,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    // The friend list with its streak days, which decides the chats a reward may be asked for.
    override val dexQueries = listOf(ContactQueries.FRIENDS, ContactQueries.SINGLE_CONVERSATION_ID, ContactQueries.STREAK_DAYS)
    @Volatile private var active = false
    private var claim: SparkClaim? = null
    private var hour = 0
    private var minute = 0
    private var retryAt = 0L

    override fun install(scope: FeatureScope) {
        val picked = options.friends(FRIENDS).distinct()
        val friends = picked.mapNotNull { uid -> uid.toLongOrNull()?.takeIf { it > 0 } }.toSet()
        // An ID that does not read must not turn a narrowed choice into every friend.
        require(friends.size == picked.size) { "A chosen friend has no valid user ID" }
        require(friends.size <= MAX_FRIENDS) { "Pick at most $MAX_FRIENDS friends" }
        hour = options.number(HOUR).toInt()
        minute = options.number(MINUTE).toInt()
        active = true
        claim = claims(friends) { active }
        val worker = startWorker(::run)
        scope.own(AutoCloseable {
            active = false
            worker.close()
        })
    }

    private fun run() {
        try {
            // The host loads its chats after it starts; a catch-up run before that would find none.
            Thread.sleep(START_DELAY_MILLIS)
            while (active) Thread.sleep(round().coerceIn(MIN_SLEEP_MILLIS, MAX_SLEEP_MILLIS))
        } catch (_: InterruptedException) {
            // The feature was turned off.
        }
    }

    // One pass of the worker: claims when today's run is due and answers how long to wait.
    internal fun round(): Long {
        val claim = claim ?: return MAX_SLEEP_MILLIS
        if (!active) return MAX_SLEEP_MILLIS
        val account = try {
            accountId()
        } catch (_: IllegalStateException) {
            return ACCOUNT_RETRY_MILLIS
        }
        val now = clock()
        val zone = zone()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun timeOn(daysAhead: Long) = today.plusDays(daysAhead).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        val stamp = "${accountKey(account)}:${today.toEpochDay()}"
        if (memory.read(ID, LAST_RUN) == stamp) return timeOn(1) - now
        if (now < timeOn(0)) return timeOn(0) - now
        // A retry time further away than one delay was set before the clock moved back and no longer holds.
        if (now < retryAt && retryAt - now <= RETRY_MILLIS) return retryAt - now
        try {
            val report = claim.claimAll(account) { active }
            logger.log("feature=$ID round scanned=${report.scanned} claimed=${report.claimed} already=${report.already} " +
                "failed=${report.failed} unknown=${report.unknown} unavailable=${report.unavailable}")
            // A later reward list can only fail to read after a submission, so these counts tell an answer apart.
            val answered = report.scanned > report.unavailable || report.claimed + report.already + report.failed + report.unknown > 0
            if (answered) {
                memory.write(ID, LAST_RUN, stamp)
                return timeOn(1) - clock()
            }
        } catch (failure: HostContactsUnavailable) {
            logger.log("feature=$ID round_failed ${failure.javaClass.simpleName}")
        } catch (failure: ReflectiveOperationException) {
            logger.log("feature=$ID round_failed ${(failure.cause ?: failure).javaClass.simpleName}")
        } catch (failure: RuntimeException) {
            // A lookup that could not be made asked the server nothing.
            logger.log("feature=$ID round_failed ${failure.javaClass.simpleName}")
        }
        retryAt = clock() + RETRY_MILLIS
        return RETRY_MILLIS
    }

    // The account is remembered as a hash, so the stored value carries no user ID.
    private fun accountKey(account: Long): String =
        MessageDigest.getInstance("SHA-256").digest(account.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    companion object : TikTokFeature {
        const val ID = "AutoSparkClaim"
        const val FRIENDS = "friends"
        const val HOUR = "hour"
        const val MINUTE = "minute"
        val OPTIONS = listOf(
            FriendsOption(FRIENDS),
            NumberOption(HOUR, default = 21, min = 0, max = 23),
            NumberOption(MINUTE, default = 0, min = 0, max = 59),
        )
        const val LAST_RUN = "last-run"
        const val RETRY_MILLIS = 10 * 60_000L
        private const val MAX_FRIENDS = 500
        private const val START_DELAY_MILLIS = 30_000L
        private const val ACCOUNT_RETRY_MILLIS = 15_000L
        private const val MIN_SLEEP_MILLIS = 1_000L
        private const val MAX_SLEEP_MILLIS = 60_000L

        private fun daemon(task: () -> Unit): AutoCloseable {
            val thread = Thread(task).apply { isDaemon = true; name = "nuke-spark-claim" }
            thread.start()
            return AutoCloseable { thread.interrupt() }
        }

        // The claim's host access is built at install: the features are created before the Dex queries are located.
        override fun create(services: TikTokFeatureServices) = with(services) {
            val account = ImAccount(hostClassLoader)
            listOf(AutoSparkClaim(options(ID, OPTIONS), memory, logger, account::id, { friends, active ->
                val contacts = HostContacts { DouyinContacts.open(resolver, hostClassLoader, logger, ID).load() }
                SparkClaim(
                    SparkHost(hostClassLoader, account, active),
                    LoadedSparkConversations(contacts, ConversationModels(hostClassLoader), account, hostClassLoader, friends),
                )
            }))
        }
    }
}
