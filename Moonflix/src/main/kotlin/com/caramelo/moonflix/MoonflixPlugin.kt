package com.caramelo.moonflix

import com.caramelo.tracks.MediaTracks
import android.content.Context
import com.caramelo.movies.BrowserMovieProvider
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException

@CloudstreamPlugin
class MoonflixPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(MoonflixProvider()) }
}

internal class MoonflixProvider : BrowserMovieProvider() {
    override var name = "Moonflix"
    override var mainUrl = "https://moonflix.website"
    override val mainPage = mainPageOf("/trending/movie/day" to "Trending Movies", "/trending/tv/day" to "Trending TV",
        "/movie/popular" to "Popular Movies", "/tv/popular" to "Popular TV", "/movie/top_rated" to "Top Rated Movies")
    private var key: Pair<Long, String>? = null
    private val keyLock = Mutex()
    private val playerOrigin = "https://player.moonflix.website"
    private val transport = "moonflix-png-ts"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private suspend fun publicCatalogKey() = keyLock.withLock {
        key?.takeIf { System.currentTimeMillis() - it.first < 3_600_000L }?.let { return@withLock it.second }
        val html = app.get("$mainUrl/browse", timeout = 18).text
        val script = Regex("""<script[^>]+src=["'](/assets/index-[A-Za-z0-9_-]+\.js)["']""")
            .find(html)?.groupValues?.get(1) ?: throw ErrorLoadingException("Moonflix catalog client unavailable")
        val client = app.get(mainUrl + script, referer = "$mainUrl/browse", timeout = 18).text
        val publicKey = Regex("""["']([0-9a-f]{32})["']\.trim\(\)\?\?["']{2},[A-Za-z0-9_$]+=["']https://api\.themoviedb\.org/3["']""")
            .find(client)?.groupValues?.get(1) ?: throw ErrorLoadingException("Moonflix public metadata configuration changed")
        key = System.currentTimeMillis() to publicKey
        publicKey
    }
    override suspend fun metadata(path: String): JsonNode {
        val response = app.get("https://api.themoviedb.org/3$path", params = mapOf("api_key" to publicCatalogKey()),
            referer = "$mainUrl/", allowRedirects = false, timeout = 20)
        if (response.code !in 200..299) throw ErrorLoadingException("Moonflix metadata unavailable (HTTP ${response.code})")
        return json.readTree(response.text)
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1) return newHomePageResponse(request, emptyList(), false)
        val response = metadata("${request.data}?page=$page&language=en-US")
        val type = if (request.data.contains("/tv/")) "tv" else "movie"
        return newHomePageResponse(request, response.path("results").mapNotNull { card(it, type) }, page < response.path("total_pages").asInt(page))
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val mediaTracks = MediaTracks(subtitleCallback)
        if (isCasting) return false
        val item = parsePlayback(data) ?: return false
        val params = buildMap {
            put("type", item.type)
            put("tmdb", item.id.toString())
            item.season?.let { put("s", it.toString()) }
            item.episode?.let { put("e", it.toString()) }
        }
        val endpoints = listOf(
            "https://filmu-mpbu.onrender.com/api/stream" to "https://embed.filmu.in",
            "https://vidzy-wlqs.onrender.com/api/stream" to "https://player.vidzee.wtf"
        )
        val emitted = mutableSetOf<String>()
        for ((endpoint, origin) in endpoints) try {
            val response = app.get(endpoint, params = params, referer = "$playerOrigin/",
                headers = mapOf("Origin" to playerOrigin, "User-Agent" to userAgent), timeout = 18)
            if (response.code !in 200..299) continue
            val bundle = json.readTree(response.text)
            if (!bundle.path("available").asBoolean(true)) continue
            val candidates = listOfNotNull(bundle.string("m3u8")) + bundle.path("sources").mapNotNull { it.string("url") }
            val headers = mapOf("Origin" to origin, "User-Agent" to userAgent)
            for (link in candidates.distinct().take(4)) {
                val uri = URI(link)
                if (uri.scheme != "https" || uri.host == null || uri.userInfo != null || link in emitted) continue
                val manifest = app.get(link, headers = headers, referer = "$origin/", timeout = 15)
                if (manifest.code !in 200..299 || !manifest.text.trimStart().startsWith("#EXTM3U")) continue
                emitted.add(link)
                mediaTracks.publish(newExtractorLink(name, "$name – Direct HLS ${emitted.size}", link, ExtractorLinkType.M3U8) {
                    referer = "$origin/"
                    this.headers = headers
                    extractorData = transport
                }, callback, manifest.text, manifest.url)
                mediaTracks.sidecars(bundle.path("subtitles").toList() + bundle.path("tracks").toList(),
                    manifest.url, headers + ("Referer" to "$origin/"))
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        if (emitted.isEmpty()) throw ErrorLoadingException("Moonflix has no direct stream available for this title")
        return true
    }
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (extractorLink.extractorData != transport) return null
        return Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body ?: return@Interceptor response
            if (!response.isSuccessful || !response.peekBody(8).bytes().contentEquals(PNG_SIGNATURE)) return@Interceptor response
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > 32 * 1024 * 1024) throw IOException("Moonflix media segment is too large")
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            val ts = pngTransport(bytes) ?: throw IOException("Invalid Moonflix PNG media segment")
            response.newBuilder().removeHeader("Content-Length").removeHeader("Content-Range").removeHeader("Content-Encoding")
                .header("Content-Type", "video/mp2t").body(ts.toResponseBody("video/mp2t".toMediaType())).build()
        }
    }
    private fun pngTransport(bytes: ByteArray): ByteArray? {
        var offset = PNG_SIGNATURE.size
        while (offset + 12 <= bytes.size) {
            var length = 0L
            repeat(4) { length = (length shl 8) or (bytes[offset + it].toLong() and 255) }
            val start = offset + 8
            val end = start + length
            if (end + 4 > bytes.size) return null
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type == "tsDa" && length >= 3 * 188 &&
                listOf(start, start + 188, start + 376).all { bytes[it] == 0x47.toByte() }) {
                return bytes.copyOfRange(start, end.toInt())
            }
            if (type == "IEND") return null
            offset = end.toInt() + 4
        }
        return null
    }
    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
    }
}
