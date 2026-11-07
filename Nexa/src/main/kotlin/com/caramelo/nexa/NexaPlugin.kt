package com.caramelo.nexa

import android.content.Context
import com.caramelo.movies.BrowserMovieProvider
import com.caramelo.movies.MovieId
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

@CloudstreamPlugin
class NexaPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(NexaProvider()) }
}

internal class NexaProvider : BrowserMovieProvider() {
    override var name = "Nexa"
    override var mainUrl = "https://nexa.mov"
    override var sequentialMainPage = true
    override val mainPage = mainPageOf("top10" to "Top 10", "trending-week" to "Trending This Week",
        "acclaimed-films" to "Acclaimed Films", "airing-now" to "Currently Airing", "acclaimed-series" to "Critically Acclaimed Series",
        "imdb-popular-movies" to "Popular Movies", "rt-popular-tv" to "Popular TV")
    @Volatile private var home: Pair<Long, JsonNode>? = null
    private var key: Pair<Long, String>? = null
    private val keyLock = Mutex()

    private suspend fun publicCatalogKey() = keyLock.withLock {
        key?.takeIf { System.currentTimeMillis() - it.first < 3_600_000L }?.let { return@withLock it.second }
        val entry = app.get("$mainUrl/", timeout = 18).text
        val entryPath = Regex("""["'](?:\./|/)(_app/immutable/entry/app\.[A-Za-z0-9_-]+\.js)["']""")
            .find(entry)?.groupValues?.get(1) ?: throw ErrorLoadingException("Nexa catalog client unavailable")
        val loader = app.get("$mainUrl/$entryPath", referer = "$mainUrl/", timeout = 18).text
        val clientFile = Regex("""from["']\.\./chunks/([A-Za-z0-9_-]+\.js)["']""").find(loader)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Nexa catalog client unavailable")
        val client = app.get("$mainUrl/_app/immutable/chunks/$clientFile", referer = "$mainUrl/", timeout = 18).text
        val catalog = client.substringAfter("https://api.themoviedb.org/3", "")
        val pattern = """function [A-Za-z_\$][\w\$]*\(\)\{return${96.toChar()}([0-9a-f]{32})${96.toChar()}\}async function"""
        val publicKey = Regex(pattern).find(catalog)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Nexa public metadata configuration changed")
        key = System.currentTimeMillis() to publicKey
        publicKey
    }
    override suspend fun metadata(path: String): JsonNode {
        val response = app.get("https://api.themoviedb.org/3$path", params = mapOf("api_key" to publicCatalogKey()),
            referer = "$mainUrl/", allowRedirects = false, timeout = 20)
        if (response.code !in 200..299) throw ErrorLoadingException("Nexa metadata unavailable (HTTP ${response.code})")
        return json.readTree(response.text)
    }
    override fun titleUrl(item: MovieId) = "$mainUrl/watch/tmdb-${item.type}/${item.id}"
    override fun player(item: MovieId) = titleUrl(item) + (if (item.type == "tv") "/s${item.season}-e${item.episode}" else "")
    override fun parseTitle(url: String): MovieId {
        val uri = URI(url)
        val match = Regex("^/watch/tmdb-(movie|tv)/([1-9][0-9]{0,8})/?$").matchEntire(uri.path)
            ?.takeIf { uri.scheme == "https" && uri.host == "nexa.mov" && uri.userInfo == null }
            ?: throw ErrorLoadingException("Invalid Nexa title URL")
        return MovieId(match.groupValues[1], match.groupValues[2].toInt())
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1) return newHomePageResponse(request, emptyList(), false)
        val catalog = home?.takeIf { System.currentTimeMillis() - it.first < 300_000L }?.second ?: run {
            val response = app.get("https://backend.nexa.mov/v1/home", headers = mapOf("Origin" to mainUrl), referer = "$mainUrl/", timeout = 20)
            if (response.code !in 200..299) throw ErrorLoadingException("Nexa catalog unavailable (HTTP ${response.code})")
            json.readTree(response.text).also { home = System.currentTimeMillis() to it }
        }
        val row = if (request.data == "top10") catalog.path("top10")
            else catalog.path("rails").firstOrNull { it.string("id") == request.data }?.path("items")
        val items = row?.mapNotNull { item ->
            val ref = item.path("ref")
            if (ref.string("source") != "tmdb") return@mapNotNull null
            val type = ref.string("type")?.takeIf { it in setOf("movie", "tv") } ?: return@mapNotNull null
            val id = ref.path("id").asInt().takeIf { it > 0 } ?: return@mapNotNull null
            val detail = item.path("detail")
            val title = detail.string("title") ?: return@mapNotNull null
            result(MovieId(type, id), title, image(detail.string("poster") ?: detail.string("backdrop")),
                detail.path("year").asInt().takeIf { it > 0 })
        }.orEmpty()
        return newHomePageResponse(request, items, false)
    }
}
