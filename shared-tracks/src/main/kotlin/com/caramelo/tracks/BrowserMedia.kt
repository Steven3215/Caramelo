package com.caramelo.tracks

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.URI

internal data class BrowserMedia(
    val url: String,
    val type: ExtractorLinkType,
    val referer: String,
    val headers: Map<String, String>,
    val manifest: String? = null,
    val manifestUrl: String = url
)

/** Resolve only media requested by the embed's ordinary player. */
internal suspend fun browserMedia(
    page: String,
    referer: String,
    allowMp4: Boolean = false,
    extensionlessHlsPath: String? = null,
    preservePageResources: Boolean = false
): BrowserMedia? {
    val direct = HlsTracks.extension(page) == "m3u8" || (allowMp4 && HlsTracks.extension(page) == "mp4")
    val request = if (direct) null else {
        val extensions = if (allowMp4) "(?:m3u8|mp4)" else "m3u8"
        val hlsPath = extensionlessHlsPath?.let { "|^https://[^/?#]+${Regex.escape(it)}" }.orEmpty()
        val match = Regex("(?:\\.$extensions$hlsPath)(?:[?#]|$)", RegexOption.IGNORE_CASE)
        val captured = if (!preservePageResources) {
            WebViewResolver(interceptUrl = match, useOkhttp = false, timeout = 25_000L)
                .resolveUsingWebView(page, referer = referer, headers = emptyMap()).first
        } else captureMediaWithPageResources(page, referer, match)
        captured ?: return null
    }
    if (request != null && request.method != "GET") return null
    val url = HlsTracks.webUrl(null, request?.url?.toString() ?: page)?.takeIf { it.startsWith("https://") } ?: return null
    val referrer = request?.header("Referer") ?: referer
    val headers = listOf("Origin", "User-Agent").mapNotNull { key -> request?.header(key)?.let { key to it } }.toMap()
    val extension = if (extensionlessHlsPath != null && URI(url).path == extensionlessHlsPath) "m3u8"
        else HlsTracks.extension(url)
    return when (extension) {
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

// Player frameworks can abort initialization if their stylesheet preloads fail.
@SuppressLint("SetJavaScriptEnabled")
private suspend fun captureMediaWithPageResources(page: String, referer: String, match: Regex): Request? =
    withContext(Dispatchers.Main.immediate) {
        val context = getContext() as? Context ?: return@withContext null
        val view = WebView(context)
        val result = CompletableDeferred<Request>()
        try {
            val agent = view.settings.userAgentString
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (request.method != "GET" || request.isForMainFrame || request.url.scheme != "https" ||
                        !match.containsMatchIn(url)) return null

                    val candidate = runCatching {
                        Request.Builder().url(url).apply {
                            for (key in listOf("Origin", "Referer", "User-Agent")) {
                                val value = request.requestHeaders.entries.firstOrNull { it.key.equals(key, true) }?.value
                                    ?: if (key == "User-Agent") agent else null
                                if (!value.isNullOrBlank()) header(key, value)
                            }
                        }.build()
                    }.getOrNull() ?: return null
                    if (!result.complete(candidate)) return null
                    return WebResourceResponse("text/plain", "UTF-8", null)
                }
            }
            view.loadUrl(page, mapOf("Referer" to referer))
            withTimeoutOrNull(40_000L) { result.await() }
        } finally {
            view.stopLoading()
            view.destroy()
        }
    }
