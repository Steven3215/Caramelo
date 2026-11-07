package com.caramelo.movies

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.getContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

// Player frameworks can abort initialization if their stylesheet preloads fail.
@SuppressLint("SetJavaScriptEnabled")
internal suspend fun captureMediaWithPageResources(page: String, referer: String, match: Regex): Request? =
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
