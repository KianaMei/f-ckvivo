package io.github.nuke.oss.patcher.patch

import com.android.apksig.ApkSigner
import com.google.gson.JsonParser
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor

class PatchRoundTripTest {
    @get:Rule val privateDirectory = TemporaryFolder()

    @Test fun `signed target patches with current module independent signing and JNI verification`() {
        val original = signedTarget()
        val module = File(requireNotNull(System.getProperty("nuke.patch.module")))
        val reportDirectory = File(requireNotNull(System.getProperty("nuke.patch.report")))
        reportDirectory.deleteRecursively()
        assertTrue(reportDirectory.mkdirs())
        assertEquals(TARGET, PatchApkValidator.target(original).packageName)
        assertEquals("io.github.nuke.oss", PatchApkValidator.module(module).packageName)
        NativePatchFiles.ensureLoaded()
        val moduleReport = JsonParser.parseString(NativePatchFiles.inspectApk(module.path)).asJsonObject
        assertTrue(moduleReport.get("ok").asBoolean)
        assertFalse(moduleReport.get("already_patched").asBoolean)
        val key = LocalPatchKey.loadOrCreate(privateDirectory.newFolder())
        val stages = mutableListOf<String>()
        val output = PatchEngine().patch(original, module, File(reportDirectory, "output"), key, stages::add)
        val report = NativePatchFiles.verifyPatched(original.path, module.path, output.path)
        val value = JsonParser.parseString(report).asJsonObject
        assertTrue(report, value.get("ok").asBoolean)
        assertTrue(value.get("already_patched").asBoolean)
        assertEquals(64, value.get("sha256").asString.length)
        assertTrue(stages.contains("stage:VERIFYING"))
        assertEquals("stage:VERIFYING", stages.last { it.startsWith("stage:") })
        File(reportDirectory, "verification.json").writeText(report)
        File(reportDirectory, "signer-sha256.txt").writeText(key.certificateSha256 + "\n")
        // TemporaryFolder removes the private keys and passwords; only public evidence is retained.
    }

    // The smallest target the engine and the Rust inspection accept: a complete base APK with a manifest and a DEX
    // header, signed so the level-2 signature bypass has an original certificate to embed.
    private fun signedTarget(): File {
        val unsigned = privateDirectory.newFile("target-unsigned.apk")
        ZipOutputStream(unsigned.outputStream()).use { zip ->
            fun write(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            write("AndroidManifest.xml", manifest())
            write("classes.dex", dexHeader())
        }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=NukeApp-OSS Patch Target")
        val now = System.currentTimeMillis()
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(subject, BigInteger.ONE, Date(now - DAY), Date(now + DAY), subject, keyPair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)),
        )
        val signed = File(privateDirectory.root, "target.apk")
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("target", keyPair.private, listOf(certificate)).build()))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(MIN_SDK)
            .build()
            .sign()
        return signed
    }

    private fun manifest(): ByteArray {
        val xml = AxmlWriter()
        xml.ns("android", NS, 0)
        val root = xml.child(null, "manifest")
        root.attr(null, "package", -1, NodeVisitor.TYPE_STRING, TARGET)
        root.attr(NS, "versionCode", 0x0101021b, NodeVisitor.TYPE_FIRST_INT, 1)
        root.attr(NS, "versionName", 0x0101021c, NodeVisitor.TYPE_STRING, "1.0")
        root.child(null, "uses-sdk").apply { attr(NS, "minSdkVersion", 0x0101020c, NodeVisitor.TYPE_FIRST_INT, MIN_SDK); end() }
        root.child(null, "application").apply { attr(NS, "label", 0x01010001, NodeVisitor.TYPE_STRING, "Target"); end() }
        root.end()
        xml.end()
        return xml.toByteArray()
    }

    // A header-only DEX: the patched APK replaces it and the Rust inspection reads only the header fields.
    private fun dexHeader(): ByteArray = ByteBuffer.allocate(DEX_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
        putInt(32, DEX_HEADER_SIZE)
        putInt(36, DEX_HEADER_SIZE)
        putInt(40, 0x12345678)
    }.array()

    companion object {
        private const val NS = "http://schemas.android.com/apk/res/android"
        private const val TARGET = "com.example.target"
        private const val MIN_SDK = 27
        private const val DEX_HEADER_SIZE = 112
        private const val DAY = 24L * 60 * 60 * 1000
    }
}
