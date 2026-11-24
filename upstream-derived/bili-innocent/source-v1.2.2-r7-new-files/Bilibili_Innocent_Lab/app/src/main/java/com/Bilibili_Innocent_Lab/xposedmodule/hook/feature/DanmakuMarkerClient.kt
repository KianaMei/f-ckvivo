package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorVideoId
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 只读弹幕抽样：按 360s 分段拉取 `seg.so`，在本地找「同一句话被至少 3 个不同发送者
 * 在 5 秒内重复」的刷屏点，作为进度条标记。全程不写任何宿主/设备文件。
 */
internal data class DanmakuMarkerScan(val video: SponsorVideoId, val durationMs: Long,
    val points: List<Long>, val segments: Int, val danmaku: Int)

internal object DanmakuMarkerClient {
    private const val SEGMENT_SECONDS = 360
    private const val CLUSTER_MS = 5_000L
    private const val MIN_SENDERS = 3
    private const val MAX_POINTS = 120
    private const val MAX_SEGMENT_BYTES = 4_000_000

    fun scan(video: SponsorVideoId, trace: (String) -> Unit = {}): DanmakuMarkerScan? {
        val meta = runCatching { JSONObject(get("https://api.bilibili.com/x/web-interface/view?bvid=${video.bvid}")) }
            .getOrElse { trace("view-failed:${it.javaClass.simpleName}"); return null }
        if (meta.optInt("code") != 0) { trace("view-code:${meta.optInt("code")}"); return null }
        val data = meta.optJSONObject("data") ?: return null
        val aid = data.optLong("aid"); if (aid <= 0) return null
        val pages = data.optJSONArray("pages") ?: return null
        var duration = 0
        for (index in 0 until pages.length()) {
            val page = pages.optJSONObject(index) ?: continue
            if (page.optLong("cid") == video.cid) { duration = page.optInt("duration"); break }
        }
        if (duration <= 0) { trace("duration-missing"); return null }
        val segments = (duration + SEGMENT_SECONDS - 1) / SEGMENT_SECONDS
        if (segments <= 0 || segments > 40) return null
        val entries = ArrayList<Triple<Long, String, String>>()
        for (segment in 0 until segments) {
            val url = "https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=$aid&pid=${video.cid}&segment_index=${segment + 1}"
            val body = runCatching { getBytes(url) }.getOrElse { trace("seg-${segment}-failed"); continue }
            runCatching { parse(body, entries) }.onFailure { trace("seg-${segment}-parse") }
        }
        trace("danmaku=${entries.size} segments=$segments")
        if (entries.isEmpty()) return null
        val points = consensus(entries, duration.toLong() * 1000)
        trace("points=${points.size}")
        return DanmakuMarkerScan(video, duration.toLong() * 1000, points, segments, entries.size)
    }

    /** 同一文本 key 在同一 5s 桶里被至少 3 个不同 midHash 发送即为刷屏点。 */
    internal fun consensus(entries: List<Triple<Long, String, String>>, durationMs: Long): List<Long> {
        val groups = HashMap<Pair<String, Long>, MutableSet<String>>()
        for ((progress, content, sender) in entries) {
            if (progress <= 0 || progress >= durationMs) continue
            val text = content.trim().replace(Regex("\\s+"), "")
            if (text.length !in 2..64 || text.all { it.isDigit() || it in PUNCTUATION }) continue
            val bucket = progress / CLUSTER_MS
            groups.getOrPut(text to bucket) { HashSet() }.add(sender)
        }
        val bursts = ArrayList<Long>()
        for ((key, senders) in groups) {
            if (senders.size < MIN_SENDERS) continue
            bursts += key.second * CLUSTER_MS
        }
        if (bursts.isEmpty()) return emptyList()
        bursts.sort()
        val merged = ArrayList<Long>()
        for (time in bursts) {
            if (merged.isEmpty() || time - merged.last() > CLUSTER_MS * 2) merged += time
        }
        return merged.filter { it in 60_000L until (durationMs - 5_000L) }
            .distinct()
            .take(MAX_POINTS)
    }

    private const val PUNCTUATION = "，。！？、；：,.!?;:~-_—"

    private fun parse(body: ByteArray, output: MutableList<Triple<Long, String, String>>) {
        var position = 0
        while (position < body.size) {
            val key = varint(body, position) ?: return
            position = key.second
            val field = (key.first shr 3).toInt()
            when ((key.first and 7).toInt()) {
                0 -> position = varint(body, position)?.second ?: return
                1 -> position += 8
                2 -> {
                    val length = varint(body, position)?.first?.toInt() ?: return
                    position = varint(body, position)?.second ?: return
                    if (length < 0 || position + length > body.size) return
                    if (field == 1) runCatching { elem(body, position, length, output) }
                    position += length
                }
                5 -> position += 4
                else -> return
            }
        }
    }

    private fun elem(body: ByteArray, start: Int, length: Int, output: MutableList<Triple<Long, String, String>>) {
        var progress = 0L; var text = ""; var sender = ""
        var position = start
        val end = start + length
        while (position < end) {
            val key = varint(body, position) ?: return
            position = key.second
            when ((key.first and 7).toInt()) {
                0 -> {
                    val value = varint(body, position) ?: return
                    position = value.second
                    if ((key.first shr 3).toInt() == 2) progress = value.first
                }
                2 -> {
                    val size = varint(body, position)?.first?.toInt() ?: return
                    position = varint(body, position)?.second ?: return
                    if (size < 0 || position + size > end) return
                    when ((key.first shr 3).toInt()) {
                        6 -> sender = String(body, position, size, Charsets.UTF_8)
                        7 -> text = String(body, position, size, Charsets.UTF_8)
                    }
                    position += size
                }
                1 -> position += 8
                5 -> position += 4
                else -> return
            }
        }
        if (progress > 0 && text.isNotEmpty()) output += Triple(progress, text, sender.ifEmpty { text })
    }

    private fun varint(body: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L; var shift = 0; var position = start
        while (position < body.size && shift <= 63) {
            val byte = body[position].toInt() and 255
            position++
            value = value or ((byte and 127).toLong() shl shift)
            if (byte and 128 == 0) return value to position
            shift += 7
        }
        return null
    }

    private fun get(address: String): String = getBytes(address).toString(Charsets.UTF_8)

    private fun getBytes(address: String): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 BIL-Marker")
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            if (connection.responseCode != 200) error("http-${connection.responseCode}")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > MAX_SEGMENT_BYTES) error("too-large")
                }
            }
            return output.toByteArray()
        } finally { connection.disconnect() }
    }
}
