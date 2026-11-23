package io.github.nuke.oss.app.tiktok.feature.chat.streak

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Whether the signed-in account already spoke in a one-to-one chat on a local day, so a scheduled streak message is not
// sent on top of a message the user wrote by hand. Written from the chat list's row events (a row the host draws as the
// user's own message), read by the streak worker just before it claims a friend. Both live in the host process, and
// nothing here reaches the network: the file only remembers what the host already showed.
internal class SelfSpeech(private val file: File) {
    // day -> conversation IDs the user spoke in that day; the newest days are kept, older ones are dropped.
    fun mark(conversationId: String, day: Long) {
        if (conversationId.isEmpty()) return
        // Called from a chat row hook, so nothing may reach the host: a failed write only costs the duplicate this
        // store exists to avoid.
        try {
            synchronized(this) {
                val days = read()
                if (days[day]?.contains(conversationId) == true) return
                write(days + (day to (days[day] ?: emptySet()) + conversationId))
            }
        } catch (_: java.io.IOException) {
        } catch (_: RuntimeException) {
        }
    }

    fun spoke(conversationId: String?, day: Long): Boolean {
        if (conversationId.isNullOrEmpty()) return false
        return synchronized(this) { read()[day]?.contains(conversationId) == true }
    }

    private fun read(): Map<Long, Set<String>> {
        if (!file.exists()) return emptyMap()
        val lines = try {
            file.readLines()
        } catch (_: java.io.IOException) {
            return emptyMap()
        }
        val days = LinkedHashMap<Long, MutableSet<String>>()
        lines.forEach { line ->
            val space = line.indexOf(' ')
            if (space <= 0) return@forEach
            val day = line.substring(0, space).toLongOrNull() ?: return@forEach
            val conversation = line.substring(space + 1).takeIf(String::isNotEmpty) ?: return@forEach
            days.getOrPut(day) { LinkedHashSet() }.add(conversation)
        }
        return days
    }

    private fun write(days: Map<Long, Set<String>>) {
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the streak speech directory" }
        val encoded = days.entries
            .sortedByDescending { it.key }
            .take(MAX_DAYS)
            .flatMap { (day, conversations) -> conversations.sorted().map { "$day $it" } }
            .joinToString("\n", postfix = "\n")
        val temporary = File(directory, file.name + ".new")
        FileOutputStream(temporary).use { output ->
            output.write(encoded.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        // A plan only ever asks about today, so a handful of days is enough to survive a clock change.
        const val MAX_DAYS = 7
    }
}
