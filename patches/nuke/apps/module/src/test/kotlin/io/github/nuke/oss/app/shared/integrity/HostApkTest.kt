package io.github.nuke.oss.app.shared.integrity

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostApkTest {
    private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test fun `the digest is the SHA-256 of the file`() {
        val file = Files.createTempFile("host", ".apk").toFile()
        val bytes = "an apk stand-in".toByteArray()
        try {
            file.writeBytes(bytes)
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, HostApk.digest(file))
            assertEquals(64, expected.length)
        } finally {
            file.delete()
        }
    }

    @Test fun `an empty list leaves the host unchecked`() {
        assertEquals(HostVerdict.UNCONFIGURED, HostApk.verdict(digest, emptySet()))
        assertEquals(HostVerdict.UNCONFIGURED, HostApk.verdict(null, emptySet()))
    }

    @Test fun `a listed digest is known however it is written`() {
        assertEquals(HostVerdict.KNOWN, HostApk.verdict(digest, setOf(digest)))
        assertEquals(HostVerdict.KNOWN, HostApk.verdict(digest.uppercase(), setOf(digest)))
    }

    @Test fun `another build is unknown and an unreadable file is its own verdict`() {
        assertEquals(HostVerdict.UNKNOWN, HostApk.verdict(digest, setOf(digest.reversed())))
        assertEquals(HostVerdict.UNREADABLE, HostApk.verdict(null, setOf(digest)))
    }

    @Test fun `a file that does not exist has no digest`() {
        val file = File(Files.createTempDirectory("host").toFile(), "missing.apk")
        assertNull(HostApk.digest(file))
    }

    @Test fun `every shipped digest is lowercase hex, so a filled list compares cleanly`() {
        for (entry in KnownHostApks.DIGESTS) {
            assertEquals(entry.lowercase(), entry)
            assertEquals(64, entry.length)
            assertEquals(true, entry.all { it in "0123456789abcdef" })
        }
    }
}
