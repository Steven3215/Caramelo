package com.steven3215.sportsbite

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs

internal object SportsbiteProtocol {
    const val MAX_BYTES = 64 * 1024 * 1024
    fun webUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme in setOf("http", "https") && !u.host.isNullOrBlank() && u.userInfo == null
    }.getOrDefault(false)
    fun eventKey(url: String, base: String): String = itemKey(url, base, "event")
    fun itemKey(url: String, base: String, kind: String): String {
        val u = URI(url)
        val b = URI(base)
        require(u.scheme == b.scheme && u.host == b.host && u.port == b.port && u.userInfo == null)
        require(u.path.startsWith("/$kind/"))
        val key = URLDecoder.decode(u.rawPath.removePrefix("/$kind/"), "UTF-8")
        require(Regex("[A-Za-z0-9_-]{1,240}").matches(key))
        return key
    }
    @OptIn(ExperimentalEncodingApi::class)
    fun source(html: String, base: String = "https://sportsbite.org"): String? {
        val encoded = Regex("""(?:var|let|const)\s+source\s*=\s*(?:window\.)?atob\(\s*["']([^"']+)["']\s*\)""")
            .find(html)?.groupValues?.get(1)
        val value = if (encoded != null) runCatching { Base64.Default.decode(encoded).toString(Charsets.UTF_8) }.getOrNull()
            else Regex("""(?:var|let|const)\s+source\s*=\s*["']((?:https?://|/)[^"']+)["']""").find(html)?.groupValues?.get(1)
        return value?.let { runCatching { URI(base).resolve(it).toString() }.getOrNull() }?.takeIf { webUrl(it) }
    }
    // The site's embeds can return its SPA shell. These public first-party routes are
    // verified as HLS before being returned; never offer an HTML response as a stream.
    fun playlistRoute(embed: String, base: String): String? = runCatching {
        val u = URI(embed)
        val b = URI(base)
        require(u.scheme == b.scheme && u.host == b.host && u.port == b.port && u.userInfo == null)
        val route = Regex("^/(247|t247|cv|mx|tlive|lol|dk)-embed/embed/([A-Za-z0-9_-]{1,240})$").matchEntire(u.path)
        if (route != null) "$base/${route.groupValues[1]}/${route.groupValues[2]}" +
            if (route.groupValues[1] in setOf("mx", "tlive", "lol", "dk")) "?v=high" else ""
        else Regex("^/selfhost/(?:embed|e)/(s-[A-Za-z0-9_-]{1,240})$").matchEntire(u.path)?.let {
            "$base/selfhost/play/${it.groupValues[1]}?raw=1"
        }
    }.getOrNull()
    fun playbackUrl(value: String, base: String): String {
        val u = URI(value)
        return if (u.host == URI(base).host && u.path.startsWith("/selfhost/"))
            "$base${u.rawPath}?raw=1" else value
    }
    fun playlist(text: String) = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")
    fun looksLikeTs(b: ByteArray, at: Int = 0, length: Int = b.size - at): Boolean =
        at >= 0 && length >= 188 * 3 && at.toLong() + length <= b.size &&
            b[at] == 0x47.toByte() && b[at + 188] == 0x47.toByte() && b[at + 376] == 0x47.toByte()
    private fun be(b: ByteArray, at: Int): Int {
        require(at >= 0 && at + 4 <= b.size)
        val value = (0..3).fold(0L) { n, i -> (n shl 8) or (b[at + i].toLong() and 255) }
        require(value <= Int.MAX_VALUE)
        return value.toInt()
    }
    fun bounded(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            require(out.size().toLong() + count <= limit) { "Media transport exceeds size limit" }
            out.write(chunk, 0, count)
        }
        return out.toByteArray()
    }
    fun unwrap(input: ByteArray): ByteArray? = runCatching {
        require(input.size <= MAX_BYTES)
        if (looksLikeTs(input)) return input
        val png = input.size >= 8 && input.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))
        if (!png) {
            for (at in 0 until minOf(65536, input.size - 376))
                if (looksLikeTs(input, at)) return input.copyOfRange(at, input.size)
            return null
        }
        var offset = 8
        var width = 0
        var height = 0
        var channels = 0
        val compressed = ByteArrayOutputStream()
        while (offset + 12 <= input.size) {
            val length = be(input, offset)
            require(length.toLong() + offset + 12 <= input.size)
            val start = offset + 8
            val type = input.copyOfRange(offset + 4, start).toString(Charsets.US_ASCII)
            if (looksLikeTs(input, start, length)) return input.copyOfRange(start, start + length)
            when (type) {
                "IHDR" -> {
                    require(length == 13)
                    width = be(input, start)
                    height = be(input, start + 4)
                    require(width > 0 && height > 0 && input[start + 8].toInt() == 8 &&
                        input[start + 10].toInt() == 0 && input[start + 11].toInt() == 0 && input[start + 12].toInt() == 0)
                    channels = when (input[start + 9].toInt()) { 2 -> 3; 6 -> 4; else -> error("Unsupported PNG color type") }
                    require(width.toLong() * height * channels + height <= MAX_BYTES)
                }
                "IDAT" -> compressed.write(input, start, length)
                "IEND" -> {
                    val after = offset + 12 + length
                    if (looksLikeTs(input, after)) return input.copyOfRange(after, input.size)
                    break
                }
            }
            offset += length + 12
        }
        require(width > 0 && height > 0 && channels > 0 && compressed.size() > 0)
        val stride = width * channels
        val rawSize = height * (stride + 1)
        val raw = InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { bounded(it, rawSize) }
        require(raw.size == rawSize)
        val rgb = ByteArray(width * height * 3)
        var previous = ByteArray(stride)
        var src = 0
        var dest = 0
        repeat(height) {
            val filter = raw[src++].toInt() and 255
            require(filter in 0..4)
            val row = ByteArray(stride)
            for (i in row.indices) {
                val left = if (i >= channels) row[i - channels].toInt() and 255 else 0
                val up = previous[i].toInt() and 255
                val corner = if (i >= channels) previous[i - channels].toInt() and 255 else 0
                val prediction = when (filter) {
                    0 -> 0; 1 -> left; 2 -> up; 3 -> (left + up) / 2
                    else -> {
                        val p = left + up - corner
                        val a = abs(p - left); val b = abs(p - up); val c = abs(p - corner)
                        if (a <= b && a <= c) left else if (b <= c) up else corner
                    }
                }
                row[i] = ((raw[src++].toInt() and 255) + prediction).toByte()
            }
            for (x in 0 until width) for (c in 0..2) rgb[dest++] = row[x * channels + c]
            previous = row
        }
        require(rgb.size >= 12 && rgb.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "TIKTIKPX")
        val length = be(rgb, 8)
        require(length > 0 && 12L + length <= rgb.size)
        val ts = GZIPInputStream(ByteArrayInputStream(rgb, 12, length)).use { bounded(it) }
        require(looksLikeTs(ts))
        ts
    }.getOrNull()
}
