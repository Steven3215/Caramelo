package com.caramelo.lightflix

import android.content.Context
import com.caramelo.movies.BrowserMovieProvider
import com.caramelo.movies.MovieId
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@CloudstreamPlugin
class LightflixPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(LightflixProvider(context.applicationContext)) }
}

internal class LightflixProvider(context: Context) : BrowserMovieProvider(context) {
    override var name = "Lightflix"
    override var mainUrl = "https://lightflix.app"
    override val mainPage = mainPageOf("/trending/movie/week" to "Trending Movies", "/trending/tv/week" to "Trending TV",
        "/movie/popular" to "Popular Movies", "/tv/popular" to "Popular TV", "/movie/top_rated" to "Top Rated Movies")
    private data class Session(val cookies: Map<String, String>, val key: String, val expiresAt: Long)
    private val lock = Mutex()
    private var cached: Session? = null
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Origin" to "https://lightflix.app", "Accept" to "application/json",
        "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty"
    )
    private suspend fun session(previous: Session? = null) = lock.withLock {
        cached?.takeIf { it != previous && it.expiresAt > System.currentTimeMillis() + 30_000L }?.let { return@withLock it }
        val entry = app.get("$mainUrl/en", headers = mapOf("User-Agent" to browserHeaders.getValue("User-Agent")), timeout = 20)
        val token = app.post("$mainUrl/api/tmdb-key", headers = browserHeaders, referer = "$mainUrl/en",
            cookies = entry.cookies, json = emptyMap<String, String>(), allowRedirects = false, timeout = 15)
        if (token.code !in 200..299) throw ErrorLoadingException("Lightflix anonymous catalog session was rejected (HTTP ${token.code})")
        val response = json.readTree(token.text)
        val key = response.string("key") ?: throw ErrorLoadingException("Lightflix catalog key unavailable")
        Session(entry.cookies + token.cookies, key, response.path("expiresAt").asLong()).also { cached = it }
    }
    override suspend fun metadata(path: String): JsonNode {
        suspend fun request(session: Session) = app.get("$mainUrl/api/tmdb$path", headers = browserHeaders + ("x-api-key" to session.key),
            referer = "$mainUrl/en", cookies = session.cookies, allowRedirects = false, timeout = 20)
        val session = session()
        val first = request(session)
        val response = if (first.code == 401) request(session(session)) else first
        if (response.code !in 200..299) throw ErrorLoadingException("Lightflix catalog unavailable (HTTP ${response.code})")
        return json.readTree(response.text)
    }
    override fun titleUrl(item: MovieId) = "$mainUrl/en/${item.type}/${item.id}"
    override fun player(item: MovieId) = if (item.type == "movie") "https://velarium.cc/en/watch/movie/${item.id}?autoPlay=true"
        else "https://velarium.cc/en/watch/tv/${item.id}-${item.season}-${item.episode}?autoPlay=true"
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1) return newHomePageResponse(request, emptyList(), false)
        val response = metadata("${request.data}?page=$page&language=en-US")
        val type = if (request.data.contains("/tv/")) "tv" else "movie"
        return newHomePageResponse(request, response.path("results").mapNotNull { card(it, type) }, page < response.path("total_pages").asInt(page))
    }
}
