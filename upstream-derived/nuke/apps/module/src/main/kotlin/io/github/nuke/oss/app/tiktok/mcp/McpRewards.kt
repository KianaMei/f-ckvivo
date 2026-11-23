package io.github.nuke.oss.app.tiktok.mcp

import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessClaim
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessTarget
import io.github.nuke.oss.app.tiktok.feature.chat.claims.ClosenessTransport
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkClaim
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkConversations
import io.github.nuke.oss.app.tiktok.feature.chat.claims.SparkTransport

// What claiming in one chat came to, counted as the claims count. halt says that nothing more of this kind may be
// asked in this run.
internal class RewardCounts(
    val scanned: Int,
    val claimed: Int,
    val already: Int,
    val failed: Int,
    val unknown: Int,
    val unavailable: Int,
    val details: List<String>,
    val halt: Boolean = false,
)

// One private chat that may hold a reward. claim reads and claims there, on the calling thread, and stops when
// canSubmit says so.
internal class RewardTarget(val conversation: String, val claim: (canSubmit: () -> Boolean) -> RewardCounts)

// One kind of reward as the MCP server claims it: the chats among the given ones that may hold it, for the account the
// run was started for. A lookup that cannot be made throws, since nothing was asked then.
internal fun interface RewardSource {
    fun targets(account: Long, conversations: Set<String>): List<RewardTarget>
}

// Spark rewards, with the rules of the message tab's own claim: only loaded private chats with a friend whose streak
// is positive, each claimed by SparkClaim on its own so that a run can stop between two chats.
internal fun sparkRewards(transport: SparkTransport, loaded: SparkConversations) = RewardSource { account, conversations ->
    loaded.load(account).filter { it.id in conversations }.map { chat ->
        RewardTarget(chat.id) { canSubmit ->
            val report = SparkClaim(transport) { listOf(chat) }.claimAll(account, canSubmit)
            RewardCounts(report.scanned, report.claimed, report.already, report.failed, report.unknown, report.unavailable, report.details)
        }
    }
}

// Pet closeness, over the friends whose streak is positive. A submission that went out without an answer ends the run,
// as it ends ClosenessClaim's own batch: no other pet request may overlap an unfinished write.
internal fun closenessRewards(transport: ClosenessTransport, friends: (account: Long) -> List<ClosenessTarget>) = RewardSource { account, conversations ->
    friends(account).filter { it.conversationId in conversations }.distinctBy { it.conversationId }.map { friend ->
        RewardTarget(friend.conversationId) { canSubmit ->
            val report = ClosenessClaim(transport).claimAll(account, listOf(friend), canSubmit)
            RewardCounts(
                report.scanned, report.claimed, report.already, report.failed, report.unknown, report.unavailable, report.details,
                halt = report.unknown > 0,
            )
        }
    }
}
