import com.kernelpack.KernelPack
import com.kernelpack.PackRequest
import com.kernelpack.profile.BaselineRegistry
import com.kernelpack.profile.BaselineScheme
import com.kernelpack.vivo.VrKoProbe
import com.kernelpack.vivo.VrKoTagPatcher
import java.io.File

fun main(args: Array<String>) {
    val boot = File(args[0]).readBytes()
    val vendorBoot = File(args[1]).readBytes()
    val base = File(args[2]).readBytes()
    val outDir = File(args.getOrElse(3) { "out" }).apply { mkdirs() }

    val probe = VrKoProbe.probe(vendorBoot)
    println("vr.ko: path=${probe.modulePath} size=${probe.moduleSize} sha=${probe.moduleSha256Prefix} tagA=0x%02x tagB=0x%02x".format(probe.tagA, probe.tagB))

    val tagPatch = VrKoTagPatcher.patch(base, probe.tagA)
    println("tag patch: changed=${tagPatch.changed} old=0x%02x new=0x%02x sites=${tagPatch.patchedSites} anchors=${tagPatch.anchorSites}".format(
        tagPatch.oldTagA, tagPatch.newTagA, tagPatch.patchedSites, tagPatch.anchorSites))

    val result = KernelPack.pack(
        PackRequest(
            bootImage = boot,
            baseLibrary = base,
            baseline = BaselineRegistry.BASELINE_6_1,
            scheme = BaselineScheme.VIVO,
            log = { println("[log] $it") },
        )
    )

    println("===== summary =====")
    print(result.summary())
    println("gate=$result.gate")
    println("warnings=${result.warnings}")

    File(outDir, "kallsyms.txt").bufferedWriter().use { w ->
        for (sym in result.analysis.symbols) {
            w.write("%016x %c %s%n".format(sym.address, sym.type, sym.name))
        }
    }
    val packed = result.packedLibrary
    if (packed != null) File(outDir, "payload-vivo.so").writeBytes(packed)
    File(outDir, "offsets.json").writeText(result.offsetsJson)
    File(outDir, "target.h").writeText(result.targetHeader)
    println("packed=${packed?.size}")
    if (packed != null) verify(File(outDir, "payload-vivo.so"))
}

private fun verify(path: java.io.File) {
    val bytes = path.readBytes()
    val r = com.kernelpack.vivo.VrKoPayloadCheck.check(bytes)
    println("verify: hasBypass=${r.hasBypass} status=${r.status} detagLog=${r.evidence.detagLogMarker} tagBAdd=${r.evidence.tagBAddSites} andNot0x400=${r.evidence.syscallTpFlagClearSites} execBytes=${r.evidence.executableBytes}")
}
