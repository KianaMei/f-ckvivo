package io.github.nuke.oss.app.shared.media

import java.io.ByteArrayOutputStream

// Packs a still and its short video into one Motion Photo the way Google's camera does it: the MP4 is appended to the
// JPEG and an XMP packet in a new APP1 segment says how long the video is, so a viewer can play it from the file.
internal object MotionPhoto {
    private const val SOI = 0xD8
    private const val APP1 = 0xE1
    private const val SOS = 0xDA
    private const val MARKER = 0xFF

    fun wrap(jpeg: ByteArray, video: ByteArray, presentationUs: Long = 0L): ByteArray {
        require(jpeg.size >= 2 && jpeg[0].toInt() and 0xFF == MARKER && jpeg[1].toInt() and 0xFF == SOI) { "Not a JPEG" }
        require(video.isNotEmpty()) { "The video is empty" }
        val xmp = xmp(video.size, presentationUs)
        val out = ByteArrayOutputStream(jpeg.size + video.size + xmp.size + 8)
        // SOI, then the XMP segment, then the rest of the JPEG exactly as it was, then the video.
        out.write(jpeg, 0, 2)
        out.write(MARKER)
        out.write(APP1)
        val length = xmp.size + 2
        out.write(length shr 8 and 0xFF)
        out.write(length and 0xFF)
        out.write(xmp)
        out.write(jpeg, 2, jpeg.size - 2)
        out.write(video)
        return out.toByteArray()
    }

    // Where the video starts, or null when the file carries no such XMP packet; used to check a file before saving.
    fun videoOffset(photo: ByteArray): Int? {
        val packet = xmpOf(photo) ?: return null
        val text = String(packet, Charsets.UTF_8)
        val length = Regex("GCamera:MicroVideoOffset=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (length <= 0 || length > photo.size) return null
        return photo.size - length
    }

    private fun xmp(videoLength: Int, presentationUs: Long): ByteArray {
        val text = XMP
            .replace("{{offset}}", videoLength.toString())
            .replace("{{presentation}}", presentationUs.coerceAtLeast(0L).toString())
        return text.toByteArray(Charsets.UTF_8)
    }

    // The APP1 payload that starts with the XMP namespace, skipping the JFIF/Exif segments a camera writes.
    private fun xmpOf(photo: ByteArray): ByteArray? {
        var index = 2
        while (index + 4 <= photo.size) {
            if (photo[index].toInt() and 0xFF != MARKER) return null
            val marker = photo[index + 1].toInt() and 0xFF
            if (marker == SOS) return null
            val length = (photo[index + 2].toInt() and 0xFF shl 8) or (photo[index + 3].toInt() and 0xFF)
            if (length < 2 || index + 2 + length > photo.size) return null
            if (marker == APP1) {
                val start = index + 4
                val end = index + 2 + length
                val payload = photo.copyOfRange(start, end)
                val prefix = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII)
                if (payload.size > prefix.size && prefix.indices.all { payload[it] == prefix[it] }) {
                    return payload.copyOfRange(prefix.size, payload.size)
                }
            }
            index += 2 + length
        }
        return null
    }

    // The namespaces and the three camera fields Google's format defines; the packet is padded as the XMP rules ask.
    private val XMP = """
        http://ns.adobe.com/xap/1.0/
        <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="nuke">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about=""
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            GCamera:MicroVideo="1"
            GCamera:MicroVideoVersion="1"
            GCamera:MicroVideoOffset="{{offset}}"
            GCamera:MicroVideoPresentationTimestampUs="{{presentation}}"/>
         </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
        
    """.trimIndent()
}
