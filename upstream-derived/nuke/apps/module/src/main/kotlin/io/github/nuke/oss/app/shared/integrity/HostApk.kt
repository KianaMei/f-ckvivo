package io.github.nuke.oss.app.shared.integrity

import java.io.File
import java.io.IOException
import java.security.MessageDigest

// What the host APK on this device is, as far as the module can tell. A repackaged build keeps the version name and
// code, so the whole file's digest is the only thing that tells it apart from the build the module was verified on.
internal enum class HostVerdict {
    KNOWN,
    UNKNOWN,
    UNREADABLE,
    // The known-good list is empty: the host is not being checked at all.
    UNCONFIGURED;

    val label: String get() = name.lowercase()
}

// The host builds whose whole APK digest this module was verified against. Empty by default: the check reads the
// hundred-megabyte host APK only once someone fills this in, and a filled list is meant to be updated whenever the host
// updates. Digests are lowercase hex SHA-256 of the APK file as installed.
internal object KnownHostApks {
    val DIGESTS: Set<String> = emptySet()
}

internal object HostApk {
    // Streamed in chunks, since the host APK is far too large to read into one array. Null when the file cannot be read,
    // which is reported as its own verdict rather than as a mismatch.
    fun digest(file: File): String? = try {
        if (!file.isFile) null else {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        // SecurityException from a file the process may not read, and anything a broken stream throws.
        null
    }

    fun verdict(digest: String?, known: Set<String>): HostVerdict = when {
        known.isEmpty() -> HostVerdict.UNCONFIGURED
        digest == null -> HostVerdict.UNREADABLE
        known.any { it.equals(digest, ignoreCase = true) } -> HostVerdict.KNOWN
        else -> HostVerdict.UNKNOWN
    }

    private const val BUFFER = 64 * 1024
}
