package io.github.nuke.oss.app.shared.media

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Turns a download name template into a file name the gallery accepts. A token is #name# (or {name}) and expands to
// one of the metadata values; a token without a value expands to nothing. The result is one path component: separators,
// wildcards, control characters and the characters Windows refuses are replaced, runs of separators are collapsed, and
// the name is cut to a byte budget rather than a character budget, since the filesystem counts bytes. A template that
// expands to nothing falls back to the time of the download, so a save never ends up without a name.
internal object MediaNaming {
    const val DEFAULT_TEMPLATE = "#author#_#desc#"
    const val DEFAULT_FOLDER = "video"
    // FAT/EXFAT allow 255 bytes; the gallery appends "_1" for a name it has seen, so leave room for that.
    const val MAX_BYTES = 150
    private const val FALLBACK_PREFIX = "media"
    private val TOKEN = Regex("#([A-Za-z][A-Za-z0-9]*)#|\\$?\\{([A-Za-z][A-Za-z0-9]*)\\}")
    private val INVALID = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val SEPARATORS = Regex("[\\s_]+")
    private val REPEATED = Regex("-{2,}|\\.{2,}")

    // The name without its extension; the writer appends the extension of the file it actually saved. index counts
    // from 1 for the second image of a set, and is appended in the host's two-digit style when the template has no
    // index token of its own.
    fun fileName(
        template: String,
        values: Map<String, String>,
        extension: String,
        index: Int = 0,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val tokens = HashMap<String, String>(values.size + 6)
        for ((key, value) in values) tokens[key] = value
        tokens["ext"] = extension
        tokens["index"] = if (index > 0) String.format(Locale.ROOT, "%02d", index) else ""
        tokens["timestamp"] = now.toString()
        val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { this.timeZone = timeZone }
        tokens["date"] = date.format(Date(now))
        val time = SimpleDateFormat("HHmmss", Locale.ROOT).apply { this.timeZone = timeZone }
        tokens["time"] = time.format(Date(now))
        tokens["datetime"] = "${tokens["date"]}_${tokens["time"]}"
        var pattern = template.ifBlank { DEFAULT_TEMPLATE }
        if (index > 0 && !pattern.contains("index")) pattern += "_${tokens["index"]}"
        val expanded = TOKEN.replace(pattern) { match ->
            val key = match.groupValues[1].ifEmpty { match.groupValues[2] }
            tokens[key].orEmpty()
        }
        val cleaned = sanitize(expanded)
        return if (cleaned.isEmpty()) "${FALLBACK_PREFIX}_${now}" else cleaned
    }

    // A single directory under the gallery folder of the media kind. Anything that is not plain words is refused, so a
    // value from the settings can never climb out of the folder the feature owns.
    fun folder(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 40) return DEFAULT_FOLDER
        if (!value.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return DEFAULT_FOLDER
        return if (value == "." || value == "..") DEFAULT_FOLDER else value
    }

    private fun sanitize(input: String): String {
        val replaced = INVALID.replace(input, "_")
        val joined = SEPARATORS.replace(replaced, "_")
        val single = REPEATED.replace(joined) { match -> match.value.take(1) }
        return limit(single.trim('.', '-', '_'))
    }

    // Joins code points while their UTF-8 form fits, so a long Chinese description is cut between characters.
    private fun limit(name: String): String {
        var bytes = 0
        val output = StringBuilder(name.length)
        var offset = 0
        while (offset < name.length) {
            val codePoint = name.codePointAt(offset)
            val width = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + width > MAX_BYTES) break
            output.appendCodePoint(codePoint)
            bytes += width
            offset += Character.charCount(codePoint)
        }
        return output.toString().trimEnd('.', '-', '_')
    }
}
