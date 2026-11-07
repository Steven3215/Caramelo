package com.caramelo.tracks

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLinkType

internal data class BrowserMedia(
    val url: String,
    val type: ExtractorLinkType,
    val referer: String,
    val headers: Map<String, String>,
    val manifest: String? = null,
    val manifestUrl: String = url
)

/** Resolve only media requested by the embed's ordinary player. */
internal suspend fun browserMedia(page: String, referer: String, allowMp4: Boolean = false): BrowserMedia? {
    val direct = HlsTracks.extension(page) == "m3u8" || (allowMp4 && HlsTracks.extension(page) == "mp4")
    val request = if (direct) null else {
        val extensions = if (allowMp4) "(?:m3u8|mp4)" else "m3u8"
        WebViewResolver(interceptUrl = Regex("\\.$extensions(?:[?#]|$)", RegexOption.IGNORE_CASE),
            useOkhttp = false, timeout = 25_000L)
            .resolveUsingWebView(page, referer = referer, headers = emptyMap()).first ?: return null
    }
    if (request != null && request.method != "GET") return null
    val url = HlsTracks.webUrl(null, request?.url?.toString() ?: page)?.takeIf { it.startsWith("https://") } ?: return null
    val referrer = request?.header("Referer") ?: referer
    val headers = listOf("Origin", "User-Agent").mapNotNull { key -> request?.header(key)?.let { key to it } }.toMap()
    return when (HlsTracks.extension(url)) {
        "m3u8" -> {
            val response = app.get(url, headers = headers, referer = referrer, timeout = 12)
            val manifest = response.text
            if (response.code in 200..299 && manifest.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) {
                BrowserMedia(url, ExtractorLinkType.M3U8, referrer, headers, manifest, response.url)
            } else null
        }
        "mp4" -> {
            if (!allowMp4) return null
            val response = app.get(url, headers = headers + ("Range" to "bytes=0-1023"), referer = referrer, timeout = 12)
            response.body?.close()
            if (response.code in 200..299 && response.headers["Content-Type"]?.startsWith("video/", true) == true) {
                BrowserMedia(url, ExtractorLinkType.VIDEO, referrer, headers)
            } else null
        }
        else -> null
    }
}
